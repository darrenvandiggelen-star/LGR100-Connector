from pathlib import Path

path = Path('app/src/main/java/com/getfunds/lgr100connector/MainActivity.java')
s = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'V1.1 patch failed: could not find {label}')
    s = s.replace(old, new, 1)

# Version labels after V1.0 has run.
s = s.replace('LG 360 VR Connector V1.0', 'LG 360 VR Connector V1.1')
s = s.replace('=== V1.0 WAKE SEQUENCE START ===', '=== V1.1 WAKE SEQUENCE START ===')
s = s.replace('=== V1.0 WAKE SEQUENCE COMPLETE ===', '=== V1.1 WAKE SEQUENCE COMPLETE ===')
s = s.replace(
    'VR gaming screen: proper stereo alignment, head tracking, live size/depth/position controls and rotation-aware capture',
    'GPU VR gaming screen: zero-copy MediaProjection texture, 960px capture, fusion-first defaults and live FPS')

# OpenGL / SurfaceTexture imports.
s = s.replace('import android.graphics.RectF;\n', 'import android.graphics.RectF;\nimport android.graphics.SurfaceTexture;\n')
s = s.replace('import android.media.projection.MediaProjectionManager;\n',
              'import android.media.projection.MediaProjectionManager;\nimport android.opengl.GLES11Ext;\nimport android.opengl.GLES20;\nimport android.opengl.GLSurfaceView;\n')
s = s.replace('import android.view.Gravity;\n', 'import android.view.Gravity;\nimport android.view.Surface;\n')
s = s.replace('import java.nio.ByteBuffer;\n', 'import java.nio.ByteBuffer;\nimport java.nio.FloatBuffer;\n')
s = s.replace('import java.util.Locale;\n',
              'import java.util.Locale;\n\nimport javax.microedition.khronos.egl.EGLConfig;\nimport javax.microedition.khronos.opengles.GL10;\n')

# Gaming defaults: exact duplicate first, head locked first. User can add disparity after fusion works.
s = s.replace('private volatile float vrScreenScale = 0.80f;', 'private volatile float vrScreenScale = 0.78f;')
s = s.replace('private volatile float vrStereoDepth = 0.012f;', 'private volatile float vrStereoDepth = 0.0f;')
s = s.replace('private volatile boolean vrWorldLocked = true;', 'private volatile boolean vrWorldLocked = false;')

replace_once(
'''    private volatile boolean vrWorldLocked = false;''',
'''    private volatile boolean vrWorldLocked = false;\n    private volatile double vrRenderFps = 0.0;\n    private volatile double vrCaptureFps = 0.0;''',
'VR FPS fields')

# Let convergence move through zero in either direction. One headset may need crossed or uncrossed disparity.
s = s.replace('vrStereoDepth = (float) clamp(vrStereoDepth + delta, 0.0, 0.045);',
              'vrStereoDepth = (float) clamp(vrStereoDepth + delta, -0.045, 0.045);')

# Reset to fusion-first / gaming-first defaults.
s = s.replace('vrScreenScale = 0.80f;\n        vrStereoDepth = 0.012f;',
              'vrScreenScale = 0.78f;\n        vrStereoDepth = 0.0f;')
s = s.replace('vrWorldLocked = true;\n        resetOrientation();\n        updateVrScreenStatus();\n        append("VR screen tuning reset to defaults");',
              'vrWorldLocked = false;\n        resetOrientation();\n        updateVrScreenStatus();\n        append("VR screen tuning reset: HEAD LOCKED, zero eye separation");')

