#include "binder_service.h"
#include "log_util.h"
#include "media_pipeline.h"

#include <dlfcn.h>
#include <libgen.h>
#include <linux/limits.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <string>

namespace {

using InitFn = int (*)(void*);

bool loadLibvc(const char* baseDir) {
    char shadowPath[PATH_MAX];
    char vcPath[PATH_MAX];
    std::snprintf(shadowPath, sizeof(shadowPath), "%s/libvc++.so", baseDir);
    std::snprintf(vcPath, sizeof(vcPath), "%s/libvc.so", baseDir);

    if (dlopen(shadowPath, RTLD_NOW | RTLD_GLOBAL) == nullptr) {
        ICE_LOGW("dlopen %s failed: %s; trying libshadowhook.so", shadowPath, dlerror());
        std::snprintf(shadowPath, sizeof(shadowPath), "%s/libshadowhook.so", baseDir);
        if (dlopen(shadowPath, RTLD_NOW | RTLD_GLOBAL) == nullptr) {
            ICE_LOGE("dlopen shadowhook failed: %s", dlerror());
            return false;
        }
    }

    void* handle = dlopen(vcPath, RTLD_NOW);
    if (handle == nullptr) {
        ICE_LOGE("dlopen %s failed: %s", vcPath, dlerror());
        return false;
    }

    auto init = reinterpret_cast<InitFn>(dlsym(handle, "init"));
    if (init == nullptr) {
        ICE_LOGE("dlsym(init) failed: %s", dlerror());
        return false;
    }

    const int rc = init(nullptr);
    ICE_LOGI("libvc init rc=%d", rc);
    return rc == 0;
}

std::string resolveBaseDir(const char* argv0) {
    char path[PATH_MAX];
    if (argv0 == nullptr) {
        return "/data/camera";
    }
    std::snprintf(path, sizeof(path), "%s", argv0);
    char* dir = dirname(path);
    if (dir == nullptr || dir[0] == '\0') {
        return "/data/camera";
    }
    return dir;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <service_name>\n", argv[0] == nullptr ? "vcplax" : argv[0]);
        return 2;
    }

    const std::string serviceName = argv[1];
    const std::string baseDir = resolveBaseDir(argv[0]);

    ICE_LOGI("vcplax start service=%s base=%s", serviceName.c_str(), baseDir.c_str());

    char selfPath[PATH_MAX];
    if (readlink("/proc/self/exe", selfPath, sizeof(selfPath) - 1) > 0) {
        selfPath[sizeof(selfPath) - 1] = '\0';
        unlink(selfPath);
    }

    if (!loadLibvc(baseDir.c_str())) {
        ICE_LOGW("libvc load failed; continuing with binder-only mode");
    }

    icecam::MediaContext mediaContext;
    icecam::MediaPipeline pipeline(&mediaContext);

    if (!icecam::VliveBinderService::registerService(serviceName, &pipeline)) {
        ICE_LOGE("failed to register binder service");
        return 1;
    }

    return icecam::VliveBinderService::runThreadPool();
}
