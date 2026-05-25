package com.raiiiden.taczmagazines.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.raiiiden.taczmagazines.TaCZMagazines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

// Renders the tick-based progress arc centered on the active hotbar magazine slot
// when an in-hand loading/unloading session is active.
@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, value = Dist.CLIENT)
public class InHandLoadingOverlay {

    private static final int   SEGMENTS  = 64;
    private static final float OUTER_R   = 7.0f;
    private static final float INNER_R   = 4.5f;
    private static final int   COLOR_BG  = 0x55AAAAAA; // semi-transparent gray ring
    private static final int   COLOR_FG  = 0xFFFFFFFF; // white progress arc

    @SubscribeEvent
    public static void onHotbarRender(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!MagazineLoadingHandler.isInHandActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int selected = mc.player.getInventory().selected;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // Hotbar slot icon centre:
        //   Left edge of hotbar  = sw/2 - 91
        //   Item icon left       = +1 (border) + slot*20 + 2 (inset) = hotbar+3 + slot*20
        //   Item centre X        = +8  →  sw/2 - 91 + 3 + slot*20 + 8 = sw/2 - 80 + slot*20
        //   Item top             = hotbar top (sh-22) + 3  →  centre Y = sh - 22 + 3 + 8 = sh - 11
        float cx = sw / 2f - 80f + selected * 20;
        float cy = sh - 11f;

        float progress = MagazineLoadingHandler.inHandProgress;
        Matrix4f matrix = event.getGuiGraphics().pose().last().pose();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        // Background ring
        drawArc(matrix, cx, cy, OUTER_R, INNER_R, 0f, Mth.TWO_PI, COLOR_BG);

        // Progress arc — grows clockwise from top
        if (progress > 0f) {
            float end = Mth.TWO_PI * Math.min(progress, 1f);
            drawArc(matrix, cx, cy, OUTER_R, INNER_R, -Mth.HALF_PI, -Mth.HALF_PI + end, COLOR_FG);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void drawArc(Matrix4f matrix, float cx, float cy,
                                 float outerR, float innerR,
                                 float startAngle, float endAngle, int argb) {
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
