#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

namespace icecam {

struct PollCounters {
    int32_t frames_decoded = 0;
    int32_t frames_injected = 0;
    int32_t codec_errors = 0;
    int32_t queue_depth = 0;
    int32_t network_state = 0;
};

struct MediaContext {
    int mode = 0;
    std::string path;
    bool loop = true;
    bool auto_rotate = false;
    bool mirror = false;
    int angle = 0;
    int64_t seek_start_us = 0;
    int64_t seek_end_us = -1;
    int transform_mode = 0;
    float pan_x = 0.f;
    float pan_y = 0.f;
    float zoom_x = 1.f;
    float zoom_y = 1.f;
    uint32_t color = 0;
    bool hard_recovery = false;
    bool playing = false;
    PollCounters counters;
    std::mutex mutex;
    std::thread decode_thread;
};

MediaContext& global_media_context();

void flush_media_pipeline(MediaContext* ctx);
void start_pipeline(MediaContext* ctx, const char* uri);
void stop_pipeline(MediaContext* ctx);
void maybe_restart_decode_thread(MediaContext* ctx);

}  // namespace icecam
