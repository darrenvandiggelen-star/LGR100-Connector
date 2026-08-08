from pathlib import Path

path = Path('app/src/main/java/com/getfunds/lgr100connector/MainActivity.java')
s = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'V0.9 patch failed: could not find {label}')
    s = s.replace(old, new, 1)

# Version labels after V0.8 has run.
s = s.replace('LG 360 VR Connector V0.8', 'LG 360 VR Connector V0.9')
s = s.replace('=== V0.8 WAKE SEQUENCE START ===', '=== V0.9 WAKE SEQUENCE START ===')
s = s.replace('=== V0.8 WAKE SEQUENCE COMPLETE ===', '=== V0.9 WAKE SEQUENCE COMPLETE ===')
s = s.replace(
    'Uses the original OpenHMD LGR100 float IMU packet format and verified GZ/GX/GY axis mapping',
    'Uses OpenHMD float IMU format with corrected physical axes: yaw=GY, pitch=GX, roll=GZ')

# The V0.7 mapping was collected while packets were still being mis-decoded as int16.
# With the real OpenHMD float parser, the coordinate system is Y-up:
# pitch rotates around X, yaw around Y, roll around Z.
replace_once(
'''    private volatile String axisMappingResult = "VERIFIED: YAW=GZ  PITCH=GX  ROLL=GY";\n    private volatile int mappedYawAxis = 2;\n    private volatile int mappedPitchAxis = 0;\n    private volatile int mappedRollAxis = 1;''',
'''    private volatile String axisMappingResult = "OPENHMD: YAW=GY  PITCH=GX  ROLL=GZ";\n    private volatile int mappedYawAxis = 1;\n    private volatile int mappedPitchAxis = 0;\n    private volatile int mappedRollAxis = 2;''',
'physical axis defaults')

replace_once(
'''            // Mapping verified on the user's LGR100: yaw=GZ, pitch=GX, roll=GY.\n            yawDeg += Math.toDegrees(filteredGz * dt);\n            pitchDeg += Math.toDegrees(filteredGx * dt);\n            rollDeg += Math.toDegrees(filteredGy * dt);''',
'''            // OpenHMD LGR100 coordinates are Y-up: yaw=GY, pitch=GX, roll=GZ.\n            // V0.7's GZ/GX/GY result came from the old incorrect int16 decoder and is discarded.\n            yawDeg += Math.toDegrees(filteredGy * dt);\n            pitchDeg += Math.toDegrees(filteredGx * dt);\n            rollDeg += Math.toDegrees(filteredGz * dt);''',
'orientation axis integration')

# Add an axis note where possible, but don't fail the build if the UI formatting changed.
s = s.replace(
    'Orientation: YAW=%7.2f  PITCH=%7.2f  ROLL=%7.2f deg\\n',
    'Orientation: YAW=%7.2f  PITCH=%7.2f  ROLL=%7.2f deg\\nTracking axes: YAW=GY  PITCH=GX  ROLL=GZ\\n')

path.write_text(s, encoding='utf-8')
print('V0.9 OpenHMD physical-axis patch applied successfully')
