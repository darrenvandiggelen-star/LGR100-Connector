package com.getfunds.lgr100connector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
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
import android.view.Surface;

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_WIDTH = "capture_width";
    public static final String EXTRA_HEIGHT = "capture_height";
    public static final String EXTRA_DENSITY = "capture_density";

    private static final String CHANNEL_ID = "lgr100_screen_share";
    private static final int NOTIFICATION_ID = 360;
    private static final int MAX_CAPTURE_LONG_EDGE = 960;

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile ScreenCaptureService activeInstance;
    private static volatile SurfaceTexture pendingGpuTexture;
    private static volatile boolean captureActive = false;
    private static volatile boolean gpuCaptureActive = false;
    private static volatile int captureWidth = 0;
    private static volatile int captureHeight = 0;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private int width;
    private int height;
    private int density;
    private SurfaceTexture gpuTexture;
    private Surface gpuSurface;
    private ImageReader fallbackReader;

    public static boolean isCaptureActive() { return captureActive; }
    public static boolean isGpuCaptureActive() { return gpuCaptureActive; }
    public static int getCaptureWidth() { return captureWidth; }
    public static int getCaptureHeight() { return captureHeight; }

    /**
     * The external-display GL renderer owns the SurfaceTexture and calls this after setting its
     * buffer size on the GL thread. The service only wraps it in a Surface and hands that Surface
     * to MediaProjection. This avoids cross-thread SurfaceTexture resize races.
     */
    public static void attachGpuSurface(SurfaceTexture texture) {
        if (texture == null) return;
        ScreenCaptureService service;
        synchronized (INSTANCE_LOCK) {
            pendingGpuTexture = texture;
            service = activeInstance;
        }
        if (service != null) service.postAttachGpuSurface(texture);
    }

    public static void detachGpuSurface(SurfaceTexture texture) {
        if (texture == null) return;
        ScreenCaptureService service;
        synchronized (INSTANCE_LOCK) {
            if (pendingGpuTexture == texture) pendingGpuTexture = null;
            service = activeInstance;
        }
        if (service != null) service.postDetachGpuSurface(texture);
    }

    /** Legacy Canvas method retained so older generated fallback code still compiles. */
    public static boolean drawLatestFrame(Canvas canvas, RectF bounds, Paint paint) {
        return false;
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        captureThread = new HandlerThread("lgr100-gpu-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        synchronized (INSTANCE_LOCK) {
            activeInstance = this;
        }
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

        int requestedW = Math.max(2, intent.getIntExtra(EXTRA_WIDTH, 960));
        int requestedH = Math.max(2, intent.getIntExtra(EXTRA_HEIGHT, 436));
        int[] fitted = fitCaptureSize(requestedW, requestedH);
        width = fitted[0];
        height = fitted[1];
        density = Math.max(120, intent.getIntExtra(EXTRA_DENSITY, 420));
        publishCaptureSize();

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
                gpuCaptureActive = false;
                releaseDisplayObjects();
                mediaProjection = null;
                stopSelf();
            }
        }, captureHandler);

        // Fixed game geometry for the entire capture session. V1.1 resized the producer while
        // COD was rotating into landscape, which can stall a SurfaceTexture/VirtualDisplay queue
        // on some devices. The V1.2 caller supplies the phone's landscape aspect from the start.
        fallbackReader = makeDrainReader(width, height);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "LGR100 GPU Game Capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                fallbackReader.getSurface(),
                null,
                captureHandler);

        captureActive = virtualDisplay != null;
        if (!captureActive) {
            stopSelf();
            return START_NOT_STICKY;
        }

        SurfaceTexture pending;
        synchronized (INSTANCE_LOCK) {
            pending = pendingGpuTexture;
        }
        if (pending != null) postAttachGpuSurface(pending);
        return START_NOT_STICKY;
    }

    private int[] fitCaptureSize(int rawW, int rawH) {
        int w = Math.max(2, rawW);
        int h = Math.max(2, rawH);
        float scale = Math.min(1.0f, MAX_CAPTURE_LONG_EDGE / (float) Math.max(w, h));
        int outW = Math.max(2, (Math.round(w * scale) / 2) * 2);
        int outH = Math.max(2, (Math.round(h * scale) / 2) * 2);
        return new int[]{outW, outH};
    }

    private void publishCaptureSize() {
        captureWidth = width;
        captureHeight = height;
    }

    private ImageReader makeDrainReader(int w, int h) {
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image image = null;
            try {
                image = r.acquireLatestImage();
            } catch (Throwable ignored) {
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);
        return reader;
    }

    private void postAttachGpuSurface(SurfaceTexture texture) {
        Handler h = captureHandler;
        if (h == null) return;
        h.post(() -> attachGpuSurfaceInternal(texture));
    }

    private void attachGpuSurfaceInternal(SurfaceTexture texture) {
        if (texture == null || virtualDisplay == null) return;
        if (gpuTexture == texture && gpuSurface != null) return;

        releaseGpuSurfaceOnly();
        gpuTexture = texture;
        try {
            // Do not call texture.setDefaultBufferSize() here. The GL owner already did that on
            // its rendering thread, preventing a race with updateTexImage().
            gpuSurface = new Surface(gpuTexture);
            virtualDisplay.setSurface(gpuSurface);
            gpuCaptureActive = true;

            if (fallbackReader != null) {
                try { fallbackReader.close(); } catch (Throwable ignored) {}
                fallbackReader = null;
            }
        } catch (Throwable t) {
            gpuCaptureActive = false;
            releaseGpuSurfaceOnly();
            ensureFallbackSurface();
        }
    }

    private void postDetachGpuSurface(SurfaceTexture texture) {
        Handler h = captureHandler;
        if (h == null) return;
        h.post(() -> {
            if (gpuTexture != texture) return;
            try { if (virtualDisplay != null) virtualDisplay.setSurface(null); } catch (Throwable ignored) {}
            releaseGpuSurfaceOnly();
            gpuCaptureActive = false;
            ensureFallbackSurface();
        });
    }

    private void releaseGpuSurfaceOnly() {
        if (gpuSurface != null) {
            try { gpuSurface.release(); } catch (Throwable ignored) {}
            gpuSurface = null;
        }
        gpuTexture = null;
    }

    private void ensureFallbackSurface() {
        if (virtualDisplay == null || fallbackReader != null) return;
        try {
            fallbackReader = makeDrainReader(width, height);
            virtualDisplay.setSurface(fallbackReader.getSurface());
        } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        captureActive = false;
        gpuCaptureActive = false;
        synchronized (INSTANCE_LOCK) {
            if (activeInstance == this) activeInstance = null;
        }
        stopProjectionObjects();
        captureWidth = 0;
        captureHeight = 0;
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        super.onDestroy();
    }

    private void releaseDisplayObjects() {
        captureActive = false;
        gpuCaptureActive = false;
        if (virtualDisplay != null) {
            try { virtualDisplay.setSurface(null); } catch (Throwable ignored) {}
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (fallbackReader != null) {
            try { fallbackReader.close(); } catch (Throwable ignored) {}
            fallbackReader = null;
        }
        releaseGpuSurfaceOnly();
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
            channel.setDescription("Keeps GPU phone/game capture active for the LG 360 VR.");
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
                .setContentText("Persistent VR game capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
