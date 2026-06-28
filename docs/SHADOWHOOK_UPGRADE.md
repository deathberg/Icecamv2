# ShadowHook 1.0.x → 2.x — исследование для Icecamv2

## Вердикт

| Вопрос | Ответ |
|--------|-------|
| Заменить только `libvc++.so` на 2.x? | **Нет** — reference `libvc.so` сломается |
| Апгрейд возможен? | **Да**, через пересборку `libvc_clone` на ShadowHook 2.0.1+ |
| Что сейчас | Reference ShadowHook **1.0.10** (~74 KB) из testicecam2.apk |

## Почему drop-in не работает

Reference `libvc.so` вызывает:
- `shadowhook_init(SHADOWHOOK_MODE_UNIQUE, 0)` — глобальный режим 1.0
- `shadowhook_hook_sym_name` × 5 на `libcameraservice.so`

ShadowHook 2.x:
- Per-hook flags через `shadowhook_hook_sym_name_2`
- Новый linker init (errno 12 на части ROM)
- Требует `libshadowhook_nothing.so` рядом (API 35+)

Maven `shadowhook:2.0.0` (~91 KB) **не совместим** с reference libvc — проверено на устройстве.

## План апгрейда (средний срок)

1. Портировать `clone/native/libvc_clone.cpp` из Oldicecam `final-re-e3a1`
2. Hooks на `Camera3OutputStream::returnBufferCheckedLocked` (не libui)
3. `implementation 'com.bytedance.android:shadowhook:2.0.1'`
4. Deploy: `libshadowhook.so` + `libshadowhook_nothing.so` → `/data/libvc++.so` path
5. E2E на Xiaomi Android 13

## Рекомендация

**Сейчас:** оставить 1.0.10 — подмена уже работает.

**Параллельно:** ветка `libvc_clone` + ShadowHook 2.0.1 для open-source стека.
