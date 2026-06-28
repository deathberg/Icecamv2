#include "binder_service.h"
#include "MediaContext.h"
#include "BinderProtocol.h"
#include "parcel_helpers.h"

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>

#include <android/log.h>
#include <string>

#define LOG_TAG "vcplax"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace icecam {

namespace {

using namespace icecam::binder;

AIBinder_Class* g_class = nullptr;

binder_status_t writeOkInt(AParcel* out, int32_t value) {
    binder_status_t st = AParcel_writeInt32(out, 0);
    if (st != STATUS_OK) return st;
    return AParcel_writeInt32(out, value);
}

binder_status_t writeOkInts(AParcel* out, const int32_t* values, size_t count) {
    binder_status_t st = AParcel_writeInt32(out, 0);
    if (st != STATUS_OK) return st;
    for (size_t i = 0; i < count; ++i) {
        st = AParcel_writeInt32(out, values[i]);
        if (st != STATUS_OK) return st;
    }
    return STATUS_OK;
}

binder_status_t onTransact(AIBinder* /*binder*/, transaction_code_t code, const AParcel* in,
                           AParcel* out) {
    auto& ctx = MediaContext::instance();
    binder_status_t st = STATUS_OK;

    switch (static_cast<uint32_t>(code)) {
        case TX_PLAY_SOURCE: {
            std::string path;
            int32_t mirrorIgnored = 0;
            int32_t loop = 0;
            st = readParcelString(in, &path);
            if (st != STATUS_OK) return st;
            st = AParcel_readInt32(in, &mirrorIgnored);
            if (st != STATUS_OK) return st;
            st = AParcel_readInt32(in, &loop);
            if (st != STATUS_OK) return st;
            (void)mirrorIgnored;

            if (!ctx.hooksReady()) return writeOkInt(out, 0);

            const bool ok = ctx.startPlayback(path, loop != 0);
            return writeOkInt(out, ok ? kOkPlay : 0);
        }
        case TX_STATUS:
            return writeOkInt(out, ctx.playbackStatus());
        case TX_POLL_STATE: {
            const PollCounters c = ctx.pollCounters();
            const int32_t vals[5] = {c.framesDecoded, c.framesPresented, c.queueDepth, c.codecState,
                                     c.networkTicks};
            return writeOkInts(out, vals, 5);
        }
        case TX_SET_MODE: {
            int32_t mode = 0;
            std::string path;
            st = AParcel_readInt32(in, &mode);
            if (st != STATUS_OK) return st;
            st = readParcelString(in, &path);
            if (st != STATUS_OK) return st;
            ctx.setMode(mode, path);
            ctx.stopPlayback();
            ctx.startPlayback(path, true);
            return writeOkInt(out, kOkSetMode);
        }
        case TX_GET_STATUS:
            ctx.setSeekRangeUs(0, -1);
            return writeOkInt(out, ctx.playbackStatus());
        case TX_AUTO_ROTATE: {
            int32_t v = 0;
            AParcel_readInt32(in, &v);
            ctx.setAutoRotate(v != 0);
            return writeOkInt(out, 1);
        }
        case TX_LOOP: {
            int32_t v = 0;
            AParcel_readInt32(in, &v);
            ctx.setLoop(v != 0);
            return writeOkInt(out, 1);
        }
        case TX_ANGLE: {
            int32_t angle = 0;
            AParcel_readInt32(in, &angle);
            ctx.setAngle(angle);
            return writeOkInt(out, 1);
        }
        case TX_MIRROR: {
            int32_t v = 0;
            AParcel_readInt32(in, &v);
            ctx.setMirror(v != 0);
            return writeOkInt(out, 1);
        }
        case TX_SEEK_RANGE: {
            int64_t startUs = 0;
            int64_t endUs = 0;
            AParcel_readInt64(in, &startUs);
            AParcel_readInt64(in, &endUs);
            ctx.setSeekRangeUs(startUs, endUs);
            return writeOkInt(out, 1);
        }
        case TX_TRANSFORM: {
            TransformParams p{};
            AParcel_readInt32(in, &p.mode);
            AParcel_readFloat(in, &p.panX);
            AParcel_readFloat(in, &p.panY);
            AParcel_readFloat(in, &p.zoomX);
            AParcel_readFloat(in, &p.zoomY);
            AParcel_readInt32(in, &p.colorFlags);
            ctx.setTransform(p);
            return writeOkInt(out, 1);
        }
        case TX_HARD_RECOVERY:
            ctx.toggleHardRecovery();
            return writeOkInt(out, 1);
        default:
            return STATUS_UNKNOWN_TRANSACTION;
    }
}

void* onCreate(void* /*args*/) { return nullptr; }

void onDestroy(void* /*userdata*/) {}

}  // namespace

AIBinder* createVliveService() {
    if (!g_class) {
        g_class = AIBinder_Class_define(kDescriptor, onCreate, onDestroy, onTransact);
        if (!g_class) {
            LOGE("AIBinder_Class_define failed");
            return nullptr;
        }
    }
    AIBinder* binder = AIBinder_new(g_class, nullptr);
    if (!binder) return nullptr;
    AIBinder_incStrong(binder);
    LOGI("VliveBinder service created");
    return binder;
}

void markLibvcReady() { MediaContext::instance().setHooksReady(true); }

}  // namespace icecam
