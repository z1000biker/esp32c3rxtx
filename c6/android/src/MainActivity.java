package gr.sv1eex.c6sdr;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String PERMISSION = "gr.sv1eex.c6sdr.USB_PERMISSION";
    private static final int ESPRESSIF = 0x303a;
    private static final int USB_SERIAL_JTAG = 0x1001;
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private WebView web;
    private UsbManager manager;
    private volatile Session session;
    private volatile boolean destroyed;
    private int permissionRequest;
    private UsbDevice permissionDevice;

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PERMISSION.equals(intent.getAction())) {
                int id = permissionRequest;
                permissionRequest = 0;
                UsbDevice device = permissionDevice;
                permissionDevice = null;
                if (id == 0) return;
                if (device == null || !manager.hasPermission(device)) {
                    reply(id, "USB permission denied", "");
                    return;
                }
                openDevice(id, device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice device = deviceFrom(intent);
                Session current = session;
                if (current != null && current.device.equals(device)) closeSession();
            }
        }
    };

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceFrom(Intent intent) {
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (UsbManager) getSystemService(USB_SERVICE);
        IntentFilter filter = new IntentFilter(PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbEvents, filter);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setBlockNetworkLoads(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidUsb");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onDestroy() {
        destroyed = true;
        unregisterReceiver(usbEvents);
        closeSession();
        io.shutdownNow();
        web.removeJavascriptInterface("AndroidUsb");
        web.destroy();
        super.onDestroy();
    }

    private void reply(int id, String error, String value) {
        runOnUiThread(() -> {
            if (destroyed) return;
            web.evaluateJavascript("window.onUsbResult(" + id + "," + JSONObject.quote(error)
                    + "," + JSONObject.quote(value) + ")", null);
        });
    }

    private synchronized void closeSession() {
        Session current = session;
        session = null;
        if (current != null) current.connection.close();
    }

    private synchronized void openDevice(int id, UsbDevice device) {
        if (destroyed) return;
        closeSession();
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            reply(id, "Cannot open USB device", "");
            return;
        }
        try {
            session = Session.open(device, connection);
            reply(id, "", "");
        } catch (IllegalStateException e) {
            connection.close();
            reply(id, e.getMessage(), "");
        }
    }

    private final class Bridge {
        @JavascriptInterface public void open(int id) {
            runOnUiThread(() -> {
                UsbDevice device = null;
                for (UsbDevice candidate : manager.getDeviceList().values()) {
                    if (candidate.getVendorId() == ESPRESSIF && candidate.getProductId() == USB_SERIAL_JTAG) {
                        if (device != null) {
                            reply(id, "Connect only one Espressif board", "");
                            return;
                        }
                        device = candidate;
                    }
                }
                if (device == null) {
                    reply(id, "Connect ESP32-C6 USB through a data/OTG cable", "");
                } else if (manager.hasPermission(device)) {
                    openDevice(id, device);
                } else {
                    permissionRequest = id;
                    permissionDevice = device;
                    Intent intent = new Intent(PERMISSION).setPackage(getPackageName());
                    PendingIntent request = PendingIntent.getBroadcast(MainActivity.this, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    manager.requestPermission(device, request);
                }
            });
        }

        @JavascriptInterface public void read(int id) {
            Session current = session;
            if (current == null) {
                reply(id, "USB disconnected", "");
                return;
            }
            io.execute(() -> {
                byte[] packet = new byte[current.input.getMaxPacketSize()];
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
                while (session == current && bytes.size() < 4096) {
                    int n = current.connection.bulkTransfer(current.input, packet, packet.length,
                            bytes.size() == 0 ? 150 : 4);
                    if (n <= 0) break;
                    bytes.write(packet, 0, n);
                }
                if (session != current) reply(id, "USB disconnected", "");
                else reply(id, "", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
            });
        }

        @JavascriptInterface public void write(int id, String value) {
            Session current = session;
            if (current == null) {
                reply(id, "USB disconnected", "");
                return;
            }
            io.execute(() -> {
                byte[] bytes = Base64.decode(value, Base64.DEFAULT);
                int count = current.connection.bulkTransfer(current.output, bytes, bytes.length, 1000);
                reply(id, count == bytes.length ? "" : "USB write failed", "");
            });
        }

        @JavascriptInterface public void close(int id) {
            closeSession();
            reply(id, "", "");
        }
    }

    private static final class Session {
        final UsbDevice device;
        final UsbDeviceConnection connection;
        final UsbEndpoint input, output;

        Session(UsbDevice device, UsbDeviceConnection connection, UsbEndpoint input, UsbEndpoint output) {
            this.device = device;
            this.connection = connection;
            this.input = input;
            this.output = output;
        }

        static Session open(UsbDevice device, UsbDeviceConnection connection) {
            UsbInterface control = null;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM
                        && iface.getInterfaceSubclass() == 2) control = iface;
            }
            if (control == null) throw new IllegalStateException("CDC control interface missing");
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface data = device.getInterface(i);
                if (data.getInterfaceClass() != UsbConstants.USB_CLASS_CDC_DATA
                        || data.getId() != control.getId() + 1) continue;
                UsbEndpoint input = null, output = null;
                for (int j = 0; j < data.getEndpointCount(); j++) {
                    UsbEndpoint endpoint = data.getEndpoint(j);
                    if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) input = endpoint;
                    else output = endpoint;
                }
                if (input == null || output == null) continue;
                if (!connection.claimInterface(control, true) || !connection.claimInterface(data, true)) {
                    throw new IllegalStateException("Cannot claim CDC serial interfaces");
                }
                byte[] coding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(115200).put((byte) 0).put((byte) 0).put((byte) 8).array();
                connection.controlTransfer(0x21, 0x20, 0, control.getId(), coding, coding.length, 500);
                connection.controlTransfer(0x21, 0x22, 1, control.getId(), null, 0, 500);
                return new Session(device, connection, input, output);
            }
            throw new IllegalStateException("CDC bulk endpoints missing");
        }
    }
}
