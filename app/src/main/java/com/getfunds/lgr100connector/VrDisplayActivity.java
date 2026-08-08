package com.getfunds.lgr100connector;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Dedicated renderer activity launched directly on the LGR100 / HDMI display.
 * Call of Duty can own the phone display while this activity keeps consuming and rendering
 * MediaProjection frames on the external display.
 */
public class VrDisplayActivity extends Activity {
    private static volatile WeakReference<VrDisplayActivity> activeRef = new WeakReference<>(null);

    private static volatile float screenScale = 0.78f;
    private static volatile float stereoDepth = 0.0f;
    private static volatile float offsetX = 0.0f;
    private static volatile float offsetY = 0.0f;
    private static volatile boolean worldLocked = false;
    private static volatile double yawDeg = 0.0;
    private static volatile double pitchDeg = 0.0;
    private static volatile double rollDeg = 0.0;
    private static volatile boolean calibrated = false;
    private static volatile double captureFps = 0.0;
    private static volatile double renderFps = 0.0;

    private VrGlView glView;

    public static void updateTuning(float scale, float depth, float x, float y, boolean world) {
        screenScale = clampf(scale, 0.40f, 0.98f);
        stereoDepth = clampf(depth, -0.05f, 0.05f);
        offsetX = clampf(x, -0.30f, 0.30f);
        offsetY = clampf(y, -0.30f, 0.30f);
        worldLocked = world;
        requestRenderActive();
    }

    public static void updateTracking(double yaw, double pitch, double roll, boolean isCalibrated) {
        yawDeg = yaw;
        pitchDeg = pitch;
        rollDeg = roll;
        calibrated = isCalibrated;
    }

    public static double getCaptureFps() { return captureFps; }
    public static double getRenderFps() { return renderFps; }

    public static void requestRenderActive() {
        VrDisplayActivity a = activeRef.get();
        if (a != null && a.glView != null) {
            try { a.glView.requestRender(); } catch (Throwable ignored) {}
        }
    }

    public static void finishActive() {
        VrDisplayActivity a = activeRef.get();
        if (a != null) {
            try { a.runOnUiThread(a::finish); } catch (Throwable ignored) {}
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activeRef = new WeakReference<>(this);
        glView = new VrGlView(this);
        setContentView(glView);
    }

    @Override protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
    }

    @Override protected void onPause() {
        // Intentionally do not pause GLSurfaceView here. On a multi-display device this Activity
        // can lose top focus when COD is focused on the phone while remaining visible on LGR100.
        super.onPause();
    }

