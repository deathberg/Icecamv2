#include "graphic_buffer_hooks.h"

#include "common/xor_decode.h"

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <shadowhook.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>

#define VC_TAG "libvc"
#define VC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VC_TAG, __VA_ARGS__)
#define VC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VC_TAG, __VA_ARGS__)

namespace {

// XOR blobs from libvc.so .rodata (arm64 file VA offsets in RE docs).
static const uint8_t kLibBlob[] = {
        0x4a, 0x62, 0x5f, 0x54, 0x49, 0x5c, 0x4e, 0x5a, 0x4b, 0x5d, 0x4c, 0x5e, 0x4d, 0x5f, 0x4e, 0x60, 0x4f, 0x61, 0x50};
static const uint8_t kSymLockBlob[] = {
        0x5f, 0x5a, 0x4e, 0x37, 0x61, 0x6e, 0x64, 0x72, 0x6f, 0x69, 0x64, 0x31, 0x33, 0x47, 0x72, 0x61, 0x70, 0x68, 0x69, 0x63,
        0x42, 0x75, 0x66, 0x66, 0x65, 0x72, 0x34, 0x6c, 0x6f, 0x63, 0x6b, 0x45, 0x6a, 0x50, 0x50, 0x76, 0x50, 0x69, 0x53, 0x33,
        0x5f, 0x45};

struct HookInitCtx {
    uint8_t keyLib = 0;
    uint8_t keySym = 0;
};

HookInitCtx g_ctx{};

void* hookWorker(void*) {
    VC_LOGI("hook worker started");
    return nullptr;
}

extern "C" __attribute__((visibility("default"))) int init(HookInitCtx* ctx) {
    if (ctx != nullptr) {
        g_ctx = *ctx;
    }

    const int shRc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (shRc != 0) {
        VC_LOGE("shadowhook_init failed rc=%d err=%s", shRc, shadowhook_to_errmsg(shRc));
        return -1;
    }
    VC_LOGI("shadowhook_init ok (2.x)");

    const std::string lib = icecam::decodeXor(kLibBlob, sizeof(kLibBlob), g_ctx.keyLib);
    const std::string symLock = icecam::decodeXor(kSymLockBlob, sizeof(kSymLockBlob), g_ctx.keySym);
    VC_LOGI("decoded lib=%s sym=%s", lib.c_str(), symLock.c_str());

    if (!icecam::installGraphicBufferHooks()) {
        VC_LOGE("GraphicBuffer hook install incomplete");
        return -2;
    }

    pthread_t tid;
    pthread_create(&tid, nullptr, hookWorker, nullptr);
    pthread_detach(tid);
    return 0;
}

}  // namespace
