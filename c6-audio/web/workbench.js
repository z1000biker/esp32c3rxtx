'use strict';

const $ = (id) => document.getElementById(id);
const FFTN = 2048;
const INFO = 1, MODE = 2, FREQUENCY = 3, RATE = 4, FORMAT = 8, DIGITAL_GAIN = 13;
let port, waiter, demod, audio, volume, audioAt, pcmNodes = new Set();
let avgMag, peakMag, frame = 0, expectedIndex, gaps = 0, lostSamples = 0n, lastIqAt = 0;
let profile = STREAM_PROFILES.low;
let ready = false, receiving = false, busy = false, centerHz = 2442000000, offsetHz = 0;
const decoder = new TextDecoder();

function log(message) {
  const view = $('log');
  view.textContent = (view.textContent + message + '\n').slice(-8000);
  view.scrollTop = view.scrollHeight;
}

function render() {
  $('connect').textContent = port ? 'Disconnect USB' : 'Connect USB';
  $('connect').disabled = busy;
  $('live').disabled = !ready || busy || receiving;
  $('stop').disabled = !receiving || busy;
  $('freq').disabled = receiving || busy;
  $('profile').disabled = receiving || busy;
  $('stateCard').textContent = receiving ? 'AUDIO RX' : 'IDLE';
  $('statCard').textContent = frame + ' blocks · ' + gaps + ' gaps · ' + lostSamples + ' lost samples';
  const zoom = Number($('zoom').value) || 1;
  const span = profile.rate / zoom;
  const marker = 50 + 100 * offsetHz / span;
  $('tunedMarker').style.left = marker + '%';
  $('tunedMarker').hidden = marker < 0 || marker > 100;
  $('leftFreq').textContent = ((centerHz - span / 2) / 1e6).toFixed(6);
  $('rightFreq').textContent = ((centerHz + span / 2) / 1e6).toFixed(6) + ' MHz';
  $('spanText').textContent = 'Span ' + (span / 1000).toFixed(2) + ' kHz';
  $('zoomText').value = zoom + '×';
}

function status(text, cls = '') {
  $('connTop').textContent = text;
  $('connTop').className = 'status ' + cls;
}

function settle(error, data) {
  if (!waiter) return;
  const request = waiter;
  waiter = null;
  clearTimeout(request.timer);
  if (error) request.reject(error);
  else request.resolve(data);
}

function request(cmd, options) {
  if (!port || waiter) throw Error('USB disconnected or busy');
  const connection = port;
  return new Promise((resolve, reject) => {
    const active = {cmd, resolve, reject};
    active.timer = setTimeout(() => {
      if (waiter === active) settle(Error('Firmware did not answer command ' + cmd));
    }, 3000);
    waiter = active;
    Promise.resolve().then(() => connection.write(requestFrame(cmd, options))).catch((error) => {
      if (waiter === active) settle(error);
    });
  });
}

function onReply(cmd, result, payload) {
  if (waiter?.cmd !== cmd) return;
  settle(result ? Error('Firmware error ' + result + ' for command ' + cmd) : null, payload);
}

function integerPayload(value, bytes) {
  const body = new Uint8Array(bytes);
  const view = new DataView(body.buffer);
  if (bytes === 8) view.setBigUint64(0, BigInt(value), true);
  else view.setUint32(0, value, true);
  return body;
}

function clearAudio() {
  for (const node of pcmNodes) {
    try { node.stop(); } catch {}
  }
  pcmNodes.clear();
  audioAt = 0;
}

async function prepareAudio() {
  if (!audio) {
    audio = new AudioContext();
    volume = audio.createGain();
    volume.gain.value = Number($('volume').value) / 100;
    volume.connect(audio.destination);
  }
  await audio.resume();
  clearAudio();
}

function queueAudio(samples) {
  if (!samples.length || audio.state !== 'running') return;
  const now = audio.currentTime;
  if (audioAt < now || audioAt > now + 0.55) {
    clearAudio();
    audioAt = now + 0.15;
  }
  const pcm = audio.createBuffer(1, samples.length, demod.audioRate);
  pcm.copyToChannel(samples, 0);
  const node = audio.createBufferSource();
  node.buffer = pcm;
  node.connect(volume);
  pcmNodes.add(node);
  node.onended = () => {
    pcmNodes.delete(node);
    node.disconnect();
  };
  node.start(audioAt);
  audioAt += pcm.duration;
}

