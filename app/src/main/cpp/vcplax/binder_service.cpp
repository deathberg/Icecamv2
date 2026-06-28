#include "binder_service.h"

#include "media_pipeline.h"

#include <android/binder_parcel_utils.h>
#include <android/log.h>

#include <cstring>

#define VP_TAG "vcplax"
#define VP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VP_TAG, __VA_ARGS__)
#define VP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VP_TAG, __VA_ARGS__)

namespace icecam {

namespace {
constexpr int kOkPlay = 1;
constexpr int kOkSetMode = 4;
constexpr int kOkTransform = 14;
constexpr int kStatusPlaying = 5;

bool enforceInterface(const AParcel* in) {
    std::string token;
    if (AParcel_readString(in, &token) != STATUS_OK) return false;
    return token == VliveBinderService::kDescriptor;
}

void writeNoException(AParcel* out) { AParcel_writeInt32(out, 0); }
}  // namespace

VliveBinderService::VliveBinderService(std::string serviceName)
        : serviceName_(std::move(serviceName)), pipeline_(std::make_unique<MediaPipeline>(&media_)) {}

VliveBinderService::~VliveBinderService() { pipeline_->stop(); }

void* VliveBinderService::onCreate(void* args) {
    const char* name = args != nullptr ? static_cast<const char*>(args) : "";
    return new VliveBinderService(name);
}

void VliveBinderService::onDestroy(void* userData) {
    delete static_cast<VliveBinderService*>(userData);
}

binder_status_t VliveBinderService::onTransact(AIBinder* binder, transaction_code_t code, const AParcel* in,
                                                 AParcel* out) {
    void* user = AIBinder_getUserData(binder);
    if (user == nullptr) return STATUS_UNKNOWN_ERROR;
    return static_cast<VliveBinderService*>(user)->handleTransact(code, in, out);
}

AIBinder* VliveBinderService::createBinder(const char* serviceName) {
    AIBinder_Class* clazz =
            AIBinder_Class_define(kDescriptor, onCreate, onDestroy, onTransact);
    return AIBinder_new(clazz, const_cast<char*>(serviceName));
}

binder_status_t VliveBinderService::handleTransact(transaction_code_t code, const AParcel* in, AParcel* out) {
    if (!enforceInterface(in)) return STATUS_BAD_TYPE;

    switch (code) {
        case 11: {
            std::string path;
            int32_t mirrorIgnored = 0;
            int32_t loop = 0;
            if (AParcel_readString(in, &path) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(in, &mirrorIgnored) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(in, &loop) != STATUS_OK) return STATUS_BAD_VALUE;
            (void)mirrorIgnored;
            pipeline_->startPlayback(path, loop != 0);
            writeNoException(out);
            AParcel_writeInt32(out, kOkPlay);
            VP_LOGI("TX11 play path=%s loop=%d", path.c_str(), loop);
            return STATUS_OK;
        }
        case 12: {
            writeNoException(out);
            AParcel_writeInt32(out, media_.playing ? 1 : 0);
            return STATUS_OK;
        }
        case 13: {
            writeNoException(out);
            for (int i = 0; i < 5; ++i) {
                AParcel_writeInt32(out, media_.pollCounters[i].load(std::memory_order_relaxed));
            }
            return STATUS_OK;
        }
        case 14: {
            int32_t mode = 0;
            std::string path;
            if (AParcel_readInt32(in, &mode) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readString(in, &path) != STATUS_OK) return STATUS_BAD_VALUE;
            pipeline_->stop();
            pipeline_->setModeAndPath(mode, path);
            writeNoException(out);
            AParcel_writeInt32(out, kOkSetMode);
            VP_LOGI("TX14 mode=%d path=%s", mode, path.c_str());
            return STATUS_OK;
        }
        case 15: {
            writeNoException(out);
            AParcel_writeInt32(out, media_.playing ? kStatusPlaying : 0);
            return STATUS_OK;
        }
        case 16: {
            int32_t v = 0;
            if (AParcel_readInt32(in, &v) != STATUS_OK) return STATUS_BAD_VALUE;
            {
                std::lock_guard<std::mutex> lock(media_.mutex);
                media_.autoRotate = v != 0;
            }
            writeNoException(out);
            AParcel_writeInt32(out, 1);
            return STATUS_OK;
        }
        case 17: {
            int32_t v = 0;
            if (AParcel_readInt32(in, &v) != STATUS_OK) return STATUS_BAD_VALUE;
            {
                std::lock_guard<std::mutex> lock(media_.mutex);
                media_.loop = v != 0;
            }
            writeNoException(out);
            AParcel_writeInt32(out, 1);
            return STATUS_OK;
        }
        case 18: {
            int32_t angle = 0;
            if (AParcel_readInt32(in, &angle) != STATUS_OK) return STATUS_BAD_VALUE;
            {
                std::lock_guard<std::mutex> lock(media_.mutex);
                media_.angle = angle;
            }
            writeNoException(out);
            AParcel_writeInt32(out, 1);
            return STATUS_OK;
        }
        case 19: {
            int32_t v = 0;
            if (AParcel_readInt32(in, &v) != STATUS_OK) return STATUS_BAD_VALUE;
            {
                std::lock_guard<std::mutex> lock(media_.mutex);
                media_.mirror = v != 0;
            }
            writeNoException(out);
            AParcel_writeInt32(out, 1);
            return STATUS_OK;
        }
        case 22: {
            int64_t start = 0;
            int64_t end = 0;
            if (AParcel_readInt64(in, &start) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt64(in, &end) != STATUS_OK) return STATUS_BAD_VALUE;
            {
                std::lock_guard<std::mutex> lock(media_.mutex);
                media_.seekStartUs = start;
                media_.seekEndUs = end;
            }
            writeNoException(out);
            AParcel_writeInt32(out, 1);
            VP_LOGI("TX22 seek %lld..%lld", static_cast<long long>(start), static_cast<long long>(end));
            return STATUS_OK;
        }
        case 24: {
            TransformParams params{};
            if (AParcel_readInt32(in, &params.mode) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readFloat(in, &params.panX) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readFloat(in, &params.panY) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readFloat(in, &params.zoomX) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readFloat(in, &params.zoomY) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(in, &params.color) != STATUS_OK) return STATUS_BAD_VALUE;
            pipeline_->setTransform(params);
            writeNoException(out);
            AParcel_writeInt32(out, kOkTransform);
            VP_LOGI("TX24 mode=%d pan=(%.2f,%.2f) zoom=(%.2f,%.2f) color=0x%08x", params.mode, params.panX,
                    params.panY, params.zoomX, params.zoomY, params.color);
            return STATUS_OK;
        }
        case 25: {
            {
                std::lock_guard<std::mutex> lock(media_.mutex);
                media_.hardRecovery = !media_.hardRecovery;
                media_.resetPollCounters();
            }
            pipeline_->stop();
            writeNoException(out);
            AParcel_writeInt32(out, 1);
            VP_LOGI("TX25 hard recovery toggled");
            return STATUS_OK;
        }
        default:
            return STATUS_UNKNOWN_TRANSACTION;
    }
}

}  // namespace icecam
