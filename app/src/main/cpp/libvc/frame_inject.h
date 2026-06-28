#pragma once

#include <cstdint>
#include <mutex>

namespace icecam {

struct FrameSlot {
    std::mutex mutex;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t yStride = 0;
    uint32_t uvStride = 0;
    uint64_t timestampUs = 0;
    bool hasFrame = false;
    uint8_t* data = nullptr;
    size_t capacity = 0;
};

FrameSlot& globalFrameSlot();
void setInjectTransform(int32_t mode, float panX, float panY, float zoomX, float zoomY, int32_t color);

}  // namespace icecam