# Show actual GL/render and incoming capture rates in the control panel.
replace_once(
'''        String text = String.format(Locale.US,\n                "VR SCREEN | size %.0f%% | stereo depth %.1f%% | X %+.0f%% | Y %+.0f%% | %s",\n                vrScreenScale * 100f, vrStereoDepth * 100f, vrOffsetX * 100f, vrOffsetY * 100f,\n                vrWorldLocked ? "WORLD LOCKED" : "HEAD LOCKED");''',
'''        String text = String.format(Locale.US,\n                "VR SCREEN | size %.0f%% | eye offset %+.1f%% | X %+.0f%% | Y %+.0f%% | %s | capture %.0f fps | render %.0f fps | %s",\n                vrScreenScale * 100f, vrStereoDepth * 100f, vrOffsetX * 100f, vrOffsetY * 100f,\n                vrWorldLocked ? "WORLD LOCKED" : "HEAD LOCKED",\n                vrCaptureFps, vrRenderFps, ScreenCaptureService.isGpuCaptureActive() ? "GPU" : "waiting GPU");''',
'VR FPS status')

# Capture source resolution should match what the headset can actually resolve.
s = s.replace('float scale = Math.min(1.0f, 1440.0f / Math.max(width, height));',
              'float scale = Math.min(1.0f, 960.0f / Math.max(width, height));')
s = s.replace('VR gaming capture starting at ', 'GPU VR gaming capture starting at ')

# Use GLSurfaceView instead of CPU Canvas/Bitmap drawing.
replace_once(
'''            setContentView(new ScreenMirrorView(getContext()));''',
'''            setContentView(new VrGameGlView(getContext()));''',
'GPU mirror presentation')

# Insert the GPU renderer immediately before the legacy Canvas mirror view.
marker = '    private class ScreenMirrorView extends View {'
if marker not in s:
    raise SystemExit('V1.1 patch failed: could not find legacy ScreenMirrorView insertion point')

