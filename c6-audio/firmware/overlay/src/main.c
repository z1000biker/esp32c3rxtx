/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The osmosdr protocol over a serial link (esdr_proto.h, serial transport):
 * the ESP32-C6's USB-Serial-JTAG, driven through its FIFOs. One thread: idle,
 * it answers request frames; streaming, it runs the CPU-paced receiver or
 * transmitter (esp_sdr_paced.h) with interrupts locked and moves the bytes
 * between samples.
 */

#include <errno.h>
#include <string.h>

#include <zephyr/app_version.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/sys/sys_io.h>
#include <zephyr/sys/util.h>

#include <soc/usb_serial_jtag_reg.h>

#include <esp_sdr/esp_sdr.h>
#include <esp_sdr/esp_sdr_paced.h>
#if defined(CONFIG_APP_RX_DIAG)
#include <esp_sdr/esp_sdr_diag.h>
#endif
#include <esp_sdr/esp_sdr_rx.h>

#include "esdr_proto.h"

#define CPU_HZ (CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ * 1000000U)
#define RATE_MAX_HZ 250000U
#define RATE_COUNT 5U
/*
 * Transmit DAC rate limit: the interpolating poll plus reading the samples
 * from USB keep up with 250 kS/s at the DAC on the C6 (125 kS/s x 2), 500
 * on the C5's faster CPU (125 kS/s x 4).
 */
#if defined(CONFIG_SOC_SERIES_ESP32C5)
#define TX_DAC_MAX_HZ 500000U
#else
#define TX_DAC_MAX_HZ 250000U
#endif
/* The diagnostic-bus ring delivers 2.5 MS/s divided by the decimation. */
#define DIAG_IQ_HZ 2500000U
#define OUT_BLOCKS 3U
/* The diagnostic-bus build has no RAM for more. */
#if defined(CONFIG_APP_RX_DIAG)
#define TX_RING_BYTES 8192U
#else
#define TX_RING_BYTES 16384U
#endif
/* Cycles one FIFO byte access takes, and the margin left before a sample is due. */
#define IO_SLACK_CYCLES 40

/* Settings as requested by the host. */
static struct {
	uint16_t mode, bits, rx_gain, tx_gain, dgain;
	uint64_t freq_hz;
	uint32_t rate_hz, bandwidth_hz;
	int32_t corr_ppb;
} want = {
	.mode = ESDR_MODE_OFF,
	.bits = 8,
	.rx_gain = ESDR_GAIN_AUTO,
	.tx_gain = 8,
	.dgain = ESDR_DGAIN_DEFAULT,
	.freq_hz = (uint64_t)CONFIG_APP_FREQ_MHZ * 1000000U,
	.rate_hz = RATE_MAX_HZ,
};

static uint64_t center_hz;
static uint32_t lo_mhz;
static int32_t lo_khz;
static bool radio_dirty = true;
static struct esdr_stats stats;

/* USB-Serial-JTAG endpoint 1 (Zephyr's driver is disabled in the board overlay). */
static inline bool usj_readable(void)
{
	return (sys_read32(USB_SERIAL_JTAG_EP1_CONF_REG) & USB_SERIAL_JTAG_SERIAL_OUT_EP_DATA_AVAIL) != 0U;
}

static inline bool usj_writable(void)
{
	return (sys_read32(USB_SERIAL_JTAG_EP1_CONF_REG) & USB_SERIAL_JTAG_SERIAL_IN_EP_DATA_FREE) != 0U;
}

static inline void usj_flush(void)
{
	sys_write32(USB_SERIAL_JTAG_WR_DONE, USB_SERIAL_JTAG_EP1_CONF_REG);
}

/* Bytes to the host: whole receive blocks, then at most one reply frame. */
static struct {
	uint8_t blk[OUT_BLOCKS][ESDR_BLOCK_BYTES];
	/* Blocks committed and sent (counters); offset into the block being sent. */
	uint32_t committed, sent, off, pkt;
	/* The block being filled, and its sample count. */
	uint32_t fill_n;
	uint16_t next_flags;
	uint64_t index;
} out;

