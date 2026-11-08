'use strict';

const $ = (id) => document.getElementById(id);
const FFTN = 16384;
const RATE_HZ = 80000000;
const encoder = new TextEncoder();
const decoder = new TextDecoder();
const crcTable = Uint32Array.from({length: 256}, (_, value) => {
  for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
  return value;
});

let port, waiter, pendingCapture, wire = new Uint8Array(0);
let avgMag, peakMag, latestSpectrum, drawScheduled = false;
let state = 'idle', ready = false, busy = false;
let center = 2442, lastFs = RATE_HZ, frames = 0, crcErrors = 0;

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function log(message) {
  const view = $('log');
  view.textContent = (view.textContent + message + '\n').slice(-12000);
  view.scrollTop = view.scrollHeight;
}

function showStatus(text, css = '') {
  $('connTop').textContent = text;
  $('connTop').className = 'status ' + css;
}

function renderState() {
  const idle = ready && !busy && state === 'idle';
  $('connect').disabled = busy;
  $('connect').textContent = port ? 'Disconnect USB' : 'Connect USB';
  for (const id of ['tune', 'live', 'fine']) $(id).disabled = !idle;
  for (const id of ['freq', 'rfbw']) $(id).disabled = busy || state !== 'idle';
  $('stop').disabled = state !== 'live' && state !== 'fine';
  $('stateCard').textContent = state.toUpperCase();
  $('statCard').textContent = frames + ' frames · ' + crcErrors + ' CRC errors';
}

function updateAxis() {
  const zoom = Number($('zoom').value) || 1;
  const span = lastFs / zoom / 1e6;
  $('leftFreq').textContent = (center - span / 2).toFixed(3) + ' MHz';
  $('rightFreq').textContent = (center + span / 2).toFixed(3) + ' MHz';
  $('spanText').textContent = 'Span ' + span.toFixed(3) + ' MHz';
  $('zoomText').value = zoom + '×';
}

function redraw(magnitude) {
  latestSpectrum = magnitude;
  if (drawScheduled) return;
  drawScheduled = true;
  requestAnimationFrame(() => {
    drawScheduled = false;
    if (latestSpectrum) drawAll(smoothSpectrum(latestSpectrum));
    latestSpectrum = null;
    renderState();
  });
}

function settle(error, value) {
  if (!waiter) return;
  const active = waiter;
  waiter = null;
  clearTimeout(active.timer);
  if (error) active.reject(error);
  else active.resolve(value);
}

function command(text, match, timeout = 4500) {
  if (!port || waiter) throw Error('USB is disconnected or busy');
  const connection = port;
  return new Promise((resolve, reject) => {
    const request = {resolve, reject, match};
    request.timer = setTimeout(() => {
      if (waiter === request) settle(Error('Timeout: ' + text));
    }, timeout);
    waiter = request;
    Promise.resolve().then(() => connection.write(encoder.encode(text + '\n'))).catch((error) => {
      if (waiter === request) settle(error);
    });
    log('> ' + text);
  });
}

function onLine(line) {
  const data = /^DATA (\d+) ([a-f\d]{8}) (\d+)$/i.exec(line);
  if (data && Number(data[1]) >= 256 && Number(data[1]) <= 16380) {
    pendingCapture = {count: Number(data[1]), crc: parseInt(data[2], 16)};
    return;
  }
  if (!line) return;
  log('< ' + line);
  if (line.startsWith('SPECEND ') && (state === 'live' || state === 'stopping')) {
    state = 'idle';
    renderState();
  }
  if (line.startsWith('ERR ')) settle(Error(line));
  else if (waiter && waiter.match(line)) settle(null, line);
}

function onSpectrum(frame, bins) {
  const view = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
  if (crc32(frame.subarray(0, -4)) !== view.getUint32(frame.length - 4, true)) {
    crcErrors++;
    return;
  }
  if (state !== 'live') return;
  const values = new Float32Array(bins);
  const step = frame[27] || 2;
  for (let i = 0; i < bins; i++) {
    const value = frame[28 + (bins / 2 - i + bins) % bins];
    values[i] = value ? value / step - 84.3 : -140;
  }
  frames++;
  redraw(values);
}

