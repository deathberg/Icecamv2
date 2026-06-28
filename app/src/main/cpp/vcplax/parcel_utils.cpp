#include "parcel_utils.h"

#include <stdlib.h>
#include <string.h>

namespace icecam::parcel {

namespace {

bool string_allocator(void* stringData, int32_t length, char** buffer) {
    auto* out = static_cast<std::string*>(stringData);
    if (length < 0) {
        out->clear();
        *buffer = nullptr;
        return true;
    }
    out->resize(static_cast<size_t>(length));
    *buffer = length > 0 ? &(*out)[0] : nullptr;
    return true;
}

}  // namespace

binder_status_t read_string(const AParcel* in, std::string* out) {
    if (!out) return STATUS_BAD_VALUE;
    out->clear();
    return AParcel_readString(in, out, string_allocator);
}

binder_status_t write_no_exception(AParcel* out) { return AParcel_writeInt32(out, 0); }

binder_status_t write_ok_int(AParcel* out, int32_t value) {
    binder_status_t st = write_no_exception(out);
    if (st != STATUS_OK) return st;
    return AParcel_writeInt32(out, value);
}

bool read_interface_token(const AParcel* in, const char* expected) {
    std::string token;
    if (read_string(in, &token) != STATUS_OK) return false;
    return token == expected;
}

}  // namespace icecam::parcel
