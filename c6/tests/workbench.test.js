const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function fakeWorkbench() {
  const source = fs.readFileSync(path.join(__dirname, '../web/workbench.js'), 'utf8');
  const dom = new Map();
  const drawn = [];
  const sent = [];
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();
  let context;
  const node = (id) => {
    if (!dom.has(id)) dom.set(id, {textContent: '', value: {freq: '2442', rfbw: '0', zoom: '1'}[id] || ''});
    return dom.get(id);
  };
  const evaluate = (code) => vm.runInContext(code, context);
  const feed = (bytes) => {
    context.incoming = Uint8Array.from(bytes);
    evaluate('feed(incoming)');
  };
  const spectrum = Buffer.alloc(288);
  spectrum.write('SPC1');
  spectrum[26] = 8;
  spectrum[27] = 2;
  spectrum[28 + 3] = 100;
  const makeCapture = (samples) => {
    const payload = Buffer.alloc(Math.ceil(samples * 20 / 8));
    payload[2] = 8;
    return payload;
  };
  let deliver;
  const queue = [];
  const deliverBytes = (value) => {
    if (deliver) {
      const done = deliver;
      deliver = null;
      done({value});
    } else queue.push(value);
  };
  let active = true;
  const fakeSerial = {
    requestPort: async () => ({
      open: async () => {},
      close: async () => { active = false; },
      readable: {getReader: () => ({
        read: () => queue.length ? Promise.resolve({value: queue.shift()}) : new Promise((resolve) => { deliver = resolve; }),
        releaseLock() {},
        cancel: async () => {
          if (deliver) deliver({done: true});
          deliver = null;
        },
      })},
      writable: {getWriter: () => ({
        releaseLock() {},
        write: async (bytes) => {
          assert.ok(active);
          const cmd = decoder.decode(bytes).trim();
          sent.push(cmd);
          if (cmd.startsWith('SYNC ')) deliverBytes(encoder.encode(cmd + '\n'));
          else if (cmd === 'INFO') deliverBytes(encoder.encode('C6SDR 6 burst 16380\n'));
          else if (cmd.startsWith('FREQ ') || cmd.startsWith('BANDWIDTH ')) deliverBytes(encoder.encode('OK\n'));
          else if (cmd.startsWith('SPEC ')) deliverBytes(encoder.encode('SPEC 256 80000000 256 2442\n'));
          else if (!cmd) deliverBytes(encoder.encode('SPECEND 1 0 0\n'));
          else if (cmd.startsWith('CAP20 ')) {
            const payload = makeCapture(16380);
            context.capture = payload;
            const crc = evaluate('crc32(capture)').toString(16).padStart(8, '0');
            deliverBytes(encoder.encode('DATA 16380 ' + crc + ' 210\n'));
            deliverBytes(payload);
          } else assert.fail('Unexpected command ' + cmd);
        },
      })},
    }),
  };
  context = vm.createContext({
    document: {getElementById: node},
    window: {}, navigator: {serial: fakeSerial},
    TextEncoder, TextDecoder, Uint8Array, Float32Array, DataView,
    setTimeout, clearTimeout,
    requestAnimationFrame: (callback) => callback(),
    drawAll: (bins) => drawn.push(bins),
    smoothSpectrum: (bins) => bins,
    decodeIQ20: (data, count) => ({I: new Float32Array(count), Q: new Float32Array(count).fill(1)}),
    makeSpectrum: (_i, q) => {
      assert.equal(q[0], -1, 'correct on-wire Q sign');
      return new Float32Array([12]);
    },
  });
  vm.runInContext(source, context);
  context.spectrum = spectrum;
  spectrum.writeUInt32LE(evaluate('crc32(spectrum.subarray(0, -4))'), spectrum.length - 4);
  return {evaluate, feed, node, sent, drawn, spectrum};
}

test('C6 handshake, live spectrum with split USB packets, CRC and stop', async () => {
  const app = fakeWorkbench();
  await app.node('connect').onclick();
  assert.equal(app.node('fwCard').textContent, 'C6SDR 6');
  await app.node('live').onclick();
  assert.equal(app.sent.at(-1), 'SPEC 0 1 1 0 0 256');
  for (let i = 0; i < app.spectrum.length; i += 11) app.feed(app.spectrum.subarray(i, i + 11));
  assert.equal(app.drawn.length, 1);
  assert.ok(Math.abs(app.drawn[0][125] - (-34.3)) < 0.01);
  app.spectrum[40] ^= 1;
  app.feed(app.spectrum);
  assert.equal(app.evaluate('crcErrors'), 1);
  await app.node('stop').onclick();
  assert.equal(app.node('stateCard').textContent, 'IDLE');
  await app.node('connect').onclick();
  assert.equal(app.node('connTop').textContent, 'DISCONNECTED');
});

test('fine I/Q reaches FFT with corrected Q and can stop after a capture', {timeout: 1500}, async () => {
  const app = fakeWorkbench();
  await app.node('connect').onclick();
  await app.node('fine').onclick();
  while (!app.drawn.length) await new Promise((resolve) => setTimeout(resolve, 1));
  await app.node('stop').onclick();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.ok(app.drawn.length >= 1);
  assert.equal(app.drawn[0][0], 12);
  assert.equal(app.node('stateCard').textContent, 'IDLE');
  await app.node('connect').onclick();
});
