#pragma once

#include "media_context.h"

namespace icecam {

class MediaPipeline {
public:
    explicit MediaPipeline(MediaContext* ctx);
    ~MediaPipeline();

    void setModeAndPath(int32_t mode, const std::string& path);
    int32_t playSource(const std::string& path, bool loop);
    int32_t stopOrQuery();
    void snapshotPollCounters(int32_t out[5]) const;
    int32_t getStatus();
    void setAutoRotate(bool enabled);
    void setLoop(bool enabled);
    void setAngle(int32_t angle);
    void setMirror(bool enabled);
    void setSeekRange(int64_t startUs, int64_t endUs);
    void setTransform(const TransformParams& params);
    void toggleHardRecovery();
    bool libvcReady() const { return libvcReady_; }

private:
    MediaContext* ctx_;
    bool libvcReady_ = false;
    void stopDecodeThread();
    void startDecodeThread();
    static void decodeLoop(MediaContext* ctx);
};

}  // namespace icecam
