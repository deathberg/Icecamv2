#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace icecam {

/** Minimal reader for Android Java Parcel payloads (post interface token). */
class JavaParcelReader {
public:
    JavaParcelReader(const void* data, size_t size);

    bool readInt32(int32_t* out);
    bool readInt64(int64_t* out);
    bool readFloat(float* out);
    bool readString(std::string* out);

private:
    const uint8_t* data_;
    size_t size_;
    size_t pos_ = 0;

    bool ensure(size_t bytes) const;
    bool readInplace(void* dst, size_t bytes);
};

}  // namespace icecam
