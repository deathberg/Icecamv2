#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace icecam {

// XOR deobfuscation recovered from libvc.so @ 0x774b0 (Ghidra).
std::string decode_xor_blob(const uint8_t* blob, size_t len, uint8_t key_byte);

// Default hook targets when runtime key bytes are unavailable (static RE).
struct HookTarget {
    const char* lib;
    const char* sym;
};

const HookTarget* default_hook_targets(size_t* count);

}  // namespace icecam