gl_code = r'''    private class VrGameGlView extends GLSurfaceView {
        private final VrGameRenderer renderer;

        VrGameGlView(Context context) {
            super(context);
            setEGLContextClientVersion(2);
            setEGLConfigChooser(8, 8, 8, 0, 0, 0);
            renderer = new VrGameRenderer(this);
            setRenderer(renderer);
            // Continuous rendering keeps head motion smooth even when the game only updates at 30/45/60 Hz.
            setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            setPreserveEGLContextOnPause(true);
            setKeepScreenOn(true);
        }

        @Override protected void onDetachedFromWindow() {
            try { queueEvent(renderer::release); } catch (Throwable ignored) {}
            super.onDetachedFromWindow();
        }
    }

    private class VrGameRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        private final GLSurfaceView owner;
        private final float[] texMatrix = new float[16];
        private final FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer texBuffer = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        private int program;
        private int oesTexture;
        private int aPosition;
        private int aTexCoord;
        private int uTexMatrix;
        private int uTexture;
        private SurfaceTexture surfaceTexture;
        private volatile boolean frameAvailable;
        private volatile boolean released;
        private int surfaceW;
        private int surfaceH;
        private long fpsWindowNs;
        private int renderFrames;
        private int captureFrames;
        private int lastCaptureW;
        private int lastCaptureH;

        VrGameRenderer(GLSurfaceView owner) {
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

            surfaceTexture = new SurfaceTexture(oesTexture);
            int cw = Math.max(2, ScreenCaptureService.getCaptureWidth());
            int ch = Math.max(2, ScreenCaptureService.getCaptureHeight());
            surfaceTexture.setDefaultBufferSize(cw, ch);
            lastCaptureW = cw;
            lastCaptureH = ch;
            surfaceTexture.setOnFrameAvailableListener(this);
            ScreenCaptureService.attachGpuSurface(surfaceTexture);

            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            fpsWindowNs = System.nanoTime();
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            surfaceW = Math.max(2, width);
            surfaceH = Math.max(2, height);
            if (surfaceTexture != null) ScreenCaptureService.attachGpuSurface(surfaceTexture);
        }

        @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            frameAvailable = true;
        }

        @Override public void onDrawFrame(GL10 gl) {
            if (released || program == 0 || surfaceTexture == null || surfaceW <= 1 || surfaceH <= 1) return;

            int cw = Math.max(2, ScreenCaptureService.getCaptureWidth());
            int ch = Math.max(2, ScreenCaptureService.getCaptureHeight());
            if (cw != lastCaptureW || ch != lastCaptureH) {
                lastCaptureW = cw;
                lastCaptureH = ch;
                try { surfaceTexture.setDefaultBufferSize(cw, ch); } catch (Throwable ignored) {}
            }

            if (frameAvailable) {
                try {
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(texMatrix);
                    captureFrames++;
                } catch (Throwable ignored) {
                }
                frameAvailable = false;
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

            renderFrames++;
            long now = System.nanoTime();
            long elapsed = now - fpsWindowNs;
            if (elapsed >= 1_000_000_000L) {
                double sec = elapsed / 1_000_000_000.0;
                vrRenderFps = renderFrames / sec;
                vrCaptureFps = captureFrames / sec;
                renderFrames = 0;
                captureFrames = 0;
                fpsWindowNs = now;
                handler.post(MainActivity.this::updateVrScreenStatus);
            }
        }

        private void drawEye(int viewportX, int viewportW, int viewportH, float panelRotation,
                             boolean leftEye, int sourceW, int sourceH) {
            if (viewportW <= 1 || viewportH <= 1) return;
            GLES20.glViewport(viewportX, 0, viewportW, viewportH);

            // The physical LGR100 panel halves are rotated +/-90 degrees. Work in the logical
            // post-rotation viewport, then rotate the final quad into the real half-display.
            float logicalW = viewportH;
            float logicalH = viewportW;

            float maxW = logicalW * vrScreenScale;
            float maxH = logicalH * vrScreenScale;
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
            if (vrWorldLocked && calibrated) {
                headX = (float) clamp((-yawDeg / 55.0) * logicalW * 0.30, -logicalW * 0.30, logicalW * 0.30);
                headY = (float) clamp((pitchDeg / 40.0) * logicalH * 0.30, -logicalH * 0.30, logicalH * 0.30);
                headRoll = (float) clamp(-rollDeg, -35.0, 35.0);
            }

            float centerX = vrOffsetX * logicalW + headX;
            float centerY = vrOffsetY * logicalH + headY;

            // Symmetric convergence control. Zero means identical centered images in both eyes.
            centerX += logicalW * vrStereoDepth * (leftEye ? 1f : -1f);

            float hw = drawW * 0.5f;
            float hh = drawH * 0.5f;

            // Triangle strip order: BL, BR, TL, TR in logical screen coordinates (Y down).
            float[] lx = {-hw, hw, -hw, hw};
            float[] ly = { hh, hh, -hh, -hh};
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

                // Rotate the virtual monitor in its logical plane for head roll.
                float rx = rCos * x - rSin * y;
                float ry = rSin * x + rCos * y;
                rx += centerX;
                ry += centerY;

                // Rotate the complete logical eye into the physical LGR100 panel orientation.
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
            SurfaceTexture st = surfaceTexture;
            surfaceTexture = null;
            if (st != null) {
                ScreenCaptureService.detachGpuSurface(st);
                try { st.setOnFrameAvailableListener(null); } catch (Throwable ignored) {}
                try { st.release(); } catch (Throwable ignored) {}
            }
            if (oesTexture != 0) {
                int[] textures = {oesTexture};
                try { GLES20.glDeleteTextures(1, textures, 0); } catch (Throwable ignored) {}
                oesTexture = 0;
            }
            if (program != 0) {
                try { GLES20.glDeleteProgram(program); } catch (Throwable ignored) {}
                program = 0;
            }
        }

        private int createProgram(String vertexSource, String fragmentSource) {
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

        private int compileShader(int type, String source) {
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
                "  vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                "}\n";
    }

'''

s = s.replace(marker, gl_code + marker, 1)

path.write_text(s, encoding='utf-8')
print('V1.1 GPU zero-copy VR gaming patch applied successfully')
