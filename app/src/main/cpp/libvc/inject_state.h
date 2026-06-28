#pragma once

#include <cstdint>

namespace icecam {

struct InjectState {
    int mode = 0;
    float pan_x = 0.f;
    float pan_y = 0.f;
    float zoom_x = 1.f;
    float zoom_y = 1.f;
    uint32_t color = 0;
    bool mirror = false;
    int angle = 0;
    bool auto_rotate = false;
};

InjectState& inject_state();

// Called from vcplax Binder TX24/TX16-19 handlers via dlsym when co-loaded.
extern "C" void icecam_update_transform(int mode, float pan_x, float pan_y, float zoom_x, float zoom_y,
                                        uint32_t color);
extern "C" void icecam_update_display(bool auto_rotate, bool loop, int angle, bool mirror);

}  // namespace icecam
