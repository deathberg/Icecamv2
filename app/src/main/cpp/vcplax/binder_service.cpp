#include "binder_service.h"

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <android/log.h>

#include <dlfcn.h>
#include <mutex>
#include <string>

#include "media_context.h"
#include "parcel_utils.h"

#define LOG_TAG "vcplax"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace icecam {

namespace {

static constexpr const char* kDescriptor = "com.xiaomi.vlive.IMyBinderService";

enum TxCode : uint32_t {
    TX_PLAY_SOURCE = 11,
    TX_STATUS = 12,
    TX_POLL_STATE = 13,
    TX_SET_MODE = 14,
    TX_GET_STATUS = 15,
    TX_AUTO_ROTATE = 16,
    TX_LOOP = 17,
    TX_ANGLE = 18,
    TX_MIRROR = 19,
    TX_SEEK_RANGE = 22,
    TX_TRANSFORM = 24,
    TX_HARD_RECOVERY = 25,
};

struct ServiceState {
    MediaContext* ctx;
};

using UpdateTransformFn = void (*)(int, float, float, float, float, uint32_t);
using UpdateDisplayFn = void (*)(bool, bool, int, bool);

void notify_libvc_transform(MediaContext* ctx) {
    void* handle = dlopen("libvc.so", RTLD_NOW | RTLD_NOLOAD);
    if (!handle) handle = dlopen("/data/libvc.so", RTLD_NOW);
    if (!handle) handle = dlopen("/data/camera/libvc.so", RTLD_NOW);
    if (!handle) return;
    auto fn = reinterpret_cast<UpdateTransformFn>(dlsym(handle, "icecam_update_transform"));
    if (fn) {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        fn(ctx->transform_mode, ctx->pan_x, ctx->pan_y, ctx->zoom_x, ctx->zoom_y, ctx->color);
    }
}

void notify_libvc_display(MediaContext* ctx) {
    void* handle = dlopen("libvc.so", RTLD_NOW | RTLD_NOLOAD);
    if (!handle) handle = dlopen("/data/libvc.so", RTLD_NOW);
    if (!handle) handle = dlopen("/data/camera/libvc.so", RTLD_NOW);
    if (!handle) return;
    auto fn = reinterpret_cast<UpdateDisplayFn>(dlsym(handle, "icecam_update_display"));
    if (fn) {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        fn(ctx->auto_rotate, ctx->loop, ctx->angle, ctx->mirror);
    }
}

binder_status_t handle_tx_play_source(ServiceState* state, const AParcel* in, AParcel* out) {
    std::string path;
    int32_t mirror_ignored = 0;
    int32_t loop = 0;
    binder_status_t st = parcel::read_string(in, &path);
    if (st != STATUS_OK) return st;
    st = AParcel_readInt32(in, &mirror_ignored);
    if (st != STATUS_OK) return st;
    st = AParcel_readInt32(in, &loop);
    if (st != STATUS_OK) return st;
    (void)mirror_ignored;

    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->loop = loop != 0;
    }
    if (path.empty()) return parcel::write_ok_int(out, 0);
    start_pipeline(ctx, path.c_str());
    maybe_restart_decode_thread(ctx);
    return parcel::write_ok_int(out, 1);
}

binder_status_t handle_tx_status(ServiceState* state, const AParcel* in, AParcel* out) {
    (void)in;
    (void)state;
    return parcel::write_ok_int(out, 1);
}

binder_status_t handle_tx_poll_state(ServiceState* state, AParcel* out) {
    MediaContext* ctx = state->ctx;
    PollCounters c;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        c = ctx->counters;
    }
    binder_status_t st = parcel::write_no_exception(out);
    if (st != STATUS_OK) return st;
    st = AParcel_writeInt32(out, c.frames_decoded);
    if (st != STATUS_OK) return st;
    st = AParcel_writeInt32(out, c.frames_injected);
    if (st != STATUS_OK) return st;
    st = AParcel_writeInt32(out, c.codec_errors);
    if (st != STATUS_OK) return st;
    st = AParcel_writeInt32(out, c.queue_depth);
    if (st != STATUS_OK) return st;
    return AParcel_writeInt32(out, c.network_state);
}

binder_status_t handle_tx_set_mode(ServiceState* state, const AParcel* in, AParcel* out) {
    int32_t mode = 0;
    std::string path;
    binder_status_t st = AParcel_readInt32(in, &mode);
    if (st != STATUS_OK) return st;
    st = parcel::read_string(in, &path);
    if (st != STATUS_OK) return st;

    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->mode = mode;
        ctx->path = path;
    }
    flush_media_pipeline(ctx);
    stop_pipeline(ctx);
    if (!path.empty()) start_pipeline(ctx, path.c_str());
    return parcel::write_ok_int(out, 4);
}

binder_status_t handle_tx_get_status(ServiceState* state, const AParcel* in, AParcel* out) {
    (void)in;
    MediaContext* ctx = state->ctx;
    int32_t status = 0;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        status = ctx->playing ? 5 : 0;
    }
    (void)state;
    return parcel::write_ok_int(out, status);
}

binder_status_t handle_tx_auto_rotate(ServiceState* state, const AParcel* in, AParcel* out) {
    int32_t v = 0;
    binder_status_t st = AParcel_readInt32(in, &v);
    if (st != STATUS_OK) return st;
    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->auto_rotate = v != 0;
    }
    notify_libvc_display(ctx);
    return parcel::write_ok_int(out, 1);
}

