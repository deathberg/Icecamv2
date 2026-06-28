# IceCam-Log-Spy

Minimal Android logcat monitor — captures tags containing **IceCam**, **libvc**, **float**, or **TX**, writes to `/sdcard/icecam_debug.log`, shows live stream in a ScrollView.

## Install & permissions

```bash
# Build (requires Android SDK + JDK 17)
./gradlew assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk

# READ_LOGS is signature|development — grant manually:
adb shell pm grant com.icecam.logspy android.permission.READ_LOGS

# Optional: notification permission on Android 13+
adb shell pm grant com.icecam.logspy android.permission.POST_NOTIFICATIONS
```

## Usage

1. Launch **IceCam-Log-Spy** — foreground service starts immediately and keeps running in background.
2. Live logs appear in the ScrollView (UI keeps last ~800 lines for speed).
3. **Dump** — copies full log file to clipboard (paste into Composer).
4. **Share** — sends log text via any share target.
5. **Clear UI** — clears on-screen buffer only; file is untouched.

## Log file

```
/sdcard/icecam_debug.log
```

Pull from device:

```bash
adb pull /sdcard/icecam_debug.log .
```

## Architecture

| Component | Role |
|-----------|------|
| `MainActivity.LogcatReader` | `ProcessBuilder("logcat", "-v", "threadtime")` — fastest raw stream |
| `LogCaptureService` | Foreground service, file persistence, auto-restart on logcat death |
| Broadcast | Service → UI without binding (survives Activity pause) |

## Branch

Development branch: `logger`