function onIq(i, q, index) {
  if (!receiving) return;
  // A queue-full flag may be set even if the host drained it before any samples were lost.
  if (expectedIndex !== undefined && index !== expectedIndex) {
    gaps++;
    if (index > expectedIndex) lostSamples += index - expectedIndex;
    demod = new AudioDemodulator($('mode').value, offsetHz, profile.rate);
  }
  expectedIndex = index + BigInt(i.length);
  lastIqAt = performance.now();
  queueAudio(demod.process(i, q));
  frame++;
  if (frame % 8 === 1) {
    drawAll(smoothSpectrum(makeSpectrum(i, q)));
    render();
  }
}

async function readLoop(connection) {
  const stream = new StreamDecoder(onReply, onIq);
  try {
    while (port === connection) {
      const bytes = await connection.read();
      if (port === connection && bytes.length) stream.push(bytes);
    }
  } catch (error) {
    if (port === connection) {
      log(error.message);
      await disconnect();
      status('USB LOST', 'bad');
    }
  }
}

async function disconnect() {
  const connection = port;
  port = null;
  ready = receiving = false;
  settle(Error('USB disconnected'));
  clearAudio();
  try { if (connection) await connection.close(); } catch {}
  $('fwCard').textContent = '—';
  status('DISCONNECTED');
}

async function connect() {
  if (port) {
    try { if (receiving) await stop(); } finally { await disconnect(); }
    return;
  }
  status('OPENING USB');
  port = window.AndroidUsb ? await androidPort() : await browserPort();
  readLoop(port);
  try {
    const data = await request(INFO, {size: 72});
    const name = decoder.decode(data.subarray(8, 40)).replace(/\0.*$/, '');
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    if (data.length < 72 || view.getUint32(0, true) !== 0x52445345
      || view.getUint16(4, true) !== 1 || !name.startsWith('c6_audio_rx ')) {
      throw Error('Flash the ESP32-C6 Audio RX firmware first');
    }
    ready = true;
    $('fwCard').textContent = name;
    status('READY', 'ok');
  } catch (error) {
    await disconnect();
    throw Error(error.message + '. The previous spectrum-only firmware cannot play audio.');
  }
}

async function start() {
  const signalHz = Math.round(Number($('freq').value) * 1e6);
  if (signalHz < 2400000000 || signalHz > 2500000000 || !Number.isFinite(signalHz)) {
    throw Error('Enter the analog signal frequency in MHz (2400–2500)');
  }
  await prepareAudio();
  profile = STREAM_PROFILES[$('profile').value] ?? STREAM_PROFILES.low;
  centerHz = Math.round((signalHz - profile.tuningOffset) / 1000) * 1000;
  if (centerHz < 2400000000) {
    centerHz = Math.round((signalHz + profile.tuningOffset) / 1000) * 1000;
  }
  offsetHz = signalHz - centerHz;
  await request(MODE, {value: 0});
  await request(FREQUENCY, {body: integerPayload(centerHz, 8)});
  await request(RATE, {body: integerPayload(profile.rate, 4)});
  await request(FORMAT, {value: profile.bits});
  await request(DIGITAL_GAIN, {value: profile.gain});
  expectedIndex = undefined;
  frame = gaps = 0;
  lostSamples = 0n;
  avgMag = peakMag = null;
  demod = new AudioDemodulator($('mode').value, offsetHz, profile.rate);
  lastIqAt = performance.now();
  receiving = true;
  try { await request(MODE, {value: 1}); }
  catch (error) {
    receiving = false;
    clearAudio();
    throw error;
  }
  $('freqCard').textContent = (signalHz / 1e6).toFixed(6) + ' MHz';
  status('RX + AUDIO', 'ok');
}

async function stop() {
  receiving = false;
  clearAudio();
  await request(MODE, {value: 0});
  status('READY', 'ok');
}

function bind(id, action) {
  $(id).onclick = async () => {
    if (busy) return;
    busy = true;
    render();
    try { await action(); }
    catch (error) {
      log(error.message);
      status('ERROR', 'bad');
    } finally {
      busy = false;
      render();
    }
  };
}

bind('connect', connect);
bind('live', start);
bind('stop', stop);
$('mode').onchange = () => {
  if (receiving) demod = new AudioDemodulator($('mode').value, offsetHz, profile.rate);
};
$('volume').oninput = () => {
  if (volume) volume.gain.setTargetAtTime(Number($('volume').value) / 100, audio.currentTime, 0.01);
};
$('zoom').oninput = render;
setInterval(() => {
  if (receiving && !busy && performance.now() - lastIqAt > 3000) {
    $('stop').click();
    log('No I/Q blocks received for three seconds. Check the USB connection and firmware.');
  }
}, 1000);
render();
