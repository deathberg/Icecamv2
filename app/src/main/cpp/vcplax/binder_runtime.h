#pragma once

#include <android/binder_ibinder.h>
#include <android/binder_status.h>
#include <stdint.h>

namespace icecam {

bool binder_runtime_init();
binder_status_t binder_add_service(const char* name, AIBinder* binder);
void binder_start_thread_pool(uint32_t threads);
void binder_join_thread_pool();

}  // namespace icecam