function feed(bytes) {
  const joined = new Uint8Array(wire.length + bytes.length);
  joined.set(wire);
  joined.set(bytes, wire.length);
  wire = joined;
  while (wire.length) {
    if (pendingCapture) {
      const {count, crc} = pendingCapture;
      const length = Math.ceil(count * 20 / 8);
      if (wire.length < length) return;
      const data = wire.subarray(0, length);
      wire = wire.subarray(length);
      pendingCapture = null;
      if (crc32(data) !== crc) {
        crcErrors++;
        if (waiter?.match('DATA')) settle(Error('I/Q CRC mismatch'));
        continue;
      }
      if (state === 'fine') {
        const {I, Q} = decodeIQ20(data, count);
        for (let i = 0; i < Q.length; i++) Q[i] = -Q[i];
        frames++;
        redraw(makeSpectrum(I, Q));
      }
      if (waiter?.match('DATA')) settle(null, 'DATA');
      continue;
    }
    if (wire[0] === 83 && wire[1] === 80 && wire[2] === 67 && wire[3] === 49) {
      if (wire.length < 28) return;
      const bits = wire[26];
      if (bits < 8 || bits > 11) throw Error('Invalid spectrum frame size');
      const bins = 1 << bits;
      const length = 32 + bins;
      if (wire.length < length) return;
      onSpectrum(wire.subarray(0, length), bins);
      wire = wire.subarray(length);
      continue;
    }
    const end = wire.indexOf(10);
    if (end < 0) {
      if (wire.length > 4096) throw Error('Unrecognized USB data; reset the board and reconnect');
      return;
    }
    const line = decoder.decode(wire.subarray(0, end)).trim();
    wire = wire.subarray(end + 1);
    onLine(line);
  }
}

async function browserPort() {
  if (!navigator.serial) throw Error('Use Chrome or Edge with the ESP32-C6 USB port');
  const device = await navigator.serial.requestPort({filters: [{usbVendorId: 0x303a, usbProductId: 0x1001}]});
  await device.open({baudRate: 115200, bufferSize: 262144});
  const reader = device.readable.getReader();
  const writer = device.writable.getWriter();
  return {
    read: async () => {
      const {value, done} = await reader.read();
      if (done) throw Error('USB disconnected');
      return value;
    },
    write: (bytes) => writer.write(bytes),
    async close() {
      try { await reader.cancel(); } catch {}
      reader.releaseLock();
      writer.releaseLock();
      await device.close();
    },
  };
}

const callbacks = new Map();
let nextCallback = 0;
window.onUsbResult = (id, error, payload) => {
  const callback = callbacks.get(id);
  if (!callback) return;
  callbacks.delete(id);
  if (error) callback.reject(Error(error));
  else callback.resolve(payload);
};

function androidCall(operation, ...args) {
  return new Promise((resolve, reject) => {
    const id = ++nextCallback;
    callbacks.set(id, {resolve, reject});
    try { AndroidUsb[operation](id, ...args); }
    catch (error) {
      callbacks.delete(id);
      reject(error);
    }
  });
}

async function androidPort() {
  await androidCall('open');
  return {
    read: async () => Uint8Array.from(atob(await androidCall('read')), (ch) => ch.charCodeAt(0)),
    write: (bytes) => androidCall('write', btoa(String.fromCharCode(...bytes))),
    close: () => androidCall('close'),
  };
}

async function readLoop(connection) {
  try {
    while (port === connection) {
      const bytes = await connection.read();
      if (port === connection && bytes.length) feed(bytes);
    }
  } catch (error) {
    if (port === connection) {
      await disconnect();
      showStatus('USB LOST', 'bad');
      log(error.message);
    }
  }
}

