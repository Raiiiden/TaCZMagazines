package com.raiiiden.taczmagazines.client;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
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

    // ── limits ────────────────────────────────────────────────────────────────
    private static final int MAX_RESERVE_SLOTS = 6;

    // ── reserve silhouette dims ───────────────────────────────────────────────
    private static final int RES_W   = 8;
    private static final int RES_H   = 18;
    private static final int RES_GAP = 3;   // gap between reserve silhouettes

    // ── loaded silhouette dims ────────────────────────────────────────────────
    private static final int LOAD_W = 11;  // a bit wider than reserves
    private static final int LOAD_H = 24;  // a bit taller than reserves

    private static final int COLOR_FULL        = 0xFFE8E8E8;   // near-white (>66 %)
    private static final int COLOR_HALF        = 0xFFCC9900;   // amber     (25-66 %)
    private static final int COLOR_LOW         = 0xFFBB2200;   // red       (<25 %)
    private static final int COLOR_EMPTY_BG    = 0xFF1E1E1E;   // dark slot bg
    private static final int COLOR_RES_BORDER  = 0xFF777777;   // reserve outline
    private static final int COLOR_LOAD_BORDER = 0xFFCCCCCC;   // loaded outline (brighter)

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
        int maxAmmo     = resolveMaxAmmo(gun, gunIndex);

        // ── reserve mags ───────────────────────────────────────────────────
        List<MagEntry> reserves = collectReserves(player, gun);

        // ── layout ────────────────────────────────────────────────────────
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        // TaCZ draws its gun-icon at (W-117, H-44), size 39×13.
        // Icon centre y ≈ H-38.  Both rows of silhouettes are centred on that.
        int centerY = H - 38;

        int loadedLeft = W - 63;          // right edge lands at W-63
        int loadedTop  = centerY - LOAD_H / 2;

        boolean overflow  = reserves.size() > MAX_RESERVE_SLOTS;
        int displayCount  = Math.min(reserves.size(), MAX_RESERVE_SLOTS);
        int totalReserveW = displayCount * RES_W + Math.max(0, displayCount - 1) * RES_GAP;
        int reserveRight  = W - 117 - 3;             // 3 px gap from gun-icon left edge
        int reserveStart  = reserveRight - totalReserveW;
        int reserveTop    = centerY - RES_H / 2;

        GuiGraphics gfx = event.getGuiGraphics();
        Font font = mc.font;

        for (int i = 0; i < displayCount; i++) {
            MagEntry e = reserves.get(i);
            int x = reserveStart + i * (RES_W + RES_GAP);
            renderSilhouette(gfx, x, reserveTop, RES_W, RES_H, e.ammo, e.max, COLOR_RES_BORDER);
        }

        if (overflow) {
            int extra  = reserves.size() - MAX_RESERVE_SLOTS;
            String lbl = "+" + extra;
            gfx.drawString(font, lbl,
                    reserveStart - font.width(lbl) - 3,
                    reserveTop + (RES_H - font.lineHeight) / 2,
                    0xFF888888, false);
        }

        // ── loaded silhouette (larger, brighter border) ────────────────────
        renderSilhouette(gfx, loadedLeft, loadedTop, LOAD_W, LOAD_H, currentAmmo, maxAmmo, COLOR_LOAD_BORDER);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void renderSilhouette(
            GuiGraphics gfx, int x, int y, int w, int h,
            int ammo, int max, int borderColor) {

        float ratio   = (max > 0) ? Math.min(1f, (float) ammo / max) : 0f;
        int   fillPx  = Math.round(ratio * h);
        int   emptyPx = h - fillPx;

        // Empty (top) region
        gfx.fill(x, y, x + w, y + emptyPx, COLOR_EMPTY_BG);

        // Filled (bottom) region
        if (fillPx > 0) {
            gfx.fill(x, y + emptyPx, x + w, y + h, fillColor(ratio));
        }

        // 1-px outline
        gfx.fill(x,         y,         x + w,     y + 1,     borderColor); // top
        gfx.fill(x,         y + h - 1, x + w,     y + h,     borderColor); // bottom
        gfx.fill(x,         y,         x + 1,     y + h,     borderColor); // left
        gfx.fill(x + w - 1, y,         x + w,     y + h,     borderColor); // right
    }

    private static int fillColor(float ratio) {
        if (ratio > 0.66f) return COLOR_FULL;
        if (ratio > 0.25f) return COLOR_HALF;
        return COLOR_LOW;
    }

    private static int resolveMaxAmmo(ItemStack gun, CommonGunIndex index) {
        // Prefer stored magazine's tagged max capacity (respects extended mags).
        ItemStack storedMag = gun.getCapability(GunMagazineProvider.GUN_MAGAZINE)
                .map(GunMagazineCapability::getStoredMagazine)
                .orElse(ItemStack.EMPTY);
        if (!storedMag.isEmpty()) {
            int taggedMax = MagazineItem.getMaxCapacity(storedMag);
            if (taggedMax > 0) return taggedMax;
        }
        // Fallback: use TaCZ's attachment-aware ammo count.
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