/* Write what is queued while the FIFO takes it; at most @p max bytes. */
static void out_service(uint32_t max)
{
	while (max-- > 0U && out.sent != out.committed && usj_writable()) {
		sys_write32(out.blk[out.sent % OUT_BLOCKS][out.off++], USB_SERIAL_JTAG_EP1_REG);
		if (++out.pkt == 64U) {
			usj_flush();
			out.pkt = 0;
		}
		if (out.off == ESDR_BLOCK_BYTES) {
			out.off = 0;
			out.sent++;
			stats.rx_blocks++;
			stats.usb_in_bytes += ESDR_BLOCK_BYTES;
		}
	}
}

/*
 * Write queued bytes until @p due is near. Each byte needs room first: a
 * write to a full FIFO stalls the CPU until the host takes a packet.
 */
static void out_until(uint32_t due)
{
	while (out.sent != out.committed &&
	       (int32_t)(due - esp_sdr_paced_cycles()) > IO_SLACK_CYCLES) {
		if (!usj_writable()) {
			return;
		}
		sys_write32(out.blk[out.sent % OUT_BLOCKS][out.off++], USB_SERIAL_JTAG_EP1_REG);
		if (++out.pkt == 64U) {
			usj_flush();
			out.pkt = 0;
		}
		if (out.off == ESDR_BLOCK_BYTES) {
			out.off = 0;
			out.sent++;
			stats.rx_blocks++;
			stats.usb_in_bytes += ESDR_BLOCK_BYTES;
		}
	}
}

/* Send a short buffer now (idle, or after a stream stopped), giving up after 100 ms. */
static void write_now(const uint8_t *p, size_t n)
{
	int64_t end = k_uptime_get() + 100;

	for (size_t k = 0; k < n; k++) {
		while (!usj_writable()) {
			if (k_uptime_get() > end) {
				return;
			}
		}
		sys_write32(p[k], USB_SERIAL_JTAG_EP1_REG);
		if (++out.pkt == 64U) {
			usj_flush();
			out.pkt = 0;
		}
	}
	usj_flush();
	out.pkt = 0;
}

/* Request frames from the host. */
static struct {
	union {
		struct esdr_ser_hdr h;
		uint8_t raw[sizeof(struct esdr_ser_hdr)];
	};
	uint8_t data[ESDR_SER_MAX_DATA];
	uint32_t got, tx_left;
	bool ready;
} req;

/* One byte from the host. */
static void req_feed(uint8_t b)
{
	if (req.tx_left > 0U) {
		stats.usb_out_bytes++;
		req.tx_left--;
		return;
	}
	if (req.got < sizeof(req.raw)) {
		req.raw[req.got++] = b;
		/* Resynchronize on the magic. */
		while (req.got > 0U && req.got <= 4U &&
		       memcmp(req.raw, &(uint32_t){sys_cpu_to_le32(ESDR_SER_MAGIC)}, req.got) != 0) {
			memmove(req.raw, req.raw + 1, --req.got);
		}
		if (req.got == sizeof(req.raw)) {
			if (req.h.req == ESDR_SER_TX_DATA) {
				req.tx_left = sys_le16_to_cpu(req.h.len);
				req.got = 0;
			} else if ((req.h.flags & ESDR_SER_IN) != 0U || req.h.len == 0U) {
				req.ready = true;
			} else if (sys_le16_to_cpu(req.h.len) > ESDR_SER_MAX_DATA) {
				req.h.len = 0;
				req.ready = true;
			}
		}
		return;
	}
	req.data[req.got++ - sizeof(req.raw)] = b;
	if (req.got - sizeof(req.raw) == sys_le16_to_cpu(req.h.len)) {
		req.ready = true;
	}
}

/* Read from the host while there is room for transmit data; at most @p max bytes. */
static void in_service(uint32_t max)
{
	while (max-- > 0U && !req.ready && usj_readable()) {
		req_feed((uint8_t)sys_read32(USB_SERIAL_JTAG_EP1_REG));
	}
}

