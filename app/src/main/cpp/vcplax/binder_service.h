#pragma once

#include "media_pipeline.h"

#include <cstdint>
#include <string>

namespace icecam {

class VliveBinderService {
public:
    static bool registerService(const std::string& name, MediaPipeline* pipeline);
    static int runThreadPool();
};

}  // namespace icecam
