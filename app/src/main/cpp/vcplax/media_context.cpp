#include "media_context.h"

#include <android/log.h>

#include <chrono>
#include <cstring>

#define LOG_TAG "vcplax"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace icecam {

namespace {

void decode_loop(MediaContext* ctx) {
    while (ctx->playing) {
        {
            std::lock_guard<std::mutex> lock(ctx->mutex);
            ctx->counters.frames_decoded++;
            ctx->counters.frames_injected++;
            ctx->counters.queue_depth = ctx->playing ? 1 : 0;
            if (ctx->mode == 2) ctx->counters.network_state = 1;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(33));
    }
}

}  // namespace

MediaContext& global_media_context() {
    static MediaContext ctx;
    return ctx;
}

void flush_media_pipeline(MediaContext* ctx) {
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    ctx->counters.queue_depth = 0;
    LOGI("pipeline flushed path=%s mode=%d", ctx->path.c_str(), ctx->mode);
}

void stop_pipeline(MediaContext* ctx) {
    if (!ctx) return;
    ctx->playing = false;
    if (ctx->decode_thread.joinable()) ctx->decode_thread.join();
    flush_media_pipeline(ctx);
}

void start_pipeline(MediaContext* ctx, const char* uri) {
    if (!ctx || !uri) return;
    stop_pipeline(ctx);
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->path = uri;
        ctx->playing = true;
        ctx->counters.frames_decoded = 0;
        ctx->counters.frames_injected = 0;
        ctx->counters.codec_errors = 0;
        ctx->counters.queue_depth = 0;
        if (ctx->mode == 2) ctx->counters.network_state = 1;
        else ctx->counters.network_state = 0;
    }
    LOGI("start_pipeline mode=%d uri=%s", ctx->mode, uri);
    ctx->decode_thread = std::thread(decode_loop, ctx);
}

void maybe_restart_decode_thread(MediaContext* ctx) {
    if (!ctx) return;
    if (ctx->playing && !ctx->decode_thread.joinable()) {
        ctx->decode_thread = std::thread(decode_loop, ctx);
    }
}

}  // namespace icecam
