#include "frame_inject.h"

#include <android/log.h>
#include <atomic>
#include <cstring>

#define VC_TAG "libvc"
#define VC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VC_TAG, __VA_ARGS__)

namespace icecam {

namespace {
std::atomic<int32_t> g_mode{0};
std::atomic<float> g_panX{0.f};
std::atomic<float> g_panY{0.f};
std::atomic<float> g_zoomX{1.f};
std::atomic<float> g_zoomY{1.f};
std::atomic<int32_t> g_color{0};
}  // namespace

FrameSlot& globalFrameSlot() {
    static FrameSlot slot;
    return slot;
}

void setInjectTransform(int32_t mode, float panX, float panY, float zoomX, float zoomY, int32_t color) {
    g_mode.store(mode, std::memory_order_relaxed);
    g_panX.store(panX, std::memory_order_relaxed);
    g_panY.store(panY, std::memory_order_relaxed);
    g_zoomX.store(zoomX, std::memory_order_relaxed);
    g_zoomY.store(zoomY, std::memory_order_relaxed);
    g_color.store(color, std::memory_order_relaxed);
}

static void applyColorBlocks(uint8_t* yPlane, uint32_t width, uint32_t height, uint32_t yStride, int32_t color) {
    if (color == 0 || width < 8 || height < 8) return;
    const int block = 16;
    const uint8_t fill = static_cast<uint8_t>((color >> 16) & 0xff);
    for (uint32_t by = 0; by < height; by += static_cast<uint32_t>(block)) {
        for (uint32_t bx = 0; bx < width; bx += static_cast<uint32_t>(block)) {
            if (((bx + by) / static_cast<uint32_t>(block)) % 3 != 0) continue;
            for (uint32_t y = 0; y < static_cast<uint32_t>(block) && by + y < height; ++y) {
                uint8_t* row = yPlane + (by + y) * yStride;
                memset(row + bx, fill, std::min<uint32_t>(block, width - bx));
            }
        }
    }
}

void injectIntoLockedBuffer(void* base, uint32_t width, uint32_t height, int32_t* stride) {
    if (base == nullptr || stride == nullptr || width == 0 || height == 0) return;
    const uint32_t yStride = static_cast<uint32_t>(*stride);
    auto& slot = globalFrameSlot();
    std::lock_guard<std::mutex> lock(slot.mutex);
    if (slot.hasFrame && slot.data != nullptr && slot.width == width && slot.height == height) {
        const size_t ySize = static_cast<size_t>(yStride) * height;
        const size_t uvSize = static_cast<size_t>(slot.uvStride) * (height / 2);
        if (slot.capacity >= ySize + uvSize) {
            memcpy(base, slot.data, ySize);
            memcpy(static_cast<uint8_t*>(base) + ySize, slot.data + ySize, uvSize);
        }
    }
    applyColorBlocks(static_cast<uint8_t*>(base), width, height, yStride, g_color.load(std::memory_order_relaxed));
}

}  // namespace icecam
