#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

namespace icecam {

struct TransformParams {
    int32_t mode = 0;
    float panX = 0.f;
    float panY = 0.f;
    float zoomX = 1.f;
    float zoomY = 1.f;
    int32_t colorFlags = 0;
};

struct PollCounters {
    int32_t frameIndex = 0;
    int32_t decodedFrames = 0;
    int32_t queueDepth = 0;
    int32_t codecState = 0;
    int32_t networkTicks = 0;
};

class MediaContext {
public:
    std::mutex mutex;
    int32_t mode = 1;
    std::string mediaPath;
    bool loop = true;
    bool autoRotate = false;
    bool mirror = false;
    int32_t angle = 0;
    int32_t playbackStatus = 0;
    bool hardRecovery = false;
    int64_t seekStartUs = 0;
    int64_t seekEndUs = 0;
    TransformParams transform{};
    PollCounters counters{};
    std::atomic<bool> playing{false};
    std::atomic<bool> stopThread{false};
    std::thread decodeThread;

    void resetPipelineLocked() {
        stopThread.store(true);
        if (decodeThread.joinable()) {
            decodeThread.join();
        }
        stopThread.store(false);
        playing.store(false);
        counters = {};
        playbackStatus = 0;
    }

    void snapshotCounters(int32_t out[5]) const {
        out[0] = counters.frameIndex;
        out[1] = counters.decodedFrames;
        out[2] = counters.queueDepth;
        out[3] = counters.codecState;
        out[4] = counters.networkTicks;
    }
};

}  // namespace icecam
