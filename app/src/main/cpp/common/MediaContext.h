#pragma once

#include <cstdint>
#include <atomic>
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
    int32_t framesDecoded = 0;
    int32_t framesPresented = 0;
    int32_t queueDepth = 0;
    int32_t codecState = 0;
    int32_t networkTicks = 0;
};

class MediaContext {
public:
    static MediaContext& instance();

    void resetPipeline();
    void setMode(int32_t mode, const std::string& path);
    void setLoop(bool loop);
    void setAutoRotate(bool enabled);
    void setMirror(bool enabled);
    void setAngle(int32_t angle);
    void setSeekRangeUs(int64_t startUs, int64_t endUs);
    void setTransform(const TransformParams& params);
    void toggleHardRecovery();

    bool startPlayback(const std::string& path, bool loop);
    void stopPlayback();

    int32_t playbackStatus() const;
    PollCounters pollCounters() const;

    bool hooksReady() const { return hooksReady_.load(); }
    void setHooksReady(bool ready) { hooksReady_.store(ready); }

private:
    MediaContext() = default;
    void decodeLoop();

    mutable std::mutex mutex_;
    std::thread decodeThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> hooksReady_{false};
    std::atomic<bool> hardRecovery_{false};

    int32_t mode_ = 0;
    std::string path_;
    bool loop_ = true;
    bool autoRotate_ = false;
    bool mirror_ = false;
    int32_t angle_ = 0;
    int64_t seekStartUs_ = 0;
    int64_t seekEndUs_ = -1;
    TransformParams transform_{};
    PollCounters counters_{};
    int32_t status_ = 0;
};

}  // namespace icecam
