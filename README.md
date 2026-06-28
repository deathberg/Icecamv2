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

1. Tap **START** — foreground service begins NDJSON capture to `/sdcard/icecam_debug.log`.
2. Green dot = recording; red = stopped or logcat stalled.
3. **STOP** — halts capture (saves battery).
4. **EXPORT** — prepends system metadata (OS, memory, IceCam processes) + Share Intent; also writes `/sdcard/icecam_export.log`.
5. **CLEAR** — wipes log file and UI for a fresh report.

## Log format (AI-friendly NDJSON)

Each captured line is written as one JSON object:

```json
{"type":"log","ts":"06-28 12:00:00.123","level":"ERROR","cat":"JNI","pid":1234,"tid":5678,"tag":"IceCamNative","msg":"..."}
```

Categories: `JNI`, `BINDER`, `ERROR`, `INFO`. UI shows compact prefixes: `[ERROR][JNI] IceCamNative: ...`

## Export metadata

EXPORT prepends:
- `Build.DISPLAY`, device info
- `ActivityManager.MemoryInfo` (avail/total/low)
- Running processes matching icecam/libvc/float/tx
- JSON metadata line for machine parsing

## Log file

```
/sdcard/icecam_debug.log
```

Pull from device (path shown in app status bar):

```bash
adb pull /sdcard/icecam_debug.log .
# or if scoped storage blocked public path:
adb pull /sdcard/Android/data/com.icecam.logspy/files/icecam_debug.log .
```

## Architecture

| Component | Role |
|-----------|------|
| `MainActivity.LogcatReader` | `ProcessBuilder("logcat", "-v", "threadtime")` — fastest raw stream |
| `LogCaptureService` | Foreground service, file persistence, auto-restart on logcat death |
| Broadcast | Service → UI without binding (survives Activity pause) |

## Branch

Development branch: `logger`
