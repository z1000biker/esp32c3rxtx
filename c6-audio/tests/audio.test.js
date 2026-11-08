'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {StreamDecoder, AudioDemodulator, requestFrame, STREAM_PROFILES} = require('../web/receiver.js');

const BLOCK = 4096;

const TONE = 1000;

function streamBlock(mode, index, profile, extraFlags = 0) {
  const wide = profile.bits === 16;
  const count = wide ? 1020 : 2040;
  const block = Buffer.alloc(BLOCK);
  block.write('EBLK');
  block.writeUInt16LE((wide ? 4 : 0) | extraFlags, 4);
  block.writeUInt16LE(count, 6);
  block.writeBigUInt64LE(BigInt(index), 8);
  for (let k = 0; k < count; k++) {
    const t = (index + k) / profile.rate;
    const w = 2 * Math.PI * t;
    let angle = profile.tuningOffset * w;
    let amplitude = 0.18;
    if (mode === 'AM') amplitude *= 1 + 0.6 * Math.cos(TONE * w);
    if (mode === 'NFM') angle += 1.8 * Math.sin(TONE * w);
    if (mode === 'USB' || mode === 'LSB') {
      angle += (mode === 'USB' ? TONE : -TONE) * w;
    }
    const i = amplitude * Math.cos(angle), q = amplitude * Math.sin(angle);
    if (wide) {
      block.writeInt16LE(Math.round(32767 * i), 16 + k * 4);
      block.writeInt16LE(Math.round(32767 * q), 18 + k * 4);
    } else {
      block.writeInt8(Math.round(127 * i), 16 + k * 2);
      block.writeInt8(Math.round(127 * q), 17 + k * 2);
    }
  }
  return block;
}

function amplitudeAt(samples, frequency, audioRate) {
  let re = 0, im = 0;
  for (let k = 0; k < samples.length; k++) {
    const theta = k * 2 * Math.PI * frequency / audioRate;
    re += samples[k] * Math.cos(theta);
    im += samples[k] * Math.sin(theta);
  }
  return 2 * Math.hypot(re, im) / samples.length;
}

for (const [name, profile] of Object.entries(STREAM_PROFILES)) {
  for (const mode of ['NFM', 'AM', 'USB', 'LSB']) {
    test(name + ': ' + mode + ' demodulates 1 kHz from fragmented I/Q blocks', () => {
      const demod = new AudioDemodulator(mode, profile.tuningOffset, profile.rate);
      const audio = [];
      const count = profile.bits === 16 ? 1020 : 2040;
      let expected = 0n;
      const parser = new StreamDecoder(() => assert.fail('unexpected reply'), (i, q, index) => {
        assert.equal(index, expected);
        expected += BigInt(i.length);
        audio.push(...demod.process(i, q));
      });
      for (let block = 0; block < 16; block++) {
        const bytes = streamBlock(mode, block * count, profile);
        for (let p = 0; p < bytes.length; p += 187) parser.push(bytes.subarray(p, p + 187));
      }
      const stable = audio.slice(2500);
      assert.equal(audio.length, 16 * count / demod.decimation);
      const wanted = amplitudeAt(stable, TONE, demod.audioRate);
      assert.ok(wanted > 0.08, 'audible tone amplitude');
      assert.ok(wanted > 40 * amplitudeAt(stable, 1850, demod.audioRate), '1 kHz dominates unrelated audio');
    });
  }
}

test('serial requests and replies retain payload boundaries', () => {
  const bytes = Buffer.from(requestFrame(1, {size: 72}));
  assert.equal(bytes.toString('ascii', 0, 4), 'ESRL');
  assert.equal(bytes[5], 1);
  assert.equal(bytes.readUInt16LE(8), 72);
  assert.equal(bytes.length, 12);
  const reply = Buffer.concat([Buffer.from(requestFrame(1, {body: Uint8Array.of(0x45, 0x53)}))]);
  let calls = 0;
  const parser = new StreamDecoder((id, error, data) => {
    assert.deepEqual([id, error, ...data], [1, 0, 0x45, 0x53]);
    calls++;
  }, () => assert.fail('unexpected samples'));
  parser.push(Buffer.from([0xff, 0x00]));
  parser.push(reply.subarray(0, 9));
  parser.push(reply.subarray(9));
  assert.equal(calls, 1);
});

