package dev.icecam.app;

import android.content.SharedPreferences;
import java.util.Locale;

/**
 * Native TX24 «三色» color correction — NOT geometry zoom/pan.
 * Wire: TX24(mode, x, y, intensity, diameter, colorArgb)
 */
public final class ColorCorrectionState {
    public int mode = 1;
    public float x = 0.55f;
    public float y = 0.38f;
    public float intensity = 1f;
    public float diameter = 1f;
    public int colorArgb = 0;

    public static ColorCorrectionState load(SharedPreferences p) {
        ColorCorrectionState s = new ColorCorrectionState();
        s.mode = p.getInt("PlayAutoColor_mode", 1);
        int mx = p.getInt("MonitorTargetX", 55);
        int my = p.getInt("MonitorTargetY", 380);
        s.x = p.getFloat("ColorX", mx / 100f);
        s.y = p.getFloat("ColorY", my / 100f);
        s.intensity = clamp(p.getFloat("ColorIntensity", p.getInt("Scale", 100) / 100f), 0.05f, 8f);
        s.diameter = clamp(p.getFloat("ColorDiameter", 1f), 0.05f, 8f);
        s.colorArgb = p.getInt("ColorArgb", 0);
        return s;
    }

    public void save(SharedPreferences p) {
        p.edit()
                .putInt("PlayAutoColor_mode", mode)
                .putFloat("ColorX", x)
                .putFloat("ColorY", y)
                .putFloat("ColorIntensity", intensity)
                .putFloat("ColorDiameter", diameter)
                .putInt("ColorArgb", colorArgb)
                .putInt("AutoColor_X", Math.round(x * 100))
                .putInt("AutoColor_Y", Math.round(y * 100))
                .apply();
    }

    public void adjustIntensity(float delta) {
        intensity = clamp(intensity + delta, 0.05f, 8f);
    }

    public void adjustDiameter(float delta) {
        diameter = clamp(diameter + delta, 0.05f, 8f);
    }

    public String summary() {
        return String.format(Locale.US, "color mode=%d xy=(%.2f,%.2f) int=%.2f dia=%.2f argb=0x%08X",
                mode, x, y, intensity, diameter, colorArgb);
    }

    private static float clamp(float v, float mn, float mx) {
        return Math.max(mn, Math.min(mx, v));
    }
}
