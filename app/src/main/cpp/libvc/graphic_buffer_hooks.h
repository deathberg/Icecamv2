#pragma once

#include <cstdint>

namespace icecam {

using GraphicBufferLockFn = int (*)(void* thiz, uint32_t usage, void** vaddr, int* stride, int* format);
using GraphicBufferLockYCbCrFn = int (*)(void* thiz, uint32_t usage, void* rect, void* ycbcr);

extern GraphicBufferLockFn origGraphicBufferLock;
extern GraphicBufferLockYCbCrFn origGraphicBufferLockYCbCr;

int proxyGraphicBufferLock(void* thiz, uint32_t usage, void** vaddr, int* stride, int* format);
int proxyGraphicBufferLockYCbCr(void* thiz, uint32_t usage, void* rect, void* ycbcr);

void setInjectionColor(uint32_t rgb);
int injectedBlockCount();

}  // namespace icecam
