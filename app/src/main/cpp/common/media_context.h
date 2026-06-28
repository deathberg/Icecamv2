#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

namespace icecam {

struct TransformParams {
    int32_t mode = 0;
    float panX = 0.f;
    float panY = 0.f;
    float zoomX = 1.f;
    float zoomY = 1.f;
    int32_t color = 0;
};

struct MediaContext {
    std::mutex mutex;
    std::string path;
    int32_t mode = 1;
    bool loop = false;
    bool autoRotate = false;
    bool mirror = false;
    int32_t angle = 0;
    int64_t seekStartUs = 0;
    int64_t seekEndUs = -1;
    TransformParams transform{};
    bool playing = false;
    bool hardRecovery = false;

    std::atomic<int32_t> pollCounters[5]{};

    void resetPollCounters() {
        for (auto& c : pollCounters) c.store(0, std::memory_order_relaxed);
    }
};

}  // namespace icecam
