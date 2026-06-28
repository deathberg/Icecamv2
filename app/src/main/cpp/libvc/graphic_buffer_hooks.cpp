#include "graphic_buffer_hooks.h"

#include "log_util.h"

#include <atomic>
#include <cstdint>

namespace icecam {

namespace {
std::atomic<int32_t> g_injectedBlocks{0};
std::atomic<uint32_t> g_lastColor{0};
}  // namespace

GraphicBufferLockFn origGraphicBufferLock = nullptr;
GraphicBufferLockYCbCrFn origGraphicBufferLockYCbCr = nullptr;

void setInjectionColor(uint32_t rgb) {
    g_lastColor.store(rgb);
}

int injectedBlockCount() {
    return g_injectedBlocks.load();
}

int proxyGraphicBufferLock(void* thiz, uint32_t usage, void** vaddr, int* stride, int* format) {
    if (origGraphicBufferLock == nullptr) {
        return -1;
    }
    const int rc = origGraphicBufferLock(thiz, usage, vaddr, stride, format);
    if (rc == 0 && vaddr != nullptr && *vaddr != nullptr) {
        g_injectedBlocks.fetch_add(1);
    }
    return rc;
}

int proxyGraphicBufferLockYCbCr(void* thiz, uint32_t usage, void* rect, void* ycbcr) {
    if (origGraphicBufferLockYCbCr == nullptr) {
        return -1;
    }
    const int rc = origGraphicBufferLockYCbCr(thiz, usage, rect, ycbcr);
    if (rc == 0) {
        g_injectedBlocks.fetch_add(1);
    }
    return rc;
}

}  // namespace icecam
