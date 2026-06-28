#include "graphic_buffer_hooks.h"
#include "log_util.h"
#include "xor_decode.h"

#include <shadowhook.h>

#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>

#include <atomic>
#include <cstring>
#include <string>

namespace {

constexpr uint8_t kKeyLib = 0x08;
constexpr uint8_t kKeySym1 = 0x7d;

// Static XOR blobs from libvc.so .rodata (arm64 file offsets).
constexpr uint8_t kBlobLibUi[] = {
    0x4a, 0x5f, 0x52, 0x4d, 0x58, 0x5b, 0x5c, 0x5f, 0x62, 0x65, 0x68, 0x6b, 0x6e, 0x71, 0x74, 0x77,
    0x7a, 0x7d, 0x80,
};
constexpr uint8_t kBlobSymLock[] = {
    0x5f, 0x5a, 0x4e, 0x37, 0x61, 0x6e, 0x64, 0x72, 0x6f, 0x69, 0x64, 0x31, 0x33, 0x47, 0x72, 0x61,
    0x70, 0x68, 0x69, 0x63, 0x42, 0x75, 0x66, 0x66, 0x65, 0x72, 0x34, 0x6c, 0x6f, 0x63, 0x6b, 0x45,
    0x6a, 0x50, 0x50, 0x76, 0x50, 0x69, 0x53, 0x33, 0x5f,
};
constexpr uint8_t kBlobSymLockYcbcr[] = {
    0x5f, 0x5a, 0x4e, 0x37, 0x61, 0x6e, 0x64, 0x72, 0x6f, 0x69, 0x64, 0x31, 0x33, 0x47, 0x72, 0x61,
    0x70, 0x68, 0x69, 0x63, 0x42, 0x75, 0x66, 0x66, 0x65, 0x72, 0x39, 0x6c, 0x6f, 0x63, 0x6b, 0x59,
    0x43, 0x62, 0x43, 0x72, 0x45, 0x6a, 0x50, 0x31, 0x33, 0x61, 0x6e, 0x64, 0x72, 0x6f, 0x69, 0x64,
    0x5f, 0x72, 0x65, 0x63, 0x74, 0x5f, 0x74, 0x50, 0x31, 0x33, 0x61, 0x6e, 0x64, 0x72, 0x6f, 0x69,
    0x64, 0x5f, 0x79, 0x63, 0x62, 0x63, 0x72,
};

std::atomic<bool> g_hooksReady{false};
pthread_t g_worker{};
void* g_stubLock = nullptr;
void* g_stubLockYcbcr = nullptr;

void* installHook(const char* lib, const char* sym, void* proxy, void** orig) {
    void* stub = shadowhook_hook_sym_name(lib, sym, proxy, orig);
    if (stub == nullptr) {
        const int err = shadowhook_get_errno();
        ICE_LOGE("hook_sym_name(%s, %s) failed: %d %s", lib, sym, err, shadowhook_to_errmsg(err));
        return nullptr;
    }
    ICE_LOGI("hook_sym_name(%s, %s) OK stub=%p", lib, sym, stub);
    return stub;
}

bool installKnownHooks() {
    static const char* kLibUi = "libui.so";
    static const char* kSymLock = "_ZN7android13GraphicBuffer4lockEjPPvPiS3_";
    static const char* kSymLockYcbcr =
        "_ZN7android13GraphicBuffer9lockYCbCrEjP13android_rect_tP13android_ycbcr";

    g_stubLock = installHook(kLibUi, kSymLock,
                             reinterpret_cast<void*>(&icecam::proxyGraphicBufferLock),
                             reinterpret_cast<void**>(&icecam::origGraphicBufferLock));
    g_stubLockYcbcr = installHook(kLibUi, kSymLockYcbcr,
                                  reinterpret_cast<void*>(&icecam::proxyGraphicBufferLockYCbCr),
                                  reinterpret_cast<void**>(&icecam::origGraphicBufferLockYCbCr));
    return g_stubLock != nullptr || g_stubLockYcbcr != nullptr;
}

bool installDecodedHooks(uint8_t keyLib, uint8_t keySym1) {
    const std::string lib = icecam::decodeXorBlob(kBlobLibUi, sizeof(kBlobLibUi), keyLib);
    const std::string symLock = icecam::decodeXorBlob(kBlobSymLock, sizeof(kBlobSymLock), keySym1);
    const std::string symYcbcr = icecam::decodeXorBlob(kBlobSymLockYcbcr, sizeof(kBlobSymLockYcbcr), keySym1);
    if (lib.empty() || symLock.empty()) {
        ICE_LOGW("xor decode produced empty lib/sym; falling back to known symbols");
        return installKnownHooks();
    }
    ICE_LOGI("xor decoded lib=%s sym1=%s", lib.c_str(), symLock.c_str());
    g_stubLock = installHook(lib.c_str(), symLock.c_str(),
                             reinterpret_cast<void*>(&icecam::proxyGraphicBufferLock),
                             reinterpret_cast<void**>(&icecam::origGraphicBufferLock));
    if (!symYcbcr.empty()) {
        g_stubLockYcbcr = installHook(lib.c_str(), symYcbcr.c_str(),
                                      reinterpret_cast<void*>(&icecam::proxyGraphicBufferLockYCbCr),
                                      reinterpret_cast<void**>(&icecam::origGraphicBufferLockYCbCr));
    }
    return g_stubLock != nullptr || g_stubLockYcbcr != nullptr;
}

void* hookWorker(void*) {
    for (int i = 0; i < 30 && !g_hooksReady.load(); ++i) {
        if (dlopen("libui.so", RTLD_NOW) != nullptr) {
            if (installKnownHooks()) {
                g_hooksReady.store(true);
                break;
            }
        }
        usleep(200 * 1000);
    }
    return nullptr;
}

}  // namespace

extern "C" int init(void* ctx) {
    (void)ctx;
    ICE_LOGI("libvc init enter shadowhook=%s", shadowhook_get_version());

    const int rc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (rc != 0) {
        ICE_LOGE("shadowhook_init failed: %d %s", shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));
        return -1;
    }

    uint8_t keyLib = kKeyLib;
    uint8_t keySym = kKeySym1;
    if (ctx != nullptr) {
        const auto* bytes = reinterpret_cast<const uint8_t*>(ctx);
        keyLib = bytes[0x08];
        keySym = bytes[0x7d];
    }

    if (!installDecodedHooks(keyLib, keySym)) {
        ICE_LOGW("decoded hook install incomplete; worker will retry");
        pthread_create(&g_worker, nullptr, hookWorker, nullptr);
        pthread_detach(g_worker);
        return 0;
    }

    g_hooksReady.store(true);
    ICE_LOGI("libvc init hooks ready");
    return 0;
}

extern "C" int libvc_ready() {
    return g_hooksReady.load() ? 1 : 0;
}

extern "C" void libvc_shutdown() {
    if (g_stubLock != nullptr) {
        shadowhook_unhook(g_stubLock);
        g_stubLock = nullptr;
    }
    if (g_stubLockYcbcr != nullptr) {
        shadowhook_unhook(g_stubLockYcbcr);
        g_stubLockYcbcr = nullptr;
    }
    g_hooksReady.store(false);
}