/* Read from the host until @p due is near (or nothing waits, or the transmit ring is full). */
static void in_until(uint32_t due)
{
	while (!req.ready && (int32_t)(due - esp_sdr_paced_cycles()) > IO_SLACK_CYCLES &&
	       usj_readable()) {
		req_feed((uint8_t)sys_read32(USB_SERIAL_JTAG_EP1_REG));
	}
}

static uint32_t rate_round(uint32_t hz)
{
	for (uint32_t r = RATE_MAX_HZ; r > (RATE_MAX_HZ >> (RATE_COUNT - 1U)); r /= 2U) {
		/* Above the geometric mean of r and r / 2. */
		if (2U * (uint64_t)hz * hz >= (uint64_t)r * r) {
			return r;
		}
	}
	return RATE_MAX_HZ >> (RATE_COUNT - 1U);
}

static unsigned int dgain_db(void)
{
	if (want.dgain != ESDR_DGAIN_DEFAULT) {
		return want.dgain;
	}
	return want.bits == 8U ? 24U : 0U;
}

/* Frequency, correction, gain and filter into the radio. */
static int radio_apply(void)
{
	/* A fast crystal puts the LO high: program the opposite offset. */
	int64_t hz = (int64_t)want.freq_hz - (int64_t)want.freq_hz * want.corr_ppb / 1000000000LL;
	int ret;

	lo_mhz = (uint32_t)(hz / 1000000);
	lo_khz = (int32_t)((hz - (int64_t)lo_mhz * 1000000 + 500) / 1000);
	ret = esp_sdr_set_freq(lo_mhz);
	if (ret == 0) {
		ret = esp_sdr_set_freq_offset(lo_khz);
	}
	if (ret == 0) {
		ret = esp_sdr_rx_set_gain(want.rx_gain == ESDR_GAIN_AUTO ? ESP_SDR_RX_GAIN_AUTO
									 : (int)want.rx_gain);
	}
	if (ret == 0) {
		ret = want.bandwidth_hz == 0U ? esp_sdr_rx_set_lpf(ESP_SDR_RX_LPF_AUTO)
					      : esp_sdr_rx_set_bandwidth(want.bandwidth_hz / 1000000U);
	}
	/* Where the LO lands, back in the host's frequency scale. */
	center_hz = ((uint64_t)lo_mhz * 1000000U + (int64_t)lo_khz * 1000) +
		    (int64_t)want.freq_hz * want.corr_ppb / 1000000000LL;
	/* Applied once: a setting the radio refuses keeps its previous value. */
	radio_dirty = false;
	return ret;
}

static int handle_out(uint8_t r, uint16_t value, const uint8_t *data, size_t len)
{
	switch (r) {
	case ESDR_REQ_SET_MODE:
		if (value != ESDR_MODE_OFF && value != ESDR_MODE_RX) {
			return -ENOTSUP;
		}
		want.mode = value;
		return 0;
	case ESDR_REQ_SET_FREQ:
		if (len != 8U) {
			return -EINVAL;
		}
		{
			uint64_t hz = sys_get_le64(data);

			if (hz < 2400000000ULL || hz > 2500000000ULL) {
				return -EINVAL;
			}
			want.freq_hz = hz;
		}
		return radio_apply();
	case ESDR_REQ_SET_SAMPLE_RATE:
		if (len != 4U) {
			return -EINVAL;
		}
		want.rate_hz = rate_round(sys_get_le32(data));
		return 0;
	case ESDR_REQ_SET_RX_GAIN:
		if (value != ESDR_GAIN_AUTO && value > (uint16_t)esp_sdr_rx_gain_max()) {
			return -EINVAL;
		}
		want.rx_gain = value;
		radio_dirty = true;
		return 0;
	case ESDR_REQ_SET_BANDWIDTH:
		if (len != 4U) {
			return -EINVAL;
		}
		want.bandwidth_hz = sys_get_le32(data);
		radio_dirty = true;
		return 0;
	case ESDR_REQ_SET_FORMAT:
		if (value != 8U && value != 16U) {
			return -EINVAL;
		}
		want.bits = value;
		return 0;
	case ESDR_REQ_SET_FREQ_CORR:
		if (len != 4U) {
			return -EINVAL;
		}
		want.corr_ppb = (int32_t)sys_get_le32(data);
		radio_dirty = true;
		return 0;
	case ESDR_REQ_SET_OPTIONS:
		/* No offset tuning: the paced receiver sums around the LO itself. */
		return 0;
	case ESDR_REQ_SET_DIGITAL_GAIN:
		if (value != ESDR_DGAIN_DEFAULT && (value > ESDR_DGAIN_MAX_DB || value % 6U != 0U)) {
			return -EINVAL;
		}
		want.dgain = value;
		return 0;
	default:
		return -ENOTSUP;
	}
}

