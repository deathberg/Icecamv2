#include "binder_service.h"

#include "log_util.h"

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>

#include <dlfcn.h>
#include <mutex>
#include <string>

namespace icecam {
namespace {

constexpr const char* kDescriptor = "com.xiaomi.vlive.IMyBinderService";

using FnAddService = binder_status_t (*)(AIBinder*, const char*);
using FnStartThreadPool = void (*)();
using FnJoinThreadPool = void (*)();

FnAddService gAddService = nullptr;
FnStartThreadPool gStartThreadPool = nullptr;
FnJoinThreadPool gJoinThreadPool = nullptr;

AIBinder_Class* gClass = nullptr;
MediaPipeline* gPipeline = nullptr;
std::mutex gBinderLock;

bool loadBinderRuntime() {
    void* handle = dlopen("libbinder_ndk.so", RTLD_NOW);
    if (handle == nullptr) {
        ICE_LOGE("dlopen libbinder_ndk failed: %s", dlerror());
        return false;
    }
    gAddService = reinterpret_cast<FnAddService>(dlsym(handle, "AServiceManager_addService"));
    gStartThreadPool = reinterpret_cast<FnStartThreadPool>(dlsym(handle, "ABinderProcess_startThreadPool"));
    gJoinThreadPool = reinterpret_cast<FnJoinThreadPool>(dlsym(handle, "ABinderProcess_joinThreadPool"));
    if (gAddService == nullptr || gStartThreadPool == nullptr || gJoinThreadPool == nullptr) {
        ICE_LOGE("binder_ndk service symbols missing");
        return false;
    }
    return true;
}

void* onCreate(void* args) {
    return args;
}

void onDestroy(void* userData) {
    (void)userData;
}

bool stringAlloc(void* stringData, int32_t length, char** buffer) {
    auto* out = static_cast<std::string*>(stringData);
    if (length < 0) {
        out->clear();
        *buffer = nullptr;
        return true;
    }
    if (length == 0) {
        out->clear();
        *buffer = out->data();
        return true;
    }
    out->resize(static_cast<size_t>(length - 1));
    *buffer = out->data();
    return true;
}

bool readInterfaceToken(const AParcel* in) {
    int32_t strict = 0;
    if (AParcel_readInt32(in, &strict) != STATUS_OK) {
        return false;
    }
    std::string token;
    if (AParcel_readString(in, &token, stringAlloc) != STATUS_OK) {
        return false;
    }
    return token.empty() || token == kDescriptor;
}

bool readString(const AParcel* in, std::string* out) {
    return AParcel_readString(in, out, stringAlloc) == STATUS_OK;
}

void writeStatusReply(AParcel* out, int32_t value) {
    AParcel_writeInt32(out, 0);
    AParcel_writeInt32(out, value);
}

void writePollReply(AParcel* out, const int32_t counters[5]) {
    AParcel_writeInt32(out, 0);
    for (int i = 0; i < 5; ++i) {
        AParcel_writeInt32(out, counters[i]);
    }
}

binder_status_t dispatchTransaction(transaction_code_t code, const AParcel* in, AParcel* out) {
    if (gPipeline == nullptr) {
        return STATUS_UNKNOWN_ERROR;
    }

    switch (code) {
        case 11: {
            std::string path;
            int32_t mirrorIgnored = 0;
            int32_t loop = 0;
            if (!readString(in, &path) || AParcel_readInt32(in, &mirrorIgnored) != STATUS_OK ||
                AParcel_readInt32(in, &loop) != STATUS_OK) {
                return STATUS_BAD_VALUE;
            }
            writeStatusReply(out, gPipeline->playSource(path, loop != 0));
            return STATUS_OK;
        }
        case 12: {
            writeStatusReply(out, gPipeline->stopOrQuery());
            return STATUS_OK;
        }
        case 13: {
            int32_t counters[5] = {};
            gPipeline->snapshotPollCounters(counters);
            writePollReply(out, counters);
            return STATUS_OK;
        }
        case 14: {
            int32_t mode = 0;
            std::string path;
            if (AParcel_readInt32(in, &mode) != STATUS_OK || !readString(in, &path)) {
                return STATUS_BAD_VALUE;
            }
            gPipeline->setModeAndPath(mode, path);
            writeStatusReply(out, 4);
            return STATUS_OK;
        }
        case 15: {
            writeStatusReply(out, gPipeline->getStatus());
            return STATUS_OK;
        }
        case 16: {
            int32_t value = 0;
            if (AParcel_readInt32(in, &value) != STATUS_OK) return STATUS_BAD_VALUE;
            gPipeline->setAutoRotate(value != 0);
            writeStatusReply(out, 1);
            return STATUS_OK;
        }
        case 17: {
            int32_t value = 0;
            if (AParcel_readInt32(in, &value) != STATUS_OK) return STATUS_BAD_VALUE;
            gPipeline->setLoop(value != 0);
            writeStatusReply(out, 1);
            return STATUS_OK;
        }
        case 18: {
            int32_t angle = 0;
            if (AParcel_readInt32(in, &angle) != STATUS_OK) return STATUS_BAD_VALUE;
            gPipeline->setAngle(angle);
            writeStatusReply(out, 1);
            return STATUS_OK;
        }
        case 19: {
            int32_t value = 0;
            if (AParcel_readInt32(in, &value) != STATUS_OK) return STATUS_BAD_VALUE;
            gPipeline->setMirror(value != 0);
            writeStatusReply(out, 1);
            return STATUS_OK;
        }
        case 22: {
            int64_t startUs = 0;
            int64_t endUs = 0;
            if (AParcel_readInt64(in, &startUs) != STATUS_OK || AParcel_readInt64(in, &endUs) != STATUS_OK) {
                return STATUS_BAD_VALUE;
            }
            gPipeline->setSeekRange(startUs, endUs);
            writeStatusReply(out, 1);
            return STATUS_OK;
        }
        case 24: {
            TransformParams params{};
            float f = 0.f;
            if (AParcel_readInt32(in, &params.mode) != STATUS_OK || AParcel_readFloat(in, &f) != STATUS_OK) {
                return STATUS_BAD_VALUE;
            }
            params.panX = f;
            if (AParcel_readFloat(in, &f) != STATUS_OK) return STATUS_BAD_VALUE;
            params.panY = f;
            if (AParcel_readFloat(in, &f) != STATUS_OK) return STATUS_BAD_VALUE;
            params.zoomX = f;
            if (AParcel_readFloat(in, &f) != STATUS_OK) return STATUS_BAD_VALUE;
            params.zoomY = f;
            if (AParcel_readInt32(in, &params.colorFlags) != STATUS_OK) return STATUS_BAD_VALUE;
            gPipeline->setTransform(params);
            writeStatusReply(out, 1);
            return STATUS_OK;
        }
        case 25: {
            gPipeline->toggleHardRecovery();
            writeStatusReply(out, 1);
            return STATUS_OK;
        }
        default:
            ICE_LOGW("unknown TX code=%u", code);
            return STATUS_UNKNOWN_TRANSACTION;
    }
}

binder_status_t onTransact(AIBinder* binder, transaction_code_t code, const AParcel* in, AParcel* out) {
    (void)binder;
    std::lock_guard<std::mutex> lock(gBinderLock);
    if (!readInterfaceToken(in)) {
        return STATUS_BAD_VALUE;
    }
    return dispatchTransaction(code, in, out);
}

}  // namespace

bool VliveBinderService::registerService(const std::string& name, MediaPipeline* pipeline) {
    if (!loadBinderRuntime()) {
        return false;
    }
    gPipeline = pipeline;
    if (gClass == nullptr) {
        gClass = AIBinder_Class_define(kDescriptor, onCreate, onDestroy, onTransact);
    }
    if (gClass == nullptr) {
        ICE_LOGE("AIBinder_Class_define failed");
        return false;
    }

    AIBinder* binder = AIBinder_new(gClass, nullptr);
    if (binder == nullptr) {
        ICE_LOGE("AIBinder_new failed");
        return false;
    }
    const binder_status_t status = gAddService(binder, name.c_str());
    if (status != STATUS_OK) {
        ICE_LOGE("AServiceManager_addService(%s) failed: %d", name.c_str(), status);
        AIBinder_decStrong(binder);
        return false;
    }
    ICE_LOGI("registered binder service %s", name.c_str());
    AIBinder_decStrong(binder);
    return true;
}

int VliveBinderService::runThreadPool() {
    if (gStartThreadPool == nullptr || gJoinThreadPool == nullptr) {
        return 1;
    }
    gStartThreadPool();
    gJoinThreadPool();
    return 0;
}

}  // namespace icecam