test('the workbench plays the low-rate stream and counts only real gaps', async () => {
  const {readFileSync} = require('node:fs');
  const {join} = require('node:path');
  const vm = require('node:vm');
  const elements = new Map();
  const values = {volume: '65', freq: '2442', mode: 'NFM', zoom: '1', profile: 'low'};
  function element(id) {
    if (!elements.has(id)) elements.set(id, {value: values[id], textContent: '', style: {}});
    return elements.get(id);
  }
  let reply, pendingRead;
  const commands = [], pcm = [];
  function receive(bytes) {
    if (pendingRead) {
      const resolve = pendingRead;
      pendingRead = null;
      resolve(bytes);
    } else {
      reply = bytes;
    }
  }
  const port = {
    read() {
      if (reply) {
        const bytes = reply;
        reply = null;
        return Promise.resolve(bytes);
      }
      return new Promise((resolve) => { pendingRead = resolve; });
    },
    async write(bytes) {
      const cmd = bytes[4];
      commands.push(Buffer.from(bytes));
      const message = Buffer.alloc(cmd === 1 ? 84 : 12);
      message.write('ESRL');
      message[4] = cmd;
      if (cmd === 1) {
        message.writeUInt16LE(72, 8);
        message.write('ESDR', 12);
        message.writeUInt16LE(1, 16);
        message.write('c6_audio_rx 0.1.0', 20);
      }
      receive(message);
    },
    async close() { receive(new Uint8Array()); },
  };
  class AudioContext {
    state = 'running';
    currentTime = 10;
    destination = {};
    resume() {}
    createGain() { return {gain: {}, connect() {}}; }
    createBuffer(channels, size, rate) {
      assert.equal(channels, 1);
      assert.equal(rate, STREAM_PROFILES.low.rate);
      return {duration: size / rate, copyToChannel: (samples) => pcm.push(samples)};
    }
    createBufferSource() {
      return {connect() {}, start(at) { assert.ok(at > 10); }, stop() {}};
    }
  }
  const context = vm.createContext({
    document: {getElementById: element}, window: {}, TextDecoder, AudioContext,
    performance, setTimeout, clearTimeout, setInterval() {}, Uint8Array, DataView,
    browserPort: async () => port, drawAll() {}, smoothSpectrum() {}, makeSpectrum() {},
  });
  for (const file of ['receiver.js', 'workbench.js']) {
    vm.runInContext(readFileSync(join(__dirname, '../web', file), 'utf8'), context);
  }
  await element('connect').onclick();
  await element('live').onclick();
  assert.deepEqual(commands.map((command) => command[4]), [1, 2, 3, 4, 8, 13, 2]);
  assert.equal(commands[3].readUInt32LE(12), 15625);
  assert.equal(commands[4].readUInt16LE(6), 8);
  receive(streamBlock('NFM', 0, STREAM_PROFILES.low));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(pcm.length, 1);
  assert.equal(pcm[0].length, 2040);
  assert.ok(amplitudeAt(pcm[0].slice(80), 1000, STREAM_PROFILES.low.rate) > 0.08);
  receive(streamBlock('NFM', 2040, STREAM_PROFILES.low, 3));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(vm.runInContext('gaps', context), 0);
  receive(streamBlock('NFM', 4090, STREAM_PROFILES.low));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(vm.runInContext('gaps', context), 1);
  assert.equal(vm.runInContext('lostSamples', context), 10n);
  await element('stop').onclick();
  await element('connect').onclick();
});
