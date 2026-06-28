#pragma once

#ifdef __cplusplus
extern "C" {
#endif

void ABinderProcess_setThreadPoolMaxThreadCount(uint32_t numThreads) __INTRODUCED_IN(29);
bool ABinderProcess_isThreadPoolStarted(void) __INTRODUCED_IN(29);
void ABinderProcess_startThreadPool(void) __INTRODUCED_IN(29);
void ABinderProcess_joinThreadPool(void) __INTRODUCED_IN(29);

#ifdef __cplusplus
}
#endif
