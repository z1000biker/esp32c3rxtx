'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {StreamDecoder, AudioDemodulator, requestFrame, IQ_RATE, AUDIO_RATE} = require('../web/receiver.js');

const BLOCK = 4096;
const OFFSET = 12000;
const TONE = 1000;

function streamBlock(mode, index) {
  const block = Buffer.alloc(BLOCK);
  block.write('EBLK');
  block.writeUInt16LE(4, 4);
  block.writeUInt16LE(1020, 6);
  block.writeBigUInt64LE(BigInt(index), 8);
  for (let k = 0; k < 1020; k++) {
    const t = (index + k) / IQ_RATE;
    const w = 2 * Math.PI * t;
    let angle = OFFSET * w;
    let amplitude = 0.18;
    if (mode === 'AM') amplitude *= 1 + 0.6 * Math.cos(TONE * w);
    if (mode === 'NFM') angle += 1.8 * Math.sin(TONE * w);
    if (mode === 'USB' || mode === 'LSB') {
      angle += (mode === 'USB' ? TONE : -TONE) * w;
    }
    block.writeInt16LE(Math.round(32767 * amplitude * Math.cos(angle)), 16 + k * 4);
    block.writeInt16LE(Math.round(32767 * amplitude * Math.sin(angle)), 18 + k * 4);
  }
  return block;
}

function amplitudeAt(samples, frequency) {
  let re = 0, im = 0;
  for (let k = 0; k < samples.length; k++) {
    const theta = k * 2 * Math.PI * frequency / AUDIO_RATE;
    re += samples[k] * Math.cos(theta);
    im += samples[k] * Math.sin(theta);
  }
  return 2 * Math.hypot(re, im) / samples.length;
}

for (const mode of ['NFM', 'AM', 'USB', 'LSB']) {
  test(mode + ' demodulates a 1 kHz tone from fragmented 16-bit USB I/Q blocks', () => {
    const demod = new AudioDemodulator(mode, OFFSET);
    const audio = [];
    let expected = 0n;
    const parser = new StreamDecoder(() => assert.fail('unexpected reply'), (i, q, index) => {
      assert.equal(index, expected);
      expected += BigInt(i.length);
      audio.push(...demod.process(i, q));
    });
    for (let block = 0; block < 24; block++) {
      const bytes = streamBlock(mode, block * 1020);
      for (let p = 0; p < bytes.length; p += 187) parser.push(bytes.subarray(p, p + 187));
    }
    const stable = audio.slice(2500);
    assert.equal(audio.length, 24 * 510);
    const wanted = amplitudeAt(stable, TONE);
    assert.ok(wanted > 0.08, 'audible tone amplitude');
    assert.ok(wanted > 40 * amplitudeAt(stable, 1850), '1 kHz dominates unrelated audio');
  });
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

test('the workbench sends received PCM to the Web Audio playback queue', async () => {
  const {readFileSync} = require('node:fs');
  const {join} = require('node:path');
  const vm = require('node:vm');
  const elements = new Map();
  const values = {volume: '65', freq: '2442', mode: 'NFM', zoom: '1'};
  function element(id) {
    if (!elements.has(id)) elements.set(id, {value: values[id], textContent: ''});
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
      commands.push(cmd);
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
      assert.equal(rate, AUDIO_RATE);
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
  assert.deepEqual(commands, [1, 2, 3, 4, 8, 13, 2]);
  receive(streamBlock('NFM', 0));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(pcm.length, 1);
  assert.equal(pcm[0].length, 510);
  assert.ok(amplitudeAt(pcm[0].slice(80), 1000) > 0.08);
  await element('stop').onclick();
  await element('connect').onclick();
});
