#include "media_pipeline.h"

#include "log_util.h"

#include <chrono>
#include <cstdio>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

namespace icecam {

MediaPipeline::MediaPipeline(MediaContext* ctx) : ctx_(ctx) {
    libvcReady_ = true;
}

MediaPipeline::~MediaPipeline() {
    stopDecodeThread();
}

void MediaPipeline::stopDecodeThread() {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->resetPipelineLocked();
}

void MediaPipeline::startDecodeThread() {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    if (ctx_->decodeThread.joinable()) {
        return;
    }
    ctx_->stopThread.store(false);
    ctx_->playing.store(true);
    ctx_->playbackStatus = 5;
    ctx_->counters.codecState = 1;
    ctx_->decodeThread = std::thread(decodeLoop, ctx_);
}

void MediaPipeline::decodeLoop(MediaContext* ctx) {
    ICE_LOGI("decode thread start path=%s mode=%d", ctx->mediaPath.c_str(), ctx->mode);
    while (!ctx->stopThread.load()) {
        {
            std::lock_guard<std::mutex> lock(ctx->mutex);
            ctx->counters.frameIndex++;
            ctx->counters.decodedFrames++;
            ctx->counters.queueDepth = (ctx->counters.queueDepth + 1) % 8;
            if (ctx->mode == 2) {
                ctx->counters.networkTicks++;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(ctx->mode == 2 ? 33 : 40));
    }
    ICE_LOGI("decode thread stop");
}

void MediaPipeline::setModeAndPath(int32_t mode, const std::string& path) {
    stopDecodeThread();
    {
        std::lock_guard<std::mutex> lock(ctx_->mutex);
        ctx_->mode = mode;
        ctx_->mediaPath = path;
        ctx_->counters = {};
        ctx_->playbackStatus = 0;
    }
    ICE_LOGI("TX14 set mode=%d path=%s", mode, path.c_str());
    if (!path.empty()) {
        struct stat st {};
        if (stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode)) {
            startDecodeThread();
        } else if (mode == 2) {
            // RTMP / network mode — start even when path is a URL.
            startDecodeThread();
        }
    }
}

int32_t MediaPipeline::playSource(const std::string& path, bool loop) {
    if (!libvcReady_) {
        ICE_LOGW("TX11 rejected: libvc not ready");
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(ctx_->mutex);
        ctx_->mediaPath = path;
        ctx_->loop = loop;
    }
    setModeAndPath(ctx_->mode > 0 ? ctx_->mode : 1, path);
    ICE_LOGI("TX11 play path=%s loop=%d", path.c_str(), loop ? 1 : 0);
    return 1;
}

int32_t MediaPipeline::stopOrQuery() {
    stopDecodeThread();
    {
        std::lock_guard<std::mutex> lock(ctx_->mutex);
        ctx_->playbackStatus = 0;
    }
    ICE_LOGI("TX12 stop/query");
    return 1;
}

void MediaPipeline::snapshotPollCounters(int32_t out[5]) const {
    ctx_->snapshotCounters(out);
}

int32_t MediaPipeline::getStatus() {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    return ctx_->playing.load() ? 5 : ctx_->playbackStatus;
}

void MediaPipeline::setAutoRotate(bool enabled) {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->autoRotate = enabled;
}

void MediaPipeline::setLoop(bool enabled) {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->loop = enabled;
}

void MediaPipeline::setAngle(int32_t angle) {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->angle = angle;
}

void MediaPipeline::setMirror(bool enabled) {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->mirror = enabled;
    if (enabled) {
        ctx_->autoRotate = false;
    }
}

void MediaPipeline::setSeekRange(int64_t startUs, int64_t endUs) {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->seekStartUs = startUs;
    ctx_->seekEndUs = endUs;
    ICE_LOGI("TX22 seek %lld..%lld", static_cast<long long>(startUs), static_cast<long long>(endUs));
}

void MediaPipeline::setTransform(const TransformParams& params) {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->transform = params;
    ICE_LOGI("TX24 transform mode=%d pan=(%.2f,%.2f) zoom=(%.2f,%.2f) color=0x%08x",
             params.mode, params.panX, params.panY, params.zoomX, params.zoomY, params.colorFlags);
}

void MediaPipeline::toggleHardRecovery() {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->hardRecovery = !ctx_->hardRecovery;
    ICE_LOGI("TX25 hard recovery=%d", ctx_->hardRecovery ? 1 : 0);
}

}  // namespace icecam
