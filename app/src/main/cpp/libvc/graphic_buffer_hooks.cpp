#include "graphic_buffer_hooks.h"
#include "inject_state.h"
#include "xor_decode.h"

#include <android/log.h>
#include <shadowhook.h>

#include <atomic>
#include <cstring>

#define LOG_TAG "libvc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace icecam {

namespace {

using LockFn = int (*)(void* thiz, uint32_t usage, void** vaddr, int* stride, int* height);
using UnlockFn = int (*)(void* thiz);
using LockYcbcrFn = int (*)(void* thiz, uint32_t usage, void* ycbcr);
using FromFn = void* (*)(void* buffer);

LockFn orig_lock = nullptr;
UnlockFn orig_unlock = nullptr;
LockYcbcrFn orig_lock_ycbcr = nullptr;
FromFn orig_from = nullptr;

std::atomic<bool> g_hooks_ready{false};

void apply_color_block(uint8_t* y, int width, int height, int y_stride, uint32_t color) {
    if (!y || width <= 0 || height <= 0 || y_stride <= 0) return;
    const uint8_t fill = static_cast<uint8_t>((color >> 16) & 0xff);
    const int block_w = width / 8;
    const int block_h = height / 8;
    if (block_w <= 0 || block_h <= 0) return;
    for (int row = 0; row < block_h; ++row) {
        uint8_t* row_ptr = y + row * y_stride;
        for (int col = 0; col < block_w; ++col) row_ptr[col] = fill;
    }
}

int proxy_lock(void* thiz, uint32_t usage, void** vaddr, int* stride, int* height) {
    const int rc = orig_lock ? orig_lock(thiz, usage, vaddr, stride, height) : -1;
    if (rc == 0 && vaddr && *vaddr) {
        const auto& s = inject_state();
        if (s.mode != 0 && stride && height) {
            apply_color_block(static_cast<uint8_t*>(*vaddr), *stride, *height, *stride, s.color);
        }
    }
    return rc;
}

int proxy_unlock(void* thiz) { return orig_unlock ? orig_unlock(thiz) : -1; }

int proxy_lock_ycbcr(void* thiz, uint32_t usage, void* ycbcr) {
    return orig_lock_ycbcr ? orig_lock_ycbcr(thiz, usage, ycbcr) : -1;
}

void* proxy_from(void* buffer) { return orig_from ? orig_from(buffer) : nullptr; }

bool install_one(const char* lib, const char* sym, void* proxy, void** orig) {
    void* stub = shadowhook_hook_sym_name(lib, sym, proxy, orig);
    if (stub == nullptr) {
        const int err = shadowhook_get_errno();
        LOGE("hook failed %s:%s err=%d %s", lib, sym, err, shadowhook_to_errmsg(err));
        return false;
    }
    LOGI("hooked %s:%s orig=%p", lib, sym, orig ? *orig : nullptr);
    return true;
}

}  // namespace

bool install_graphic_buffer_hooks() {
    if (g_hooks_ready.load()) return true;

    size_t count = 0;
    const HookTarget* targets = default_hook_targets(&count);
    bool ok = true;

    if (count > 0) {
        ok &= install_one(targets[0].lib, targets[0].sym, reinterpret_cast<void*>(proxy_lock),
                          reinterpret_cast<void**>(&orig_lock));
    }
    if (count > 1) {
        ok &= install_one(targets[1].lib, targets[1].sym, reinterpret_cast<void*>(proxy_lock_ycbcr),
                          reinterpret_cast<void**>(&orig_lock_ycbcr));
    }
    if (count > 2) {
        ok &= install_one(targets[2].lib, targets[2].sym, reinterpret_cast<void*>(proxy_unlock),
                          reinterpret_cast<void**>(&orig_unlock));
    }
    if (count > 3) {
        ok &= install_one(targets[3].lib, targets[3].sym, reinterpret_cast<void*>(proxy_from),
                          reinterpret_cast<void**>(&orig_from));
    }

    g_hooks_ready.store(ok);
    return ok;
}

}  // namespace icecam
