package com.raiiiden.taczmagazines.client;

import net.minecraft.client.Minecraft;

// Compensates overlay rendering for the player's GUI scale.
//
// Our overlays draw in Minecraft's GUI-scaled coordinate space, so they normally grow/shrink
// with the GUI scale setting like the vanilla HUD. Their sizes were tuned to look right at
// GUI scale 3, so we treat that as the reference and scale rendering by REFERENCE / guiScale
// to keep a constant on-screen size across GUI scale settings.
public final class HudScaleUtil {

    public static final double REFERENCE_GUI_SCALE = 3.0;

    private HudScaleUtil() {}

    // Full lock: render at the scale-3 size at every GUI scale (grows below 3, shrinks above 3).
    public static float lockFactor() {
        return (float) (REFERENCE_GUI_SCALE / currentGuiScale());
    }

    // Clamp: only shrink when the GUI scale is above the reference; leave 3 and below untouched.
    public static float clampAboveFactor() {
        double guiScale = currentGuiScale();
        if (guiScale <= REFERENCE_GUI_SCALE) return 1f;
        return (float) (REFERENCE_GUI_SCALE / guiScale);
    }

    private static double currentGuiScale() {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return guiScale <= 0 ? 1.0 : guiScale;
    }
}