static int handle_in(uint8_t r, uint8_t *buf, size_t max)
{
	switch (r) {
	case ESDR_REQ_GET_INFO: {
		struct esdr_info info = {
			.magic = ESDR_INFO_MAGIC,
			.proto_version = ESDR_PROTO_VERSION,
			.info_size = sizeof(info),
			.freq_min_hz = 2400000000ULL,
			.freq_max_hz = 2500000000ULL,
			.rx_rate_max_hz = RATE_MAX_HZ,
			.rx_rate_count = RATE_COUNT,
			.rx_gain_max = (uint16_t)esp_sdr_rx_gain_max(),
			.tx_gain_max = 0,
			.block_bytes = ESDR_BLOCK_BYTES,
			.full_scale16 = 32767,
		};

		strncpy(info.firmware, "c6_audio_rx " APP_VERSION_STRING, sizeof(info.firmware) - 1);
		memcpy(buf, &info, MIN(max, sizeof(info)));
		return (int)MIN(max, sizeof(info));
	}
	case ESDR_REQ_GET_STATE: {
		struct esdr_state st = {
			.mode = want.mode,
			.bits = want.bits,
			.rx_gain = want.rx_gain,
			.tx_gain = want.tx_gain,
			.digital_gain_db = (uint16_t)dgain_db(),
			.freq_hz = want.freq_hz,
			.center_hz = center_hz,
			.sample_rate_hz = want.rate_hz,
			.bandwidth_hz = want.bandwidth_hz,
			.freq_corr_ppb = want.corr_ppb,
			.lo_mhz = lo_mhz,
			.lo_offset_khz = lo_khz,
		};

		memcpy(buf, &st, MIN(max, sizeof(st)));
		return (int)MIN(max, sizeof(st));
	}
	case ESDR_REQ_GET_STATS:
		memcpy(buf, &stats, MIN(max, sizeof(stats)));
		return (int)MIN(max, sizeof(stats));
	default:
		return -ENOTSUP;
	}
}

/* Answer the pending request frame. */
static void req_answer(void)
{
	struct {
		struct esdr_ser_hdr h;
		uint8_t data[ESDR_SER_MAX_DATA];
	} __packed rep = {.h = {.magic = sys_cpu_to_le32(ESDR_SER_MAGIC), .req = req.h.req}};
	uint16_t len = sys_le16_to_cpu(req.h.len);
	int ret;

	if ((req.h.flags & ESDR_SER_IN) != 0U) {
		ret = handle_in(req.h.req, rep.data, MIN(len, sizeof(rep.data)));
	} else {
		ret = handle_out(req.h.req, sys_le16_to_cpu(req.h.value), req.data, len);
	}
	if (ret > 0) {
		rep.h.len = sys_cpu_to_le16((uint16_t)ret);
	} else {
		rep.h.status = sys_cpu_to_le16((uint16_t)(int16_t)ret);
	}
	write_now((const uint8_t *)&rep, sizeof(rep.h) + (ret > 0 ? (size_t)ret : 0U));
	req.ready = false;
	req.got = 0;
}

/* Scale factor from a sum of W inputs (each up to 512) to int16, with the digital gain, Q16. */
static uint32_t rx_scale(void)
{
	uint32_t w = ESP_SDR_PACED_INPUT_HZ / want.rate_hz;

	return (uint32_t)(((uint64_t)64U << (16U + dgain_db() / 6U)) / w);
}

