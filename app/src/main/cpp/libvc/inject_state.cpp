#include "inject_state.h"

namespace icecam {

InjectState& inject_state() {
    static InjectState state;
    return state;
}

extern "C" void icecam_update_transform(int mode, float pan_x, float pan_y, float zoom_x, float zoom_y,
                                        uint32_t color) {
    auto& s = inject_state();
    s.mode = mode;
    s.pan_x = pan_x;
    s.pan_y = pan_y;
    s.zoom_x = zoom_x;
    s.zoom_y = zoom_y;
    s.color = color;
}

extern "C" void icecam_update_display(bool auto_rotate, bool loop, int angle, bool mirror) {
    auto& s = inject_state();
    s.auto_rotate = auto_rotate;
    s.angle = angle;
    s.mirror = mirror;
    (void)loop;
}

}  // namespace icecam
