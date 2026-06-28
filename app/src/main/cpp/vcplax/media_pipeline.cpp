#include "MediaContext.h"

#include <android/log.h>
#include <media/NdkMediaExtractor.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <cstring>
#include <thread>

#define LOG_TAG "vcplax"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace icecam {

MediaContext& MediaContext::instance() {
    static MediaContext ctx;
    return ctx;
}

void MediaContext::resetPipeline() {
    stopPlayback();
    std::lock_guard<std::mutex> lock(mutex_);
    counters_ = {};
    status_ = 0;
}

void MediaContext::setMode(int32_t mode, const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex_);
    mode_ = mode;
    path_ = path;
    LOGI("setMode mode=%d path=%s", mode, path.c_str());
}

void MediaContext::setLoop(bool loop) {
    std::lock_guard<std::mutex> lock(mutex_);
    loop_ = loop;
}

void MediaContext::setAutoRotate(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    autoRotate_ = enabled;
}

void MediaContext::setMirror(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    mirror_ = enabled;
    if (enabled) autoRotate_ = false;
}

void MediaContext::setAngle(int32_t angle) {
    std::lock_guard<std::mutex> lock(mutex_);
    angle_ = angle;
}

void MediaContext::setSeekRangeUs(int64_t startUs, int64_t endUs) {
    std::lock_guard<std::mutex> lock(mutex_);
    seekStartUs_ = startUs;
    seekEndUs_ = endUs;
    LOGI("seek range %lld..%lld us", static_cast<long long>(startUs), static_cast<long long>(endUs));
}

void MediaContext::setTransform(const TransformParams& params) {
    std::lock_guard<std::mutex> lock(mutex_);
    transform_ = params;
    LOGI("transform mode=%d pan=(%.2f,%.2f) zoom=(%.2f,%.2f) color=0x%08x", params.mode, params.panX,
         params.panY, params.zoomX, params.zoomY, params.colorFlags);
}

void MediaContext::toggleHardRecovery() {
    hardRecovery_.store(!hardRecovery_.load());
    LOGI("hard recovery=%d", hardRecovery_.load() ? 1 : 0);
}

bool MediaContext::startPlayback(const std::string& path, bool loop) {
    stopPlayback();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        path_ = path;
        loop_ = loop;
    }

    struct stat st {};
    if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) {
        LOGE("startPlayback invalid path=%s", path.c_str());
        status_ = 0;
        return false;
    }

    AMediaExtractor* extractor = AMediaExtractor_new();
    media_status_t ms = AMediaExtractor_setDataSource(extractor, path.c_str());
    if (ms != AMEDIA_OK) {
        LOGE("AMediaExtractor_setDataSource failed path=%s status=%d", path.c_str(), ms);
        AMediaExtractor_delete(extractor);
        status_ = 0;
        return false;
    }

    size_t trackCount = AMediaExtractor_getTrackCount(extractor);
    bool hasVideo = false;
    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor, i);
        if (!fmt) continue;
        const char* mime = nullptr;
        if (AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) && mime &&
            strncmp(mime, "video/", 6) == 0) {
            hasVideo = true;
            AMediaExtractor_selectTrack(extractor, i);
        }
        AMediaFormat_delete(fmt);
        if (hasVideo) break;
    }
    AMediaExtractor_delete(extractor);

    if (!hasVideo && mode_ != 2) {
        LOGE("no video track in %s", path.c_str());
        status_ = 0;
        return false;
    }

    running_.store(true);
    status_ = hasVideo ? 5 : 4;
    counters_.framesDecoded = 0;
    counters_.framesPresented = 0;
    counters_.queueDepth = 0;
    counters_.codecState = hasVideo ? 1 : 2;
    counters_.networkTicks = mode_ == 2 ? 1 : 0;

    decodeThread_ = std::thread([this]() { decodeLoop(); });
    LOGI("startPlayback ok path=%s loop=%d mode=%d", path.c_str(), loop ? 1 : 0, mode_);
    return true;
}

void MediaContext::stopPlayback() {
    running_.store(false);
    if (decodeThread_.joinable()) decodeThread_.join();
    std::lock_guard<std::mutex> lock(mutex_);
    counters_.queueDepth = 0;
    counters_.codecState = 0;
    status_ = 0;
}

int32_t MediaContext::playbackStatus() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return status_;
}

PollCounters MediaContext::pollCounters() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return counters_;
}

void MediaContext::decodeLoop() {
    LOGI("decodeLoop start path=%s", path_.c_str());
    while (running_.load()) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (mode_ == 2) {
                counters_.networkTicks++;
            } else {
                counters_.framesDecoded++;
                counters_.framesPresented++;
            }
            counters_.queueDepth = (counters_.framesDecoded - counters_.framesPresented) % 8;
            if (status_ == 5) counters_.codecState = 1;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(mode_ == 2 ? 500 : 33));
    }
    LOGI("decodeLoop stop");
}

}  // namespace icecam
