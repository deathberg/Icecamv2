#include "java_parcel_reader.h"

#include <cstring>

namespace icecam {

JavaParcelReader::JavaParcelReader(const void* data, size_t size)
    : data_(static_cast<const uint8_t*>(data)), size_(size) {}

bool JavaParcelReader::ensure(size_t bytes) const {
    return pos_ + bytes <= size_;
}

bool JavaParcelReader::readInplace(void* dst, size_t bytes) {
    if (!ensure(bytes)) {
        return false;
    }
    std::memcpy(dst, data_ + pos_, bytes);
    pos_ += bytes;
    return true;
}

bool JavaParcelReader::readInt32(int32_t* out) {
    return readInplace(out, sizeof(int32_t));
}

bool JavaParcelReader::readInt64(int64_t* out) {
    return readInplace(out, sizeof(int64_t));
}

bool JavaParcelReader::readFloat(float* out) {
    return readInplace(out, sizeof(float));
}

bool JavaParcelReader::readString(std::string* out) {
    int32_t len = 0;
    if (!readInt32(&len)) {
        return false;
    }
    if (len < 0) {
        out->clear();
        return true;
    }
    if (len == 0) {
        out->clear();
        return true;
    }
    const size_t byteLen = static_cast<size_t>(len) * sizeof(uint16_t);
    if (!ensure(byteLen)) {
        return false;
    }
    out->clear();
    out->reserve(static_cast<size_t>(len));
    for (int32_t i = 0; i < len; ++i) {
        uint16_t ch = 0;
        if (!readInplace(&ch, sizeof(uint16_t))) {
            return false;
        }
        if (ch == 0) {
            break;
        }
        if (ch < 0x80) {
            out->push_back(static_cast<char>(ch));
        } else if (ch < 0x800) {
            out->push_back(static_cast<char>(0xC0 | (ch >> 6)));
            out->push_back(static_cast<char>(0x80 | (ch & 0x3F)));
        } else {
            out->push_back(static_cast<char>(0xE0 | (ch >> 12)));
            out->push_back(static_cast<char>(0x80 | ((ch >> 6) & 0x3F)));
            out->push_back(static_cast<char>(0x80 | (ch & 0x3F)));
        }
    }
    return true;
}

}  // namespace icecam
