#!/usr/bin/env python3
"""
C3TRX desktop app — ESP32-C3 2.4 GHz TRX (firmware C3TRX 1, raw IQ stream)

RX : firmware streams IQF1 frames (128 x int16 I/Q); all DSP is done here:
     BFO shift -> boxcar decimation to ~16 kHz -> complex FIR channel filter ->
     AM / FM / USB / LSB / CW demod -> AGC -> squelch -> sound card.
TX : firmware command  TX <AM|FM|USB|LSB> <amp> <param>  then 32 kS/s int16 PCM.
     Sources: microphone, tone, carrier, waterfall text. Half duplex (RX is stopped for TX).
WF text: the message is rendered to a bitmap and sent in USB/LSB as a set of tones,
     one tone per pixel, one pixel line per time slice, so it is painted on any waterfall.

Install (Windows PowerShell):
    py -m pip install pyside6 pyqtgraph numpy pyserial sounddevice
Run:
    py c3trx_app.py            (pick the port in the window)
    py c3trx_app.py --mock     (no hardware: simulated +1 kHz carrier, for testing)
"""
import sys, time, struct, threading, queue, argparse, math, wave
import numpy as np

from PySide6 import QtCore, QtGui, QtWidgets
import pyqtgraph as pg

try:
    import serial, serial.tools.list_ports
except ImportError:
    serial = None
try:
    import sounddevice as sd
except Exception:
    sd = None

AUDIO_SR = 48000          # sound card rate (we resample to it)
TX_SR = 32000             # firmware TX audio rate (fixed in C3TRX 1)
FFT_N = 1024
MAGIC = b"IQF1"
MODE_BW = {"AM": 6000, "FM": 12000, "USB": 2700, "LSB": 2700, "CW": 500}
MODE_BFO = {"AM": 0, "FM": 0, "USB": 1500, "LSB": -1500, "CW": 700}


# --------------------------------------------------------------------------- serial
class MockSerial:
    """Simulated C3TRX 1: +1 kHz carrier at 48 kS/s plus noise."""
    def __init__(self):
        self.out = bytearray(); self.lock = threading.Lock()
        self.rx = False; self.ph = 0.0; self.t = time.time(); self.is_open = True
        self.tx = False; self.tx_n = 0; self.tx_last = 0.0
    def write(self, b):
        b = bytes(b)
        if self.tx and b != b"\x03" and not b[:4] in (b"INFO", b"FREQ", b"RX 1", b"TX U", b"TX L", b"TX A", b"TX F"):   # PCM while transmitting
            self.tx_n += len(b)//2; self.tx_last = time.time(); return len(b)
        if b == b"\x03":
            self.rx = False; self._put(b"RXEND\r\n"); return len(b)
        for line in b.decode(errors="ignore").split("\n"):
            line = line.strip()
            if line == "INFO": self._put(b"C3TRX 1 RXIQ TXAM TXFM TXUSB TXLSB FREQ=2400000 (MOCK)\r\n")
            elif line.startswith("FREQ "): self._put(f"OK FREQ {line[5:]}\r\n".encode())
            elif line.startswith("RX "): self._put(b"RXREADY 128 2400000\r\n"); self.rx = True; self.t = time.time()
            elif line.startswith("TX "):
                self._put(f"TXREADY {line.split()[1]} 2400000 32000\r\n".encode())
                self.tx = True; self.tx_n = 0; self.tx_last = time.time()
        return len(b)
    def _put(self, b):
        with self.lock: self.out += b
    def read(self, n):
        if self.tx and time.time() - self.tx_last > 0.3:         # like the firmware: end TX on silence
            self.tx = False; self._put(f"TXEND samples={self.tx_n} underruns=0\r\n".encode())
        if self.rx:
            now = time.time(); nfr = int((now - self.t) * 48000 / 128); self.t += nfr * 128 / 48000
            for _ in range(min(nfr, 50)):
                k = np.arange(128); p = self.ph + 2*np.pi*1000/48000*k; self.ph = (p[-1] + 2*np.pi*1000/48000) % (2*np.pi)
                # +1 kHz carrier with a DC offset and an IQ imbalance (Q 15 % low, 6° skew), like a real zero-IF
                i = 0.3*np.cos(p) + 0.05 + 0.001*np.random.randn(128)
                q = 0.255*np.sin(p + 0.105) - 0.03 + 0.001*np.random.randn(128)
                iq = np.empty(256, np.int16); iq[0::2] = i*32767; iq[1::2] = q*32767
                self._put(MAGIC + struct.pack("<HHI", 128, 0, 48000) + iq.tobytes())
        time.sleep(0.005)
        with self.lock:
            d = bytes(self.out[:n]); del self.out[:n]; return d
    @property
    def in_waiting(self): return 65536
    def close(self): self.is_open = False


class Link(QtCore.QObject):
    line = QtCore.Signal(str)
    def __init__(self, ser):
        super().__init__(); self.ser = ser; self.iq = queue.Queue(maxsize=400)
        self.rate = 0; self.dropped = 0; self.run = True; self.wlock = threading.Lock()
        threading.Thread(target=self._reader, daemon=True).start()
    def send(self, b):
        with self.wlock: self.ser.write(b)
    def send_line(self, s):
        self.line.emit("> " + s); self.send((s + "\n").encode())
    def _reader(self):
        buf = bytearray()
        while self.run:
            try: d = self.ser.read(max(1, min(65536, getattr(self.ser, "in_waiting", 1) or 1)))
            except Exception as e: self.line.emit(f"! serial error: {e}"); return
            if d: buf += d
            while True:
                m = buf.find(MAGIC); nl = buf.find(b"\n")
                if m >= 0 and (nl < 0 or m < nl):
                    if m > 0: self.dropped += m; del buf[:m]; continue
                    if len(buf) < 12: break
                    cnt, _flags, rate = struct.unpack_from("<HHI", buf, 4)
                    if cnt == 0 or cnt > 4096: del buf[:4]; continue
                    tot = 12 + cnt*4
                    if len(buf) < tot: break
                    iq = np.frombuffer(bytes(buf[12:tot]), np.int16).astype(np.float32) / 32768.0
                    del buf[:tot]
                    if rate: self.rate = rate if not self.rate or abs(rate - self.rate) > 0.05*self.rate else self.rate
                    z = iq[0::2] + 1j*iq[1::2]
                    try: self.iq.put_nowait(z.astype(np.complex64))
                    except queue.Full: self.dropped += cnt
                    continue
                if nl < 0:
                    if len(buf) > 200000: del buf[:-16]
                    break
                s = bytes(buf[:nl]).decode(errors="replace").strip(); del buf[:nl+1]
                if s:
                    try: self.line.emit("< " + s)
                    except RuntimeError: return      # window already closed


