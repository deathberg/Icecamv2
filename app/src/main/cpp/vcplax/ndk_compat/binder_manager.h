#pragma once

#include <android/binder_ibinder.h>
#include <android/binder_status.h>

#ifdef __cplusplus
extern "C" {
#endif

binder_status_t AServiceManager_addService(const char* instance, AIBinder* binder) __INTRODUCED_IN(29);
AIBinder* AServiceManager_checkService(const char* instance) __INTRODUCED_IN(29);
AIBinder* AServiceManager_getService(const char* instance) __INTRODUCED_IN(29);

#ifdef __cplusplus
}
#endif
