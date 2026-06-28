#pragma once

#include "common/media_context.h"

#include <android/binder_ibinder.h>

#include <memory>
#include <string>

namespace icecam {

class MediaPipeline;

class VliveBinderService {
public:
    static constexpr const char* kDescriptor = "com.xiaomi.vlive.IMyBinderService";

    explicit VliveBinderService(std::string serviceName);
    ~VliveBinderService();

    static AIBinder* createBinder(const char* serviceName);
    MediaContext& mediaContext() { return media_; }

private:
    static void* onCreate(void* args);
    static void onDestroy(void* userData);
    static binder_status_t onTransact(AIBinder* binder, transaction_code_t code, const AParcel* in,
                                      AParcel* out);

    binder_status_t handleTransact(transaction_code_t code, const AParcel* in, AParcel* out);

    std::string serviceName_;
    MediaContext media_;
    std::unique_ptr<MediaPipeline> pipeline_;
};

}  // namespace icecam
