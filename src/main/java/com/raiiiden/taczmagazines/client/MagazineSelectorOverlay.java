package com.raiiiden.taczmagazines.client;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.item.MagazineItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// HUD overlay that shows compatible magazines from the player's inventory when the reload key is held
@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, value = Dist.CLIENT)
public class MagazineSelectorOverlay {


    private static final List<Integer> magazineSlots = new ArrayList<>();

    private static final List<ItemStack> magazineStacks = new ArrayList<>();

    private static int selectedIndex = 0;

    private static boolean open = false;

    // ── lifecycle ─────────────────────────────────────────────────────────────

    public static void open(LocalPlayer player) {
        magazineSlots.clear();
        magazineStacks.clear();
        selectedIndex = 0;

        ItemStack gun = player.getMainHandItem();

        Optional<IItemHandler> capOpt = player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        capOpt.ifPresent(cap -> {
            for (int i = 0; i < cap.getSlots(); i++) {
                ItemStack stack = cap.getStackInSlot(i);
                if (stack.getItem() instanceof MagazineItem magItem) {
                    if (magItem.isAmmoBoxOfGun(gun, stack) && magItem.getAmmoCount(stack) > 0) {
                        magazineSlots.add(i);
                        magazineStacks.add(stack.copy());
                    }
                }
            }
        });

        open = !magazineSlots.isEmpty();
    }

    public static void close() {
        open = false;
        magazineSlots.clear();
        magazineStacks.clear();
    }


    public static int getSelectedSlot() {
        if (!open || magazineSlots.isEmpty()) return -1;
        return magazineSlots.get(selectedIndex);
    }

    // ── input ─────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!open || magazineSlots.isEmpty()) return;
        int delta = event.getScrollDelta() > 0 ? -1 : 1;
        selectedIndex = Math.floorMod(selectedIndex + delta, magazineSlots.size());
        event.setCanceled(true); // prevent hotbar slot scrolling while selector is open
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (!open || magazineSlots.isEmpty()) return;
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics gfx = event.getGuiGraphics();
        Font font = mc.font;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int count = magazineStacks.size();
        int slotSize = 20;         // px per slot (icon + padding)
        int padding = 4;
        int totalWidth = count * slotSize + (count - 1) * padding;
        int startX = (screenW - totalWidth) / 2;
        int y = screenH - 80;      // above the hotbar

        // Background
        int bgPad = 6;
        gfx.fill(startX - bgPad, y - bgPad,
                startX + totalWidth + bgPad, y + slotSize + bgPad + 10,
                0xBB000000);

        // Title
        Component title = Component.literal("Select Magazine");
        gfx.drawCenteredString(font, title, screenW / 2, y - bgPad - 2, 0xFFFFFF);

        // Slots
        for (int i = 0; i < count; i++) {
            int x = startX + i * (slotSize + padding);
            boolean selected = (i == selectedIndex);

            // Slot background
            int bgColor = selected ? 0xCC888833 : 0xCC444444;
            gfx.fill(x - 2, y - 2, x + slotSize - 2, y + slotSize - 2, bgColor);

            // Item icon
            ItemStack stack = magazineStacks.get(i);
            gfx.renderItem(stack, x, y);

            // Ammo count
            if (stack.getItem() instanceof MagazineItem magItem) {
                int ammo = magItem.getAmmoCount(stack);
                String ammoStr = String.valueOf(ammo);
                gfx.drawString(font, ammoStr, x + slotSize - font.width(ammoStr) - 1, y + slotSize - 8, selected ? 0xFFFF44 : 0xAAAAAA);
            }

            // Selection indicator
            if (selected) {
                gfx.fill(x - 2, y + slotSize - 2, x + slotSize - 2, y + slotSize, 0xFFFFFF44);
            }
        }

        // Hint
        Component hint = Component.literal("Scroll to select  |  Release R to load");
        gfx.drawCenteredString(font, hint, screenW / 2, y + slotSize + 2, 0xAAAAAA);
    }
}
