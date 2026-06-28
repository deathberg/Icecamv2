#include <android/binder_ibinder.h>
#include <android/binder_status.h>
#include <android/log.h>

#include <dlfcn.h>
#include <string.h>
#include <unistd.h>

#include "binder_service.h"
#include "binder_runtime.h"
#include "media_context.h"

#define LOG_TAG "vcplax"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using InitFn = int (*)(void*);

bool load_libvc(const char* base_dir) {
    char path[512];
    snprintf(path, sizeof(path), "%s/libvc.so", base_dir);
    void* vc = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!vc) {
        LOGE("dlopen %s failed: %s", path, dlerror());
        vc = dlopen("/data/libvc.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!vc) {
        LOGE("dlopen libvc.so failed: %s", dlerror());
        return false;
    }
    auto init = reinterpret_cast<InitFn>(dlsym(vc, "init"));
    if (!init) {
        LOGE("dlsym init failed: %s", dlerror());
        return false;
    }
    const int rc = init(nullptr);
    LOGI("libvc init rc=%d", rc);
    return rc == 0;
}

std::string exe_dir() {
    char path[512] = {};
    const ssize_t n = readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (n <= 0) return "/data/camera";
    path[n] = '\0';
    char* slash = strrchr(path, '/');
    if (slash) *slash = '\0';
    return path;
}

void anti_forensics_unlink_self() {
    char path[512] = {};
    const ssize_t n = readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (n > 0) {
        path[n] = '\0';
        unlink(path);
        LOGI("unlinked self %s", path);
    }
}

}  // namespace

int main(int argc, char** argv) {
    const char* service_name = (argc >= 2) ? argv[1] : "privsam_service";
    LOGI("vcplax start service=%s", service_name);

    const std::string base = exe_dir();
    load_libvc(base.c_str());
    anti_forensics_unlink_self();

    if (!icecam::binder_runtime_init()) {
        LOGE("binder runtime init failed");
        return 1;
    }

    icecam::MediaContext& ctx = icecam::global_media_context();
    AIBinder* service = icecam::create_vlive_binder_service(&ctx);
    binder_status_t st = icecam::binder_add_service(service_name, service);
    if (st != STATUS_OK) {
        LOGE("binder_add_service failed: %d", st);
        return 1;
    }
    LOGI("registered service %s", service_name);
    icecam::binder_start_thread_pool(4);
    icecam::binder_join_thread_pool();
    return 0;
}
