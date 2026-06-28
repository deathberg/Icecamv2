# Icecamv2

Full clone of the IceCam virtual camera stack, rebuilt from the `kil0jq` reference archive with **ShadowHook 2.x (prefab)** and native sources for `libvc` + `vcplax`.

**Build:** `2.0.0-icecamv2` (versionCode 27)

## What changed vs reference archive

| Area | Reference (v26) | Icecamv2 |
|---|---|---|
| ShadowHook | Prebuilt `libshadowhook.so` 1.0.x in `jniLibs/` | Maven `com.bytedance.android:shadowhook:2.0.0` via prefab |
| `libvc.so` | Prebuilt binary | Built from `app/src/main/cpp/libvc/` (ShadowHook 2.x) |
| `vcplax` daemon | Prebuilt 12 MB binary | Sources in `app/src/main/cpp/vcplax/` (Binder + NDK MediaCodec); **runtime binary** still from reference APK until AOSP sysroot build |
| TX13 poll | Missing in Java client | `VliveBinderClient.pollState()` + `BinderPollScheduler` (1 Hz) |

## Project layout

```text
app/
  build.gradle                  # prefab + externalNativeBuild
  src/main/cpp/
    CMakeLists.txt
    libvc/                      # ShadowHook 2.x GraphicBuffer hooks
    vcplax/                     # BBinder service (TX 11–25)
  src/main/java/dev/icecam/app/ # Java UI + Binder client (from archive)
docs/                           # RE reports from archive
```

## Build

Requirements: JDK 17, Android SDK, NDK 27.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Native artifacts packaged into the APK:

- `libvc.so` — inject/hook library (`init` + `shadowhook_hook_sym_name`)
- `vcplax.so` — PIE executable deployed as `/data/vcplax` (Binder daemon)
- `libshadowhook.so` — from ShadowHook 2.x AAR (also copied to `/data/libvc++.so` at runtime)

## Runtime flow

```text
IceCamApp → BinderPollScheduler (TX13 @ 1 Hz)
MainActivity / FloatService → RootBootstrap (su deploy)
  → libvc.so + libshadowhook.so + vcplax
  → Binder: com.xiaomi.vlive.IMyBinderService
  → TX 11/12/13/14/15/16/17/18/19/22/24/25
```

## Native notes

- **libvc** uses `shadowhook_init(SHADOWHOOK_MODE_UNIQUE)` and hooks `GraphicBuffer::lock/unlock/lockYCbCr` in `libui.so`.
- **vcplax** implements the recovered Binder protocol; media path uses `AMediaExtractor` + `AMediaCodec` (local MP4). RTMP mode sets `pollCounters[4]` when active.
- XOR string decode from original `libvc` is preserved for logging; hook targets use confirmed mangled symbol names.

## RE references

See `docs/CLONE_ROADMAP.md`, `docs/BINDER_TX_RUNTIME_ANALYSIS.md`, and `docs/NATIVE_FINAL_DECOMPILE.md`.
