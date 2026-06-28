#include "media_pipeline.h"

#include "libvc/frame_inject.h"

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

#include <chrono>
#include <cstring>
#include <thread>

#define VP_TAG "vcplax"
#define VP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VP_TAG, __VA_ARGS__)
#define VP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VP_TAG, __VA_ARGS__)

namespace icecam {

MediaPipeline::MediaPipeline(MediaContext* ctx) : ctx_(ctx) {}

MediaPipeline::~MediaPipeline() { stop(); }

void MediaPipeline::setModeAndPath(int32_t mode, const std::string& path) {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->mode = mode;
    ctx_->path = path;
    activeMode_ = mode;
    activePath_ = path;
    VP_LOGI("setModeAndPath mode=%d path=%s", mode, path.c_str());
}

void MediaPipeline::setTransform(const TransformParams& params) {
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->transform = params;
    setInjectTransform(params.mode, params.panX, params.panY, params.zoomX, params.zoomY, params.color);
}

void MediaPipeline::startPlayback(const std::string& path, bool loop) {
    stop();
    {
        std::lock_guard<std::mutex> lock(ctx_->mutex);
        ctx_->path = path;
        ctx_->playing = true;
        ctx_->loop = loop;
        activePath_ = path;
        loop_ = loop;
    }
    stopRequested_.store(false, std::memory_order_relaxed);
    running_.store(true, std::memory_order_relaxed);
    worker_ = std::thread(&MediaPipeline::decodeLoop, this);
    VP_LOGI("startPlayback path=%s loop=%d", path.c_str(), loop ? 1 : 0);
}

void MediaPipeline::stop() {
    stopRequested_.store(true, std::memory_order_relaxed);
    if (worker_.joinable()) worker_.join();
    running_.store(false, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(ctx_->mutex);
        ctx_->playing = false;
    }
    closeSourceLocked();
}

bool MediaPipeline::openSourceLocked() {
    closeSourceLocked();
    if (activePath_.empty()) return false;

    auto* extractor = AMediaExtractor_new();
    if (extractor == nullptr) return false;
    media_status_t st = AMediaExtractor_setDataSource(extractor, activePath_.c_str());
    if (st != AMEDIA_OK) {
        AMediaExtractor_delete(extractor);
        VP_LOGE("setDataSource failed path=%s status=%d", activePath_.c_str(), st);
        return false;
    }

    const size_t trackCount = AMediaExtractor_getTrackCount(extractor);
    int videoTrack = -1;
    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, i);
        if (format == nullptr) continue;
        const char* mime = nullptr;
        if (AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && mime != nullptr &&
            strncmp(mime, "video/", 6) == 0) {
            videoTrack = static_cast<int>(i);
            AMediaFormat_delete(format);
            break;
        }
        AMediaFormat_delete(format);
    }
    if (videoTrack < 0) {
        AMediaExtractor_delete(extractor);
        return false;
    }

    AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, static_cast<size_t>(videoTrack));
    const char* mime = nullptr;
    AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);
    auto* codec = AMediaCodec_createDecoderByType(mime != nullptr ? mime : "video/avc");
    if (codec == nullptr) {
        AMediaFormat_delete(format);
        AMediaExtractor_delete(extractor);
        return false;
    }
    AMediaCodec_configure(codec, format, nullptr, nullptr, 0);
    AMediaCodec_start(codec);
    AMediaFormat_delete(format);

    extractor_ = extractor;
    codec_ = codec;
    videoTrack_ = videoTrack;
    VP_LOGI("opened source track=%d mime=%s", videoTrack, mime != nullptr ? mime : "?");
    return true;
}

void MediaPipeline::closeSourceLocked() {
    if (codec_ != nullptr) {
        AMediaCodec_stop(static_cast<AMediaCodec*>(codec_));
        AMediaCodec_delete(static_cast<AMediaCodec*>(codec_));
        codec_ = nullptr;
    }
    if (extractor_ != nullptr) {
        AMediaExtractor_delete(static_cast<AMediaExtractor*>(extractor_));
        extractor_ = nullptr;
    }
    videoTrack_ = -1;
}

void MediaPipeline::pumpCounters() {
    ctx_->pollCounters[0].fetch_add(1, std::memory_order_relaxed);
    ctx_->pollCounters[1].fetch_add(16, std::memory_order_relaxed);
    ctx_->pollCounters[2].fetch_add(1, std::memory_order_relaxed);
    if (activeMode_ == 2) {
        ctx_->pollCounters[4].fetch_add(1, std::memory_order_relaxed);
    }
}

void MediaPipeline::decodeLoop() {
    if (!openSourceLocked()) {
        std::lock_guard<std::mutex> lock(ctx_->mutex);
        ctx_->playing = false;
        return;
    }

    auto* extractor = static_cast<AMediaExtractor*>(extractor_);
    auto* codec = static_cast<AMediaCodec*>(codec_);
    AMediaExtractor_selectTrack(extractor, static_cast<size_t>(videoTrack_));

    bool inputDone = false;
    while (!stopRequested_.load(std::memory_order_relaxed)) {
        if (!inputDone) {
            const ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(codec, 10'000);
            if (bufIdx >= 0) {
                size_t sampleSize = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(codec, static_cast<size_t>(bufIdx), &sampleSize);
                const ssize_t read = AMediaExtractor_readSampleData(extractor, buf, sampleSize);
                if (read < 0) {
                    AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(bufIdx), 0, 0, 0,
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    const int64_t pts = AMediaExtractor_getSampleTime(extractor);
                    AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(bufIdx), 0, static_cast<size_t>(read), pts, 0);
                    AMediaExtractor_advance(extractor);
                }
            }
        }

        AMediaCodecBufferInfo info{};
        const ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 10'000);
        if (outIdx >= 0) {
            pumpCounters();
            AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outIdx), false);
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                if (loop_) {
                    AMediaExtractor_seekTo(extractor, 0, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
                    AMediaCodec_flush(codec);
                    inputDone = false;
                } else {
                    break;
                }
            }
        } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* outFmt = AMediaCodec_getOutputFormat(codec);
            int32_t width = 0;
            int32_t height = 0;
            AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_WIDTH, &width);
            AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_HEIGHT, &height);
            auto& slot = globalFrameSlot();
            std::lock_guard<std::mutex> lock(slot.mutex);
            slot.width = static_cast<uint32_t>(width);
            slot.height = static_cast<uint32_t>(height);
            slot.yStride = static_cast<uint32_t>(width);
            slot.uvStride = static_cast<uint32_t>(width);
            const size_t need = static_cast<size_t>(width) * static_cast<size_t>(height) * 3 / 2;
            if (slot.capacity < need) {
                delete[] slot.data;
                slot.data = new uint8_t[need];
                slot.capacity = need;
            }
            slot.hasFrame = width > 0 && height > 0;
            AMediaFormat_delete(outFmt);
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    closeSourceLocked();
    running_.store(false, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(ctx_->mutex);
    ctx_->playing = false;
}

}  // namespace icecam
