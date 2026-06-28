#pragma once

#include <android/binder_parcel.h>
#include <android/binder_status.h>

#include <string>

namespace icecam::parcel {

binder_status_t read_string(const AParcel* in, std::string* out);
binder_status_t write_no_exception(AParcel* out);
binder_status_t write_ok_int(AParcel* out, int32_t value);
bool read_interface_token(const AParcel* in, const char* expected);

}  // namespace icecam::parcel