/*
 * The paced loop's timing, in the fields the S3's ring reports its own in:
 * ring_errors counts polls a whole period late, ring_late_max is the worst
 * lateness in CPU cycles.
 */
static void pacing_stats(void)
{
	struct esp_sdr_paced_stats ps;

	esp_sdr_paced_get_stats(&ps);
	stats.ring_runs++;
	stats.ring_errors += ps.late;
	stats.ring_late_max = MAX(stats.ring_late_max, ps.late_max);
}

static void out_commit(void)
{
	struct esdr_block_hdr *h = (struct esdr_block_hdr *)out.blk[out.committed % OUT_BLOCKS];

	h->magic = sys_cpu_to_le32(ESDR_BLOCK_MAGIC);
	h->flags = sys_cpu_to_le16(out.next_flags | (want.bits == 16U ? ESDR_BLK_16BIT : 0U));
	h->samples = sys_cpu_to_le16((uint16_t)out.fill_n);
	h->index = sys_cpu_to_le64(out.index);
	out.index += out.fill_n;
	out.committed++;
	out.next_flags = 0;
	out.fill_n = 0;
	stats.rx_samples += sys_le16_to_cpu(h->samples);
}

/* Receive samples into the output blocks: 16-bit or 8-bit items, a block at a time. */
static bool rx_full;
static uint32_t rx_per_block;

static void rx_begin(void)
{
	rx_per_block = ESDR_BLOCK_PAYLOAD / (want.bits == 16U ? 4U : 2U);
	rx_full = false;
	out.fill_n = 0;
}

/* One sample, int16 full scale; commits the block when it is full. */
static void rx_emit(int32_t vi, int32_t vq)
{
	uint8_t *blk = out.blk[out.committed % OUT_BLOCKS] + sizeof(struct esdr_block_hdr);

	if (rx_full) {
		/* The block index moves on: the host sees the gap. */
		stats.rx_overflow_samples++;
		out.index++;
	} else if (want.bits == 16U) {
		sys_put_le16((uint16_t)vi, blk + 4U * out.fill_n);
		sys_put_le16((uint16_t)vq, blk + 4U * out.fill_n + 2U);
		out.fill_n++;
	} else {
		blk[2U * out.fill_n] = (uint8_t)(int8_t)CLAMP((vi + 128) >> 8, -127, 127);
		blk[2U * out.fill_n + 1U] = (uint8_t)(int8_t)CLAMP((vq + 128) >> 8, -127, 127);
		out.fill_n++;
	}
	if (out.fill_n == rx_per_block) {
		out_commit();
	}
	/* No free block: drop until one is, then mark the gap. */
	rx_full = out.committed - out.sent >= OUT_BLOCKS;
	if (rx_full && out.next_flags == 0U) {
		out.next_flags = ESDR_BLK_OVERFLOW | ESDR_BLK_GAP;
		stats.rx_overflows++;
	}
}

/* After a stream: whole blocks only, the partial one is dropped and the restart marked. */
static void rx_end(void)
{
	int64_t until = k_uptime_get() + 300;

	while (out.sent != out.committed && k_uptime_get() < until) {
		out_service(64);
	}
	out.sent = out.committed;
	out.off = 0;
	usj_flush();
	out.pkt = 0;
	out.fill_n = 0;
	out.next_flags = ESDR_BLK_GAP;
}

#if defined(CONFIG_APP_RX_DIAG)
/* The diagnostic-bus ring: the library decimates, this takes its blocks of samples. */
static uint64_t diag_expect;
static unsigned int diag_dgain_shift;

static void diag_iq_sink(void *user, const int32_t *iq, size_t n, uint64_t index)
{
	ARG_UNUSED(user);
	if (index != diag_expect) {
		/* Blocks of the ring were skipped: close the block in progress, move on, flag the gap. */
		if (out.fill_n != 0U) {
			out_commit();
		}
		out.index += index - diag_expect;
		out.next_flags |= ESDR_BLK_GAP;
	}
	diag_expect = index + n;
	for (size_t j = 0; j < n; j++) {
		/* Raw counts times 16 to int16 with the digital gain: x * 2^g / 16. */
		int32_t vi = (iq[2U * j] << diag_dgain_shift) >> 4;
		int32_t vq = (iq[2U * j + 1U] << diag_dgain_shift) >> 4;

		rx_emit(CLAMP(vi, -32767, 32767), CLAMP(vq, -32767, 32767));
	}
	/* Between blocks, with interrupts open: what the USB FIFO takes, and a look for a request. */
	out_service(256);
	in_service(16);
	if (req.ready) {
		esp_sdr_diag_stop();
	}
}

