from pathlib import Path

path = Path('app/src/main/java/com/getfunds/lgr100connector/MainActivity.java')
s = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'V0.7 patch failed: could not find {label}')
    s = s.replace(old, new, 1)

# Version labels after V0.5 and V0.6 patches have run.
s = s.replace('LG 360 VR Connector V0.6', 'LG 360 VR Connector V0.7')
s = s.replace('=== V0.6 WAKE SEQUENCE START ===', '=== V0.7 WAKE SEQUENCE START ===')
s = s.replace('=== V0.6 WAKE SEQUENCE COMPLETE ===', '=== V0.7 WAKE SEQUENCE COMPLETE ===')
s = s.replace('Calibration noise is reported separately; valid baselines are no longer rejected as movement',
              'Adds guided IMU axis mapping so yaw/pitch/roll are measured from this LGR100 instead of assumed')

# Add axis mapping state.
replace_once(
'''    private volatile double yawDeg = 0.0;\n    private volatile double pitchDeg = 0.0;\n    private volatile double rollDeg = 0.0;\n    private long lastOrientationNs = 0;''',
'''    private volatile double yawDeg = 0.0;\n    private volatile double pitchDeg = 0.0;\n    private volatile double rollDeg = 0.0;\n    private long lastOrientationNs = 0;\n\n    private volatile boolean axisMappingActive = false;\n    private volatile long axisMappingStartMs = 0;\n    private final double[][] axisEnergy = new double[3][3];\n    private final long[] axisSamples = new long[3];\n    private volatile String axisMappingPrompt = "NOT STARTED";\n    private volatile String axisMappingResult = "NOT MAPPED";\n    private volatile int mappedYawAxis = -1;\n    private volatile int mappedPitchAxis = -1;\n    private volatile int mappedRollAxis = -1;''',
'axis mapping fields')

# Replace the misleading head-track button with the mapping routine.
replace_once(
'''        Button headTest = button("Head-track test");\n        headTest.setOnClickListener(v -> showHeadTrackDisplay());''',
'''        Button headTest = button("Map IMU axes");\n        headTest.setOnClickListener(v -> beginAxisMapping());''',
'head-track button')

# Feed each valid IMU sample into the guided mapper.
replace_once(
'''                    accumulateCalibration(gx, gy, gz, ax, ay, az, now);\n                    updateOrientation(gx, gy, gz, ax, ay, az);''',
'''                    accumulateCalibration(gx, gy, gz, ax, ay, az, now);\n                    accumulateAxisMapping(gx, gy, gz, now);\n                    updateOrientation(gx, gy, gz, ax, ay, az);''',
'IMU mapper hook')

