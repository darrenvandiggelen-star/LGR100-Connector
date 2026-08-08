from pathlib import Path

path = Path('app/src/main/java/com/getfunds/lgr100connector/MainActivity.java')
s = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'V1.2 patch failed: could not find {label}')
    s = s.replace(old, new, 1)

# Version labels after V1.1 has run.
s = s.replace('LG 360 VR Connector V1.1', 'LG 360 VR Connector V1.2')
s = s.replace('=== V1.1 WAKE SEQUENCE START ===', '=== V1.2 WAKE SEQUENCE START ===')
s = s.replace('=== V1.1 WAKE SEQUENCE COMPLETE ===', '=== V1.2 WAKE SEQUENCE COMPLETE ===')
s = s.replace(
    'GPU VR gaming screen: zero-copy MediaProjection texture, 960px capture, fusion-first defaults and live FPS',
    'Persistent VR gaming: dedicated HDMI activity, frame-driven GPU rendering and fixed landscape capture')

# Secondary-display Activity launch support.
s = s.replace('import android.app.Activity;\n', 'import android.app.Activity;\nimport android.app.ActivityOptions;\n')

# If capture is already running, reopen/bring up the dedicated external-display renderer instead
# of recreating the old Presentation renderer tied to the phone Activity.
replace_once(
'''        if (ScreenCaptureService.isCaptureActive()) {\n            showScreenMirrorDisplay();\n            refreshStatus();\n            return;\n        }''',
'''        if (ScreenCaptureService.isCaptureActive()) {\n            showVrDisplayActivity();\n            refreshStatus();\n            return;\n        }''',
'active capture renderer launch')

# Capture at the phone's LANDSCAPE aspect from the start. COD is landscape, and keeping one
# fixed producer geometry avoids SurfaceTexture/VirtualDisplay resize races while the game opens.
replace_once(
'''        int width = dm.widthPixels, height = dm.heightPixels;\n        float scale = Math.min(1.0f, 960.0f / Math.max(width, height));\n        int captureW = Math.max(2, Math.round(width * scale));\n        int captureH = Math.max(2, Math.round(height * scale));''',
'''        int rawW = dm.widthPixels, rawH = dm.heightPixels;\n        int gameW = Math.max(rawW, rawH);\n        int gameH = Math.min(rawW, rawH);\n        float scale = Math.min(1.0f, 960.0f / Math.max(gameW, gameH));\n        int captureW = Math.max(2, Math.round(gameW * scale));\n        int captureH = Math.max(2, Math.round(gameH * scale));''',
'fixed landscape capture geometry')

# Replace the Presentation launch after MediaProjection with a true Activity on the external display.
replace_once(
'''        handler.postDelayed(() -> { showScreenMirrorDisplay(); refreshStatus(); }, 700);''',
'''        handler.postDelayed(() -> { showVrDisplayActivity(); refreshStatus(); }, 450);''',
'VR activity launch after capture')

# Insert dedicated display launch before stopScreenShare().
replace_once(
'''    private void stopScreenShare() {''',
'''    private void showVrDisplayActivity() {\n        Display d = findExternalDisplay();\n        if (d == null) {\n            append("VR renderer: external LGR100 display not found");\n            return;\n        }\n\n        VrDisplayActivity.updateTuning(vrScreenScale, vrStereoDepth, vrOffsetX, vrOffsetY, vrWorldLocked);\n        VrDisplayActivity.updateTracking(yawDeg, pitchDeg, rollDeg, calibrated);\n\n        try {\n            Intent vr = new Intent(this, VrDisplayActivity.class);\n            vr.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);\n            ActivityOptions options = ActivityOptions.makeBasic();\n            options.setLaunchDisplayId(d.getDisplayId());\n            startActivity(vr, options.toBundle());\n            append("Persistent VR renderer launched on display " + d.getDisplayId() + " (" + d.getName() + ")");\n        } catch (Throwable t) {\n            append("Secondary-display Activity launch failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());\n            append("Falling back to Presentation renderer");\n            showScreenMirrorDisplay();\n        }\n    }\n\n    private void stopScreenShare() {''',
'dedicated VR display activity launcher')

# Finish the external renderer task when screen sharing is stopped.
replace_once(
'''    private void stopScreenShare() {\n        try { stopService(new Intent(this, ScreenCaptureService.class)); } catch (Exception ignored) {}''',
'''    private void stopScreenShare() {\n        VrDisplayActivity.finishActive();\n        try { stopService(new Intent(this, ScreenCaptureService.class)); } catch (Exception ignored) {}''',
'stop dedicated VR renderer')

# Keep external renderer tuning synchronized with the phone control panel and read FPS from it.
replace_once(
'''    private void updateVrScreenStatus() {\n        if (vrScreenStatus == null) return;\n        String text = String.format(Locale.US,''',
'''    private void updateVrScreenStatus() {\n        VrDisplayActivity.updateTuning(vrScreenScale, vrStereoDepth, vrOffsetX, vrOffsetY, vrWorldLocked);\n        vrCaptureFps = VrDisplayActivity.getCaptureFps();\n        vrRenderFps = VrDisplayActivity.getRenderFps();\n        if (vrScreenStatus == null) return;\n        String text = String.format(Locale.US,''',
'sync VR tuning and FPS')

# Feed the confirmed LGR100 orientation into the secondary-display renderer.
replace_once(
'''            rollDeg = clamp(rollDeg, -45.0, 45.0);\n        }\n    }\n\n    private void resetOrientation()''',
'''            rollDeg = clamp(rollDeg, -45.0, 45.0);\n        }\n        VrDisplayActivity.updateTracking(yawDeg, pitchDeg, rollDeg, calibrated);\n    }\n\n    private void resetOrientation()''',
'live tracking bridge')

# Recenter should immediately update the secondary-display renderer too.
replace_once(
'''        append("Relative orientation reset to 0 / 0 / 0");''',
'''        VrDisplayActivity.updateTracking(yawDeg, pitchDeg, rollDeg, calibrated);\n        VrDisplayActivity.requestRenderActive();\n        append("Relative orientation reset to 0 / 0 / 0");''',
'recenter tracking bridge')

path.write_text(s, encoding='utf-8')
print('V1.2 persistent secondary-display gaming patch applied successfully')