static void run_rx_diag(void)
{
	struct esp_sdr_diag_stats ds;
	struct esp_sdr_diag_cfg cfg = {
		.decim = (uint8_t)(DIAG_IQ_HZ / want.rate_hz),
		.lsb = ESP_SDR_DIAG_LSB_AUTO,
		.iq_sink = diag_iq_sink,
	};
	int ret;

	rx_begin();
	diag_expect = 0;
	diag_dgain_shift = dgain_db() / 6U;
	ret = esp_sdr_diag_run(&cfg, &ds);
	if (ret != 0) {
		/* Could not start, or the ring overran: the host sees the stream stop. */
		want.mode = ESDR_MODE_OFF;
	}
	stats.ring_runs++;
	stats.ring_errors += ds.skips + (ds.overrun ? 1U : 0U);
	stats.rx_ring_lost_pairs += (uint64_t)ds.skipped_blocks * (ESP_SDR_DIAG_BLOCK / 16U) / cfg.decim;
	stats.ring_cycles_x100 = ds.sink_cycles_x100;
	rx_end();
}
#endif

static void run_rx(void)
{
	uint32_t k = rx_scale();
	unsigned int key;
	int ret;

#if defined(CONFIG_APP_RX_DIAG)
	run_rx_diag();
	return;
#endif
	ret = esp_sdr_rx_paced_start(want.rate_hz);
	if (ret != 0) {
		want.mode = ESDR_MODE_OFF;
		return;
	}
	rx_begin();
	key = irq_lock();
	esp_sdr_paced_sync();
	uint32_t last_block = out.sent;
	uint32_t last_progress = esp_sdr_paced_cycles();

	while (!req.ready) {
		int32_t i, q;
		if (esp_sdr_rx_paced_poll(&i, &q)) {
			rx_emit(CLAMP((int32_t)(((int64_t)i * k) >> 16), -32767, 32767),
				CLAMP((int32_t)(((int64_t)q * k) >> 16), -32767, 32767));
		}
		/* Sample instants stay exact: I/O only while a byte fits before the next one is due. */
		out_until(esp_sdr_paced_deadline());
		in_until(esp_sdr_paced_deadline());
		if (out.sent != last_block) {
			last_block = out.sent;
			last_progress = esp_sdr_paced_cycles();
		} else if (esp_sdr_paced_cycles() - last_progress > 2U * CPU_HZ) {
			want.mode = ESDR_MODE_OFF;
			break;
		}
	}
	irq_unlock(key);
	esp_sdr_rx_paced_stop();
	pacing_stats();
	rx_end();
}

int main(void)
{
	uint32_t stale_ms = 0;
	int ret;

	printk("osmosdr_serial %s\n", APP_VERSION_STRING);
	ret = esp_sdr_init();
	if (ret != 0) {
		printk("esp_sdr_init: %d\n", ret);
		return 0;
	}
	for (;;) {
		if (radio_dirty) {
			(void)radio_apply();
		}
		if (want.mode == ESDR_MODE_RX) {
			run_rx();
		} else {
			uint32_t got = req.got + req.tx_left;

			in_service(64);
			if (!req.ready) {
				k_msleep(1);
				/* A frame the host abandoned halfway (it crashed): drop it after 200 ms. */
				if (got != 0U && got == req.got + req.tx_left) {
					if (++stale_ms >= 200U) {
						req.got = 0;
						req.tx_left = 0;
						stale_ms = 0;
					}
				} else {
					stale_ms = 0;
				}
			}
		}
		if (req.ready) {
			req_answer();
		}
	}
	return 0;
}
