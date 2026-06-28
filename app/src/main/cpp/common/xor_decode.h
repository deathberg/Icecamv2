#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace icecam {

// Recovered from libvc.so @ 0x774b0 (Ghidra).
inline std::string decodeXorBlob(const uint8_t* blob, size_t len, uint8_t keyByte) {
    std::string out(len, '\0');
    uint8_t seed = 7;
    for (size_t i = 0; i < len; ++i) {
        const uint8_t b = static_cast<uint8_t>(seed ^ blob[i]);
        seed = static_cast<uint8_t>(seed + 0x1f);
        out[i] = static_cast<char>((static_cast<int>(i) + b - 0x11) ^ (keyByte + static_cast<int>(i)));
    }
    return out;
}

}  // namespace icecam
