#pragma once

#include <android/binder_ibinder.h>

#include "media_context.h"

namespace icecam {

AIBinder* create_vlive_binder_service(MediaContext* ctx);

}  // namespace icecam
