# Icecamv2

Full clone of the IceCam virtual camera stack, rebuilt from the `kil0jq` reference archive with **native sources** and **ShadowHook 2.x** (Prefab).

**Build:** `0.27-icecamv2-shadowhook2` (versionCode 27)

## What changed vs the reference archive

| Area | Reference archive | Icecamv2 |
|---|---|---|
| ShadowHook | Prebuilt `libshadowhook.so` 1.0.10 in `jniLibs` | `com.bytedance.android:shadowhook:2.0.1` via Prefab |
| libvc.so | Prebuilt binary | Built from `app/src/main/cpp/libvc/` |
| vcplax | Prebuilt binary | Built from `app/src/main/cpp/vcplax/` (libbinder_ndk) |
| TX13 poll | Missing in Java client | `VliveBinderClient.pollState()` + `PipelinePoller` |
| Binder handlers | Documented stubs only | Full TX11–25 handlers in native code |

## Project layout

```text
app/
  build.gradle                  # prefab + externalNativeBuild + shadowhook dep
  src/main/cpp/
    CMakeLists.txt
    libvc/                      # GraphicBuffer hooks (shadowhook_hook_sym_name)
    vcplax/                     # Binder daemon (AServiceManager_addService)
  src/main/java/dev/icecam/app/
    VliveBinderClient.java      # Binder IPC incl. pollState TX13
    PipelinePoller.java         # 1 Hz TX13 heartbeat
    RootBootstrap.java          # su deploy to /data/camera, /data/libvc.so
```

## Build

Requirements: JDK 17, Android SDK, NDK (via AGP).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Native outputs packaged into the APK:

- `libvc.so` — hook injector (links `shadowhook::shadowhook`)
- `libshadowhook.so` — from Maven Prefab (replaces old jniLibs copy)
- `vcplax.so` — root daemon executable (renamed ELF, deployed as `/data/vcplax`)

## Backend model

```text
MainActivity / FloatService
  → su bootstrap (RootBootstrap)
  → deploy libvc.so, libshadowhook.so → /data/libvc++.so, vcplax
  → Binder: com.xiaomi.vlive.IMyBinderService
  → TX 11/12/13/14/15/16/17/18/19/22/24/25
```

Legacy apply path: `TX14(mode=1, path) → TX11(path, 0, loop)`.

## RE references

See `docs/` for Binder protocol analysis (`BINDER_TX_RUNTIME_ANALYSIS.md`, `CLONE_ROADMAP.md`, `NATIVE_FINAL_DECOMPILE.md`).
