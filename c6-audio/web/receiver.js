'use strict';

const SERIAL_MAGIC = 0x4c525345;
const BLOCK_MAGIC = 0x4b4c4245;
const BLOCK_LENGTH = 4096;
const IQ_RATE = 62500;
const AUDIO_RATE = IQ_RATE / 2;

function requestFrame(command, {value = 0, size = 0, body = new Uint8Array()} = {}) {
  const packet = new Uint8Array(12 + body.length);
  const view = new DataView(packet.buffer);
  view.setUint32(0, SERIAL_MAGIC, true);
  packet[4] = command;
  packet[5] = size ? 1 : 0;
  view.setUint16(6, value, true);
  view.setUint16(8, size || body.length, true);
  packet.set(body, 12);
  return packet;
}

class StreamDecoder {
  constructor(reply, iq) {
    this.reply = reply;
    this.iq = iq;
    this.buffer = new Uint8Array(0);
  }

  push(input) {
    const buffer = new Uint8Array(this.buffer.length + input.length);
    buffer.set(this.buffer);
    buffer.set(input, this.buffer.length);
    const view = new DataView(buffer.buffer);
    let offset = 0;
    while (buffer.length - offset >= 4) {
      const magic = view.getUint32(offset, true);
      const remaining = buffer.length - offset;
      if (magic === BLOCK_MAGIC) {
        if (remaining < BLOCK_LENGTH) break;
        const flags = view.getUint16(offset + 4, true);
        const count = view.getUint16(offset + 6, true);
        const wide = (flags & 4) !== 0;
        if ((flags & 8) === 0 && count > 0 && count <= (wide ? 1020 : 2040)) {
          const i = new Float32Array(count);
          const q = new Float32Array(count);
          let pos = offset + 16;
          for (let k = 0; k < count; k++) {
            if (wide) {
              i[k] = view.getInt16(pos, true) / 32768;
              q[k] = view.getInt16(pos + 2, true) / 32768;
              pos += 4;
            } else {
              i[k] = view.getInt8(pos++) / 128;
              q[k] = view.getInt8(pos++) / 128;
            }
          }
          this.iq(i, q, view.getBigUint64(offset + 8, true), flags);
        }
        offset += BLOCK_LENGTH;
      } else if (magic === SERIAL_MAGIC) {
        if (remaining < 12) break;
        const size = view.getUint16(offset + 8, true);
        if (size > 512) {
          offset++;
          continue;
        }
        if (remaining < 12 + size) break;
        this.reply(buffer[offset + 4], view.getInt16(offset + 10, true),
          buffer.subarray(offset + 12, offset + 12 + size));
        offset += 12 + size;
      } else {
        offset++;
      }
    }
    this.buffer = buffer.slice(offset);
  }
}

class LowPass {
  constructor(hz, q = Math.SQRT1_2) {
    const omega = 2 * Math.PI * hz / IQ_RATE;
    const cos = Math.cos(omega);
    const alpha = Math.sin(omega) / (2 * q);
    this.b0 = (1 - cos) / (2 * (1 + alpha));
    this.b1 = 2 * this.b0;
    this.a1 = -2 * cos / (1 + alpha);
    this.a2 = (1 - alpha) / (1 + alpha);
    this.z1 = this.z2 = 0;
  }

  step(x) {
    const y = x * this.b0 + this.z1;
    this.z1 = x * this.b1 - this.a1 * y + this.z2;
    this.z2 = x * this.b0 - this.a2 * y;
    return y;
  }
}

class AudioDemodulator {
  constructor(mode, offsetHz) {
    this.mode = mode;
    this.side = mode === 'USB' ? 1 : mode === 'LSB' ? -1 : 0;
    const bandwidth = this.side ? 1500 : mode === 'NFM' ? 6500 : 5000;
    this.stepHz = 2 * Math.PI * (offsetHz + this.side * 1700) / IQ_RATE;
    this.sideHz = 2 * Math.PI * this.side * 1700 / IQ_RATE;
    this.filtersI = [new LowPass(bandwidth, 0.541196), new LowPass(bandwidth, 1.306563)];
    this.filtersQ = [new LowPass(bandwidth, 0.541196), new LowPass(bandwidth, 1.306563)];
    this.audioFilter = new LowPass(mode === 'NFM' ? 3400 : 5000);
    this.dcAlpha = 1 - Math.exp(-2 * Math.PI * 105 / IQ_RATE);
    this.osc = this.bfo = this.dc = 0;
    this.prevI = this.prevQ = 0;
    this.level = 0.01;
    this.decimate = false;
  }

  process(i, q) {
    const samples = new Float32Array(Math.ceil(i.length / 2));
    let length = 0;
    for (let k = 0; k < i.length; k++) {
      const cos = Math.cos(this.osc), sin = Math.sin(this.osc);
      this.osc = (this.osc + this.stepHz) % (2 * Math.PI);
      const re = this.filtersI[1].step(this.filtersI[0].step(i[k] * cos + q[k] * sin));
      const im = this.filtersQ[1].step(this.filtersQ[0].step(q[k] * cos - i[k] * sin));
      let audio;
      if (this.mode === 'NFM') {
        audio = Math.atan2(im * this.prevI - re * this.prevQ, re * this.prevI + im * this.prevQ)
          * IQ_RATE / (2 * Math.PI * 2500);
        this.prevI = re;
        this.prevQ = im;
      } else if (this.mode === 'AM') {
        audio = Math.hypot(re, im);
      } else {
        audio = re * Math.cos(this.bfo) - im * Math.sin(this.bfo);
        this.bfo = (this.bfo + this.sideHz) % (2 * Math.PI);
      }
      this.dc += this.dcAlpha * (audio - this.dc);
      audio = this.audioFilter.step(audio - this.dc);
      if (this.mode !== 'NFM') {
        this.level += 0.0005 * (Math.abs(audio) - this.level);
        audio *= Math.min(300, 0.24 / Math.max(this.level, 0.0008));
      } else {
        audio *= 0.5;
      }
      this.decimate = !this.decimate;
      if (this.decimate) samples[length++] = Math.max(-1, Math.min(1, audio));
    }
    return samples.subarray(0, length);
  }
}

if (typeof module !== 'undefined') {
  module.exports = {StreamDecoder, AudioDemodulator, requestFrame, IQ_RATE, AUDIO_RATE};
}
