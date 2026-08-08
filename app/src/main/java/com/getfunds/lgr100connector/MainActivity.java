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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    private TextView sensorStatus;
    private TextView log;
    private Presentation presentation;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean activating = false;
    private volatile boolean keepAliveEnabled = false;
    private volatile boolean sensorStreaming = false;
    private Thread sensorThread;

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override public void run() {
            if (!keepAliveEnabled || sensorStreaming || headset == null || !usbManager.hasPermission(headset)) return;
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
                    stopSensorDiagnostics();
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

        append("LG 360 VR Connector V0.3");
        append("Display orientation corrected from V0.2");
        append("Sensor diagnostics: gyro + accelerometer + proximity/raw");
        scan();
        refreshStatus();
    }

    @Override protected void onDestroy() {
        stopSensorDiagnostics();
        keepAliveEnabled = false;
        handler.removeCallbacks(keepAliveRunnable);
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (presentation != null) presentation.dismiss();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("LG 360 VR Connector V0.3");
        title.setTextSize(25);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(status);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button scan = button("Scan");
        scan.setOnClickListener(v -> { scan(); refreshStatus(); });
        row1.addView(scan, weighted());
        Button permission = button("USB permission");
        permission.setOnClickListener(v -> requestPermission());
        row1.addView(permission, weighted());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button activate = button("Wake headset");
        activate.setOnClickListener(v -> activateHeadset());
        row2.addView(activate, weighted());
        Button test = button("VR test image");
        test.setOnClickListener(v -> showTestDisplay());
        row2.addView(test, weighted());
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button sensors = button("Start sensors");
        sensors.setOnClickListener(v -> startSensorDiagnostics());
        row3.addView(sensors, weighted());
        Button stop = button("Stop sensors");
        stop.setOnClickListener(v -> stopSensorDiagnostics());
        row3.addView(stop, weighted());
        root.addView(row3);

        sensorStatus = new TextView(this);
        sensorStatus.setTextSize(13);
        sensorStatus.setTextColor(Color.BLACK);
        sensorStatus.setBackgroundColor(Color.rgb(235, 238, 242));
        sensorStatus.setPadding(dp(10), dp(10), dp(10), dp(10));
        sensorStatus.setText("SENSOR CHECK\nNot running\nStart Sensors, then rotate/tilt the headset and cover/uncover the proximity sensor.");
        root.addView(sensorStatus);

        log = new TextView(this);
        log.setTextSize(11);
        log.setTextColor(Color.DKGRAY);
        log.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);
        setContentView(root);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
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
        if (sensorStreaming) { append("Stop sensor diagnostics before running wake sequence again"); return; }
        if (headset == null) scan();
        if (headset == null) { append("Activation stopped: headset not detected"); return; }
        if (!usbManager.hasPermission(headset)) { append("USB permission required first"); requestPermission(); return; }

        activating = true;
        append("=== V0.3 WAKE SEQUENCE START ===");
        new Thread(this::doActivationSequence, "lgr100-activate").start();
    }

    private void doActivationSequence() {
        UsbDevice device = headset;
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) { append("openDevice() failed"); activating = false; return; }

        UsbInterface hid = null;
        try {
            EndpointSet set = claimHid(connection, device);
            hid = set.hid;
            if (hid == null) { append("No claimable HID interface found"); return; }

            append("IN endpoint: " + endpointName(set.in));
            append("OUT endpoint: " + endpointName(set.out));
            if (set.in != null) drainInput(connection, set.in);

            boolean sleepOk = sendCommand(connection, hid, set.out, "Sleep Disable", true);
            sleepQuiet(120);
            if (set.in != null) readResponse(connection, set.in, "after Sleep Disable");

            boolean startOk = sendCommand(connection, hid, set.out, "VR App Start", true);
            sleepQuiet(250);
            if (set.in != null) readResponse(connection, set.in, "after VR App Start");

            append("Sleep Disable sent=" + sleepOk + " | VR App Start sent=" + startOk);
            if (sleepOk || startOk) {
                keepAliveEnabled = true;
                handler.removeCallbacks(keepAliveRunnable);
                handler.postDelayed(keepAliveRunnable, 1000);
            }
        } catch (Throwable t) {
            append("Activation error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (hid != null) try { connection.releaseInterface(hid); } catch (Throwable ignored) {}
            connection.close();
            activating = false;
        }

        append("=== V0.3 WAKE SEQUENCE COMPLETE ===");
        handler.postDelayed(() -> {
            refreshStatus();
            Display d = findExternalDisplay();
            if (d != null) { appendDisplayInfo(d); showTestDisplay(); }
        }, 400);
    }

    private void startSensorDiagnostics() {
        if (sensorStreaming) { append("Sensor diagnostics already running"); return; }
        if (activating) { append("Wait for wake sequence to finish first"); return; }
        if (headset == null) scan();
        if (headset == null) { append("Sensor test stopped: headset not detected"); return; }
        if (!usbManager.hasPermission(headset)) { append("USB permission required first"); requestPermission(); return; }

        keepAliveEnabled = false;
        handler.removeCallbacks(keepAliveRunnable);
        sensorStreaming = true;
        setSensorStatus("SENSOR CHECK\nStarting...\nMove/rotate the headset. Cover/uncover the proximity sensor.");
        append("=== SENSOR DIAGNOSTICS START ===");
        sensorThread = new Thread(this::runSensorDiagnostics, "lgr100-sensors");
        sensorThread.start();
    }

    private void runSensorDiagnostics() {
        UsbDevice device = headset;
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) { append("Sensor openDevice() failed"); sensorStreaming = false; return; }

        UsbInterface hid = null;
        long packets = 0, imuPackets = 0, otherPackets = 0;
        long startMs = System.currentTimeMillis();
        long lastKeepAlive = 0, lastProxQuery = 0, lastUi = 0;
        String lastOther = "none";
        int peakGyro = 0, accelSpan = 0;
        int minAx = Integer.MAX_VALUE, maxAx = Integer.MIN_VALUE;
        int minAy = Integer.MAX_VALUE, maxAy = Integer.MIN_VALUE;
        int minAz = Integer.MAX_VALUE, maxAz = Integer.MIN_VALUE;

        try {
            EndpointSet set = claimHid(connection, device);
            hid = set.hid;
            if (hid == null || set.in == null) { append("Sensor test requires a claimable HID interface and IN endpoint"); return; }

            drainInput(connection, set.in);
            sendCommand(connection, hid, set.out, "Sleep Disable", false);
            sleepQuiet(80);
            sendCommand(connection, hid, set.out, "VR App Start", false);
            sleepQuiet(100);

            boolean accelOn = sendCommand(connection, hid, set.out, "Accel On", true);
            sleepQuiet(60);
            boolean gyroOn = sendCommand(connection, hid, set.out, "Gyro On", true);
            sleepQuiet(60);
            boolean proxOn = sendCommand(connection, hid, set.out, "Proximity On", true);
            sleepQuiet(80);
            append("Sensor enable results: Accel=" + accelOn + " Gyro=" + gyroOn + " Proximity=" + proxOn);
            append("Expecting IMU report 0x05 on endpoint " + endpointName(set.in));

            byte[] buffer = new byte[Math.max(64, set.in.getMaxPacketSize())];
            while (sensorStreaming && headset != null) {
                long now = System.currentTimeMillis();

                if (now - lastKeepAlive >= 1400) {
                    writePrimary(connection, set.out, "Sleep Disable");
                    lastKeepAlive = now;
                }
                if (now - lastProxQuery >= 1200) {
                    writePrimary(connection, set.out, "Proximity Get Data");
                    lastProxQuery = now;
                }

                int n = connection.bulkTransfer(set.in, buffer, buffer.length, 120);
                if (n <= 0) continue;
                packets++;
                int reportId = buffer[0] & 0xFF;

                if (reportId == 0x05 && n >= 13) {
                    imuPackets++;
                    short gx = le16(buffer, 1), gy = le16(buffer, 3), gz = le16(buffer, 5);
                    short ax = le16(buffer, 7), ay = le16(buffer, 9), az = le16(buffer, 11);

                    peakGyro = Math.max(peakGyro, Math.max(Math.abs((int) gx), Math.max(Math.abs((int) gy), Math.abs((int) gz))));
                    minAx = Math.min(minAx, ax); maxAx = Math.max(maxAx, ax);
                    minAy = Math.min(minAy, ay); maxAy = Math.max(maxAy, ay);
                    minAz = Math.min(minAz, az); maxAz = Math.max(maxAz, az);
                    accelSpan = Math.max(maxAx - minAx, Math.max(maxAy - minAy, maxAz - minAz));

                    if (now - lastUi >= 120) {
                        double gxDps = gx / 131.0, gyDps = gy / 131.0, gzDps = gz / 131.0;
                        double axG = ax / 16384.0, ayG = ay / 16384.0, azG = az / 16384.0;
                        double gyroMag = Math.sqrt(gxDps * gxDps + gyDps * gyDps + gzDps * gzDps);
                        double accelMag = Math.sqrt(axG * axG + ayG * ayG + azG * azG);
                        String gyroCheck = peakGyro > 80 ? "PASS - motion detected" : "WAIT - rotate headset";
                        String accelCheck = accelSpan > 500 ? "PASS - tilt/motion detected" : "WAIT - tilt headset";
                        String unknown = n > 13 ? hexRange(buffer, 13, Math.min(n, 31)) : "none";
                        double seconds = Math.max(0.001, (now - startMs) / 1000.0);
                        double rate = imuPackets / seconds;

                        String text = String.format(Locale.US,
                                "SENSOR CHECK - LIVE\n" +
                                "IMU report: 0x%02X | packets: %d | %.1f Hz\n" +
                                "GYRO raw:  X=%6d  Y=%6d  Z=%6d\n" +
                                "GYRO approx: X=%7.1f Y=%7.1f Z=%7.1f deg/s | mag=%.1f\n" +
                                "Gyro check: %s\n\n" +
                                "ACCEL raw: X=%6d  Y=%6d  Z=%6d\n" +
                                "ACCEL approx: X=%6.3f Y=%6.3f Z=%6.3f g | mag=%.3f g\n" +
                                "Accel check: %s\n\n" +
                                "PROXIMITY: query active; cover/uncover center sensor\n" +
                                "Raw sensor tail: %s\n" +
                                "Other USB response: %s\n" +
                                "Other packets: %d",
                                reportId, imuPackets, rate,
                                (int) gx, (int) gy, (int) gz,
                                gxDps, gyDps, gzDps, gyroMag, gyroCheck,
                                (int) ax, (int) ay, (int) az,
                                axG, ayG, azG, accelMag, accelCheck,
                                unknown, lastOther, otherPackets);
                        setSensorStatus(text);
                        lastUi = now;
                    }
                } else {
                    otherPackets++;
                    lastOther = "ID 0x" + String.format(Locale.US, "%02X", reportId) + " (" + n + "B): " + hex(buffer, Math.min(n, 32));
                    if (otherPackets <= 12 || otherPackets % 20 == 0) append("Sensor/non-IMU packet: " + lastOther);
                }
            }
        } catch (Throwable t) {
            append("Sensor error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            setSensorStatus("SENSOR CHECK\nERROR: " + t.getMessage());
        } finally {
            if (hid != null) {
                try {
                    EndpointSet set = endpointsOnly(device, hid);
                    if (set.out != null) {
                        writePrimary(connection, set.out, "Accel Off");
                        writePrimary(connection, set.out, "Gyro Off");
                        writePrimary(connection, set.out, "Proximity Off");
                        writePrimary(connection, set.out, "Sleep Disable");
                    }
                } catch (Throwable ignored) {}
                try { connection.releaseInterface(hid); } catch (Throwable ignored) {}
            }
            connection.close();
            sensorStreaming = false;
            append("=== SENSOR DIAGNOSTICS STOPPED === packets=" + packets + " imu=" + imuPackets);
            if (headset != null && usbManager.hasPermission(headset)) {
                keepAliveEnabled = true;
                handler.postDelayed(keepAliveRunnable, 700);
            }
        }
    }

    private void stopSensorDiagnostics() {
        if (!sensorStreaming) return;
        sensorStreaming = false;
        append("Stopping sensor diagnostics...");
        Thread t = sensorThread;
        if (t != null) t.interrupt();
    }

    private EndpointSet claimHid(UsbDeviceConnection connection, UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            boolean claimed = connection.claimInterface(intf, true);
            append("Claim HID interface " + intf.getId() + " = " + claimed);
            if (!claimed) continue;
            return endpointsOnly(device, intf);
        }
        return new EndpointSet(null, null, null);
    }

    private EndpointSet endpointsOnly(UsbDevice device, UsbInterface intf) {
        UsbEndpoint in = null, out = null;
        for (int e = 0; e < intf.getEndpointCount(); e++) {
            UsbEndpoint ep = intf.getEndpoint(e);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN && in == null) in = ep;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT && out == null) out = ep;
        }
        return new EndpointSet(intf, in, out);
    }

    private void drainInput(UsbDeviceConnection connection, UsbEndpoint epIn) {
        byte[] buffer = new byte[Math.max(64, epIn.getMaxPacketSize())];
        int packets = 0, bytes = 0;
        for (int i = 0; i < 32; i++) {
            int n = connection.bulkTransfer(epIn, buffer, buffer.length, 35);
            if (n <= 0) break;
            packets++; bytes += n;
            if (packets <= 4) append("Drain IN packet " + packets + ": " + hex(buffer, n));
        }
        append("Drained IN endpoint: packets=" + packets + " bytes=" + bytes);
    }

    private boolean sendCommand(UsbDeviceConnection connection, UsbInterface hid, UsbEndpoint epOut, String command, boolean verbose) {
        byte[] payload = buildCommand(command);
        if (verbose) append("SEND " + command + " -> " + hex(payload, payload.length));

        if (epOut != null) {
            int out = connection.bulkTransfer(epOut, payload, payload.length, 1000);
            if (verbose) append("  OUT endpoint result=" + out + "/" + payload.length);
            if (out == payload.length) return true;
        }

        int feature = connection.controlTransfer(0x21, 0x09, 0x0300, hid.getId(), payload, payload.length, 1000);
        if (verbose) append("  SET_REPORT feature/id0 result=" + feature);
        if (feature == payload.length) return true;

        int output = connection.controlTransfer(0x21, 0x09, 0x0203, hid.getId(), payload, payload.length, 1000);
        if (verbose) append("  SET_REPORT output/id3 result=" + output);
        return output == payload.length;
    }

    private int writePrimary(UsbDeviceConnection connection, UsbEndpoint epOut, String command) {
        if (epOut == null) return -1;
        byte[] payload = buildCommand(command);
        return connection.bulkTransfer(epOut, payload, payload.length, 700);
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
        if (device == null || sensorStreaming || !usbManager.hasPermission(device)) return;
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) return;
        UsbInterface hid = null;
        try {
            EndpointSet set = claimHid(connection, device);
            hid = set.hid;
            if (hid != null) writePrimary(connection, set.out, "Sleep Disable");
        } catch (Throwable ignored) {
        } finally {
            if (hid != null) try { connection.releaseInterface(hid); } catch (Throwable ignored) {}
            connection.close();
        }
    }

    private Display findExternalDisplay() {
        Display[] preferred = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : preferred) if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid()) return d;
        for (Display d : displayManager.getDisplays()) if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid()) return d;
        return null;
    }

    private void showTestDisplay() {
        Display d = findExternalDisplay();
        if (d == null) { append("No external Android display detected"); refreshStatus(); return; }
        if (presentation != null) presentation.dismiss();
        presentation = new Presentation(this, d);
        presentation.setContentView(new TestPatternView(presentation.getContext()));
        presentation.show();
        append("Corrected-orientation test image shown on " + d.getName());
        appendDisplayInfo(d);
    }

    private void appendDisplayInfo(Display d) {
        Display.Mode mode = d.getMode();
        append(String.format(Locale.US, "External display: %s %dx%d @ %.2f Hz (displayId=%d)", d.getName(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate(), d.getDisplayId()));
    }

    private void refreshStatus() {
        boolean detected = headset != null;
        boolean permission = detected && usbManager.hasPermission(headset);
        Display ext = findExternalDisplay();
        String sensor = sensorStreaming ? " • Sensors LIVE" : "";
        runOnUiThread(() -> {
            status.setText((detected ? "LGR100 DETECTED" : "LGR100 NOT DETECTED") + "\n" + (permission ? "USB permission OK" : "USB permission needed") + " • " + (ext != null ? "External display: " + ext.getName() : "No external display") + sensor);
            status.setTextColor(Color.WHITE);
            status.setBackgroundColor(detected ? Color.rgb(30, 130, 80) : Color.rgb(90, 95, 105));
        });
    }

    private short le16(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    private String endpointName(UsbEndpoint ep) {
        return ep == null ? "NONE" : String.format(Locale.US, "0x%02X", ep.getAddress());
    }

    private String hex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(length, data.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private String hexRange(byte[] data, int from, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(toExclusive, data.length);
        for (int i = Math.max(0, from); i < end; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void append(String message) {
        runOnUiThread(() -> { if (log != null) log.append(message + "\n"); });
    }

    private void setSensorStatus(String text) {
        runOnUiThread(() -> { if (sensorStatus != null) sensorStatus.setText(text); });
        refreshStatus();
    }

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private static class EndpointSet {
        final UsbInterface hid;
        final UsbEndpoint in;
        final UsbEndpoint out;
        EndpointSet(UsbInterface hid, UsbEndpoint in, UsbEndpoint out) { this.hid = hid; this.in = in; this.out = out; }
    }

    private static class TestPatternView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TestPatternView(Context context) { super(context); setKeepScreenOn(true); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float mid = w / 2f;
            c.drawColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(220, 30, 30));
            c.drawRect(0, 0, mid, h, paint);
            paint.setColor(Color.rgb(25, 190, 70));
            c.drawRect(mid, 0, w, h, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.WHITE);
            paint.setTextSize(Math.max(28, Math.min(w, h) / 11f));
            paint.setFakeBoldText(true);
            drawEye(c, 0, mid, h, "LEFT", 90f);
            drawEye(c, mid, w, h, "RIGHT", -90f);
            paint.setFakeBoldText(false);
        }

        private void drawEye(Canvas c, float left, float right, float height, String label, float rotation) {
            float cx = (left + right) / 2f;
            float cy = height / 2f;
            c.save();
            c.clipRect(left, 0, right, height);
            c.rotate(rotation, cx, cy);
            c.drawText(label, cx, cy, paint);
            float original = paint.getTextSize();
            paint.setTextSize(Math.max(20, original * 0.55f));
            c.drawText("UP ^", cx, cy - Math.max(55, height * 0.16f), paint);
            paint.setTextSize(original);
            c.restore();
        }
    }
}
