package com.raiiiden.taczmagazines.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

// Ready Or Not-style magazine HUD, enabled by {override_ammo_hud}.

@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, value = Dist.CLIENT)
public class MagazineAmmoHudOverlay {

    private static final ResourceLocation MAG_HUD_ATLAS =
            new ResourceLocation(TaCZMagazines.MODID, "textures/gui/magazine_hud.png");

    private static final int ATLAS_W = 550;
    private static final int ATLAS_H = 730;

    // ── limits ────────────────────────────────────────────────────────────────
    private static final int MAX_RESERVE_SLOTS = 6;

    private static final int MAG_CELL_W = 43;
    private static final int MAG_CELL_H = 45;
    private static final int MAG_COLUMNS = 11;
    private static final int MAG_ROWS = 4;
    private static final int MAG_CELL_INSET = 1;

    private static final int LOADED_U = 0;
    private static final int LOADED_V = 0;
    private static final int LOADED_FRAMES = 44;

    private static final int RESERVE_U = 0;
    private static final int RESERVE_V = 180;
    private static final int RESERVE_FRAMES = 44;

    // ── reserve silhouette dims ───────────────────────────────────────────────
    private static final int RES_W   = 17;
    private static final int RES_H   = 18;
    private static final int RES_GAP = 3;   // gap between reserve silhouettes

    // ── loaded silhouette dims ────────────────────────────────────────────────
    private static final int LOAD_W = 23;
    private static final int LOAD_H = 24;
    private static final int LOAD_OFFSET_X = -5;
    private static final int LOAD_OFFSET_Y = -5;

    private static final float HUD_SCALE = 1.1f;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (!MechanicsConfig.OVERRIDE_AMMO_HUD.get()) return;
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof IGun iGun)) return;

        ResourceLocation gunId = iGun.getGunId(gun);
        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) return;
        if (!gunIndex.getGunData().getReloadData().getType().equals(FeedType.MAGAZINE)) return;
        if (MagazineFamilySystem.getFamilyForGun(gunId) == null) return;

        // ── loaded-mag data ────────────────────────────────────────────────
        int currentAmmo = iGun.getCurrentAmmoCount(gun);
        int maxAmmo     = resolveGunMaxAmmo(gun, gunIndex);

        // ── reserve mags ───────────────────────────────────────────────────
        List<MagEntry> reserves = collectReserves(player, gun);

        // ── layout ────────────────────────────────────────────────────────
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        // TaCZ draws its gun-icon at (W-117, H-44), size 39×13.
        // Icon centre y ≈ H-38.  Both rows of silhouettes are centred on that.
        int centerY = H - 38;

        int resW = scaled(RES_W);
        int resH = scaled(RES_H);
        int resGap = scaled(RES_GAP);
        int loadW = scaled(LOAD_W);
        int loadH = scaled(LOAD_H);

        int loadedLeft = W - 63 + LOAD_OFFSET_X;          // right edge lands near W-63
        int loadedTop  = centerY - loadH / 2 + LOAD_OFFSET_Y;

        boolean overflow  = reserves.size() > MAX_RESERVE_SLOTS;
        int displayCount  = Math.min(reserves.size(), MAX_RESERVE_SLOTS);
        int totalReserveW = displayCount * resW + Math.max(0, displayCount - 1) * resGap;
        int reserveRight  = W - 117 - 3;             // 3 px gap from gun-icon left edge
        int reserveStart  = reserveRight - totalReserveW;
        int reserveTop    = centerY - resH / 2;

        GuiGraphics gfx = event.getGuiGraphics();
        Font font = mc.font;

        for (int i = 0; i < displayCount; i++) {
            MagEntry e = reserves.get(i);
            int x = reserveStart + i * (resW + resGap);
            renderMagazineFrame(gfx, x, reserveTop, resW, resH, e.ammo, e.max, RESERVE_U, RESERVE_V, RESERVE_FRAMES);
        }

        if (overflow) {
            int extra  = reserves.size() - MAX_RESERVE_SLOTS;
            String lbl = "+" + extra;
            gfx.drawString(font, lbl,
                    reserveStart - font.width(lbl) - 3,
                    reserveTop + (resH - font.lineHeight) / 2,
                    0xFF888888, false);
        }

        // ── loaded silhouette (larger, brighter border) ────────────────────
        renderMagazineFrame(gfx, loadedLeft, loadedTop, loadW, loadH, currentAmmo, maxAmmo, LOADED_U, LOADED_V, LOADED_FRAMES);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void renderMagazineFrame(
            GuiGraphics gfx, int x, int y, int drawW, int drawH,
            int ammo, int max, int baseU, int baseV, int frames) {

        int frame = frameForAmmo(ammo, max, frames);
        int index = frame - 1;
        int col = index / MAG_ROWS;
        int row = index % MAG_ROWS;
        int u = baseU + col * MAG_CELL_W + MAG_CELL_INSET;
        int v = baseV + row * MAG_CELL_H + MAG_CELL_INSET;
        int sourceW = MAG_CELL_W - MAG_CELL_INSET * 2;
        int sourceH = MAG_CELL_H - MAG_CELL_INSET * 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        gfx.blit(MAG_HUD_ATLAS, x, y, drawW, drawH,
                (float) u, (float) v, sourceW, sourceH, ATLAS_W, ATLAS_H);
        RenderSystem.disableBlend();
    }

    private static int frameForAmmo(int ammo, int max, int frames) {
        if (max <= 0 || ammo <= 0) return frames;
        float ratio = Math.min(1f, (float) ammo / max);
        int frame = 1 + (int) Math.floor((1f - ratio) * frames);
        return Math.max(1, Math.min(frames, frame));
    }

    private static int scaled(int value) {
        return Math.round(value * HUD_SCALE);
    }

    private static int resolveGunMaxAmmo(ItemStack gun, CommonGunIndex index) {
        return AttachmentDataUtils.getAmmoCountWithAttachment(gun, index.getGunData());
    }

    private static List<MagEntry> collectReserves(LocalPlayer player, ItemStack gun) {
        List<MagEntry> result = new ArrayList<>();
        player.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(cap -> {
            for (int i = 0; i < cap.getSlots(); i++) {
                ItemStack stack = cap.getStackInSlot(i);
                if (!(stack.getItem() instanceof MagazineItem magItem)) continue;
                if (!magItem.isAmmoBoxOfGun(gun, stack)) continue;
                int ammo = magItem.getAmmoCount(stack);
                int max  = MagazineItem.getMaxCapacity(stack);
                // Each physical magazine in a stack gets its own silhouette.
                for (int j = 0; j < stack.getCount(); j++) {
                    result.add(new MagEntry(ammo, max));
                }
            }
        });
        // Most-full first so the fullest mags are on the left (furthest from the action).
        result.sort((a, b) -> Float.compare(b.fillRatio(), a.fillRatio()));
        return result;
    }

    // ── data ──────────────────────────────────────────────────────────────────

    private record MagEntry(int ammo, int max) {
        float fillRatio() { return max > 0 ? (float) ammo / max : 0f; }
    }
}
