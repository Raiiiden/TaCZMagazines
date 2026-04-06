package com.raiiiden.taczmagazines.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.raiiiden.taczmagazines.TaCZMagazines;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Renders an EFT-style circular progress spinner around the cursor while
 * a tick-based magazine loading/unloading session is active.
 *
 * Visual:
 *   – Gray semi-transparent full ring (background)
 *   – Bright arc (orange for loading, cyan for unloading) that grows clockwise
 *     from the top as each bullet-interval elapses.
 */
@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, value = Dist.CLIENT)
public class MagazineLoadingOverlay {

    private static final int SEGMENTS   = 64;
    private static final float OUTER_R  = 6.5f;
    private static final float INNER_R  = 4.0f;

    // Colors (ARGB)
    private static final int COLOR_BACKGROUND = 0x55AAAAAA; // semi-transparent gray
    private static final int COLOR_LOAD        = 0xFFFFFFFF; // white
    private static final int COLOR_UNLOAD      = 0xFFFFFFFF; // white

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!MagazineLoadingHandler.isActive()) return;

        float progress  = MagazineLoadingHandler.progress;
        boolean unload  = MagazineLoadingHandler.isUnloading();
        int fgColor     = unload ? COLOR_UNLOAD : COLOR_LOAD;

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        // The carried item renders centered on the cursor; offset the ring slightly
        float cx = (float) mouseX;
        float cy = (float) mouseY;

        Matrix4f matrix = event.getGuiGraphics().pose().last().pose();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        // Full ring (background) — always draw the complete circle
        drawArc(matrix, cx, cy, OUTER_R, INNER_R, 0f, Mth.TWO_PI, COLOR_BACKGROUND);

        // Progress arc — grows clockwise from the top as progress approaches 1.0
        if (progress > 0f) {
            float endAngle = Mth.TWO_PI * Math.min(progress, 1f);
            // Start from -π/2 (top) and go clockwise (positive direction in screen coords)
            drawArc(matrix, cx, cy, OUTER_R, INNER_R, -Mth.HALF_PI, -Mth.HALF_PI + endAngle, fgColor);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /**
     * Draws a ring sector from {@code startAngle} to {@code endAngle} (radians)
     * using a TRIANGLE_STRIP between the outer and inner radii.
     */
    private static void drawArc(Matrix4f matrix, float cx, float cy,
                                 float outerR, float innerR,
                                 float startAngle, float endAngle,
                                 int argb) {
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >>  8) & 0xFF) / 255f;
        float b = ( argb        & 0xFF) / 255f;

        float span = endAngle - startAngle;
        if (Math.abs(span) < 0.001f) return;

        Tesselator  tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i <= SEGMENTS; i++) {
            float t     = (float) i / SEGMENTS;
            float angle = startAngle + t * span;
            float cos   = Mth.cos(angle);
            float sin   = Mth.sin(angle);

            bb.vertex(matrix, cx + outerR * cos, cy + outerR * sin, 0f)
              .color(r, g, b, a).endVertex();
            bb.vertex(matrix, cx + innerR * cos, cy + innerR * sin, 0f)
              .color(r, g, b, a).endVertex();
        }

        tess.end();
    }
}
