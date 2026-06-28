#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace icecam {

std::string decodeXorBlob(const uint8_t* blob, size_t len, uint8_t keyByte);

}  // namespace icecam
