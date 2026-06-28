#include "xor_decode.h"

#include <cstdint>

namespace icecam {

std::string decode_xor_blob(const uint8_t* blob, size_t len, uint8_t key_byte) {
    std::string out(len, '\0');
    uint8_t seed = 7;
    for (size_t i = 0; i < len; ++i) {
        const uint8_t b = static_cast<uint8_t>(seed ^ blob[i]);
        seed = static_cast<uint8_t>(seed + 0x1f);
        out[i] = static_cast<char>((static_cast<int>(i) + b - 0x11) ^ (key_byte + static_cast<int>(i)));
    }
    return out;
}

// GraphicBuffer hook targets (decoded from libvc.so .rodata with key bytes from init ctx).
const HookTarget* default_hook_targets(size_t* count) {
    static const HookTarget kTargets[] = {
        {"libui.so", "_ZN7android13GraphicBuffer4lockEjPPvPiS3_"},
        {"libui.so", "_ZN7android13GraphicBuffer9lockYCbCrEjP13android_ycbcr"},
        {"libui.so", "_ZN7android13GraphicBuffer6unlockEv"},
        {"libui.so", "_ZN7android13GraphicBuffer4fromEP19ANativeWindowBuffer"},
    };
    if (count) *count = sizeof(kTargets) / sizeof(kTargets[0]);
    return kTargets;
}

}  // namespace icecam
