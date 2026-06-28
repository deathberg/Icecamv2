#pragma once

#include <android/binder_parcel.h>
#include <android/binder_status.h>

#include <cstdlib>
#include <string>

namespace icecam {

inline binder_status_t readParcelString(const AParcel* in, std::string* out) {
    struct Ctx {
        std::string* target;
    } ctx{out};

    auto allocator = +[](void* stringData, int32_t length, char** buffer) -> bool {
        auto* c = static_cast<Ctx*>(stringData);
        if (length < 0) {
            c->target->clear();
            return true;
        }
        c->target->assign(static_cast<size_t>(length), '\0');
        *buffer = c->target->data();
        return true;
    };

    return AParcel_readString(in, &ctx, allocator);
}

}  // namespace icecam