async function disconnect() {
  const connection = port;
  port = null;
  ready = false;
  state = 'idle';
  pendingCapture = null;
  wire = new Uint8Array(0);
  settle(Error('USB disconnected'));
  try { if (connection) await connection.close(); } catch {}
  $('fwCard').textContent = '—';
  showStatus('DISCONNECTED');
  renderState();
}

async function connect() {
  if (port) {
    if (state === 'live') await stopReception();
    await disconnect();
    return;
  }
  showStatus('OPENING USB');
  port = window.AndroidUsb ? await androidPort() : await browserPort();
  const connection = port;
  readLoop(connection);
  try {
    let identity;
    for (let attempt = 0; attempt < 3 && !identity; attempt++) {
      if (port !== connection) throw Error('USB disconnected');
      const nonce = String(Date.now() + attempt);
      try {
        await command('SYNC ' + nonce, (line) => line === 'SYNC ' + nonce, 1000);
        identity = await command('INFO', (line) => /^(C6SDR|C3TRX|C3SDR) /.test(line));
      } catch (error) {
        if (attempt === 2) throw error;
      }
    }
    if (!identity.startsWith('C6SDR ')) throw Error('Expected ESP32-C6 ESP-SDR. Device says ' + identity);
    $('fwCard').textContent = identity.split(' ').slice(0, 2).join(' ');
    ready = true;
    showStatus('READY', 'ok');
  } catch (error) {
    await disconnect();
    throw error;
  }
}

async function tune() {
  const mhz = Number($('freq').value);
  if (!Number.isInteger(mhz) || mhz < 2300 || mhz > 2500) throw Error('Enter a whole MHz from 2300 to 2500');
  await command('FREQ ' + mhz, (line) => line === 'OK');
  center = mhz;
  $('freqCard').textContent = center + ' MHz';
  avgMag = peakMag = null;
  updateAxis();
}

async function startReception(kind) {
  await tune();
  const bandwidth = Number($('rfbw').value);
  if (!Number.isInteger(bandwidth) || (bandwidth !== 0 && (bandwidth < 12 || bandwidth > 54))) {
    throw Error('RF bandwidth must be 0 or a whole MHz from 12 to 54');
  }
  await command('BANDWIDTH ' + bandwidth, (line) => line === 'OK');
  lastFs = RATE_HZ;
  frames = crcErrors = 0;
  state = kind;
  if (kind === 'live') {
    const response = await command('SPEC 0 1 1 0 0 256', (line) => /^SPEC \d+ /.test(line));
    lastFs = Number(response.split(' ')[2]);
  } else {
    runSnapshots(port);
  }
  updateAxis();
}

async function runSnapshots(connection) {
  try {
    while (state === 'fine' && port === connection) {
      await command('CAP20 16380 0', (line) => line === 'DATA');
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
  } catch (error) {
    if (port === connection) {
      log(error.message);
      await disconnect();
    }
  } finally {
    if (port === connection) {
      state = 'idle';
      renderState();
    }
  }
}

async function stopReception() {
  const previous = state;
  state = 'stopping';
  renderState();
  if (previous === 'live') {
    try {
      await command('', (line) => line.startsWith('SPECEND '), 3000);
    } catch (error) {
      await disconnect();
      throw error;
    }
  }
}

function bind(id, operation) {
  $(id).onclick = async () => {
    if (busy) return;
    busy = true;
    renderState();
    try { await operation(); }
    catch (error) {
      log(error.message);
      if (state !== 'idle') await disconnect();
      if (!port) showStatus('DISCONNECTED', 'bad');
    } finally {
      busy = false;
      renderState();
    }
  };
}

bind('connect', connect);
bind('tune', tune);
bind('live', () => startReception('live'));
bind('fine', () => startReception('fine'));
bind('stop', stopReception);
$('zoom').oninput = () => {
  updateAxis();
  if (avgMag) drawAll(avgMag);
};
for (const id of ['wfMin', 'wfMax', 'peak']) $(id).onchange = () => {
  if (!$('peak').checked) peakMag = null;
  if (avgMag) drawAll(avgMag);
};
updateAxis();
renderState();
