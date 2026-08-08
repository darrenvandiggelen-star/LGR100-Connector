from pathlib import Path

path = Path('app/src/main/java/com/getfunds/lgr100connector/MainActivity.java')
s = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'V0.5 patch failed: could not find {label}')
    s = s.replace(old, new, 1)

# Version labels.
s = s.replace('LG 360 VR Connector V0.4', 'LG 360 VR Connector V0.5')
s = s.replace('=== V0.4 WAKE SEQUENCE START ===', '=== V0.5 WAKE SEQUENCE START ===')
s = s.replace('=== V0.4 WAKE SEQUENCE COMPLETE ===', '=== V0.5 WAKE SEQUENCE COMPLETE ===')
s = s.replace('Adds stationary calibration, relative yaw/pitch/roll, proximity state, head-track test and phone screen sharing',
              'Improved packet-based calibration, stable head-track target, fitted phone screen sharing')
s = s.replace('Calibrate 3 sec', 'Calibrate sensors')
s = s.replace('then tap Calibrate 3 sec.', 'then tap Calibrate sensors.')

replace_once(
'''    private volatile boolean calibrationActive = false;\n    private volatile boolean calibrated = false;\n    private volatile long calibrationStartMs = 0;\n    private long calibrationCount = 0;''',
'''    private static final int CALIBRATION_TARGET_SAMPLES = 120;\n    private static final int CALIBRATION_MIN_SAMPLES = 25;\n    private static final long CALIBRATION_MIN_MS = 2000;\n    private static final long CALIBRATION_MAX_MS = 12000;\n    private volatile boolean calibrationActive = false;\n    private volatile boolean calibrated = false;\n    private volatile long calibrationStartMs = 0;\n    private volatile long calibrationFirstSampleMs = 0;\n    private long calibrationCount = 0;''',
'calibration fields')

replace_once(
'''    private void beginCalibration() {\n        if (!sensorStreaming) {\n            append("Starting sensors before calibration");\n            startSensorDiagnostics();\n            handler.postDelayed(this::beginCalibration, 800);\n            return;\n        }\n        synchronized (calibrationLock) {\n            calibrationActive = true;\n            calibrated = false;\n            calibrationResult = "CALIBRATING - KEEP HEADSET COMPLETELY STILL";\n            calibrationStartMs = System.currentTimeMillis();\n            calibrationCount = 0;\n            sumGx = sumGy = sumGz = sumAx = sumAy = sumAz = 0;\n            sumGx2 = sumGy2 = sumGz2 = sumAx2 = sumAy2 = sumAz2 = 0;\n        }\n        resetOrientation();\n        append("Calibration started: keep headset still for 3 seconds");\n        updateSensorUi(0);\n    }''',
'''    private void beginCalibration() {\n        if (!sensorStreaming) {\n            append("Starting sensors before calibration");\n            startSensorDiagnostics();\n            handler.postDelayed(this::beginCalibration, 1200);\n            return;\n        }\n        synchronized (calibrationLock) {\n            calibrationActive = true;\n            calibrated = false;\n            calibrationResult = "CALIBRATING - KEEP HEADSET COMPLETELY STILL";\n            calibrationStartMs = System.currentTimeMillis();\n            calibrationFirstSampleMs = 0;\n            calibrationCount = 0;\n            sumGx = sumGy = sumGz = sumAx = sumAy = sumAz = 0;\n            sumGx2 = sumGy2 = sumGz2 = sumAx2 = sumAy2 = sumAz2 = 0;\n        }\n        resetOrientation();\n        append("Calibration started: collecting real IMU packets (target " + CALIBRATION_TARGET_SAMPLES + ", max 12s). Keep headset still.");\n        updateSensorUi(0);\n    }''',
'beginCalibration')

