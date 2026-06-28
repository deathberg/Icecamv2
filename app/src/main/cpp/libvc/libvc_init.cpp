#include "graphic_buffer_hooks.h"
#include "xor_decode.h"

#include <android/log.h>
#include <shadowhook.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <pthread.h>
#include <string>
#include <vector>

#define LOG_TAG "libvc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace icecam {

namespace {

std::atomic<bool> g_initialized{false};

// XOR blobs from libvc .rodata (arm64 file offsets). Keys come from runtime hook-state.
static const uint8_t kBlobLibName[] = {
        0x5a, 0x4b, 0x3c, 0x2d, 0x1e, 0x0f, 0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x87, 0x98, 0xa9,
        0xba, 0xcb, 0xdc,
};
static const uint8_t kBlobSymLock[] = {
        '_', 'Z', 'N', '7', 'a', 'n', 'd', 'r', 'o', 'i', 'd', '1', '3', 'G', 'r', 'a', 'p', 'h', 'i', 'c',
        'B', 'u', 'f', 'f', 'e', 'r', '4', 'l', 'o', 'c', 'k', 'E', 'j', 'P', 'P', 'v', 'P', 'i', 'S', '3',
        '_', 'E', 'v', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
};

void* hookWorker(void*) {
    LOGI("hook worker started");
    installGraphicBufferHooks();
    return nullptr;
}

bool initShadowHook() {
    const int rc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (rc != 0) {
        LOGE("shadowhook_init failed errno=%d (%s)", rc, shadowhook_to_errmsg(rc));
        return false;
    }
    LOGI("shadowhook_init ok mode=UNIQUE version=%s", shadowhook_get_version());
    return true;
}

void decodeAndLogTargets(uint8_t keyLib, uint8_t keySym) {
    const std::string lib = decodeXorBlob(kBlobLibName, sizeof(kBlobLibName), keyLib);
    const std::string sym = decodeXorBlob(kBlobSymLock, sizeof(kBlobSymLock), keySym);
    LOGI("XOR decode lib=\"%s\" sym=\"%s\"", lib.c_str(), sym.c_str());
}

}  // namespace

extern "C" {

// Original libvc exports init(ctx) — vcplax dlopen + dlsym("init").
__attribute__((visibility("default"))) int init(void* ctx) {
    if (g_initialized.exchange(true)) return 0;

    uint8_t keyLib = 0x00;
    uint8_t keySym = 0x00;
    if (ctx != nullptr) {
        const auto* bytes = static_cast<const uint8_t*>(ctx);
        keyLib = bytes[8];
        keySym = bytes[0x7d];
    }

    decodeAndLogTargets(keyLib, keySym);

    if (!initShadowHook()) return -1;
    if (!installGraphicBufferHooks()) {
        LOGE("primary hooks failed; starting worker retry");
        pthread_t tid;
        pthread_create(&tid, nullptr, hookWorker, nullptr);
        pthread_detach(tid);
    }
    return 0;
}

}  // extern "C"

}  // namespace icecam
