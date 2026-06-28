#include "binder_service.h"

#include <android/binder_ibinder.h>
#include <android/binder_status.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <cstdio>
#include <string>

#define LOG_TAG "vcplax"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using InitFn = int (*)(void*);
using AddServiceFn = binder_status_t (*)(AIBinder*, const char*);
using StartPoolFn = void (*)();
using JoinPoolFn = void (*)();

struct PlatformBinder {
    void* handle = nullptr;
    AddServiceFn addService = nullptr;
    StartPoolFn startPool = nullptr;
    JoinPoolFn joinPool = nullptr;

    bool load() {
        handle = dlopen("libbinder_ndk.so", RTLD_NOW);
        if (!handle) {
            LOGE("dlopen libbinder_ndk.so failed: %s", dlerror());
            return false;
        }
        addService = reinterpret_cast<AddServiceFn>(dlsym(handle, "AServiceManager_addService"));
        startPool = reinterpret_cast<StartPoolFn>(dlsym(handle, "ABinderProcess_startThreadPool"));
        joinPool = reinterpret_cast<JoinPoolFn>(dlsym(handle, "ABinderProcess_joinThreadPool"));
        if (!addService || !startPool || !joinPool) {
            LOGE("binder_ndk symbols missing add=%p start=%p join=%p", addService, startPool, joinPool);
            return false;
        }
        return true;
    }

    ~PlatformBinder() {
        if (handle) dlclose(handle);
    }
};

std::string dirnameOf(const char* path) {
    if (!path) return "/data";
    std::string p(path);
    const auto pos = p.find_last_of('/');
    if (pos == std::string::npos) return ".";
    return p.substr(0, pos);
}

bool loadLibvcPair(const std::string& baseDir) {
    const std::string shadowPath = baseDir + "/libvc++.so";
    const std::string libvcPath = baseDir + "/libvc.so";

    void* sh = dlopen(shadowPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!sh) {
        LOGE("dlopen %s failed: %s", shadowPath.c_str(), dlerror());
        return false;
    }
    LOGI("dlopen ok %s", shadowPath.c_str());

    void* vc = dlopen(libvcPath.c_str(), RTLD_NOW);
    if (!vc) {
        LOGE("dlopen %s failed: %s", libvcPath.c_str(), dlerror());
        return false;
    }
    LOGI("dlopen ok %s", libvcPath.c_str());

    auto init = reinterpret_cast<InitFn>(dlsym(vc, "init"));
    if (!init) {
        LOGE("dlsym init failed: %s", dlerror());
        return false;
    }

    uint8_t hookState[0x100] = {};
    hookState[8] = 0x00;
    hookState[0x7d] = 0x00;
    const int rc = init(hookState);
    LOGI("libvc init rc=%d", rc);
    return rc == 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <service_name>\n", argv[0]);
        return 1;
    }

    const char* serviceName = argv[1];
    LOGI("vcplax start service=%s", serviceName);

    PlatformBinder platform;
    if (!platform.load()) return 4;

    char exePath[512] = {};
    const ssize_t n = readlink("/proc/self/exe", exePath, sizeof(exePath) - 1);
    if (n > 0) exePath[n] = '\0';
    const std::string baseDir = dirnameOf(exePath);

    if (loadLibvcPair(baseDir)) {
        icecam::markLibvcReady();
    } else {
        LOGE("libvc init failed; TX11 returns 0 until hooks ready");
    }

    if (n > 0) unlink(exePath);

    AIBinder* svc = icecam::createVliveService();
    if (!svc) {
        LOGE("createVliveService failed");
        return 2;
    }

    binder_status_t reg = platform.addService(svc, serviceName);
    if (reg != STATUS_OK) {
        LOGE("AServiceManager_addService failed status=%d", reg);
        AIBinder_decStrong(svc);
        return 3;
    }
    LOGI("AServiceManager_addService ok name=%s", serviceName);

    platform.startPool();
    platform.joinPool();
    return 0;
}