replace_once(
'''    private void accumulateCalibration(int gx, int gy, int gz, int ax, int ay, int az, long nowMs) {\n        if (!calibrationActive) return;\n        boolean finish = false;\n        synchronized (calibrationLock) {\n            if (!calibrationActive) return;\n            calibrationCount++;\n            sumGx += gx; sumGy += gy; sumGz += gz;\n            sumAx += ax; sumAy += ay; sumAz += az;\n            sumGx2 += (double) gx * gx; sumGy2 += (double) gy * gy; sumGz2 += (double) gz * gz;\n            sumAx2 += (double) ax * ax; sumAy2 += (double) ay * ay; sumAz2 += (double) az * az;\n            if (nowMs - calibrationStartMs >= 3000) {\n                finish = true;\n                calibrationActive = false;\n            }\n        }\n        if (finish) finishCalibration();\n    }''',
'''    private void accumulateCalibration(int gx, int gy, int gz, int ax, int ay, int az, long nowMs) {\n        if (!calibrationActive) return;\n        boolean finish = false;\n        synchronized (calibrationLock) {\n            if (!calibrationActive) return;\n            if (calibrationFirstSampleMs == 0) calibrationFirstSampleMs = nowMs;\n            calibrationCount++;\n            sumGx += gx; sumGy += gy; sumGz += gz;\n            sumAx += ax; sumAy += ay; sumAz += az;\n            sumGx2 += (double) gx * gx; sumGy2 += (double) gy * gy; sumGz2 += (double) gz * gz;\n            sumAx2 += (double) ax * ax; sumAy2 += (double) ay * ay; sumAz2 += (double) az * az;\n\n            long elapsed = nowMs - calibrationStartMs;\n            long sampleElapsed = calibrationFirstSampleMs == 0 ? 0 : nowMs - calibrationFirstSampleMs;\n            boolean enoughSamples = calibrationCount >= CALIBRATION_TARGET_SAMPLES;\n            boolean enoughTime = sampleElapsed >= CALIBRATION_MIN_MS;\n            boolean timedOut = elapsed >= CALIBRATION_MAX_MS;\n            if ((enoughSamples && enoughTime) || timedOut) {\n                finish = true;\n                calibrationActive = false;\n            }\n        }\n        if (finish) finishCalibration();\n    }''',
'accumulateCalibration')

replace_once(
'''            if (calibrationCount < 50) {\n                calibrated = false;\n                calibrationResult = "FAIL - not enough sensor samples";\n                append("Calibration failed: only " + calibrationCount + " samples");\n                return;\n            }''',
'''            if (calibrationCount < CALIBRATION_MIN_SAMPLES) {\n                calibrated = false;\n                calibrationResult = "FAIL - sensor stream too sparse (" + calibrationCount + " samples)";\n                append("Calibration failed: only " + calibrationCount + " samples in " + (System.currentTimeMillis() - calibrationStartMs) + " ms");\n                return;\n            }''',
'calibration minimum')

replace_once(
'''        if (calibrationActive) {\n            long left = Math.max(0, 3000 - (System.currentTimeMillis() - calibrationStartMs));\n            cal = "CALIBRATING - KEEP STILL (" + String.format(Locale.US, "%.1f", left / 1000.0) + "s)";\n        } else cal = calibrationResult;''',
'''        if (calibrationActive) {\n            long elapsed = Math.max(0, System.currentTimeMillis() - calibrationStartMs);\n            cal = "CALIBRATING - KEEP STILL | samples " + calibrationCount + "/" + CALIBRATION_TARGET_SAMPLES +\n                    " | " + String.format(Locale.US, "%.1f", elapsed / 1000.0) + "s";\n        } else cal = calibrationResult;''',
'calibration UI')

# Do not rotate the target using roll yet. The roll estimate is still being validated and
# made the diagnostic crosshair appear skewed even when yaw/pitch were useful.
replace_once(
'''            c.rotate((float) -rollDeg, cx, cy);\n\n            paint.setStrokeWidth(4f);''',
'''            // V0.5: keep the diagnostic target level. Roll is still shown numerically,\n            // but is not applied to the crosshair until the axis mapping is validated.\n\n            paint.setStrokeWidth(4f);''',
'head-track roll transform')

# Give the mirror a larger black safety margin. ScreenCaptureService does aspect-fit inside this box.
replace_once(
'''            float boxW = height * 0.96f;\n            float boxH = eyeW * 0.96f;''',
'''            float boxW = height * 0.78f;\n            float boxH = eyeW * 0.78f;''',
'mirror safe area')

path.write_text(s, encoding='utf-8')
print('V0.5 MainActivity patch applied successfully')
