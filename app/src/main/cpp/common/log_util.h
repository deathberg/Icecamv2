#pragma once

#include <android/log.h>

#define ICECAM_LOG_TAG "Icecamv2"

#define ICE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ICECAM_LOG_TAG, __VA_ARGS__)
#define ICE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, ICECAM_LOG_TAG, __VA_ARGS__)
#define ICE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ICECAM_LOG_TAG, __VA_ARGS__)
