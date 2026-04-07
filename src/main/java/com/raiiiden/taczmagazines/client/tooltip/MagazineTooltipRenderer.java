package com.raiiiden.taczmagazines.client.tooltip;

import com.mojang.blaze3d.systems.RenderSystem;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.raiiiden.taczmagazines.tooltip.MagazineTooltipData;
import com.tacz.guns.api.DefaultAssets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class MagazineTooltipRenderer implements ClientTooltipComponent {

    private static final int PADDING = 4;
    private static final int BAR_HEIGHT = 4;
    private static final int ROW_HEIGHT = 10;
    private static final int ICON_SIZE = 16;

    private final MagazineTooltipData data;

    public MagazineTooltipRenderer(MagazineTooltipData data) {
        this.data = data;
    }

    @Override
    public int getHeight() {
        return ICON_SIZE + PADDING * 2 + ROW_HEIGHT + BAR_HEIGHT + PADDING;
    }

    @Override
    public int getWidth(Font font) {
        ItemStack mag = data.getMagazineStack();
        String ammoLine = getAmmoLine(mag);
        String countLine = getCountLine(mag);
        int textWidth = Math.max(font.width(ammoLine), font.width(countLine));
        return ICON_SIZE + PADDING + textWidth + PADDING * 2;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        ItemStack mag = data.getMagazineStack();
        if (!(mag.getItem() instanceof MagazineItem magItem)) return;

        int ammoCount   = magItem.getAmmoCount(mag);
        int maxCapacity = MagazineItem.getMaxCapacity(mag);
        ResourceLocation ammoId = magItem.getAmmoId(mag);
        boolean isEmpty = ammoId.equals(DefaultAssets.EMPTY_AMMO_ID) || ammoCount == 0;

        // ── Ammo item icon ────────────────────────────────────────────────────
        // Build a representative ammo ItemStack using TaCZ's AmmoItemBuilder
        ItemStack ammoStack = ItemStack.EMPTY;
        if (!isEmpty) {
            try {
                ammoStack = com.tacz.guns.api.item.builder.AmmoItemBuilder
                        .create().setId(ammoId).setCount(1).build();
            } catch (Exception ignored) {}
        } else {
            // Empty mag — try to get the family's ammo type for display
            String familyId = MagazineItem.getMagazineFamilyId(mag);
            if (familyId != null) {
                ResourceLocation familyAmmo = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
                if (familyAmmo != null) {
                    try {
                        ammoStack = com.tacz.guns.api.item.builder.AmmoItemBuilder
                                .create().setId(familyAmmo).setCount(1).build();
                    } catch (Exception ignored) {}
                }
            }
        }

        if (!ammoStack.isEmpty()) {
            graphics.renderItem(ammoStack, x + PADDING, y + PADDING);
        }

        // ── Text ──────────────────────────────────────────────────────────────
        int textX = x + PADDING + ICON_SIZE + PADDING;
        int textY = y + PADDING;

        // Ammo type name
        String ammoLine = getAmmoLine(mag);
        graphics.drawString(font, ammoLine, textX, textY, 0xFFFFFF, false);

        // Count
        String countLine = getCountLine(mag);
        int countColor = isEmpty ? 0xAAAAAA : 0xFFD700;
        graphics.drawString(font, countLine, textX, textY + ROW_HEIGHT, countColor, false);

        // ── Fill bar ──────────────────────────────────────────────────────────
        int barY    = y + PADDING + ICON_SIZE + PADDING;
        int barW    = getWidth(font) - PADDING * 2;
        int filled  = maxCapacity > 0 ? (int) ((float) ammoCount / maxCapacity * barW) : 0;

        // Background
        graphics.fill(x + PADDING, barY, x + PADDING + barW, barY + BAR_HEIGHT, 0xFF333333);
        // Filled portion
        if (filled > 0) {
            int barColor = getFillColor(ammoCount, maxCapacity);
            graphics.fill(x + PADDING, barY, x + PADDING + filled, barY + BAR_HEIGHT, barColor);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getAmmoLine(ItemStack mag) {
        if (!(mag.getItem() instanceof MagazineItem magItem)) return "Unknown";
        ResourceLocation ammoId = magItem.getAmmoId(mag);
        if (ammoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
            String familyId = MagazineItem.getMagazineFamilyId(mag);
            if (familyId != null) {
                ResourceLocation familyAmmo = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
                if (familyAmmo != null) return formatAmmoName(familyAmmo);
            }
            return "No Ammo";
        }
        return formatAmmoName(ammoId);
    }

    private String getCountLine(ItemStack mag) {
        if (!(mag.getItem() instanceof MagazineItem magItem)) return "0/0";
        int count = magItem.getAmmoCount(mag);
        int max   = MagazineItem.getMaxCapacity(mag);
        if (count == 0) return "Empty  (0/" + max + ")";
        return count + " / " + max + " rounds";
    }

    private static String formatAmmoName(ResourceLocation ammoId) {
        // e.g. "tacz:9x19mm" → "9x19mm"
        String path = ammoId.getPath();
        // Strip common prefixes like "ammo_" if present
        if (path.startsWith("ammo_")) path = path.substring(5);
        // Capitalise first letter
        if (!path.isEmpty()) path = Character.toUpperCase(path.charAt(0)) + path.substring(1);
        return path;
    }

    private static int getFillColor(int current, int max) {
        if (max == 0) return 0xFF888888;
        float ratio = (float) current / max;
        if (ratio > 0.6f) return 0xFF4CAF50; // green
        if (ratio > 0.3f) return 0xFFFFD700; // yellow
        return 0xFFE53935;                    // red
    }
}