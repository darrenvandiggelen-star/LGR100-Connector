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

    private static Bitmap latestFrame;
    private static volatile boolean captureActive = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private int width;
    private int height;

    public static boolean isCaptureActive() {
        return captureActive;
    }

    public static boolean drawLatestFrame(Canvas canvas, RectF bounds, Paint paint) {
        synchronized (FRAME_LOCK) {
            if (latestFrame == null || latestFrame.isRecycled()) return false;

            // V0.5: FIT_CENTER rather than stretching/cropping the phone image to the eye box.
            // This guarantees the whole captured phone screen remains visible with black bars where needed.
            float srcW = latestFrame.getWidth();
            float srcH = latestFrame.getHeight();
            if (srcW <= 0 || srcH <= 0 || bounds.width() <= 0 || bounds.height() <= 0) return false;

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

            canvas.drawBitmap(latestFrame, null, fitted, paint);
            return true;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        captureThread = new HandlerThread("lgr100-screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
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
        int density = Math.max(120, intent.getIntExtra(EXTRA_DENSITY, 420));

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

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

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
        return START_NOT_STICKY;
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
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = Math.max(0, rowStride - pixelStride * width);
            int paddedWidth = width + rowPadding / Math.max(1, pixelStride);

            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (cropped != padded) padded.recycle();

            synchronized (FRAME_LOCK) {
                Bitmap old = latestFrame;
                latestFrame = cropped;
                if (old != null && old != cropped && !old.isRecycled()) old.recycle();
            }
        } catch (Throwable ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    @Override public void onDestroy() {
        captureActive = false;
        stopProjectionObjects();

        synchronized (FRAME_LOCK) {
            if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
            latestFrame = null;
        }

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
            channel.setDescription("Keeps phone-screen capture active for the LG 360 VR.");
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
                .setContentText("Sharing phone screen to the headset")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
