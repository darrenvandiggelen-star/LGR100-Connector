from pathlib import Path

path = Path('app/src/main/java/com/getfunds/lgr100connector/MainActivity.java')
s = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'V0.6 patch failed: could not find {label}')
    s = s.replace(old, new, 1)

# Version labels after V0.5 patch has already run.
s = s.replace('LG 360 VR Connector V0.5', 'LG 360 VR Connector V0.6')
s = s.replace('=== V0.5 WAKE SEQUENCE START ===', '=== V0.6 WAKE SEQUENCE START ===')
s = s.replace('=== V0.5 WAKE SEQUENCE COMPLETE ===', '=== V0.6 WAKE SEQUENCE COMPLETE ===')
s = s.replace('Improved packet-based calibration, stable head-track target, fitted phone screen sharing',
              'Calibration noise is reported separately; valid baselines are no longer rejected as movement')

# The LGR100 raw IMU stream is noisier than the generic threshold used in V0.4/V0.5.
# Calibration should establish the bias/gravity baseline whenever enough real packets were
# collected. Noise is useful diagnostic information, but it should not be interpreted as
# proof that the user moved the headset or block head tracking.
replace_once(
'''            calibrated = true;\n            boolean steady = gyroNoiseRms < 2500 && accelNoiseRms < 2500;\n            calibrationResult = steady ? "PASS - stationary baseline captured" : "CAUTION - baseline captured but headset moved during test";''',
'''            calibrated = true;\n            String quality;\n            if (gyroNoiseRms < 2500 && accelNoiseRms < 2500) quality = "HIGH";\n            else if (gyroNoiseRms < 10000 && accelNoiseRms < 10000) quality = "MEDIUM";\n            else quality = "NOISY";\n            calibrationResult = "PASS - baseline captured | sensor quality " + quality;''',
'calibration movement warning')

# Clarify the diagnostic label so a noisy reading is not presented as user movement.
s = s.replace('Gyro bias: X=%7.1f Y=%7.1f Z=%7.1f | noise=%.1f',
              'Gyro bias: X=%7.1f Y=%7.1f Z=%7.1f | raw noise=%.1f')
s = s.replace('Gravity baseline: X=%7.1f Y=%7.1f Z=%7.1f | accel noise=%.1f',
              'Gravity baseline: X=%7.1f Y=%7.1f Z=%7.1f | raw noise=%.1f')

path.write_text(s, encoding='utf-8')
print('V0.6 calibration-quality patch applied successfully')
