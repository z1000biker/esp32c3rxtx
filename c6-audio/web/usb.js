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

