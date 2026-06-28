#include "graphic_buffer_hooks.h"

#include <android/log.h>
#include <shadowhook.h>

#include <pthread.h>
#include <unistd.h>

#define LOG_TAG "libvc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

void* hook_worker(void*) {
    // Retry hooks until libui.so is mapped (camera pipeline startup).
    for (int i = 0; i < 60; ++i) {
        if (icecam::install_graphic_buffer_hooks()) {
            LOGI("GraphicBuffer hooks ready after %d attempts", i + 1);
            return nullptr;
        }
        usleep(500 * 1000);
    }
    LOGE("GraphicBuffer hooks not installed after retries");
    return nullptr;
}

}  // namespace

// Exported init symbol — vcplax dlopens libvc.so and calls init().
extern "C" __attribute__((visibility("default"))) int init(void* ctx) {
    (void)ctx;
    LOGI("libvc init enter (ShadowHook %s)", shadowhook_get_version());

    const int rc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (rc != 0) {
        LOGE("shadowhook_init failed: %d %s", rc, shadowhook_to_errmsg(rc));
        return -1;
    }

    if (!icecam::install_graphic_buffer_hooks()) {
        pthread_t tid;
        pthread_create(&tid, nullptr, hook_worker, nullptr);
        pthread_detach(tid);
    }

    LOGI("libvc init done");
    return 0;
}
