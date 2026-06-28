#include "graphic_buffer_hooks.h"

#include "frame_inject.h"

#include <android/log.h>
#include <shadowhook.h>

#define VC_TAG "libvc"
#define VC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VC_TAG, __VA_ARGS__)
#define VC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VC_TAG, __VA_ARGS__)

namespace icecam {

namespace {

using LockFn = int (*)(void*, uint32_t, void**, int*, int*);
using UnlockFn = int (*)(void*);
using LockYCbCrFn = int (*)(void*, uint32_t, void*);

LockFn g_origLock = nullptr;
UnlockFn g_origUnlock = nullptr;
LockYCbCrFn g_origLockYCbCr = nullptr;

int hookGraphicBufferLock(void* thiz, uint32_t usage, void** vaddr, int* stride, int* height) {
    const int rc = g_origLock ? g_origLock(thiz, usage, vaddr, stride, height) : -1;
    if (rc == 0 && vaddr != nullptr && *vaddr != nullptr && stride != nullptr && height != nullptr) {
        injectIntoLockedBuffer(*vaddr, static_cast<uint32_t>(*stride), static_cast<uint32_t>(*height), stride);
    }
    return rc;
}

int hookGraphicBufferUnlock(void* thiz) {
    return g_origUnlock ? g_origUnlock(thiz) : -1;
}

int hookGraphicBufferLockYCbCr(void* thiz, uint32_t usage, void* ycbcr) {
    return g_origLockYCbCr ? g_origLockYCbCr(thiz, usage, ycbcr) : -1;
}

bool hookSym(const char* sym, void* proxy, void** orig) {
    void* stub = shadowhook_hook_sym_name(
            "libui.so",
            sym,
            proxy,
            orig);
    if (stub == nullptr) {
        VC_LOGE("hook failed sym=%s err=%d (%s)", sym, shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));
        return false;
    }
    VC_LOGI("hooked %s", sym);
    return true;
}

}  // namespace

bool installGraphicBufferHooks() {
    bool ok = true;
    ok = hookSym("_ZN7android13GraphicBuffer4lockEjPPvPiS3_", reinterpret_cast<void*>(hookGraphicBufferLock),
                 reinterpret_cast<void**>(&g_origLock)) && ok;
    ok = hookSym("_ZN7android13GraphicBuffer6unlockEv", reinterpret_cast<void*>(hookGraphicBufferUnlock),
                 reinterpret_cast<void**>(&g_origUnlock)) && ok;
    ok = hookSym("_ZN7android13GraphicBuffer9lockYCbCrEjP13android_ycbcr",
                 reinterpret_cast<void*>(hookGraphicBufferLockYCbCr),
                 reinterpret_cast<void**>(&g_origLockYCbCr)) && ok;
    return ok;
}

}  // namespace icecam
