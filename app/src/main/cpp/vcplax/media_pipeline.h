#pragma once

#include "common/media_context.h"

#include <atomic>
#include <memory>
#include <string>
#include <thread>

namespace icecam {

class MediaPipeline {
public:
    explicit MediaPipeline(MediaContext* ctx);
    ~MediaPipeline();

    void setModeAndPath(int32_t mode, const std::string& path);
    void startPlayback(const std::string& path, bool loop);
    void stop();
    void setTransform(const TransformParams& params);

private:
    void decodeLoop();
    bool openSourceLocked();
    void closeSourceLocked();
    void pumpCounters();

    MediaContext* ctx_;
    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::string activePath_;
    int32_t activeMode_ = 1;
    bool loop_ = false;
    void* extractor_ = nullptr;
    void* codec_ = nullptr;
    int videoTrack_ = -1;
};

}  // namespace icecam
