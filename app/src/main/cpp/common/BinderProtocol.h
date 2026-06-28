#pragma once

namespace icecam::binder {

constexpr const char* kDescriptor = "com.xiaomi.vlive.IMyBinderService";

constexpr uint32_t TX_PLAY_SOURCE = 11;
constexpr uint32_t TX_STATUS = 12;
constexpr uint32_t TX_POLL_STATE = 13;
constexpr uint32_t TX_SET_MODE = 14;
constexpr uint32_t TX_GET_STATUS = 15;
constexpr uint32_t TX_AUTO_ROTATE = 16;
constexpr uint32_t TX_LOOP = 17;
constexpr uint32_t TX_ANGLE = 18;
constexpr uint32_t TX_MIRROR = 19;
constexpr uint32_t TX_SEEK_RANGE = 22;
constexpr uint32_t TX_TRANSFORM = 24;
constexpr uint32_t TX_HARD_RECOVERY = 25;

constexpr int32_t kOkPlay = 1;
constexpr int32_t kOkSetMode = 4;

}  // namespace icecam::binder
