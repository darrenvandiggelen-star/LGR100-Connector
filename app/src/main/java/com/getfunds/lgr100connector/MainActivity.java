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
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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
    private static final int REQUEST_SCREEN_CAPTURE = 4104;

    private UsbManager usbManager;
    private DisplayManager displayManager;
    private MediaProjectionManager mediaProjectionManager;
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

    private volatile int lastGx, lastGy, lastGz, lastAx, lastAy, lastAz;
    private volatile long imuPackets;
    private volatile double imuRateHz;
    private volatile int proximityRaw = -1;
    private volatile String proximityState = "WAITING";
    private volatile String lastOtherPacket = "none";

    private final Object calibrationLock = new Object();
    private volatile boolean calibrationActive = false;
    private volatile boolean calibrated = false;
    private volatile long calibrationStartMs = 0;
    private long calibrationCount = 0;
    private double sumGx, sumGy, sumGz, sumAx, sumAy, sumAz;
    private double sumGx2, sumGy2, sumGz2, sumAx2, sumAy2, sumAz2;
    private volatile double gyroBiasX, gyroBiasY, gyroBiasZ;
    private volatile double gravityX, gravityY, gravityZ;
    private volatile double accelCountsPerG = 16384.0;
    private volatile double gyroNoiseRms, accelNoiseRms;
    private volatile String calibrationResult = "NOT CALIBRATED";

    private final Object orientationLock = new Object();
    private volatile double yawDeg = 0.0;
    private volatile double pitchDeg = 0.0;
    private volatile double rollDeg = 0.0;
    private long lastOrientationNs = 0;

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
                    stopScreenShare();
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
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);

        append("LG 360 VR Connector V0.4");
        append("Adds stationary calibration, relative yaw/pitch/roll, proximity state, head-track test and phone screen sharing");
        scan();
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
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
        root.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView title = new TextView(this);
        title.setText("LG 360 VR Connector V0.4");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(dp(9), dp(9), dp(9), dp(9));
        root.addView(status);

        LinearLayout row1 = row();
        Button scan = button("Scan");
        scan.setOnClickListener(v -> { scan(); refreshStatus(); });
        row1.addView(scan, weighted());
        Button permission = button("USB permission");
        permission.setOnClickListener(v -> requestPermission());
        row1.addView(permission, weighted());
        root.addView(row1);

        LinearLayout row2 = row();
        Button activate = button("Wake headset");
        activate.setOnClickListener(v -> activateHeadset());
        row2.addView(activate, weighted());
        Button test = button("VR test image");
        test.setOnClickListener(v -> showTestDisplay());
        row2.addView(test, weighted());
        root.addView(row2);

        LinearLayout row3 = row();
        Button sensors = button("Start sensors");
        sensors.setOnClickListener(v -> startSensorDiagnostics());
        row3.addView(sensors, weighted());
        Button stop = button("Stop sensors");
        stop.setOnClickListener(v -> stopSensorDiagnostics());
        row3.addView(stop, weighted());
        root.addView(row3);

        LinearLayout row4 = row();
        Button calibrate = button("Calibrate 3 sec");
        calibrate.setOnClickListener(v -> beginCalibration());
        row4.addView(calibrate, weighted());
        Button reset = button("Reset view");
        reset.setOnClickListener(v -> resetOrientation());
        row4.addView(reset, weighted());
        Button headTest = button("Head-track test");
        headTest.setOnClickListener(v -> showHeadTrackDisplay());
        row4.addView(headTest, weighted());
        root.addView(row4);

        LinearLayout row5 = row();
        Button share = button("Share phone screen");
        share.setOnClickListener(v -> requestScreenShare());
        row5.addView(share, weighted());
        Button stopShare = button("Stop screen share");
        stopShare.setOnClickListener(v -> stopScreenShare());
        row5.addView(stopShare, weighted());
        root.addView(row5);

        sensorStatus = new TextView(this);
        sensorStatus.setTextSize(12);
        sensorStatus.setTextColor(Color.BLACK);
        sensorStatus.setBackgroundColor(Color.rgb(235, 238, 242));
        sensorStatus.setPadding(dp(9), dp(9), dp(9), dp(9));
        sensorStatus.setText("SENSOR / CALIBRATION\nNot running\nStart Sensors, keep the headset still, then tap Calibrate 3 sec.");
        root.addView(sensorStatus);

        log = new TextView(this);
        log.setTextSize(10);
        log.setTextColor(Color.DKGRAY);
        log.setPadding(dp(6), dp(6), dp(6), dp(6));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);
        setContentView(root);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
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
        if (sensorStreaming) { append("Stop sensors before running wake sequence again"); return; }
        if (headset == null) scan();
        if (headset == null) { append("Activation stopped: headset not detected"); return; }
        if (!usbManager.hasPermission(headset)) { append("USB permission required first"); requestPermission(); return; }

        activating = true;
        append("=== V0.4 WAKE SEQUENCE START ===");
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

        append("=== V0.4 WAKE SEQUENCE COMPLETE ===");
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
        setSensorStatus("SENSOR / CALIBRATION\nStarting sensor stream...");
        append("=== SENSOR DIAGNOSTICS START ===");
        sensorThread = new Thread(this::runSensorDiagnostics, "lgr100-sensors");
        sensorThread.start();
    }

    private void runSensorDiagnostics() {
        UsbDevice device = headset;
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) { append("Sensor openDevice() failed"); sensorStreaming = false; return; }

        UsbInterface hid = null;
        long packets = 0;
        long otherPackets = 0;
        long startMs = System.currentTimeMillis();
        long lastKeepAlive = 0, lastProxQuery = 0, lastUi = 0;

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
            append("Sensor enable: Accel=" + accelOn + " Gyro=" + gyroOn + " Proximity=" + proxOn);

            byte[] buffer = new byte[Math.max(64, set.in.getMaxPacketSize())];
            while (sensorStreaming && headset != null) {
                long now = System.currentTimeMillis();

                if (now - lastKeepAlive >= 1400) {
                    writePrimary(connection, set.out, "Sleep Disable");
                    lastKeepAlive = now;
                }
                if (now - lastProxQuery >= 800) {
                    writePrimary(connection, set.out, "Proximity Get Data");
                    lastProxQuery = now;
                }

                int n = connection.bulkTransfer(set.in, buffer, buffer.length, 120);
                if (n <= 0) continue;
                packets++;
                int reportId = buffer[0] & 0xFF;

                if (reportId == 0x05 && n >= 13) {
                    imuPackets++;
                    int gx = le16(buffer, 1), gy = le16(buffer, 3), gz = le16(buffer, 5);
                    int ax = le16(buffer, 7), ay = le16(buffer, 9), az = le16(buffer, 11);
                    lastGx = gx; lastGy = gy; lastGz = gz;
                    lastAx = ax; lastAy = ay; lastAz = az;

                    accumulateCalibration(gx, gy, gz, ax, ay, az, now);
                    updateOrientation(gx, gy, gz, ax, ay, az);

                    double seconds = Math.max(0.001, (now - startMs) / 1000.0);
                    imuRateHz = imuPackets / seconds;

                    if (now - lastUi >= 120) {
                        updateSensorUi(otherPackets);
                        lastUi = now;
                    }
                } else {
                    otherPackets++;
                    lastOtherPacket = "ID 0x" + String.format(Locale.US, "%02X", reportId) + " (" + n + "B): " + hex(buffer, Math.min(n, 32));

                    if (reportId == 0x03 && n >= 5 && (buffer[1] & 0xFF) == 0x01 && (buffer[2] & 0xFF) == 0x03) {
                        proximityRaw = buffer[3] & 0xFF;
                        proximityState = decodeProximity(proximityRaw);
                    }

                    if (otherPackets <= 8 || otherPackets % 60 == 0) append("Sensor/non-IMU packet: " + lastOtherPacket);
                }
            }
        } catch (Throwable t) {
            append("Sensor error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            setSensorStatus("SENSOR / CALIBRATION\nERROR: " + t.getMessage());
        } finally {
            calibrationActive = false;
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
            refreshStatus();
        }
    }

    private void stopSensorDiagnostics() {
        if (!sensorStreaming) return;
        sensorStreaming = false;
        calibrationActive = false;
        append("Stopping sensor diagnostics...");
        Thread t = sensorThread;
        if (t != null) t.interrupt();
    }

    private void beginCalibration() {
        if (!sensorStreaming) {
            append("Starting sensors before calibration");
            startSensorDiagnostics();
            handler.postDelayed(this::beginCalibration, 800);
            return;
        }
        synchronized (calibrationLock) {
            calibrationActive = true;
            calibrated = false;
            calibrationResult = "CALIBRATING - KEEP HEADSET COMPLETELY STILL";
            calibrationStartMs = System.currentTimeMillis();
            calibrationCount = 0;
            sumGx = sumGy = sumGz = sumAx = sumAy = sumAz = 0;
            sumGx2 = sumGy2 = sumGz2 = sumAx2 = sumAy2 = sumAz2 = 0;
        }
        resetOrientation();
        append("Calibration started: keep headset still for 3 seconds");
        updateSensorUi(0);
    }

    private void accumulateCalibration(int gx, int gy, int gz, int ax, int ay, int az, long nowMs) {
        if (!calibrationActive) return;
        boolean finish = false;
        synchronized (calibrationLock) {
            if (!calibrationActive) return;
            calibrationCount++;
            sumGx += gx; sumGy += gy; sumGz += gz;
            sumAx += ax; sumAy += ay; sumAz += az;
            sumGx2 += (double) gx * gx; sumGy2 += (double) gy * gy; sumGz2 += (double) gz * gz;
            sumAx2 += (double) ax * ax; sumAy2 += (double) ay * ay; sumAz2 += (double) az * az;
            if (nowMs - calibrationStartMs >= 3000) {
                finish = true;
                calibrationActive = false;
            }
        }
        if (finish) finishCalibration();
    }

    private void finishCalibration() {
        synchronized (calibrationLock) {
            if (calibrationCount < 50) {
                calibrated = false;
                calibrationResult = "FAIL - not enough sensor samples";
                append("Calibration failed: only " + calibrationCount + " samples");
                return;
            }

            gyroBiasX = sumGx / calibrationCount;
            gyroBiasY = sumGy / calibrationCount;
            gyroBiasZ = sumGz / calibrationCount;
            gravityX = sumAx / calibrationCount;
            gravityY = sumAy / calibrationCount;
            gravityZ = sumAz / calibrationCount;

            double vgX = Math.max(0, sumGx2 / calibrationCount - gyroBiasX * gyroBiasX);
            double vgY = Math.max(0, sumGy2 / calibrationCount - gyroBiasY * gyroBiasY);
            double vgZ = Math.max(0, sumGz2 / calibrationCount - gyroBiasZ * gyroBiasZ);
            gyroNoiseRms = Math.sqrt((vgX + vgY + vgZ) / 3.0);

            double vaX = Math.max(0, sumAx2 / calibrationCount - gravityX * gravityX);
            double vaY = Math.max(0, sumAy2 / calibrationCount - gravityY * gravityY);
            double vaZ = Math.max(0, sumAz2 / calibrationCount - gravityZ * gravityZ);
            accelNoiseRms = Math.sqrt((vaX + vaY + vaZ) / 3.0);

            accelCountsPerG = Math.sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ);
            if (accelCountsPerG < 500) accelCountsPerG = 16384.0;

            calibrated = true;
            boolean steady = gyroNoiseRms < 2500 && accelNoiseRms < 2500;
            calibrationResult = steady ? "PASS - stationary baseline captured" : "CAUTION - baseline captured but headset moved during test";
            append(String.format(Locale.US, "Calibration complete: samples=%d gyroBias=(%.1f, %.1f, %.1f) gravity=(%.1f, %.1f, %.1f) gyroNoise=%.1f accelNoise=%.1f", calibrationCount, gyroBiasX, gyroBiasY, gyroBiasZ, gravityX, gravityY, gravityZ, gyroNoiseRms, accelNoiseRms));
        }
        resetOrientation();
    }

    private void updateOrientation(int gx, int gy, int gz, int ax, int ay, int az) {
        if (!calibrated || calibrationActive) {
            lastOrientationNs = System.nanoTime();
            return;
        }

        long nowNs = System.nanoTime();
        if (lastOrientationNs == 0) { lastOrientationNs = nowNs; return; }
        double dt = (nowNs - lastOrientationNs) / 1_000_000_000.0;
        lastOrientationNs = nowNs;
        if (dt <= 0 || dt > 0.1) return;

        double gxDps = (gx - gyroBiasX) / 131.0;
        double gyDps = (gy - gyroBiasY) / 131.0;
        double gzDps = (gz - gyroBiasZ) / 131.0;

        double scale = Math.max(1.0, accelCountsPerG);
        double axN = ax / scale, ayN = ay / scale, azN = az / scale;
        double pitchAcc = Math.toDegrees(Math.atan2(-axN, Math.sqrt(ayN * ayN + azN * azN)));
        double rollAcc = Math.toDegrees(Math.atan2(ayN, azN));

        synchronized (orientationLock) {
            yawDeg = wrap180(yawDeg + gzDps * dt);
            double pitchGyro = pitchDeg + gxDps * dt;
            double rollGyro = rollDeg + gyDps * dt;
            pitchDeg = 0.985 * pitchGyro + 0.015 * pitchAcc;
            rollDeg = 0.985 * rollGyro + 0.015 * rollAcc;
            pitchDeg = clamp(pitchDeg, -90, 90);
            rollDeg = wrap180(rollDeg);
        }
    }

    private void resetOrientation() {
        synchronized (orientationLock) {
            yawDeg = 0;
            pitchDeg = 0;
            rollDeg = 0;
            lastOrientationNs = 0;
        }
        append("Relative orientation reset to 0 / 0 / 0");
    }

    private void updateSensorUi(long otherPackets) {
        int gx = lastGx, gy = lastGy, gz = lastGz;
        int ax = lastAx, ay = lastAy, az = lastAz;
        double bgx = calibrated ? gx - gyroBiasX : gx;
        double bgy = calibrated ? gy - gyroBiasY : gy;
        double bgz = calibrated ? gz - gyroBiasZ : gz;
        double gxDps = bgx / 131.0, gyDps = bgy / 131.0, gzDps = bgz / 131.0;
        double scale = calibrated ? accelCountsPerG : 16384.0;
        double axG = ax / scale, ayG = ay / scale, azG = az / scale;
        double accelMag = Math.sqrt(axG * axG + ayG * ayG + azG * azG);

        String cal;
        if (calibrationActive) {
            long left = Math.max(0, 3000 - (System.currentTimeMillis() - calibrationStartMs));
            cal = "CALIBRATING - KEEP STILL (" + String.format(Locale.US, "%.1f", left / 1000.0) + "s)";
        } else cal = calibrationResult;

        String prox = proximityRaw >= 0 ? proximityState + " (raw 0x" + String.format(Locale.US, "%02X", proximityRaw) + ")" : "WAITING FOR RESPONSE";

        String text = String.format(Locale.US,
                "SENSOR / CALIBRATION - LIVE\n" +
                "IMU: %,d packets | %.1f Hz\n" +
                "Calibration: %s\n" +
                "Gyro bias: X=%7.1f Y=%7.1f Z=%7.1f | noise=%.1f\n" +
                "Gravity baseline: X=%7.1f Y=%7.1f Z=%7.1f | accel noise=%.1f\n\n" +
                "GYRO bias-corrected approx: X=%7.1f Y=%7.1f Z=%7.1f deg/s\n" +
                "ACCEL calibrated: X=%6.3f Y=%6.3f Z=%6.3f g | mag=%.3f\n" +
                "Orientation (relative): YAW=%7.1f PITCH=%7.1f ROLL=%7.1f\n\n" +
                "PROXIMITY: %s\n" +
                "Other packet: %s\n" +
                "Other packets: %d",
                imuPackets, imuRateHz, cal,
                gyroBiasX, gyroBiasY, gyroBiasZ, gyroNoiseRms,
                gravityX, gravityY, gravityZ, accelNoiseRms,
                gxDps, gyDps, gzDps,
                axG, ayG, azG, accelMag,
                yawDeg, pitchDeg, rollDeg,
                prox, lastOtherPacket, otherPackets);
        setSensorStatus(text);
    }

    private String decodeProximity(int raw) {
        if (raw >= 0x34 && raw <= 0x3F) return "COVERED";
        if (raw >= 0x2C && raw <= 0x31) return "UNCOVERED";
        return "UNKNOWN";
    }

    private void requestScreenShare() {
        Display d = findExternalDisplay();
        if (d == null) { append("Screen share needs the LGR100 external display first. Wake the headset."); return; }
        if (ScreenCaptureService.isCaptureActive()) {
            showScreenMirrorDisplay();
            refreshStatus();
            return;
        }
        append("Requesting Android screen-capture permission...");
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCREEN_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) { append("Screen sharing permission was not granted"); return; }

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int width = dm.widthPixels, height = dm.heightPixels;
        float scale = Math.min(1.0f, 1280.0f / Math.max(width, height));
        int captureW = Math.max(2, Math.round(width * scale));
        int captureH = Math.max(2, Math.round(height * scale));

        Intent service = new Intent(this, ScreenCaptureService.class);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(ScreenCaptureService.EXTRA_WIDTH, captureW);
        service.putExtra(ScreenCaptureService.EXTRA_HEIGHT, captureH);
        service.putExtra(ScreenCaptureService.EXTRA_DENSITY, dm.densityDpi);

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);

        append("Screen capture starting at " + captureW + "x" + captureH);
        handler.postDelayed(() -> { showScreenMirrorDisplay(); refreshStatus(); }, 700);
    }

    private void stopScreenShare() {
        try { stopService(new Intent(this, ScreenCaptureService.class)); } catch (Exception ignored) {}
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Exception ignored) {}
            presentation = null;
        }
        append("Phone screen sharing stopped");
        refreshStatus();
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
        replacePresentation(new TestPresentation(this, d));
        append("Corrected-orientation test image shown on " + d.getName());
        appendDisplayInfo(d);
    }

    private void showHeadTrackDisplay() {
        Display d = findExternalDisplay();
        if (d == null) { append("No external display for head-track test"); return; }
        if (!sensorStreaming) startSensorDiagnostics();
        replacePresentation(new HeadTrackPresentation(this, d));
        append("Head-track test shown. Calibrate first for best results.");
    }

    private void showScreenMirrorDisplay() {
        Display d = findExternalDisplay();
        if (d == null) { append("No external display for phone screen sharing"); return; }
        replacePresentation(new ScreenMirrorPresentation(this, d));
        append("Phone screen mirror shown on both LGR100 eyes");
    }

    private void replacePresentation(Presentation next) {
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Exception ignored) {}
        }
        presentation = next;
        presentation.show();
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
        String share = ScreenCaptureService.isCaptureActive() ? " • Screen SHARE" : "";
        runOnUiThread(() -> {
            status.setText((detected ? "LGR100 DETECTED" : "LGR100 NOT DETECTED") + "\n" + (permission ? "USB permission OK" : "USB permission needed") + " • " + (ext != null ? "External display: " + ext.getName() : "No external display") + sensor + share);
            status.setTextColor(Color.WHITE);
            status.setBackgroundColor(detected ? Color.rgb(30, 130, 80) : Color.rgb(90, 95, 105));
        });
    }

    private int le16(byte[] data, int offset) {
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

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private double wrap180(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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

    private static class TestPresentation extends Presentation {
        TestPresentation(Context outerContext, Display display) { super(outerContext, display); }
        @Override protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setContentView(new TestPatternView(getContext()));
        }
    }

    private class HeadTrackPresentation extends Presentation {
        HeadTrackPresentation(Context outerContext, Display display) { super(outerContext, display); }
        @Override protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setContentView(new HeadTrackView(getContext()));
        }
    }

    private static class ScreenMirrorPresentation extends Presentation {
        ScreenMirrorPresentation(Context outerContext, Display display) { super(outerContext, display); }
        @Override protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setContentView(new ScreenMirrorView(getContext()));
        }
    }

    private static class TestPatternView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TestPatternView(Context context) { super(context); setKeepScreenOn(true); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
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
            float cx = (left + right) / 2f, cy = height / 2f;
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

    private class HeadTrackView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HeadTrackView(Context context) { super(context); setKeepScreenOn(true); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float mid = w / 2f;
            c.drawColor(Color.BLACK);
            drawTrackedEye(c, 0, mid, h, 90f);
            drawTrackedEye(c, mid, w, h, -90f);
            postInvalidateDelayed(16);
        }

        private void drawTrackedEye(Canvas c, float left, float right, float height, float rotation) {
            float cx = (left + right) / 2f;
            float cy = height / 2f;
            float eyeW = right - left;

            c.save();
            c.clipRect(left, 0, right, height);
            c.rotate(rotation, cx, cy);

            float dx = (float) clamp(-yawDeg * 2.2, -height * 0.35, height * 0.35);
            float dy = (float) clamp(pitchDeg * 2.2, -eyeW * 0.35, eyeW * 0.35);
            c.rotate((float) -rollDeg, cx, cy);

            paint.setStrokeWidth(4f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.rgb(40, 200, 100));
            c.drawCircle(cx + dx, cy + dy, 45, paint);
            c.drawLine(cx + dx - 80, cy + dy, cx + dx + 80, cy + dy, paint);
            c.drawLine(cx + dx, cy + dy - 80, cx + dx, cy + dy + 80, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(26);
            c.drawText(String.format(Locale.US, "Y %.0f P %.0f R %.0f", yawDeg, pitchDeg, rollDeg), cx, cy + eyeW * 0.36f, paint);
            if (!calibrated) {
                paint.setColor(Color.YELLOW);
                c.drawText("CALIBRATE FIRST", cx, cy - eyeW * 0.36f, paint);
            }
            c.restore();
        }
    }

    private static class ScreenMirrorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();
        ScreenMirrorView(Context context) { super(context); setKeepScreenOn(true); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float mid = w / 2f;
            c.drawColor(Color.BLACK);
            boolean left = drawMirrorEye(c, 0, mid, h, 90f);
            boolean right = drawMirrorEye(c, mid, w, h, -90f);

            if (!left && !right) {
                paint.setColor(Color.WHITE);
                paint.setTextSize(30);
                paint.setTextAlign(Paint.Align.CENTER);
                c.drawText("Waiting for phone screen...", w / 2f, h / 2f, paint);
            }
            postInvalidateDelayed(33);
        }

        private boolean drawMirrorEye(Canvas c, float left, float right, float height, float rotation) {
            float cx = (left + right) / 2f;
            float cy = height / 2f;
            float eyeW = right - left;
            c.save();
            c.clipRect(left, 0, right, height);
            c.rotate(rotation, cx, cy);
            float boxW = height * 0.96f;
            float boxH = eyeW * 0.96f;
            dst.set(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f);
            boolean drawn = ScreenCaptureService.drawLatestFrame(c, dst, paint);
            c.restore();
            return drawn;
        }
    }
}
