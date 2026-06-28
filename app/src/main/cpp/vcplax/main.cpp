#include "binder_service.h"

#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#define VP_TAG "vcplax"
#define VP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VP_TAG, __VA_ARGS__)
#define VP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VP_TAG, __VA_ARGS__)

namespace {

using InitFn = int (*)(void*);

bool loadLibvcNear(const char* exePath) {
    std::string base(exePath);
    const auto slash = base.find_last_of('/');
    if (slash != std::string::npos) base.resize(slash + 1);

    const std::vector<std::string> candidates = {
            base + "libvc.so",
            "/data/libvc.so",
            "/data/camera/libvc.so",
    };

    void* handle = nullptr;
    for (const auto& path : candidates) {
        handle = dlopen(path.c_str(), RTLD_NOW);
        if (handle != nullptr) {
            VP_LOGI("dlopen libvc %s", path.c_str());
            break;
        }
    }
    if (handle == nullptr) {
        VP_LOGE("dlopen libvc failed: %s", dlerror());
        return false;
    }

    auto init = reinterpret_cast<InitFn>(dlsym(handle, "init"));
    if (init == nullptr) {
        VP_LOGE("dlsym init failed: %s", dlerror());
        return false;
    }
    const int rc = init(nullptr);
    VP_LOGI("libvc init rc=%d", rc);
    return rc == 0;
}

bool preloadShadowhook(const char* exePath) {
    std::string base(exePath);
    const auto slash = base.find_last_of('/');
    if (slash != std::string::npos) base.resize(slash + 1);

    const std::vector<std::string> candidates = {
            base + "libshadowhook.so",
            "/data/libvc++.so",
            "/data/camera/libshadowhook.so",
    };
    for (const auto& path : candidates) {
        if (dlopen(path.c_str(), RTLD_NOW) != nullptr) {
            VP_LOGI("preloaded shadowhook from %s", path.c_str());
            return true;
        }
    }
    VP_LOGE("shadowhook preload failed: %s", dlerror());
    return false;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <service_name>\n", argv[0]);
        return 1;
    }

    char exePath[256] = {};
    const ssize_t n = readlink("/proc/self/exe", exePath, sizeof(exePath) - 1);
    if (n > 0) exePath[n] = '\0';

    preloadShadowhook(exePath);
    loadLibvcNear(exePath);

    ABinderProcess_setThreadPoolMaxThreadCount(4);
    AIBinder* binder = icecam::VliveBinderService::createBinder(argv[1]);
    const binder_status_t reg = AServiceManager_addService(binder, argv[1]);
    if (reg != STATUS_OK) {
        VP_LOGE("AServiceManager_addService(%s) failed rc=%d", argv[1], reg);
        return 2;
    }

    if (n > 0) {
        unlink(exePath);
    }

    VP_LOGI("registered service=%s", argv[1]);
    ABinderProcess_joinThreadPool();
    return 0;
}
