# Icecamv2

Полноценный клон IceCam на базе эталонной кодовой базы из рефакторинга (`kil0jq.zip`).

## Что изменилось относительно архива

| Компонент | Было | Icecamv2 |
|---|---|---|
| ShadowHook | 1.0.10 prebuilt `libshadowhook.so` в jniLibs | **2.0.1** через Maven Prefab |
| libvc.so | prebuilt binary | **сборка из C++** (`app/src/main/cpp/libvc/`) |
| vcplax | prebuilt binary | **сборка из C++** (`app/src/main/cpp/vcplax/`) |
| TX13 pollState | отсутствовал в Java client | **восстановлен** в `VliveBinderClient` |

## Архитектура

```text
Java UI (dev.icecam.app)
  └─ VliveBinderClient TX11–25 + pollState TX13
        └─ Binder IPC
              └─ vcplax (native daemon)
                    ├─ onTransact handlers (libbinder_ndk)
                    ├─ MediaPipeline (AMediaExtractor + decode thread)
                    └─ dlopen libvc++.so + libvc.so
                          └─ ShadowHook 2.x hooks on libui GraphicBuffer
```

## Сборка

```bash
./gradlew assembleDebug
```

APK содержит `libvc.so`, `libshadowhook.so` (из prefab) и `vcplax.so` (daemon executable).

## Деплой на устройство (root)

1. Установить APK
2. Запустить приложение → START / RESTORE
3. `RootBootstrap` копирует native libs в `/data/` и запускает `vcplax privsam_service`

## Документация RE

- [`docs/CLONE_ROADMAP.md`](docs/CLONE_ROADMAP.md) — протокол Binder и план клонирования
- [`docs/BINDER_TX_RUNTIME_ANALYSIS.md`](docs/BINDER_TX_RUNTIME_ANALYSIS.md) — семантика TX11–TX25
- [`docs/NATIVE_FINAL_DECOMPILE.md`](docs/NATIVE_FINAL_DECOMPILE.md) — Ghidra декомпиляция vcplax/libvc