# Insert axis mapping methods before the old head-track presentation method.
replace_once(
'''    private void showHeadTrackDisplay() {\n        Display d = findExternalDisplay();\n        if (d == null) { append("No external display for head-track test"); return; }\n        if (!sensorStreaming) startSensorDiagnostics();\n        replacePresentation(new HeadTrackPresentation(this, d));\n        append("Head-track test shown. Calibrate first for best results.");\n    }''',
'''    private void beginAxisMapping() {\n        if (!sensorStreaming) {\n            append("Start sensors first; starting them now before IMU mapping");\n            startSensorDiagnostics();\n            handler.postDelayed(this::beginAxisMapping, 1300);\n            return;\n        }\n        if (!calibrated) {\n            append("Calibrate the stationary baseline before mapping axes");\n            beginCalibration();\n            handler.postDelayed(this::beginAxisMapping, 4500);\n            return;\n        }\n        Display d = findExternalDisplay();\n        if (d == null) { append("No external display for IMU axis mapping"); return; }\n\n        synchronized (axisEnergy) {\n            for (int r = 0; r < 3; r++) {\n                for (int c = 0; c < 3; c++) axisEnergy[r][c] = 0.0;\n                axisSamples[r] = 0;\n            }\n        }\n        mappedYawAxis = mappedPitchAxis = mappedRollAxis = -1;\n        axisMappingResult = "MAPPING IN PROGRESS";\n        axisMappingPrompt = "KEEP STILL";\n        axisMappingStartMs = System.currentTimeMillis();\n        axisMappingActive = true;\n        replacePresentation(new AxisMapPresentation(this, d));\n        append("IMU mapping started: 2s still, 3s left/right, 1s still, 3s up/down, 1s still, 3s tilt left/right");\n    }\n\n    private void accumulateAxisMapping(int gx, int gy, int gz, long nowMs) {\n        if (!axisMappingActive) return;\n        long elapsed = nowMs - axisMappingStartMs;\n        int phase = -1;\n        if (elapsed < 2000) {\n            axisMappingPrompt = "KEEP STILL";\n        } else if (elapsed < 5000) {\n            axisMappingPrompt = "TURN LEFT / RIGHT";\n            phase = 0;\n        } else if (elapsed < 6000) {\n            axisMappingPrompt = "KEEP STILL";\n        } else if (elapsed < 9000) {\n            axisMappingPrompt = "LOOK UP / DOWN";\n            phase = 1;\n        } else if (elapsed < 10000) {\n            axisMappingPrompt = "KEEP STILL";\n        } else if (elapsed < 13000) {\n            axisMappingPrompt = "TILT LEFT / RIGHT";\n            phase = 2;\n        } else {\n            finishAxisMapping();\n            return;\n        }\n\n        if (phase >= 0) {\n            double dx = gx - gyroBiasX;\n            double dy = gy - gyroBiasY;\n            double dz = gz - gyroBiasZ;\n            synchronized (axisEnergy) {\n                axisEnergy[phase][0] += Math.abs(dx);\n                axisEnergy[phase][1] += Math.abs(dy);\n                axisEnergy[phase][2] += Math.abs(dz);\n                axisSamples[phase]++;\n            }\n        }\n    }\n\n    private void finishAxisMapping() {\n        if (!axisMappingActive) return;\n        axisMappingActive = false;\n\n        double[][] avg = new double[3][3];\n        synchronized (axisEnergy) {\n            for (int r = 0; r < 3; r++) {\n                double count = Math.max(1.0, axisSamples[r]);\n                for (int c = 0; c < 3; c++) avg[r][c] = axisEnergy[r][c] / count;\n            }\n        }\n\n        int[][] perms = {\n                {0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}\n        };\n        double best = -1;\n        int[] chosen = perms[0];\n        for (int[] p : perms) {\n            double score = avg[0][p[0]] + avg[1][p[1]] + avg[2][p[2]];\n            if (score > best) { best = score; chosen = p; }\n        }\n        mappedYawAxis = chosen[0];\n        mappedPitchAxis = chosen[1];\n        mappedRollAxis = chosen[2];\n        axisMappingResult = "YAW=" + gyroAxisName(mappedYawAxis) +\n                "  PITCH=" + gyroAxisName(mappedPitchAxis) +\n                "  ROLL=" + gyroAxisName(mappedRollAxis);\n        axisMappingPrompt = "DONE: " + axisMappingResult;\n\n        append(String.format(Locale.US,\n                "IMU axis map DONE -> %s | yaw energy [%.0f %.0f %.0f] | pitch [%.0f %.0f %.0f] | roll [%.0f %.0f %.0f]",\n                axisMappingResult,\n                avg[0][0], avg[0][1], avg[0][2],\n                avg[1][0], avg[1][1], avg[1][2],\n                avg[2][0], avg[2][1], avg[2][2]));\n    }\n\n    private String gyroAxisName(int axis) {\n        if (axis == 0) return "GX";\n        if (axis == 1) return "GY";\n        if (axis == 2) return "GZ";\n        return "?";\n    }\n\n    private void showHeadTrackDisplay() {\n        Display d = findExternalDisplay();\n        if (d == null) { append("No external display for head-track test"); return; }\n        if (mappedYawAxis < 0) {\n            append("Run Map IMU axes before using head tracking");\n            beginAxisMapping();\n            return;\n        }\n        replacePresentation(new HeadTrackPresentation(this, d));\n        append("Mapped head-track test shown using " + axisMappingResult);\n    }''',
'axis mapping methods')

# Add a presentation for the guided mapping instructions.
replace_once(
'''    private static class ScreenMirrorPresentation extends Presentation {''',
'''    private class AxisMapPresentation extends Presentation {\n        AxisMapPresentation(Context outerContext, Display display) { super(outerContext, display); }\n        @Override protected void onCreate(Bundle savedInstanceState) {\n            super.onCreate(savedInstanceState);\n            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);\n            setContentView(new AxisMapView(getContext()));\n        }\n    }\n\n    private static class ScreenMirrorPresentation extends Presentation {''',
'axis map presentation')

# Add the display view immediately before HeadTrackView.
replace_once(
'''    private class HeadTrackView extends View {''',
'''    private class AxisMapView extends View {\n        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);\n        AxisMapView(Context context) { super(context); setKeepScreenOn(true); }\n\n        @Override protected void onDraw(Canvas c) {\n            int w = getWidth(), h = getHeight();\n            float mid = w / 2f;\n            c.drawColor(Color.BLACK);\n            drawPromptEye(c, 0, mid, h, 90f);\n            drawPromptEye(c, mid, w, h, -90f);\n            if (axisMappingActive) postInvalidateDelayed(40);\n        }\n\n        private void drawPromptEye(Canvas c, float left, float right, float height, float rotation) {\n            float cx = (left + right) / 2f;\n            float cy = height / 2f;\n            c.save();\n            c.clipRect(left, 0, right, height);\n            c.rotate(rotation, cx, cy);\n            paint.setTextAlign(Paint.Align.CENTER);\n            paint.setStyle(Paint.Style.FILL);\n            paint.setColor(axisMappingActive ? Color.WHITE : Color.rgb(70, 220, 120));\n            paint.setTextSize(28);\n            paint.setFakeBoldText(true);\n            c.drawText(axisMappingPrompt, cx, cy - 10, paint);\n            paint.setFakeBoldText(false);\n            paint.setTextSize(18);\n            if (axisMappingActive) {\n                long elapsed = Math.max(0, System.currentTimeMillis() - axisMappingStartMs);\n                c.drawText(String.format(Locale.US, "%.1f / 13.0 sec", elapsed / 1000.0), cx, cy + 35, paint);\n                c.drawText("Move only as instructed", cx, cy + 65, paint);\n            } else {\n                c.drawText("Send me this mapping result", cx, cy + 35, paint);\n            }\n            c.restore();\n        }\n    }\n\n    private class HeadTrackView extends View {''',
'axis map view')

path.write_text(s, encoding='utf-8')
print('V0.7 IMU axis-mapping patch applied successfully')