    @Override protected void onDestroy() {
        VrDisplayActivity current = activeRef.get();
        if (current == this) activeRef = new WeakReference<>(null);
        if (glView != null) {
            try { glView.releaseRenderer(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private static float clampf(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampd(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class VrGlView extends GLSurfaceView {
        private final RendererImpl renderer;

        VrGlView(Activity context) {
            super(context);
            setEGLContextClientVersion(2);
            setEGLConfigChooser(8, 8, 8, 0, 0, 0);
            renderer = new RendererImpl(this);
            setRenderer(renderer);
            // Render on new game frames instead of continuously consuming GPU time.
            setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            setPreserveEGLContextOnPause(true);
            setKeepScreenOn(true);
        }

        void releaseRenderer() {
            try { queueEvent(renderer::release); } catch (Throwable ignored) {}
        }
    }

    private static final class RendererImpl implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        private final GLSurfaceView owner;
        private final float[] texMatrix = new float[16];
        private final FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer texBuffer = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final AtomicBoolean pendingFrame = new AtomicBoolean(false);

        private int program;
        private int oesTexture;
        private int aPosition;
        private int aTexCoord;
        private int uTexMatrix;
        private int uTexture;
        private SurfaceTexture surfaceTexture;
        private HandlerThread frameCallbackThread;
        private Handler frameCallbackHandler;
        private volatile boolean released;
        private int surfaceW;
        private int surfaceH;
        private int lastCaptureW;
        private int lastCaptureH;
        private long fpsWindowNs;
        private int captureFrames;
        private int renderedFrames;

        RendererImpl(GLSurfaceView owner) {
            this.owner = owner;
            texBuffer.put(new float[]{
                    0f, 0f,
                    1f, 0f,
                    0f, 1f,
                    1f, 1f
            }).position(0);
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            released = false;
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) return;

            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uTexture = GLES20.glGetUniformLocation(program, "sTexture");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            oesTexture = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            int cw = Math.max(2, ScreenCaptureService.getCaptureWidth());
            int ch = Math.max(2, ScreenCaptureService.getCaptureHeight());
            lastCaptureW = cw;
            lastCaptureH = ch;

            surfaceTexture = new SurfaceTexture(oesTexture);
            surfaceTexture.setDefaultBufferSize(cw, ch);

            // Frame callbacks are independent of the phone Activity's main looper.
            frameCallbackThread = new HandlerThread("lgr100-vr-frame-callback");
            frameCallbackThread.start();
            frameCallbackHandler = new Handler(frameCallbackThread.getLooper());
            surfaceTexture.setOnFrameAvailableListener(this, frameCallbackHandler);
            ScreenCaptureService.attachGpuSurface(surfaceTexture);

            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            setIdentity(texMatrix);
            fpsWindowNs = System.nanoTime();
            owner.requestRender();
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            surfaceW = Math.max(2, width);
            surfaceH = Math.max(2, height);
            if (surfaceTexture != null) ScreenCaptureService.attachGpuSurface(surfaceTexture);
            owner.requestRender();
        }

        @Override public void onFrameAvailable(SurfaceTexture ignored) {
            if (released) return;
            pendingFrame.set(true);
            try { owner.requestRender(); } catch (Throwable ignored2) {}
        }

        @Override public void onDrawFrame(GL10 gl) {
            if (released || program == 0 || surfaceTexture == null || surfaceW <= 1 || surfaceH <= 1) return;

            int cw = Math.max(2, ScreenCaptureService.getCaptureWidth());
            int ch = Math.max(2, ScreenCaptureService.getCaptureHeight());
            if (cw != lastCaptureW || ch != lastCaptureH) {
                lastCaptureW = cw;
                lastCaptureH = ch;
                // Only the GL thread changes the SurfaceTexture buffer size.
                try { surfaceTexture.setDefaultBufferSize(cw, ch); } catch (Throwable ignored) {}
            }

            if (pendingFrame.getAndSet(false)) {
                try {
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(texMatrix);
                    captureFrames++;
                } catch (Throwable ignored) {}
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            texBuffer.position(0);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

            int half = surfaceW / 2;
            drawEye(0, half, surfaceH, 90f, true, cw, ch);
            drawEye(half, surfaceW - half, surfaceH, -90f, false, cw, ch);

            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);

            renderedFrames++;
            long now = System.nanoTime();
            long elapsed = now - fpsWindowNs;
            if (elapsed >= 1_000_000_000L) {
                double seconds = elapsed / 1_000_000_000.0;
                captureFps = captureFrames / seconds;
                renderFps = renderedFrames / seconds;
                captureFrames = 0;
                renderedFrames = 0;
                fpsWindowNs = now;
            }

            // Avoid losing a producer notification that raced with getAndSet(false).
            if (pendingFrame.get()) owner.requestRender();
        }

        private void drawEye(int viewportX, int viewportW, int viewportH, float panelRotation,
                             boolean leftEye, int sourceW, int sourceH) {
            if (viewportW <= 1 || viewportH <= 1) return;
            GLES20.glViewport(viewportX, 0, viewportW, viewportH);

            float logicalW = viewportH;
            float logicalH = viewportW;
            float maxW = logicalW * screenScale;
            float maxH = logicalH * screenScale;
            float srcAspect = Math.max(0.01f, sourceW / (float) Math.max(1, sourceH));
            float boxAspect = maxW / Math.max(1f, maxH);
            float drawW;
            float drawH;
            if (srcAspect >= boxAspect) {
                drawW = maxW;
                drawH = drawW / srcAspect;
            } else {
                drawH = maxH;
                drawW = drawH * srcAspect;
            }

            float headX = 0f;
            float headY = 0f;
            float headRoll = 0f;
            if (worldLocked && calibrated) {
                headX = (float) clampd((-yawDeg / 55.0) * logicalW * 0.30, -logicalW * 0.30, logicalW * 0.30);
                headY = (float) clampd((pitchDeg / 40.0) * logicalH * 0.30, -logicalH * 0.30, logicalH * 0.30);
                headRoll = (float) clampd(-rollDeg, -35.0, 35.0);
            }

            float centerX = offsetX * logicalW + headX;
            float centerY = offsetY * logicalH + headY;
            centerX += logicalW * stereoDepth * (leftEye ? 1f : -1f);

            float hw = drawW * 0.5f;
            float hh = drawH * 0.5f;
            float[] lx = {-hw, hw, -hw, hw};
            float[] ly = {hh, hh, -hh, -hh};
            float[] out = new float[8];

            double rollRad = Math.toRadians(headRoll);
            float rCos = (float) Math.cos(rollRad);
            float rSin = (float) Math.sin(rollRad);
            double panelRad = Math.toRadians(panelRotation);
            float pCos = (float) Math.cos(panelRad);
            float pSin = (float) Math.sin(panelRad);

            for (int i = 0; i < 4; i++) {
                float x = lx[i];
                float y = ly[i];
                float rx = rCos * x - rSin * y;
                float ry = rSin * x + rCos * y;
                rx += centerX;
                ry += centerY;
                float px = pCos * rx - pSin * ry;
                float py = pSin * rx + pCos * ry;
                out[i * 2] = px / (viewportW * 0.5f);
                out[i * 2 + 1] = -py / (viewportH * 0.5f);
            }

            vertexBuffer.clear();
            vertexBuffer.put(out).position(0);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        void release() {
            if (released) return;
            released = true;
            if (surfaceTexture != null) {
                ScreenCaptureService.detachGpuSurface(surfaceTexture);
                try { surfaceTexture.setOnFrameAvailableListener(null); } catch (Throwable ignored) {}
                try { surfaceTexture.release(); } catch (Throwable ignored) {}
                surfaceTexture = null;
            }
            if (frameCallbackThread != null) {
                try { frameCallbackThread.quitSafely(); } catch (Throwable ignored) {}
                frameCallbackThread = null;
                frameCallbackHandler = null;
            }
            if (oesTexture != 0) {
                int[] ids = {oesTexture};
                GLES20.glDeleteTextures(1, ids, 0);
                oesTexture = 0;
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }

        private static void setIdentity(float[] m) {
            for (int i = 0; i < 16; i++) m[i] = 0f;
            m[0] = m[5] = m[10] = m[15] = 1f;
        }

        private static int createProgram(String vertexSource, String fragmentSource) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertex == 0) return 0;
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (fragment == 0) {
                GLES20.glDeleteShader(vertex);
                return 0;
            }
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vertex);
            GLES20.glAttachShader(p, fragment);
            GLES20.glLinkProgram(p);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            if (linked[0] == 0) {
                GLES20.glDeleteProgram(p);
                return 0;
            }
            return p;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private static final String VERTEX_SHADER =
                "attribute vec2 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                "  vec4 t = uTexMatrix * vec4(aTexCoord, 0.0, 1.0);\n" +
                "  vTexCoord = t.xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                "}\n";
    }
}
