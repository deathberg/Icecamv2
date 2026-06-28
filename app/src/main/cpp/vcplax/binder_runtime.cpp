#include "binder_runtime.h"

#include <android/binder_ibinder.h>
#include <android/binder_status.h>
#include <android/log.h>

#include <dlfcn.h>

#define LOG_TAG "vcplax"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace icecam {

namespace {

using AddServiceFn = binder_status_t (*)(const char*, AIBinder*);
using SetPoolFn = void (*)(uint32_t);
using StartPoolFn = void (*)();
using JoinPoolFn = void (*)();

struct BinderApi {
    void* handle = nullptr;
    AddServiceFn add_service = nullptr;
    SetPoolFn set_pool = nullptr;
    StartPoolFn start_pool = nullptr;
    JoinPoolFn join_pool = nullptr;
};

BinderApi& api() {
    static BinderApi inst;
    return inst;
}

template <typename T>
T load_sym(void* handle, const char* name) {
    void* sym = dlsym(handle, name);
    if (!sym) LOGE("dlsym %s failed: %s", name, dlerror());
    return reinterpret_cast<T>(sym);
}

}  // namespace

bool binder_runtime_init() {
    BinderApi& a = api();
    if (a.handle) return a.add_service != nullptr;

    const char* libs[] = {"libbinder_ndk.so", "/system/lib64/libbinder_ndk.so",
                          "/system/lib/libbinder_ndk.so"};
    for (const char* lib : libs) {
        a.handle = dlopen(lib, RTLD_NOW | RTLD_GLOBAL);
        if (a.handle) break;
    }
    if (!a.handle) {
        LOGE("dlopen libbinder_ndk failed: %s", dlerror());
        return false;
    }

    a.add_service = load_sym<AddServiceFn>(a.handle, "AServiceManager_addService");
    a.set_pool = load_sym<SetPoolFn>(a.handle, "ABinderProcess_setThreadPoolMaxThreadCount");
    a.start_pool = load_sym<StartPoolFn>(a.handle, "ABinderProcess_startThreadPool");
    a.join_pool = load_sym<JoinPoolFn>(a.handle, "ABinderProcess_joinThreadPool");
    return a.add_service && a.set_pool && a.start_pool && a.join_pool;
}

binder_status_t binder_add_service(const char* name, AIBinder* binder) {
    if (!api().add_service) return STATUS_UNKNOWN_ERROR;
    return api().add_service(name, binder);
}

void binder_start_thread_pool(uint32_t threads) {
    if (api().set_pool) api().set_pool(threads);
    if (api().start_pool) api().start_pool();
}

void binder_join_thread_pool() {
    if (api().join_pool) api().join_pool();
}

}  // namespace icecam
