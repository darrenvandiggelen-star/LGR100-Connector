package com.getfunds.lgr100connector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_WIDTH = "capture_width";
    public static final String EXTRA_HEIGHT = "capture_height";
    public static final String EXTRA_DENSITY = "capture_density";

    private static final String CHANNEL_ID = "lgr100_screen_share";
    private static final int NOTIFICATION_ID = 360;
    private static final Object FRAME_LOCK = new Object();
    private static final int MAX_CAPTURE_LONG_EDGE = 1440;

    private static Bitmap latestFrame;
    private static int latestContentWidth;
    private static int latestContentHeight;
    private static volatile boolean captureActive = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private int width;
    private int height;
    private int density;

    private final Runnable reconfigureRunnable = this::reconfigureForCurrentPhoneDisplay;

    public static boolean isCaptureActive() {
        return captureActive;
    }

    public static boolean drawLatestFrame(Canvas canvas, RectF bounds, Paint paint) {
        synchronized (FRAME_LOCK) {
            if (latestFrame == null || latestFrame.isRecycled()) return false;
            int srcW = Math.min(latestContentWidth, latestFrame.getWidth());
            int srcH = Math.min(latestContentHeight, latestFrame.getHeight());
            if (srcW <= 0 || srcH <= 0 || bounds.width() <= 0 || bounds.height() <= 0) return false;

            // FIT_CENTER guarantees the entire phone/game frame is visible in each eye.
            float scale = Math.min(bounds.width() / srcW, bounds.height() / srcH);
            float drawW = srcW * scale;
            float drawH = srcH * scale;
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            RectF fitted = new RectF(
                    cx - drawW / 2f,
                    cy - drawH / 2f,
                    cx + drawW / 2f,
                    cy + drawH / 2f);
            Rect src = new Rect(0, 0, srcW, srcH);
            canvas.drawBitmap(latestFrame, src, fitted, paint);
            return true;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        captureThread = new HandlerThread("lgr100-screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY || captureHandler == null || mediaProjection == null) return;
                captureHandler.removeCallbacks(reconfigureRunnable);
                captureHandler.postDelayed(reconfigureRunnable, 250);
            }
        };
        displayManager.registerDisplayListener(displayListener, captureHandler);
    }

    @Override @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        else resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        width = Math.max(2, intent.getIntExtra(EXTRA_WIDTH, 720));
        height = Math.max(2, intent.getIntExtra(EXTRA_HEIGHT, 1280));
        density = Math.max(120, intent.getIntExtra(EXTRA_DENSITY, 420));

        if (resultCode != -1 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        stopProjectionObjects();

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                captureActive = false;
                releaseDisplayObjects();
                mediaProjection = null;
                stopSelf();
            }
        }, captureHandler);

        imageReader = makeImageReader(width, height);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "LGR100 Phone Screen Capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);

        captureActive = virtualDisplay != null;
        if (!captureActive) stopSelf();
        else captureHandler.postDelayed(reconfigureRunnable, 350);
        return START_NOT_STICKY;
    }

    private ImageReader makeImageReader(int w, int h) {
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        return reader;
    }

    @SuppressWarnings("deprecation")
    private void reconfigureForCurrentPhoneDisplay() {
        if (virtualDisplay == null || mediaProjection == null || displayManager == null) return;
        Display phone = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (phone == null) return;

        DisplayMetrics dm = new DisplayMetrics();
        phone.getRealMetrics(dm);
        if (dm.widthPixels <= 1 || dm.heightPixels <= 1) return;

        float scale = Math.min(1.0f, MAX_CAPTURE_LONG_EDGE / (float) Math.max(dm.widthPixels, dm.heightPixels));
        int newW = Math.max(2, Math.round(dm.widthPixels * scale));
        int newH = Math.max(2, Math.round(dm.heightPixels * scale));
        int newDensity = Math.max(120, dm.densityDpi);

        if (newW == width && newH == height && newDensity == density) return;

        ImageReader oldReader = imageReader;
        ImageReader newReader = makeImageReader(newW, newH);
        try {
            virtualDisplay.setSurface(null);
            virtualDisplay.resize(newW, newH, newDensity);
            virtualDisplay.setSurface(newReader.getSurface());
            imageReader = newReader;
            width = newW;
            height = newH;
            density = newDensity;
            clearLatestFrame();
            if (oldReader != null) {
                try { oldReader.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try { newReader.close(); } catch (Throwable ignored) {}
            if (oldReader != null) {
                try { virtualDisplay.setSurface(oldReader.getSurface()); } catch (Throwable ignored) {}
            }
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = Math.max(1, plane.getPixelStride());
            int rowStride = plane.getRowStride();
            int rowPadding = Math.max(0, rowStride - pixelStride * width);
            int paddedWidth = width + rowPadding / pixelStride;
            buffer.rewind();

            synchronized (FRAME_LOCK) {
                if (latestFrame == null || latestFrame.isRecycled()
                        || latestFrame.getWidth() != paddedWidth || latestFrame.getHeight() != height) {
                    if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
                    latestFrame = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
                }
                latestFrame.copyPixelsFromBuffer(buffer);
                latestContentWidth = width;
                latestContentHeight = height;
            }
        } catch (Throwable ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private static void clearLatestFrame() {
        synchronized (FRAME_LOCK) {
            if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
            latestFrame = null;
            latestContentWidth = 0;
            latestContentHeight = 0;
        }
    }

    @Override public void onDestroy() {
        captureActive = false;
        if (displayManager != null && displayListener != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Throwable ignored) {}
        }
        if (captureHandler != null) captureHandler.removeCallbacks(reconfigureRunnable);
        stopProjectionObjects();
        clearLatestFrame();

        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        super.onDestroy();
    }

    private void releaseDisplayObjects() {
        captureActive = false;
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
    }

    private void stopProjectionObjects() {
        releaseDisplayObjects();
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "LG 360 VR screen sharing",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps low-latency phone/game capture active for the LG 360 VR.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("LG 360 VR")
                .setContentText("VR game screen is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
