package com.getfunds.lgr100connector;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int LG_VENDOR_ID = 0x1004;
    private static final int LGR100_PRODUCT_ID = 0x6374;
    private static final String ACTION_USB_PERMISSION = "com.getfunds.lgr100connector.USB_PERMISSION";

    private UsbManager usbManager;
    private DisplayManager displayManager;
    private UsbDevice headset;
    private TextView status;
    private TextView log;
    private Presentation presentation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean activating = false;
    private volatile boolean keepAliveEnabled = false;

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override public void run() {
            if (!keepAliveEnabled || headset == null || !usbManager.hasPermission(headset)) return;
            new Thread(() -> sendKeepAliveOnce(), "lgr100-keepalive").start();
            handler.postDelayed(this, 1500);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                append(granted ? "USB permission granted" : "USB permission denied");
                if (device != null && isLgr100(device)) headset = device;
                refreshStatus();
                if (granted) handler.postDelayed(() -> activateHeadset(), 250);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                if (device != null && isLgr100(device)) {
                    headset = device;
                    append("LGR100 attached");
                    describeDevice(device);
                    requestPermission();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                if (device != null && isLgr100(device)) {
                    append("LGR100 detached");
                    keepAliveEnabled = false;
                    handler.removeCallbacks(keepAliveRunnable);
                    headset = null;
                    if (presentation != null) presentation.dismiss();
                    presentation = null;
                    refreshStatus();
                }
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        buildUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);

        append("LG 360 VR Connector V0.2");
        append("Target USB ID 1004:6374");
        append("Wake sequence: drain IN -> Sleep Disable -> VR App Start");
        scan();
        refreshStatus();
    }

    @Override protected void onDestroy() {
        keepAliveEnabled = false;
        handler.removeCallbacks(keepAliveRunnable);
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (presentation != null) presentation.dismiss();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("LG 360 VR Connector V0.2");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status);

        Button scan = button("Scan for LGR100");
        scan.setOnClickListener(v -> { scan(); refreshStatus(); });
        root.addView(scan);

        Button permission = button("Request USB permission");
        permission.setOnClickListener(v -> requestPermission());
        root.addView(permission);

        Button activate = button("Wake LGR100 (V0.2 sequence)");
        activate.setOnClickListener(v -> activateHeadset());
        root.addView(activate);

        Button test = button("Show bright VR test display");
        test.setOnClickListener(v -> showTestDisplay());
        root.addView(test);

        log = new TextView(this);
        log.setTextSize(12);
        log.setTextColor(Color.DKGRAY);
        log.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);
        setContentView(root);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void scan() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        headset = null;
        for (UsbDevice d : devices.values()) {
            if (isLgr100(d)) {
                headset = d;
                append(String.format(Locale.US, "Found %04X:%04X with %d interfaces", d.getVendorId(), d.getProductId(), d.getInterfaceCount()));
                describeDevice(d);
                break;
            }
        }
        if (headset == null) append("LGR100 not currently detected");
    }

    private boolean isLgr100(UsbDevice d) {
        return d != null && d.getVendorId() == LG_VENDOR_ID && d.getProductId() == LGR100_PRODUCT_ID;
    }

    private void describeDevice(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            append("Interface " + i + " class=" + intf.getInterfaceClass() + " endpoints=" + intf.getEndpointCount());
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                append(String.format(Locale.US, "  EP 0x%02X dir=%s type=%d packet=%d", ep.getAddress(), ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT", ep.getType(), ep.getMaxPacketSize()));
            }
        }
    }

    private void requestPermission() {
        if (headset == null) scan();
        if (headset == null) { append("Cannot request permission: headset not detected"); return; }
        if (usbManager.hasPermission(headset)) { append("USB permission already granted"); refreshStatus(); return; }

        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        usbManager.requestPermission(headset, pi);
        append("USB permission requested");
    }

    private void activateHeadset() {
        if (activating) { append("Activation already running"); return; }
        if (headset == null) scan();
        if (headset == null) { append("Activation stopped: headset not detected"); return; }
        if (!usbManager.hasPermission(headset)) { append("USB permission required first"); requestPermission(); return; }

        activating = true;
        append("=== V0.2 WAKE SEQUENCE START ===");
        new Thread(this::doActivationSequence, "lgr100-activate").start();
    }

    private void doActivationSequence() {
        UsbDevice device = headset;
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            append("openDevice() failed");
            activating = false;
            return;
        }

        UsbInterface hid = null;
        UsbEndpoint epIn = null;
        UsbEndpoint epOut = null;

        try {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
                hid = intf;
                boolean claimed = connection.claimInterface(intf, true);
                append("Claim HID interface " + intf.getId() + " = " + claimed);
                if (!claimed) { hid = null; continue; }

                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep;
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep;
                }
                break;
            }

            if (hid == null) {
                append("No claimable HID interface found");
                return;
            }
            append("IN endpoint: " + (epIn != null ? String.format(Locale.US, "0x%02X", epIn.getAddress()) : "NONE"));
            append("OUT endpoint: " + (epOut != null ? String.format(Locale.US, "0x%02X", epOut.getAddress()) : "NONE"));

            // Working desktop activators first consume pending input from EP 0x81.
            if (epIn != null) drainInput(connection, epIn);

            // Sleep Disable prevents the proximity/sleep logic from keeping the LCD dark.
            boolean sleepOk = sendCommand(connection, hid, epOut, "Sleep Disable");
            sleepQuiet(120);
            if (epIn != null) readResponse(connection, epIn, "after Sleep Disable");

            // This is the command known to start the display controller.
            boolean startOk = sendCommand(connection, hid, epOut, "VR App Start");
            sleepQuiet(250);
            if (epIn != null) readResponse(connection, epIn, "after VR App Start");

            append("Sleep Disable sent=" + sleepOk + " | VR App Start sent=" + startOk);

            if (sleepOk || startOk) {
                keepAliveEnabled = true;
                handler.removeCallbacks(keepAliveRunnable);
                handler.postDelayed(keepAliveRunnable, 1000);
            }

        } catch (Throwable t) {
            append("Activation error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (hid != null) {
                try { connection.releaseInterface(hid); } catch (Throwable ignored) {}
            }
            connection.close();
            activating = false;
        }

        append("=== V0.2 WAKE SEQUENCE COMPLETE ===");
        handler.postDelayed(() -> {
            refreshStatus();
            Display d = findExternalDisplay();
            if (d != null) {
                appendDisplayInfo(d);
                showTestDisplay();
            }
        }, 400);
    }

    private void drainInput(UsbDeviceConnection connection, UsbEndpoint epIn) {
        byte[] buffer = new byte[Math.max(64, epIn.getMaxPacketSize())];
        int packets = 0;
        int bytes = 0;
        for (int i = 0; i < 32; i++) {
            int n = connection.bulkTransfer(epIn, buffer, buffer.length, 40);
            if (n <= 0) break;
            packets++;
            bytes += n;
            if (packets <= 4) append("Drain IN packet " + packets + ": " + hex(buffer, n));
        }
        append("Drained IN endpoint: packets=" + packets + " bytes=" + bytes);
    }

    private boolean sendCommand(UsbDeviceConnection connection, UsbInterface hid, UsbEndpoint epOut, String command) {
        byte[] payload = buildCommand(command);
        append("SEND " + command + " -> " + hex(payload, payload.length));
        boolean ok = false;

        // Primary path: exact raw HID packet to interrupt OUT EP 0x01, matching known working tools.
        if (epOut != null) {
            int out = connection.bulkTransfer(epOut, payload, payload.length, 1500);
            append("  OUT endpoint result=" + out + "/" + payload.length);
            if (out == payload.length) ok = true;
        }

        // Fallback A: HID feature Set_Report, report ID 0. Used by libusb implementations.
        int feature = connection.controlTransfer(0x21, 0x09, 0x0300, hid.getId(), payload, payload.length, 1500);
        append("  SET_REPORT feature/id0 result=" + feature);
        if (feature == payload.length) ok = true;

        // Fallback B: output Set_Report with report ID 3 (kept for Android/HID variants).
        int output = connection.controlTransfer(0x21, 0x09, 0x0203, hid.getId(), payload, payload.length, 1500);
        append("  SET_REPORT output/id3 result=" + output);
        if (output == payload.length) ok = true;

        return ok;
    }

    private byte[] buildCommand(String command) {
        byte[] text = command.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[text.length + 2];
        payload[0] = 0x03;
        payload[1] = (byte) text.length;
        System.arraycopy(text, 0, payload, 2, text.length);
        return payload;
    }

    private void readResponse(UsbDeviceConnection connection, UsbEndpoint epIn, String label) {
        byte[] buffer = new byte[Math.max(64, epIn.getMaxPacketSize())];
        int n = connection.bulkTransfer(epIn, buffer, buffer.length, 500);
        if (n > 0) append("Response " + label + " (" + n + " bytes): " + hex(buffer, n));
        else append("Response " + label + ": none (result=" + n + ")");
    }

    private void sendKeepAliveOnce() {
        UsbDevice device = headset;
        if (device == null || !usbManager.hasPermission(device)) return;
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) return;
        UsbInterface hid = null;
        try {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
                if (!connection.claimInterface(intf, true)) continue;
                hid = intf;
                UsbEndpoint out = null;
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) { out = ep; break; }
                }
                byte[] payload = buildCommand("Sleep Disable");
                int result = out != null ? connection.bulkTransfer(out, payload, payload.length, 750) : -1;
                if (result != payload.length) {
                    result = connection.controlTransfer(0x21, 0x09, 0x0300, intf.getId(), payload, payload.length, 750);
                }
                break;
            }
        } catch (Throwable ignored) {
        } finally {
            if (hid != null) {
                try { connection.releaseInterface(hid); } catch (Throwable ignored) {}
            }
            connection.close();
        }
    }

    private String hex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(length, data.length);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private Display findExternalDisplay() {
        Display[] preferred = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : preferred) if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid()) return d;
        for (Display d : displayManager.getDisplays()) if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid()) return d;
        return null;
    }

    private void appendDisplayInfo(Display d) {
        Display.Mode mode = d.getMode();
        append(String.format(Locale.US,
                "External display: name=%s id=%d mode=%dx%d @ %.2fHz rotation=%d state=%d",
                d.getName(), d.getDisplayId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate(), d.getRotation(), d.getState()));
    }

    private void showTestDisplay() {
        Display d = findExternalDisplay();
        if (d == null) { append("No external Android display detected"); refreshStatus(); return; }
        if (presentation != null) presentation.dismiss();
        presentation = new Presentation(this, d);
        presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        presentation.setContentView(new TestPatternView(presentation.getContext()));
        presentation.show();
        appendDisplayInfo(d);
        append("Bright test image shown on " + d.getName());
    }

    private void refreshStatus() {
        boolean detected = headset != null;
        boolean permission = detected && usbManager.hasPermission(headset);
        Display ext = findExternalDisplay();
        status.setText((detected ? "LGR100 DETECTED" : "LGR100 NOT DETECTED") + "\n" +
                (permission ? "USB permission OK" : "USB permission needed") + " • " +
                (ext != null ? "External display: " + ext.getName() : "No external display") + "\n" +
                (keepAliveEnabled ? "V0.2 keep-alive ACTIVE" : "V0.2 keep-alive inactive"));
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(detected ? Color.rgb(30, 130, 80) : Color.rgb(90, 95, 105));
    }

    private void append(String message) {
        handler.post(() -> {
            if (log != null) log.append(message + "\n");
        });
    }

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private static class TestPatternView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TestPatternView(Context context) { super(context); setKeepScreenOn(true); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float mid = w / 2f;
            c.drawColor(Color.WHITE);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.RED);
            c.drawRect(0, 0, mid, h, paint);
            paint.setColor(Color.GREEN);
            c.drawRect(mid, 0, w, h, paint);

            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(24, Math.min(w, h) / 10f));
            c.save();
            c.rotate(-90, mid / 2f, h / 2f);
            c.drawText("LEFT V0.2", mid / 2f, h / 2f, paint);
            c.restore();
            c.save();
            c.rotate(90, mid + mid / 2f, h / 2f);
            c.drawText("RIGHT V0.2", mid + mid / 2f, h / 2f, paint);
            c.restore();

            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(Math.max(2, Math.min(w, h) / 200f));
            c.drawLine(mid, 0, mid, h, paint);
        }
    }
}
