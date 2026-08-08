from pathlib import Path

path = Path('app/src/main/java/com/getfunds/lgr100connector/MainActivity.java')
s = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'V0.8 patch failed: could not find {label}')
    s = s.replace(old, new, 1)

# Version labels after V0.5/V0.6/V0.7 patches have run.
s = s.replace('LG 360 VR Connector V0.7', 'LG 360 VR Connector V0.8')
s = s.replace('=== V0.7 WAKE SEQUENCE START ===', '=== V0.8 WAKE SEQUENCE START ===')
s = s.replace('=== V0.7 WAKE SEQUENCE COMPLETE ===', '=== V0.8 WAKE SEQUENCE COMPLETE ===')
s = s.replace('Adds guided IMU axis mapping so yaw/pitch/roll are measured from this LGR100 instead of assumed',
              'Uses the original OpenHMD LGR100 float IMU packet format and verified GZ/GX/GY axis mapping')

# The LGR100 reports IEEE754 float32 values, not signed 16-bit sensor counts.
replace_once(
'''    private volatile int lastGx, lastGy, lastGz, lastAx, lastAy, lastAz;''',
'''    private volatile float lastGx, lastGy, lastGz, lastAx, lastAy, lastAz;''',
'float sensor fields')

s = s.replace('private volatile double accelCountsPerG = 16384.0;',
              'private volatile double accelCountsPerG = 9.80665;')

# Add filtered angular-rate state and use the mapping measured on this physical headset.
replace_once(
'''    private long lastOrientationNs = 0;\n\n    private volatile boolean axisMappingActive = false;''',
'''    private long lastOrientationNs = 0;\n    private volatile double filteredGx = 0.0;\n    private volatile double filteredGy = 0.0;\n    private volatile double filteredGz = 0.0;\n\n    private volatile boolean axisMappingActive = false;''',
'filtered gyro fields')

replace_once(
'''    private volatile String axisMappingResult = "NOT MAPPED";\n    private volatile int mappedYawAxis = -1;\n    private volatile int mappedPitchAxis = -1;\n    private volatile int mappedRollAxis = -1;''',
'''    private volatile String axisMappingResult = "VERIFIED: YAW=GZ  PITCH=GX  ROLL=GY";\n    private volatile int mappedYawAxis = 2;\n    private volatile int mappedPitchAxis = 0;\n    private volatile int mappedRollAxis = 1;''',
'verified axis defaults')

# Return the button to a real head-track test now that the user's mapping is known.
replace_once(
'''        Button headTest = button("Map IMU axes");\n        headTest.setOnClickListener(v -> beginAxisMapping());''',
'''        Button headTest = button("Head-track test");\n        headTest.setOnClickListener(v -> showHeadTrackDisplay());''',
'head-track button restore')

