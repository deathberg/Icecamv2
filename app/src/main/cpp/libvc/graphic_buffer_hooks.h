#pragma once

#include <cstdint>

namespace icecam {

void injectIntoLockedBuffer(void* base, uint32_t width, uint32_t height, int32_t* stride);
bool installGraphicBufferHooks();

}  // namespace icecam