binder_status_t handle_tx_loop(ServiceState* state, const AParcel* in, AParcel* out) {
    int32_t v = 0;
    binder_status_t st = AParcel_readInt32(in, &v);
    if (st != STATUS_OK) return st;
    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->loop = v != 0;
    }
    notify_libvc_display(ctx);
    return parcel::write_ok_int(out, 1);
}

binder_status_t handle_tx_mirror(ServiceState* state, const AParcel* in, AParcel* out) {
    int32_t v = 0;
    binder_status_t st = AParcel_readInt32(in, &v);
    if (st != STATUS_OK) return st;
    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->mirror = v != 0;
        if (ctx->mirror) ctx->auto_rotate = false;
    }
    notify_libvc_display(ctx);
    return parcel::write_ok_int(out, 1);
}

binder_status_t handle_tx_angle(ServiceState* state, const AParcel* in, AParcel* out) {
    int32_t angle = 0;
    binder_status_t st = AParcel_readInt32(in, &angle);
    if (st != STATUS_OK) return st;
    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->angle = angle;
    }
    notify_libvc_display(ctx);
    return parcel::write_ok_int(out, 1);
}

binder_status_t handle_tx_seek_range(ServiceState* state, const AParcel* in, AParcel* out) {
    int64_t start = 0;
    int64_t end = 0;
    binder_status_t st = AParcel_readInt64(in, &start);
    if (st != STATUS_OK) return st;
    st = AParcel_readInt64(in, &end);
    if (st != STATUS_OK) return st;
    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->seek_start_us = start;
        ctx->seek_end_us = end;
    }
    (void)state;
    return parcel::write_ok_int(out, 1);
}

binder_status_t handle_tx_transform(ServiceState* state, const AParcel* in, AParcel* out) {
    int32_t mode = 0;
    float f0 = 0, f1 = 0, f2 = 0, f3 = 0;
    int32_t color = 0;
    binder_status_t st = AParcel_readInt32(in, &mode);
    if (st != STATUS_OK) return st;
    st = AParcel_readFloat(in, &f0);
    if (st != STATUS_OK) return st;
    st = AParcel_readFloat(in, &f1);
    if (st != STATUS_OK) return st;
    st = AParcel_readFloat(in, &f2);
    if (st != STATUS_OK) return st;
    st = AParcel_readFloat(in, &f3);
    if (st != STATUS_OK) return st;
    st = AParcel_readInt32(in, &color);
    if (st != STATUS_OK) return st;

    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->transform_mode = mode;
        ctx->pan_x = f0;
        ctx->pan_y = f1;
        ctx->zoom_x = f2;
        ctx->zoom_y = f3;
        ctx->color = static_cast<uint32_t>(color);
    }
    notify_libvc_transform(ctx);
    LOGI("TX24 mode=%d pan=(%.2f,%.2f) zoom=(%.2f,%.2f) color=0x%08X", mode, f0, f1, f2, f3, color);
    return parcel::write_ok_int(out, 1);
}

binder_status_t handle_tx_hard_recovery(ServiceState* state, const AParcel* in, AParcel* out) {
    (void)in;
    MediaContext* ctx = state->ctx;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->hard_recovery = !ctx->hard_recovery;
        if (ctx->hard_recovery) {
            flush_media_pipeline(ctx);
            if (!ctx->path.empty()) start_pipeline(ctx, ctx->path.c_str());
        } else {
            stop_pipeline(ctx);
        }
    }
    return parcel::write_ok_int(out, 1);
}

binder_status_t on_transact(AIBinder* binder, transaction_code_t code, const AParcel* in, AParcel* out) {
    ServiceState* state = static_cast<ServiceState*>(AIBinder_getUserData(binder));
    if (!state || !state->ctx) return STATUS_UNKNOWN_TRANSACTION;
    (void)in;  // interface token enforced by libbinder_ndk

    switch (code) {
        case TX_PLAY_SOURCE:
            return handle_tx_play_source(state, in, out);
        case TX_STATUS:
            return handle_tx_status(state, in, out);
        case TX_POLL_STATE:
            return handle_tx_poll_state(state, out);
        case TX_SET_MODE:
            return handle_tx_set_mode(state, in, out);
        case TX_GET_STATUS:
            return handle_tx_get_status(state, in, out);
        case TX_AUTO_ROTATE:
            return handle_tx_auto_rotate(state, in, out);
        case TX_LOOP:
            return handle_tx_loop(state, in, out);
        case TX_ANGLE:
            return handle_tx_angle(state, in, out);
        case TX_MIRROR:
            return handle_tx_mirror(state, in, out);
        case TX_SEEK_RANGE:
            return handle_tx_seek_range(state, in, out);
        case TX_TRANSFORM:
            return handle_tx_transform(state, in, out);
        case TX_HARD_RECOVERY:
            return handle_tx_hard_recovery(state, in, out);
        default:
            return STATUS_UNKNOWN_TRANSACTION;
    }
}

void* on_create(void* args) { return args; }

void on_destroy(void* userData) {
    delete static_cast<ServiceState*>(userData);
}

}  // namespace

AIBinder* create_vlive_binder_service(MediaContext* ctx) {
    static AIBinder_Class* clazz = nullptr;
    if (!clazz) {
        clazz = AIBinder_Class_define(kDescriptor, on_create, on_destroy, on_transact);
    }
    auto* state = new ServiceState{ctx};
    return AIBinder_new(clazz, state);
}

}  // namespace icecam