# --------------------------------------------------------------------------- DSP
def blackman_lpf(cut, fs, n):
    k = np.arange(n) - (n-1)/2; fc = cut/fs
    h = 2*fc*np.sinc(2*fc*k)
    w = 0.42 - 0.5*np.cos(2*np.pi*np.arange(n)/(n-1)) + 0.08*np.cos(4*np.pi*np.arange(n)/(n-1))
    h = h*w; return (h/h.sum()).astype(np.float32)


class Front:
    """DC removal + blind IQ amplitude/phase balance, applied before spectrum and demod."""
    def __init__(self):
        self.dc_on = True; self.iq_on = True
        self.dc = 0j; self.ii = 1e-6; self.qq = 1e-6; self.iqm = 0.0
        self.gain = 1.0; self.phase_deg = 0.0
    def process(self, z):
        if self.dc_on:
            self.dc = 0.98*self.dc + 0.02*complex(z.mean())          # ~45 ms time constant at 147 kS/s
            z = z - np.complex64(self.dc)
        if self.iq_on:
            i = z.real; q = z.imag; a = 0.01
            self.ii += a*(float(np.mean(i*i)) - self.ii)
            self.qq += a*(float(np.mean(q*q)) - self.qq)
            self.iqm += a*(float(np.mean(i*q)) - self.iqm)
            g = math.sqrt(self.qq/max(self.ii, 1e-12))               # Q/I amplitude ratio
            s = max(-0.5, min(0.5, self.iqm/math.sqrt(max(self.ii*self.qq, 1e-24))))   # sin(phase error)
            c = math.sqrt(1 - s*s)
            self.gain = g; self.phase_deg = math.degrees(math.asin(s))
            q2 = (q/g - i*s)/c                                        # orthogonalise Q against I
            z = (i + 1j*q2).astype(np.complex64)
        return z