# Decode the real packet layout from the original OpenHMD LG-R100 driver:
# report byte + 3 float gyro + 3 float accel. Apply its coordinate transforms immediately.
old_parser = '''                if (reportId == 0x05 && n >= 13) {\n                    imuPackets++;\n                    int gx = le16(buffer, 1), gy = le16(buffer, 3), gz = le16(buffer, 5);\n                    int ax = le16(buffer, 7), ay = le16(buffer, 9), az = le16(buffer, 11);\n                    lastGx = gx; lastGy = gy; lastGz = gz;\n                    lastAx = ax; lastAy = ay; lastAz = az;\n\n                    accumulateCalibration(gx, gy, gz, ax, ay, az, now);\n                    accumulateAxisMapping(gx, gy, gz, now);\n                    updateOrientation(gx, gy, gz, ax, ay, az);'''
new_parser = '''                if (reportId == 0x05 && n >= 25) {\n                    float rawGx = leFloat(buffer, 1);\n                    float rawGy = leFloat(buffer, 5);\n                    float rawGz = leFloat(buffer, 9);\n                    float rawAx = leFloat(buffer, 13);\n                    float rawAy = leFloat(buffer, 17);\n                    float rawAz = leFloat(buffer, 21);\n\n                    if (!Float.isFinite(rawGx) || !Float.isFinite(rawGy) || !Float.isFinite(rawGz) ||\n                            !Float.isFinite(rawAx) || !Float.isFinite(rawAy) || !Float.isFinite(rawAz)) {\n                        continue;\n                    }\n\n                    // Original OpenHMD LG-R100 transform. Gyro values are angular velocity (rad/s).\n                    float gx = rawGx * 4.0f;\n                    float gy = rawGy * 4.0f;\n                    float gz = -rawGz * 4.0f;\n                    float ax = rawAx;\n                    float ay = rawAy;\n                    float az = -rawAz;\n\n                    imuPackets++;\n                    lastGx = gx; lastGy = gy; lastGz = gz;\n                    lastAx = ax; lastAy = ay; lastAz = az;\n\n                    accumulateCalibration(gx, gy, gz, ax, ay, az, now);\n                    accumulateAxisMapping(gx, gy, gz, now);\n                    updateOrientation(gx, gy, gz, ax, ay, az);'''
replace_once(old_parser, new_parser, 'float32 IMU parser')

# Float sensor values throughout calibration/mapping/orientation.
s = s.replace('private void accumulateCalibration(int gx, int gy, int gz, int ax, int ay, int az, long nowMs)',
              'private void accumulateCalibration(float gx, float gy, float gz, float ax, float ay, float az, long nowMs)')
s = s.replace('private void accumulateAxisMapping(int gx, int gy, int gz, long nowMs)',
              'private void accumulateAxisMapping(float gx, float gy, float gz, long nowMs)')
s = s.replace('private void updateOrientation(int gx, int gy, int gz, int ax, int ay, int az)',
              'private void updateOrientation(float gx, float gy, float gz, float ax, float ay, float az)')

# Physical accelerometer values are already around 9.81 m/s^2 at rest.
replace_once(
'''            accelCountsPerG = Math.sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ);\n            if (accelCountsPerG < 500) accelCountsPerG = 16384.0;''',
'''            accelCountsPerG = Math.sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ);\n            if (accelCountsPerG < 1.0 || accelCountsPerG > 30.0) accelCountsPerG = 9.80665;''',
'physical gravity scale')

# Meaningful physical-unit calibration quality thresholds.
replace_once(
'''            if (gyroNoiseRms < 2500 && accelNoiseRms < 2500) quality = "HIGH";\n            else if (gyroNoiseRms < 10000 && accelNoiseRms < 10000) quality = "MEDIUM";\n            else quality = "NOISY";''',
'''            if (gyroNoiseRms < 0.02 && accelNoiseRms < 0.15) quality = "HIGH";\n            else if (gyroNoiseRms < 0.08 && accelNoiseRms < 0.60) quality = "MEDIUM";\n            else quality = "NOISY";''',
'physical quality thresholds')

