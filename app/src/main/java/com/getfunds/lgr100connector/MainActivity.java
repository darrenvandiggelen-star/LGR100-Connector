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

        append("LG 360 VR Connector V0.1");
        append("Target USB ID 1004:6374");
        append("Wake command: 03 0C + VR App Start");
        scan();
        refreshStatus();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (presentation != null) presentation.dismiss();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("LG 360 VR Connector");
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

        Button activate = button("Activate headset");
        activate.setOnClickListener(v -> activateHeadset());
        root.addView(activate);

        Button test = button("Show VR test display");
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
        if (headset == null) scan();
        if (headset == null) { append("Activation stopped: headset not detected"); return; }
        if (!usbManager.hasPermission(headset)) { append("USB permission required first"); requestPermission(); return; }

        UsbDeviceConnection connection = usbManager.openDevice(headset);
        if (connection == null) { append("openDevice() failed"); return; }

        byte[] text = "VR App Start".getBytes(StandardCharsets.US_ASCII);
        byte[] report = new byte[14];
        report[0] = 0x03;
        report[1] = 0x0C;
        System.arraycopy(text, 0, report, 2, text.length);
        int best = -1;

        try {
            for (int i = 0; i < headset.getInterfaceCount(); i++) {
                UsbInterface intf = headset.getInterface(i);
                if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
                boolean claimed = connection.claimInterface(intf, true);
                append("Claim HID interface " + intf.getId() + " = " + claimed);
                if (!claimed) continue;

                int result = connection.controlTransfer(0x21, 0x09, 0x0203, intf.getId(), report, report.length, 1500);
                append("SET_REPORT result=" + result);
                best = Math.max(best, result);

                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        int out = connection.bulkTransfer(ep, report, report.length, 1500);
                        append("OUT endpoint write=" + out);
                        best = Math.max(best, out);
                    }
                }
                connection.releaseInterface(intf);
            }
        } catch (Throwable t) {
            append("Activation error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            connection.close();
        }

        append(best >= 0 ? "Activation command transferred; checking for external display" : "Activation transfer failed");
        handler.postDelayed(() -> { refreshStatus(); if (findExternalDisplay() != null) showTestDisplay(); }, 2000);
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
        append("Test image shown on " + d.getName());
    }

    private void refreshStatus() {
        boolean detected = headset != null;
        boolean permission = detected && usbManager.hasPermission(headset);
        Display ext = findExternalDisplay();
        status.setText((detected ? "LGR100 DETECTED" : "LGR100 NOT DETECTED") + "\n" + (permission ? "USB permission OK" : "USB permission needed") + " • " + (ext != null ? "External display: " + ext.getName() : "No external display"));
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(detected ? Color.rgb(30, 130, 80) : Color.rgb(90, 95, 105));
    }

    private void append(String message) {
        if (log != null) log.append(message + "\n");
    }

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private static class TestPatternView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TestPatternView(Context context) { super(context); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float mid = w / 2f;
            c.drawColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(20, 65, 125));
            c.drawRect(0, 0, mid, h, paint);
            paint.setColor(Color.rgb(125, 45, 45));
            c.drawRect(mid, 0, w, h, paint);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(24, Math.min(w, h) / 12f));
            c.save();
            c.rotate(-90, mid / 2, h / 2f);
            c.drawText("LEFT - LGR100", mid / 2, h / 2f, paint);
            c.restore();
            c.save();
            c.rotate(90, mid + mid / 2, h / 2f);
            c.drawText("RIGHT - LGR100", mid + mid / 2, h / 2f, paint);
            c.restore();
        }
    }
}
