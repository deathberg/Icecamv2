# Icecamv2 ↔ Oldicecam RE — сводка для всех агентов

**Источник RE:** https://github.com/deathberg/Oldicecam  
**Ключевые ветки:** `cursor/final-re-e3a1`, `main`, `cursor/recon-unified-e3a1`

## Вердикт RE (99% готово)

Подмена камеры в оригинале IceCam:

```
App → Binder privsam_service → vcplax (decode MP4)
                              → libvc.so (ShadowHook 1.0.x)
                              → hooks libcameraservice.so::Camera3OutputStream::returnBufferCheckedLocked
                              → NV12 inject в preview buffers
```

**НЕ** `libui.so` / `GraphicBuffer::lock` — исправлено в `docs/RE_FINAL_REPORT.md`.

## Критичные native-файлы (reference из testicecam2.apk)

| Файл | arm64 размер | Deploy |
|------|-------------|--------|
| `libvc.so` | ~1.29 MB | `/data/libvc.so`, `/data/camera/libvc.so` |
| `libshadowhook.so` (1.0.x) | ~74 KB | `/data/libvc++.so` |
| `vcplax.so` | ~12 MB | `/data/vcplax` |

**Нельзя** использовать ShadowHook 2.x из Maven (~91 KB) — ломает init libvc.

## Binder apply sequence (runtime-verified)

1. Bootstrap: deploy libs → kill cameraserver → launch vcplax
2. TX14 (mode + path) → TX11 (play)
3. TX17 loop, TX19 mirror, TX18 angle, TX16 auto-rotate
4. TX13 poll 1 Hz (heartbeat)
5. TX24 — color/三色, **не** geometry для MP4

## Диагностика на устройстве

```bash
pidof vcplax; service check privsam_service
wc -c /data/libvc.so /data/libvc++.so /data/vcplax
grep -i libvc /proc/$(pidof cameraserver)/maps   # если пусто — stock ROM без loader
tail -40 /data/camera/vcplax.log
```

`LIBVC_NOT_MAPPED_IN_CAMERASERVER` = подмена не видна без патченного ядра / Zygisk.

## Версии Icecamv2

| Версия | Фикс |
|--------|------|
| 2.0.6 | serviceAlive `not found` bug |
| 2.0.7 | restore + cameraserver clean, TX24 guard |
| 2.0.8 | reference ShadowHook 1.0.x, TX16–19 apply, size gates |
| 2.0.9 | TX24=color (INT/DIA), ROT→TX18, preview video thumbs, TX22 reset |
