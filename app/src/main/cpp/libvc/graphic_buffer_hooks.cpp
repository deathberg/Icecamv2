#include "graphic_buffer_hooks.h"

#include <android/log.h>
#include <shadowhook.h>

#include <atomic>
#include <mutex>

#define LOG_TAG "libvc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace icecam {

namespace {

std::atomic<bool> g_hooksInstalled{false};
std::mutex g_hookMutex;

struct android_ycbcr {
    void* y;
    void* cb;
    void* cr;
    size_t ystride;
    size_t cstride;
    size_t chroma_step;
};

using LockFn = int (*)(void*, uint32_t, void**, int*, int*);
using LockYFn = int (*)(void*, uint32_t, android_ycbcr*, int*);

LockFn g_origLock = nullptr;
LockYFn g_origLockY = nullptr;

struct HookTarget {
    const char* lib;
    const char* sym;
    void* replacement;
    void** orig;
};

int hookLock(void* gb, uint32_t usage, void** vaddr, int* stride, int* bytesPerPixel) {
    const int rc = g_origLock ? g_origLock(gb, usage, vaddr, stride, bytesPerPixel) : -1;
    if (rc == 0 && vaddr && *vaddr) {
        LOGI("GraphicBuffer::lock ok usage=0x%x stride=%d", usage, stride ? *stride : -1);
    }
    return rc;
}

int hookLockY(void* gb, uint32_t usage, android_ycbcr* ycbcr, int* bytesPerPixel) {
    const int rc = g_origLockY ? g_origLockY(gb, usage, ycbcr, bytesPerPixel) : -1;
    if (rc == 0 && ycbcr && ycbcr->y) {
        LOGI("GraphicBuffer::lockYCbCr ok usage=0x%x ystride=%zu", usage, ycbcr->ystride);
    }
    return rc;
}

bool installOne(const HookTarget& target) {
    void* stub = shadowhook_hook_sym_name(target.lib, target.sym, target.replacement, target.orig);
    if (stub == nullptr) {
        LOGE("shadowhook_hook_sym_name failed lib=%s sym=%s errno=%d (%s)", target.lib, target.sym,
             shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));
        return false;
    }
    LOGI("hook installed lib=%s sym=%s stub=%p", target.lib, target.sym, stub);
    return true;
}

}  // namespace

bool installGraphicBufferHooks() {
    if (g_hooksInstalled.load()) return true;
    std::lock_guard<std::mutex> lock(g_hookMutex);
    if (g_hooksInstalled.load()) return true;

    const HookTarget targets[] = {
            {"libui.so",
             "_ZN7android13GraphicBuffer4lockEjPPvPiS3_",
             reinterpret_cast<void*>(hookLock),
             reinterpret_cast<void**>(&g_origLock)},
            {"libui.so",
             "_ZN7android13GraphicBuffer9lockYCbCrEjP13android_ycbcrPi",
             reinterpret_cast<void*>(hookLockY),
             reinterpret_cast<void**>(&g_origLockY)},
    };

    int installed = 0;
    for (const auto& t : targets) {
        if (installOne(t)) ++installed;
    }

    g_hooksInstalled.store(installed > 0);
    LOGI("GraphicBuffer hooks installed=%d", installed);
    return g_hooksInstalled.load();
}

}  // namespace icecam