# Replace the generic MPU6050 integer-count integration with the LGR100's real float/rad/s stream.
start = s.index('    private void updateOrientation(float gx, float gy, float gz, float ax, float ay, float az) {')
end = s.index('\n    private void resetOrientation()', start)
new_orientation = '''    private void updateOrientation(float gx, float gy, float gz, float ax, float ay, float az) {\n        if (!calibrated || calibrationActive) {\n            lastOrientationNs = System.nanoTime();\n            filteredGx = filteredGy = filteredGz = 0.0;\n            return;\n        }\n\n        long nowNs = System.nanoTime();\n        if (lastOrientationNs == 0) { lastOrientationNs = nowNs; return; }\n        double dt = (nowNs - lastOrientationNs) / 1_000_000_000.0;\n        lastOrientationNs = nowNs;\n        if (dt <= 0 || dt > 0.1) return;\n\n        double wx = gx - gyroBiasX;\n        double wy = gy - gyroBiasY;\n        double wz = gz - gyroBiasZ;\n\n        // Adaptive dead-zone learned from the stationary baseline. Values are rad/s.\n        double deadzone = Math.max(0.008, Math.min(0.08, gyroNoiseRms * 3.0));\n        if (Math.abs(wx) < deadzone) wx = 0.0;\n        if (Math.abs(wy) < deadzone) wy = 0.0;\n        if (Math.abs(wz) < deadzone) wz = 0.0;\n\n        // Mild low-pass filtering removes single-sample jitter without adding much latency.\n        final double alpha = 0.28;\n        filteredGx += alpha * (wx - filteredGx);\n        filteredGy += alpha * (wy - filteredGy);\n        filteredGz += alpha * (wz - filteredGz);\n\n        synchronized (orientationLock) {\n            // Mapping verified on the user's LGR100: yaw=GZ, pitch=GX, roll=GY.\n            yawDeg += Math.toDegrees(filteredGz * dt);\n            pitchDeg += Math.toDegrees(filteredGx * dt);\n            rollDeg += Math.toDegrees(filteredGy * dt);\n\n            // Diagnostic view is deliberately bounded: no +/-180 wrap and no edge teleporting.\n            yawDeg = clamp(yawDeg, -55.0, 55.0);\n            pitchDeg = clamp(pitchDeg, -40.0, 40.0);\n            rollDeg = clamp(rollDeg, -45.0, 45.0);\n        }\n    }\n'''
s = s[:start] + new_orientation + s[end:]

# Reset filter state as well as angles.
replace_once(
'''            rollDeg = 0;\n            lastOrientationNs = 0;''',
'''            rollDeg = 0;\n            filteredGx = filteredGy = filteredGz = 0.0;\n            lastOrientationNs = 0;''',
'reset filtered gyro')

# Sensor status should display floats in physical units rather than integer counts.
replace_once(
'''        int gx = lastGx, gy = lastGy, gz = lastGz;\n        int ax = lastAx, ay = lastAy, az = lastAz;''',
'''        float gx = lastGx, gy = lastGy, gz = lastGz;\n        float ax = lastAx, ay = lastAy, az = lastAz;''',
'float sensor UI locals')

replace_once(
'''        double gxDps = bgx / 131.0, gyDps = bgy / 131.0, gzDps = bgz / 131.0;\n        double scale = calibrated ? accelCountsPerG : 16384.0;''',
'''        double gxDps = Math.toDegrees(bgx), gyDps = Math.toDegrees(bgy), gzDps = Math.toDegrees(bgz);\n        double scale = calibrated ? accelCountsPerG : 9.80665;''',
'physical display conversion')

s = s.replace('Gyro bias: X=%7.1f Y=%7.1f Z=%7.1f | raw noise=%.1f',
              'Gyro bias(rad/s): X=%8.5f Y=%8.5f Z=%8.5f | noise=%.5f')
s = s.replace('Gravity baseline: X=%7.1f Y=%7.1f Z=%7.1f | raw noise=%.1f',
              'Gravity(m/s2): X=%7.3f Y=%7.3f Z=%7.3f | noise=%.3f')
s = s.replace('GYRO bias-corrected approx: X=%7.1f Y=%7.1f Z=%7.1f deg/s',
              'GYRO corrected: X=%7.2f Y=%7.2f Z=%7.2f deg/s')

# Add little-endian float decoding beside the old int helper.
replace_once(
'''    private int le16(byte[] data, int offset) {\n        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();\n    }''',
'''    private int le16(byte[] data, int offset) {\n        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();\n    }\n\n    private float leFloat(byte[] data, int offset) {\n        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();\n    }''',
'float decoder')

path.write_text(s, encoding='utf-8')
print('V0.8 OpenHMD float IMU parser and stable tracking patch applied successfully')