class Demod:
    def __init__(self):
        self.mode = "USB"; self.bfo = 1500.0; self.bw = 2700.0; self.agc = "fast"
        self.vol = 0.6; self.sql = 0; self.vfo = 0.0; self.reset()
    def reset(self):
        self.key = None; self.ph = 0.0; self.phB = 0.0; self.dec = np.zeros(0, np.complex64)
        self.zi = None; self.prev = 1+0j; self.dc = 0.0; self.env = 0.05; self.level_db = -120.0
    def process(self, z, rate):
        if rate <= 0 or len(z) == 0: return np.zeros(0, np.float32), 16000
        D = max(1, int(rate // 16000)); fsd = rate / D
        bw = min(self.bw, 0.9*fsd)
        key = (D, round(fsd), bw)
        if key != self.key:
            n = int(min(255, max(31, round(5.5*fsd/250)))) | 1
            self.h = blackman_lpf(bw/2, fsd, n); self.zi = np.zeros(n-1, np.complex64)
            self.dec = np.zeros(0, np.complex64); self.key = key
        # 1. shift by -(VFO+BFO): VFO = clicked signal position, BFO = passband centre relative to it
        n = len(z); w = 2*np.pi*(self.vfo + self.bfo)/rate; wb = 2*np.pi*self.bfo/rate
        ph = self.ph + w*np.arange(n); self.ph = (self.ph + w*n) % (2*np.pi)
        phb_all = self.phB + wb*np.arange(n); self.phB = (self.phB + wb*n) % (2*np.pi)
        x = z * np.exp(-1j*ph).astype(np.complex64)
        ph = phb_all          # only the BFO part is undone after filtering, so audio is relative to the VFO
        # 2. boxcar decimate (carry remainder)
        x = np.concatenate([self.dec, x]); m = (len(x)//D)*D
        self.dec = x[m:]; xd = x[:m].reshape(-1, D).mean(axis=1) if m else np.zeros(0, np.complex64)
        phd = (ph[D-1::D][:len(xd)] if D > 1 else ph[:len(xd)]) if len(xd) else ph[:0]
        if len(xd) == 0: return np.zeros(0, np.float32), fsd
        # 3. FIR with state
        full = np.concatenate([self.zi, xd]); y = np.convolve(full, self.h, mode="valid")
        self.zi = full[-(len(self.h)-1):]
        p = np.mean(np.abs(y)**2); self.level_db = 10*np.log10(p + 1e-12)
        # 4. demod
        if self.mode == "AM":
            e = np.abs(y); out = np.empty_like(e)
            for i, v in enumerate(e): self.dc = 0.9995*self.dc + 0.0005*v; out[i] = v - self.dc
        elif self.mode == "FM":
            yy = np.concatenate([[self.prev], y]); out = np.angle(yy[1:]*np.conj(yy[:-1]))/np.pi; self.prev = y[-1]
        else:   # SSB/CW: back up by +BFO, take real part (rejects opposite sideband)
            if len(phd) < len(y): phd = np.pad(phd, (0, len(y)-len(phd)), mode="edge")
            out = np.real(y * np.exp(1j*phd[:len(y)]))
        out = out.astype(np.float32)
        # 5. AGC
        if self.agc != "off":
            rel = 0.0015 if self.agc == "fast" else 0.0002; g = np.empty_like(out)
            for i, v in enumerate(np.abs(out)):
                self.env += (0.05 if v > self.env else rel)*(v - self.env); g[i] = 0.3/max(self.env, 1e-4)
            out = out*g
        else: out = out*4
        if self.sql and self.level_db < -100 + self.sql: out[:] = 0
        return np.clip(out*self.vol, -1, 1), fsd


class AudioOut:
    """Resamples demod audio to the sound card rate, small adaptive ring buffer."""
    def __init__(self):
        self.buf = np.zeros(0, np.float32); self.lock = threading.Lock(); self.frac = 0.0; self.last = 0.0
        self.stream = None
        if sd:
            try:
                self.stream = sd.OutputStream(samplerate=AUDIO_SR, channels=1, dtype="float32",
                                              blocksize=512, callback=self._cb, latency="low")
                self.stream.start()
            except Exception as e: print("audio out:", e)
    def push(self, a, fs):
        if len(a) == 0: return
        step = fs/AUDIO_SR; t = self.frac + np.arange(0, len(a) - self.frac, step)
        src = np.concatenate([[self.last], a]); r = np.interp(t + 1, np.arange(len(src)), src).astype(np.float32)
        self.frac = (t[-1] + step) - len(a) if len(t) else self.frac - len(a); self.last = a[-1]
        with self.lock:
            self.buf = np.concatenate([self.buf, r])
            if len(self.buf) > AUDIO_SR*0.4: self.buf = self.buf[-int(AUDIO_SR*0.1):]   # drift: drop
    def _cb(self, out, frames, t, status):
        with self.lock:
            n = min(frames, len(self.buf)); out[:n, 0] = self.buf[:n]; out[n:, 0] = 0; self.buf = self.buf[n:]


# --------------------------------------------------------------------------- waterfall text
class WaterfallText:
    """Paints text on a waterfall. The text is rendered to a 1-bit bitmap; every time slice sends one
    line of pixels, and every lit pixel is a sine at  base + k*spacing  Hz (USB: RF = carrier + audio)."""

    @staticmethod
    def bitmap(text, height_px, bold=True):
        f = QtGui.QFont(); f.setPixelSize(max(4, int(height_px))); f.setBold(bold)
        f.setStyleStrategy(QtGui.QFont.NoAntialias)
        fm = QtGui.QFontMetrics(f)
        w = max(1, fm.horizontalAdvance(text) + 4); h = fm.height() + 2
        img = QtGui.QImage(w, h, QtGui.QImage.Format_Grayscale8); img.fill(0)
        p = QtGui.QPainter(img); p.setFont(f); p.setPen(QtGui.QColor(255, 255, 255))
        p.setRenderHint(QtGui.QPainter.TextAntialiasing, False); p.drawText(2, 1 + fm.ascent(), text); p.end()
        a = np.frombuffer(img.constBits(), np.uint8, count=img.bytesPerLine()*h).reshape(h, img.bytesPerLine())[:, :w] > 127
        rows = np.where(a.any(axis=1))[0]; cols = np.where(a.any(axis=0))[0]
        if len(rows) == 0: return np.zeros((1, 1), bool)
        return a[rows[0]:rows[-1]+1, cols[0]:cols[-1]+1]

    @staticmethod
    def frames(bm, layout, newest_top=True, lsb=False):
        """-> bool matrix (slices in transmit order, tones low->high audio frequency)."""
        if layout == "vertical":        # text runs along time: narrow (font height) and long
            F = bm.T                     # one slice per bitmap column, left to right
            if not newest_top: F = F[:, ::-1]
        else:                            # text runs along frequency: wide and short
            F = bm[::-1] if newest_top else bm      # bottom line first so the top line ends up on top
        return F[:, ::-1] if lsb else F            # LSB mirrors audio in RF: undo it

    @staticmethod
    def synth(F, sr, base, spacing, slice_s, lead_s=0.15):
        n_sl, n_t = F.shape; L = max(1, int(round(slice_s*sr))); lead = int(lead_s*sr)
        N = lead + n_sl*L + lead; t = np.arange(N)/sr; out = np.zeros(N)
        r = max(1, int(min(0.010, 0.15*slice_s)*sr)); ker = np.hanning(2*r+1); ker /= ker.sum()   # soft keying, less splatter
        rng = np.random.default_rng(7)                                            # spread tone phases
        for k in range(n_t):
            col = F[:, k]
            if not col.any(): continue
            env = np.zeros(N); env[lead:lead+n_sl*L] = np.repeat(col.astype(float), L)
            env = np.convolve(env, ker, mode="same")
            out += env*np.sin(2*np.pi*(base + k*spacing)*t + rng.uniform(0, 2*np.pi))
        pk = float(np.max(np.abs(out))) or 1.0
        return (0.9*out/pk).astype(np.float32)

    @classmethod
    def build(cls, text, layout, height_px, base, spacing, slice_ms, newest_top=True, lsb=False, sr=None):
        sr = sr or TX_SR
        bm = cls.bitmap(text, height_px); F = cls.frames(bm, layout, newest_top, lsb)
        a = cls.synth(F, sr, base, spacing, slice_ms/1000.0)
        info = dict(tones=F.shape[1], slices=F.shape[0], lo=base, hi=base + (F.shape[1]-1)*spacing,
                    seconds=len(a)/sr, bitmap=bm)
        return a, info


class WfPreview(QtWidgets.QDialog):
    """Spectrogram of the generated audio, drawn the way a waterfall will show it at the end of TX."""
    def __init__(self, parent, audio, sr, info, newest_top):
        super().__init__(parent); self.setWindowTitle("Waterfall text preview"); self.resize(720, 560)
        v = QtWidgets.QVBoxLayout(self)
        v.addWidget(QtWidgets.QLabel(f"{info['tones']} tones · {info['lo']:.0f}–{info['hi']:.0f} Hz audio "
                                     f"({info['hi']-info['lo']:.0f} Hz wide) · {info['seconds']:.1f} s"))
        sp = max(1.0, (info['hi'] - info['lo'])/max(1, info['tones']-1)) if info['tones'] > 1 else 100.0
        n = 1 << int(round(math.log2(max(128, sr/sp)))); hop = max(16, n//4)       # ~1 FFT bin per pixel
        win = np.hanning(n).astype(np.float32); frames = []
        for i in range(0, max(1, len(audio) - n), hop):
            frames.append(20*np.log10(np.abs(np.fft.rfft(audio[i:i+n]*win)) + 1e-6))
        S = np.array(frames); fr = np.fft.rfftfreq(n, 1/sr)
        lo = max(0, info['lo'] - 3*sp); hi = info['hi'] + 3*sp; sel = (fr >= lo) & (fr <= hi)
        S = S[:, sel]
        if newest_top: S = S[::-1]
        pw = pg.PlotWidget(); pw.invertY(True); pw.setLabel("bottom", "audio Hz"); pw.hideAxis("left")
        img = pg.ImageItem(S.T); img.setLookupTable(pg.colormap.get("turbo").getLookupTable(nPts=256))
        top = float(S.max()); img.setLevels((top - 30, top))
        # square pixels: one text line (time) is drawn as tall as one pixel step (Hz) is wide
        T = len(audio)/sr; d = max(1e-3, (T - 0.3)/max(1, info['slices'])); h = T/d*sp
        img.setRect(QtCore.QRectF(fr[sel][0], 0, fr[sel][-1] - fr[sel][0], h)); pw.addItem(img)
        pw.setAspectLocked(True); pw.setXRange(fr[sel][0], fr[sel][-1], padding=0); pw.setYRange(0, h, padding=0)
        v.addWidget(pw); self.plot = pw
        ratio = (fr[sel][-1] - fr[sel][0])/h
        self.resize(*((max(360, int(820*ratio) + 60), 860) if ratio < 1 else (960, max(320, int(900/ratio) + 140))))
        note = QtWidgets.QLabel("Shown with square pixels. On a real waterfall the shape depends on its FFT size and "
                                     "scroll speed: adjust Pixel (Hz) and Line (ms) until the text looks right there.")
        note.setWordWrap(True); v.addWidget(note)


# --------------------------------------------------------------------------- TX
class TxFeeder:
    def __init__(self, link, source, tone_hz, samples=None):
        self.link = link; self.source = source; self.tone = tone_hz; self.run = True
        self.samples = samples; self.pos = 0
        self.q = queue.Queue(); self.mic = None
        if source == "mic" and sd:
            self.mic = sd.InputStream(samplerate=TX_SR, channels=1, dtype="float32", blocksize=640,
                                      callback=lambda d, f, t, s: self.q.put(d[:, 0].copy()))
            self.mic.start()
        threading.Thread(target=self._loop, daemon=True).start()
    def _loop(self):
        t0 = time.time(); sent = 0; ph = 0.0
        while self.run:
            if self.source == "mic":
                try: a = self.q.get(timeout=0.1)
                except queue.Empty: continue
            else:
                due = int((time.time()-t0)*TX_SR) + 2560 - sent
                if due <= 0: time.sleep(0.01); continue
                n = min(due, 4096)
                if self.source == "text":
                    a = self.samples[self.pos:self.pos+n]; self.pos += len(a)
                    if len(a) == 0: self.run = False; break      # message done: firmware ends TX on silence
                elif self.source == "tone":
                    p = ph + 2*np.pi*self.tone/TX_SR*np.arange(n); ph = (p[-1] + 2*np.pi*self.tone/TX_SR) % (2*np.pi)
                    a = 0.5*np.sin(p)
                else: a = np.zeros(n)
                sent += n
            self.link.send((np.clip(a, -1, 1)*32767).astype("<i2").tobytes())
    def stop(self):
        self.run = False
        if self.mic: self.mic.stop(); self.mic.close()


# --------------------------------------------------------------------------- GUI
class Main(QtWidgets.QMainWindow):
    def __init__(self, mock):
        super().__init__(); self.setWindowTitle("C3TRX — ESP32-C3 2.4 GHz TRX"); self.resize(1400, 900)
        self.mock = mock; self.link = None; self.rx = False; self.tx = None
        self.dem = Demod(); self.audio = AudioOut()
        self.ring = np.zeros(FFT_N, np.complex64); self.ring_fill = 0; self.since = 0
        self.avg = None; self.wf = np.full((300, FFT_N), -120.0, np.float32); self.win = np.hanning(FFT_N).astype(np.float32)
        self._build()
        t = QtCore.QTimer(self); t.timeout.connect(self._tick); t.start(15)

    def _build(self):
        W = QtWidgets; cw = W.QWidget(); self.setCentralWidget(cw); v = W.QVBoxLayout(cw)
        r1 = W.QHBoxLayout(); v.addLayout(r1)
        self.port = W.QComboBox(); self._ports(); r1.addWidget(W.QLabel("Port")); r1.addWidget(self.port)
        b = W.QPushButton("↻"); b.clicked.connect(self._ports); r1.addWidget(b)
        self.bconn = W.QPushButton("Connect"); self.bconn.clicked.connect(self._connect); r1.addWidget(self.bconn)
        self.lfw = W.QLabel("FW: —"); self.lrate = W.QLabel("IQ rate: —"); self.llvl = W.QLabel("Level: —"); self.ldrop = W.QLabel("Dropped: 0")
        for w in (self.lfw, self.lrate, self.llvl, self.ldrop): w.setMinimumWidth(130); r1.addWidget(w)
        r1.addStretch()

        r2 = W.QHBoxLayout(); v.addLayout(r2)
        self.freq = W.QDoubleSpinBox(); self.freq.setRange(2300, 2500); self.freq.setDecimals(3); self.freq.setValue(2400.0); self.freq.setSuffix(" MHz")
        r2.addWidget(W.QLabel("Freq")); r2.addWidget(self.freq)
        bt = W.QPushButton("Tune"); bt.clicked.connect(self._tune); r2.addWidget(bt)
        self.wsp = W.QSpinBox(); self.wsp.setRange(32, 4096); self.wsp.setValue(128); r2.addWidget(W.QLabel("RX window")); r2.addWidget(self.wsp)
        self.brx = W.QPushButton("Start RX"); self.brx.clicked.connect(self._rx_toggle); r2.addWidget(self.brx)
        self.mgrp = W.QButtonGroup(self)
        for m in ["AM", "FM", "USB", "LSB", "CW"]:
            b = W.QPushButton(m); b.setCheckable(True); b.setChecked(m == "USB"); self.mgrp.addButton(b); r2.addWidget(b)
            b.clicked.connect(lambda _=False, m=m: self._mode(m))
        r2.addStretch()

        r3 = W.QHBoxLayout(); v.addLayout(r3)
        self.bfo = W.QSpinBox(); self.bfo.setRange(-50000, 50000); self.bfo.setSingleStep(50); self.bfo.setValue(1500); self.bfo.setSuffix(" Hz")
        self.bw = W.QSpinBox(); self.bw.setRange(200, 20000); self.bw.setSingleStep(100); self.bw.setValue(2700); self.bw.setSuffix(" Hz")
        self.vol = W.QSlider(QtCore.Qt.Horizontal); self.vol.setRange(0, 100); self.vol.setValue(60)
        self.sql = W.QSlider(QtCore.Qt.Horizontal); self.sql.setRange(0, 100)
        self.agc = W.QComboBox(); self.agc.addItems(["fast", "slow", "off"])
        self.zoom = W.QSlider(QtCore.Qt.Horizontal); self.zoom.setRange(0, 1000); self.lzoom = W.QLabel("1.00×")
        for lab, w in [("BFO", self.bfo), ("BW", self.bw), ("Vol", self.vol), ("Squelch", self.sql), ("AGC", self.agc), ("Zoom", self.zoom)]:
            r3.addWidget(W.QLabel(lab)); r3.addWidget(w)
        r3.addWidget(self.lzoom)
        self.bfo.valueChanged.connect(lambda x: setattr(self.dem, "bfo", float(x)))
        self.bw.valueChanged.connect(lambda x: setattr(self.dem, "bw", float(x)))
        self.vol.valueChanged.connect(lambda x: setattr(self.dem, "vol", x/100))
        self.sql.valueChanged.connect(lambda x: setattr(self.dem, "sql", x))
        self.agc.currentTextChanged.connect(lambda x: setattr(self.dem, "agc", x))
        self.zoom.valueChanged.connect(lambda x: self.lzoom.setText(f"{2**(x/100):.2f}×"))

        # front end + VFO row
        r3b = W.QHBoxLayout(); v.addLayout(r3b)
        self.front = Front()
        self.cdc = W.QCheckBox("DC notch"); self.cdc.setChecked(True); self.cdc.toggled.connect(lambda b: setattr(self.front, "dc_on", b))
        self.ciq = W.QCheckBox("IQ balance"); self.ciq.setChecked(True); self.ciq.toggled.connect(lambda b: setattr(self.front, "iq_on", b))
        self.liq = W.QLabel("IQ: —"); self.liq.setMinimumWidth(170)
        self.lvfo = W.QLabel("VFO: —"); self.lvfo.setStyleSheet("font-weight:bold;color:#ff6b6b;font-size:14px"); self.lvfo.setMinimumWidth(220)
        bc = W.QPushButton("Re-center (σήμα 10 kHz εκτός DC)"); bc.clicked.connect(self._recenter)
        bz = W.QPushButton("VFO → 0"); bz.clicked.connect(lambda: self._set_vfo(0.0))
        for w in (self.cdc, self.ciq, self.liq, self.lvfo, bc, bz): r3b.addWidget(w)
        r3b.addWidget(W.QLabel("  κλικ στο φάσμα/waterfall = συντονισμός · ροδέλα = ±10 Hz (Shift: ±1 kHz)")); r3b.addStretch()

        pg.setConfigOptions(antialias=False)
        self.spec = pg.PlotWidget(); self.spec.setYRange(-110, 0); self.spec.showGrid(x=True, y=True, alpha=0.2)
        self.spec.setLabel("bottom", "MHz"); self.spec.getAxis("bottom").enableAutoSIPrefix(False)
        self.spec.setMouseEnabled(x=False, y=False); self.spec.hideButtons()
        self.curve = self.spec.plot(pen=pg.mkPen("#4ca6ff", width=1.3))
        self.region = pg.LinearRegionItem(movable=False, brush=(68, 162, 255, 45)); self.spec.addItem(self.region)
        self.vline = pg.InfiniteLine(angle=90, pen=pg.mkPen("#ff4d4d", width=1.5)); self.spec.addItem(self.vline)
        v.addWidget(self.spec, 2)
        self.wfp = pg.PlotWidget(); self.wfp.hideAxis("left"); self.wfp.hideAxis("bottom"); self.wfp.hideButtons()
        self.wfp.setMouseEnabled(x=False, y=False); self.wfp.setXLink(self.spec); self.wfp.invertY(True)
        self.img = pg.ImageItem(); self.wfp.addItem(self.img); self.img.setLookupTable(pg.colormap.get("turbo").getLookupTable(nPts=256))
        self.wvline = pg.InfiniteLine(angle=90, pen=pg.mkPen((255, 77, 77, 120))); self.wfp.addItem(self.wvline)
        v.addWidget(self.wfp, 3)
        for pw in (self.spec, self.wfp):
            pw.scene().sigMouseClicked.connect(lambda ev, pw=pw: self._click(ev, pw))
            pw.wheelEvent = lambda ev: self._wheel(ev)
        self.pending = None

        r4 = W.QHBoxLayout(); v.addLayout(r4)
        self.txmode = W.QComboBox(); self.txmode.addItems(["USB", "LSB", "AM", "FM"])
        self.txsrc = W.QComboBox(); self.txsrc.addItems(["mic", "tone", "carrier", "text"])
        self.amp = W.QSpinBox(); self.amp.setRange(1, 480); self.amp.setValue(80)
        self.depth = W.QSpinBox(); self.depth.setRange(1, 95); self.depth.setValue(70)
        self.dev = W.QSpinBox(); self.dev.setRange(100, 12000); self.dev.setValue(2500)
        self.tone = W.QSpinBox(); self.tone.setRange(50, 5000); self.tone.setValue(1000)
        for lab, w in [("TX mode", self.txmode), ("Source", self.txsrc), ("Amp", self.amp), ("AM %", self.depth), ("FM dev", self.dev), ("Tone Hz", self.tone)]:
            r4.addWidget(W.QLabel(lab)); r4.addWidget(w)
        self.bptt = W.QPushButton("PTT  (TX)"); self.bptt.setStyleSheet("background:#c0392b;color:white;font-weight:bold;padding:6px 18px")
        self.bptt.clicked.connect(self._ptt); r4.addWidget(self.bptt); r4.addStretch()

        # waterfall text row
        r5 = W.QHBoxLayout(); v.addLayout(r5)
        self.wtext = W.QLineEdit("CQ CQ DE SV1EEX"); self.wtext.setMinimumWidth(220)
        self.wlay = W.QComboBox(); self.wlay.addItems(["vertical (narrow)", "horizontal (wide)"])
        self.wh = W.QSpinBox(); self.wh.setRange(6, 40); self.wh.setValue(12); self.wh.setSuffix(" px")
        self.wpx = W.QSpinBox(); self.wpx.setRange(20, 1000); self.wpx.setSingleStep(10); self.wpx.setValue(150); self.wpx.setSuffix(" Hz")
        self.wms = W.QSpinBox(); self.wms.setRange(10, 1000); self.wms.setSingleStep(10); self.wms.setValue(80); self.wms.setSuffix(" ms")
        self.wbase = W.QSpinBox(); self.wbase.setRange(100, 10000); self.wbase.setSingleStep(50); self.wbase.setValue(400); self.wbase.setSuffix(" Hz")
        self.wdir = W.QComboBox(); self.wdir.addItems(["newest on top", "newest at bottom"])
        for lab, w in [("WF text", self.wtext), ("Layout", self.wlay), ("Height", self.wh), ("Pixel", self.wpx),
                       ("Line", self.wms), ("Base", self.wbase), ("RX waterfall", self.wdir)]:
            r5.addWidget(W.QLabel(lab)); r5.addWidget(w)
        bp = W.QPushButton("Preview"); bp.clicked.connect(self._wf_preview); r5.addWidget(bp)
        bs = W.QPushButton("Save WAV"); bs.clicked.connect(self._wf_wav); r5.addWidget(bs)
        self.lwf = W.QLabel(""); r5.addWidget(self.lwf); r5.addStretch()
        for w in (self.wh, self.wpx, self.wms, self.wbase): w.valueChanged.connect(self._wf_info)
        for w in (self.wlay, self.wdir): w.currentIndexChanged.connect(self._wf_info)
        self.wtext.textChanged.connect(self._wf_info); self.txmode.currentTextChanged.connect(self._wf_info)
        self.log = W.QPlainTextEdit(); self.log.setReadOnly(True); self.log.setMaximumHeight(130); v.addWidget(self.log)
        if not sd: self._log("! sounddevice not installed: no audio")
        self._wf_info()

    # ---- helpers
    def _log(self, s): self.log.appendPlainText(time.strftime("%H:%M:%S ") + s)
    def _ports(self):
        self.port.clear()
        if self.mock: self.port.addItem("MOCK")
        if serial:
            for p in serial.tools.list_ports.comports(): self.port.addItem(p.device, p.device)
    def _connect(self):
        if self.link: return
        dev = self.port.currentText()
        try:
            ser = MockSerial() if dev == "MOCK" else serial.Serial(dev, 921600, timeout=0.02)
        except Exception as e: self._log(f"! open {dev}: {e}"); return
        self.link = Link(ser); self.link.line.connect(self._line)
        self.link.send(b"\n"); time.sleep(0.2); self.link.send_line("INFO"); self.bconn.setEnabled(False)
    def _line(self, s):
        self._log(s)
        if s.startswith("< C3TRX ") and s[8:9].isdigit():
            self.lfw.setText("FW: " + " ".join(s[2:].split()[:2]))
            if s[8:9] != "1":
                self._log("!!! Λάθος firmware στη συσκευή (" + " ".join(s[2:].split()[:2]) + "). Το app θέλει C3TRX 1: "
                          "πέρασε πρώτα το c3trx1_merged.bin με esptool.")
        if s.startswith("< RXREADY"): self.rx = True; self.brx.setText("Stop RX")
        if s.startswith("< RXEND"):
            self.rx = False; self.brx.setText("Start RX")
            if self.pending: self.pending = None; self._rx_toggle()      # restart after a retune
        if s.startswith("< TXEND"): self._tx_ended()

    # ---- tuning
    def _cf(self): return self.freq.value()           # hardware centre, MHz
    def _set_vfo(self, off):
        rate = self.link.rate if self.link and self.link.rate else 147000
        off = max(-0.48*rate, min(0.48*rate, round(off/10)*10))
        self.dem.vfo = float(off); self._update_vfo_label()
    def _update_vfo_label(self):
        self.lvfo.setText(f"VFO: {self._cf() + self.dem.vfo/1e6:.5f} MHz")
    def _click(self, ev, pw):
        if ev.button() != QtCore.Qt.LeftButton: return
        vb = pw.getPlotItem().vb
        if not vb.sceneBoundingRect().contains(ev.scenePos()): return
        x = vb.mapSceneToView(ev.scenePos()).x()
        self._set_vfo((x - self._cf())*1e6)
    def _wheel(self, ev):
        step = 1000 if (ev.modifiers() & QtCore.Qt.ShiftModifier) else 10
        self._set_vfo(self.dem.vfo + (step if ev.angleDelta().y() > 0 else -step)); ev.accept()
    def _retune(self, cf_mhz, vfo):
        """Firmware cannot take commands while streaming: stop, FREQ, restart."""
        self.freq.setValue(cf_mhz); self.dem.vfo = vfo; self._update_vfo_label()
        if not self.link: return
        if self.rx: self.pending = True; self.link.send(b"\x03")
        else: self.link.send_line(f"FREQ {round(cf_mhz*1000)}")
    def _recenter(self):
        target = self._cf() + self.dem.vfo/1e6
        self._retune(target - 0.010, 10000.0)           # keep the signal 10 kHz away from the DC/LO spur
    def _tune(self):
        self._retune(self._cf(), self.dem.vfo)
    def _rx_toggle(self):
        if not self.link: return
        if self.rx: self.link.send(b"\x03"); return
        self.link.send_line(f"FREQ {round(self._cf()*1000)}"); time.sleep(0.05)
        self.dem.reset(); self.avg = None; self.link.send_line(f"RX {self.wsp.value()}")
    def _mode(self, m):
        self.dem.mode = m; self.bfo.setValue(MODE_BFO[m]); self.bw.setValue(MODE_BW[m]); self.dem.reset()
        if m in ("AM", "FM", "USB", "LSB"): self.txmode.setCurrentText(m)
        self._log(f"mode -> {m} (live)")
    # ---- waterfall text
    def _wf_build(self, sr=None):
        m = self.txmode.currentText()
        return WaterfallText.build(self.wtext.text() or " ", "vertical" if self.wlay.currentIndex() == 0 else "horizontal",
                                   self.wh.value(), self.wbase.value(), self.wpx.value(), self.wms.value(),
                                   newest_top=self.wdir.currentIndex() == 0, lsb=(m == "LSB"), sr=sr)
    def _wf_info(self, *_):
        if not hasattr(self, "lwf"): return
        bm = WaterfallText.bitmap(self.wtext.text() or " ", self.wh.value())
        vert = self.wlay.currentIndex() == 0
        tones, sl = (bm.shape[0], bm.shape[1]) if vert else (bm.shape[1], bm.shape[0])
        lo = self.wbase.value(); hi = lo + (tones - 1)*self.wpx.value()
        warn = "  ⚠ above 15 kHz" if hi > 15000 else ("  (wider than an SSB channel)" if hi > 3000 else "")
        self.lwf.setText(f"{tones} tones · {lo}–{hi} Hz · {sl*self.wms.value()/1000 + 0.3:.1f} s{warn}")
    def _wf_preview(self):
        a, info = self._wf_build()
        self._wfdlg = WfPreview(self, a, TX_SR, info, self.wdir.currentIndex() == 0); self._wfdlg.show()
    def _wf_wav(self):
        fn, _ = QtWidgets.QFileDialog.getSaveFileName(self, "Save waterfall text audio", "wftext.wav", "WAV (*.wav)")
        if not fn: return
        a, info = self._wf_build(sr=48000)
        with wave.open(fn, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(48000); w.writeframes((a*32767).astype("<i2").tobytes())
        self._log(f"saved {fn}: {info['seconds']:.1f} s, 48 kHz — play it into any SSB transmitter")

    def _ptt(self):
        if not self.link: return
        if self.tx: self.tx.stop(); self.tx = None; self._log("TX: audio stopped, firmware ends TX after 0.3 s"); return
        if self.rx: self.link.send(b"\x03"); time.sleep(0.3)
        src = self.txsrc.currentText(); m = self.txmode.currentText()
        if src == "carrier" and m in ("USB", "LSB"): m = "AM"; self.txmode.setCurrentText("AM"); self._log("carrier test -> AM (SSB with no audio radiates nothing)")
        samples = None
        if src == "text":
            if m not in ("USB", "LSB"): m = "USB"; self.txmode.setCurrentText("USB"); self._log("waterfall text -> USB (AM/FM would smear the picture)")
            samples, info = self._wf_build()
            if info["hi"] > 15000: self._log("! waterfall text: top tone above 15 kHz, reduce Pixel/Height/Base"); return
            self._log(f"WF text: {info['tones']} tones {info['lo']:.0f}–{info['hi']:.0f} Hz, {info['seconds']:.1f} s")
        p = self.depth.value() if m == "AM" else self.dev.value() if m == "FM" else 0
        txf = self._cf() + self.dem.vfo/1e6                      # transmit on the VFO frequency
        if txf > 2450: self._log("! TX limited to 2300–2450 MHz by firmware"); return
        self.link.send_line(f"FREQ {round(txf*1000)}"); time.sleep(0.05)
        self.link.send_line(f"TX {m} {self.amp.value()} {p}")
        self._log(f"TX on {txf:.5f} MHz (VFO). Μετά το TX πάτα Start RX.")
        self.tx = TxFeeder(self.link, src, self.tone.value(), samples)
        self.bptt.setText("STOP TX"); self.bptt.setStyleSheet("background:#ff2d2d;color:white;font-weight:bold;padding:6px 18px")
    def _tx_ended(self):
        if self.tx: self.tx.stop(); self.tx = None
        self.bptt.setText("PTT  (TX)"); self.bptt.setStyleSheet("background:#c0392b;color:white;font-weight:bold;padding:6px 18px")

    # ---- main loop
    def _tick(self):
        if not self.link: return
        rate = self.link.rate; got = False
        while True:
            try: z = self.link.iq.get_nowait()
            except queue.Empty: break
            got = True
            z = self.front.process(z)
            a, fsd = self.dem.process(z, rate); self.audio.push(a, fsd)
            n = len(z); self.ring = np.roll(self.ring, -n); self.ring[-n:] = z
            self.ring_fill = min(FFT_N, self.ring_fill + n); self.since += n
        if rate: self.lrate.setText(f"IQ rate: {rate/1000:.1f} kHz")
        self.ldrop.setText(f"Dropped: {self.link.dropped}"); self.llvl.setText(f"Level: {self.dem.level_db:.0f} dB")
        self.liq.setText(f"IQ: gain {self.front.gain:.3f} · phase {self.front.phase_deg:+.1f}°")
        self._update_vfo_label()
        if not got or self.ring_fill < FFT_N or self.since < FFT_N//2 or not rate: return
        self.since = 0
        x = self.ring * self.win            # ring already holds DC/IQ-corrected samples
        mag = 20*np.log10(np.abs(np.fft.fftshift(np.fft.fft(x)))/(FFT_N/2) + 1e-10)
        self.avg = mag if self.avg is None else self.avg + 0.35*(mag - self.avg)
        # zoom window centred on the VFO (clamped to the band edges)
        z = 2**(self.zoom.value()/100); w = max(16, int(FFT_N/z))
        cbin = int(round(FFT_N/2 + self.dem.vfo/rate*FFT_N))
        s = max(0, min(FFT_N - w, cbin - w//2))
        cf = self._cf()
        X = cf + (np.arange(FFT_N) - FFT_N/2) * rate / FFT_N / 1e6         # absolute MHz
        self.curve.setData(X[s:s+w], self.avg[s:s+w])
        self.spec.setXRange(X[s], X[s+w-1], padding=0)
        lo = cf + (self.dem.vfo + self.dem.bfo - self.dem.bw/2)/1e6
        self.region.setRegion((lo, lo + self.dem.bw/1e6))
        vx = cf + self.dem.vfo/1e6; self.vline.setValue(vx); self.wvline.setValue(vx)
        self.wf = np.roll(self.wf, 1, axis=0); self.wf[0] = self.avg
        self.img.setImage(self.wf[:, s:s+w].T, levels=(-100, -10), autoLevels=False)
        self.img.setRect(QtCore.QRectF(X[s], 0, X[s+w-1] - X[s], self.wf.shape[0]))
        self.wfp.setYRange(0, self.wf.shape[0], padding=0)

    def closeEvent(self, e):
        if self.tx: self.tx.stop()
        if self.link:
            try: self.link.send(b"\x03")
            except Exception: pass
            self.link.run = False
        e.accept()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--mock", action="store_true"); ap.add_argument("--selftest", type=float, default=0)
    a = ap.parse_args()
    app = QtWidgets.QApplication(sys.argv); w = Main(a.mock); w.show()
    if a.selftest:     # automated check used during development
        def go():
            w._connect()
            QtCore.QTimer.singleShot(400, w._rx_toggle)
        def spec_at(hz):
            m = w.avg; k = int(round(len(m)/2 + hz/w.link.rate*len(m))); return float(m[k-1:k+2].max())
        def report():
            print("FW", w.lfw.text(), "|", w.lrate.text(), "|", w.llvl.text(), "| dropped", w.link.dropped, "|", w.liq.text())
            print(f"spectrum: signal +1k {spec_at(1000):.1f} dB | image -1k {spec_at(-1000):.1f} dB | DC {spec_at(0):.1f} dB")
            # VFO: click-tune onto the +1 kHz carrier in USB -> carrier becomes 0 Hz audio (zero beat)
            for vfo in (0.0, 1000.0, 300.0):
                w._mode("USB"); w._set_vfo(vfo); w.dem.agc = "off"; acc = []; t_end = time.time() + 0.8
                while time.time() < t_end:
                    try: z = w.link.iq.get(timeout=0.1)
                    except queue.Empty: continue
                    o, fs = w.dem.process(w.front.process(z), w.link.rate); acc.append(o)
                o = np.concatenate(acc); zc = np.sum(np.diff(np.signbit(o)) != 0)
                print(f"USB VFO {vfo:+.0f} Hz ({w.lvfo.text()}): rms {20*np.log10(np.sqrt(np.mean(o**2))+1e-9):6.1f} dB ~tone {zc/2/(len(o)/fs):.0f} Hz")
            w._set_vfo(0.0)
            for m in ["USB", "LSB", "CW", "AM", "FM"]:
                w._mode(m); w.dem.agc = "off"; acc = []
                t_end = time.time() + 0.8
                while time.time() < t_end:
                    try: z = w.link.iq.get(timeout=0.1)
                    except queue.Empty: continue
                    o, fs = w.dem.process(w.front.process(z), w.link.rate); acc.append(o)
                o = np.concatenate(acc) if acc else np.zeros(1); zc = np.sum(np.diff(np.signbit(o)) != 0)
                print(f"{m}: rms {20*np.log10(np.sqrt(np.mean(o**2))+1e-9):6.1f} dB  ~tone {zc/2/(len(o)/fs):.0f} Hz")
            # retune while streaming must go stop -> FREQ -> RX
            w._set_vfo(1000.0); w._recenter()
            QtCore.QTimer.singleShot(1500, finish)
        def finish():
            print("after re-center: freq", f"{w._cf():.4f}", "|", w.lvfo.text(), "| rx", w.rx)
            print("log tail:", " / ".join(w.log.toPlainText().splitlines()[-4:]))
            w.grab().save("selftest.png"); app.quit()
        QtCore.QTimer.singleShot(100, go); QtCore.QTimer.singleShot(int(a.selftest*1000), report)
    sys.exit(app.exec())
