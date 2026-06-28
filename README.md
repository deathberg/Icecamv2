# Icecamv2

Full clone of the IceCam virtual camera stack, rebuilt from the reference archive with **ShadowHook 2.x (prefab)** and native sources for `libvc.so` + `vcplax`.

**Build:** `0.27-icecamv2-shadowhook2` (versionCode 27)

## Layout

```text
app/
  src/main/java/dev/icecam/app/     # Java control plane (Binder client, UI, runtime v25)
  src/main/cpp/
    libvc/                          # GraphicBuffer hooks via shadowhook_hook_sym_name (2.x)
    vcplax/                         # Binder daemon (TX11–25) + media pipeline counters
    common/                         # MediaContext, XOR decode helpers
  src/main/assets/re/               # Frida scripts for on-device RE

docs/                             # Reverse-engineering reports from reference archive
tools/                            # Ghidra / Frida / Termux tooling
```

## Native stack

| Artifact | Source | Notes |
|---|---|---|
| `libshadowhook.so` | Maven prefab `com.bytedance.android:shadowhook:2.0.1` | Replaces bundled 1.0.10 |
| `libvc.so` | `app/src/main/cpp/libvc` | `init()` installs hooks on `GraphicBuffer::lock` |
| `vcplax.so` | `app/src/main/cpp/vcplax` | NDK Binder service, full TX11–25 handlers |

`RootBootstrap` still deploys `libshadowhook.so` as `/data/libvc++.so` for compatibility with the original loader path.

## Build

Requirements: JDK 17, Android SDK, NDK (via AGP 8.9).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Binder protocol

Java client: `VliveBinderClient` — legacy apply `TX14 → TX11`, transform `TX24`, heartbeat `TX13` via `BinderStatePoller` (1 Hz).

Native daemon: `vcplax` registers `privsam_service` and implements all UI-facing transaction codes documented in `docs/BINDER_TX_RUNTIME_ANALYSIS.md`.

## RE references

- [docs/APK_FULL_REVERSE_ENGINEERING.md](docs/APK_FULL_REVERSE_ENGINEERING.md)
- [docs/BINDER_TX_RUNTIME_ANALYSIS.md](docs/BINDER_TX_RUNTIME_ANALYSIS.md)
- [docs/CLONE_ROADMAP.md](docs/CLONE_ROADMAP.md)
