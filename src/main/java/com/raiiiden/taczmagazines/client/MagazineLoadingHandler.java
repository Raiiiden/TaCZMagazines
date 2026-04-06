package com.raiiiden.taczmagazines.client;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.raiiiden.taczmagazines.network.BulletTransferPacket;
import com.raiiiden.taczmagazines.network.PacketHandler;
import com.raiiiden.taczmagazines.network.UnloadGunMagPacket;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.util.InputExtraCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, value = Dist.CLIENT)
public class MagazineLoadingHandler {

    // ── Session state ─────────────────────────────────────────────────────────

    private static boolean active    = false;
    private static boolean unloading = false;
    private static int containerSlot = -1;
    private static int tickCounter   = 0;
    private static int totalTicks    = 1;

    // 0.0 → 1.0 progress within the current bullet-interval. Used by the overlay.
    public static float progress = 0f;

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isActive()        { return active; }
    public static boolean isUnloading()     { return unloading; }
    public static int    getContainerSlot() { return containerSlot; }

    public static void startLoading(int slot) {
        active        = true;
        unloading     = false;
        containerSlot = slot;
        totalTicks    = MechanicsConfig.effectiveLoadTicks();
        tickCounter   = totalTicks;
        progress      = 0f;
    }

    public static void startUnloading(int slot) {
        active        = true;
        unloading     = true;
        containerSlot = slot;
        totalTicks    = MechanicsConfig.effectiveUnloadTicks();
        tickCounter   = totalTicks;
        progress      = 0f;
    }

    public static void cancel() {
        active   = false;
        progress = 0f;
    }

    // ── Client tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active) return;

        Minecraft mc     = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // Cancel if inventory is no longer open or player gone
        if (player == null || mc.screen == null) {
            cancel();
            return;
        }

        // Validate session conditions each tick
        if (!isSessionStillValid(player)) {
            cancel();
            return;
        }

        tickCounter--;
        progress = 1f - (float) tickCounter / (float) totalTicks;

        if (tickCounter <= 0) {
            // Time to transfer one bullet
            PacketHandler.CHANNEL.sendToServer(new BulletTransferPacket(containerSlot, unloading));

            // Reset counter for next bullet
            totalTicks  = unloading ? MechanicsConfig.effectiveUnloadTicks()
                                     : MechanicsConfig.effectiveLoadTicks();
            tickCounter = totalTicks;
            progress    = 0f;
        }
    }

    // Checks client-side inventory state to decide whether the session should continue.
    private static boolean isSessionStillValid(LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || containerSlot < 0 || containerSlot >= menu.slots.size()) return false;

        Slot slot    = menu.slots.get(containerSlot);
        ItemStack mag = slot.getItem();
        if (mag.isEmpty() || !(mag.getItem() instanceof MagazineItem magItem)) return false;

        if (unloading) {
            // Session continues while the magazine still has ammo
            return magItem.getAmmoCount(mag) > 0;
        } else {
            // Loading: cursor must still have compatible ammo, magazine must have space
            ItemStack cursor = menu.getCarried();
            if (cursor.isEmpty() || !(cursor.getItem() instanceof IAmmo iAmmo)) return false;

            ResourceLocation heldAmmoId = iAmmo.getAmmoId(cursor);
            if (DefaultAssets.EMPTY_AMMO_ID.equals(heldAmmoId)) return false;

            String familyId = MagazineItem.getMagazineFamilyId(mag);
            if (familyId == null) return false;

            ResourceLocation familyAmmo = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
            if (familyAmmo == null || !familyAmmo.equals(heldAmmoId)) return false;

            return magItem.getAmmoCount(mag) < MagazineItem.getMaxCapacity(mag);
        }
    }

    // ── Block left-clicks in inventory during active unload session ───────────

    @SubscribeEvent
    public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!active || !unloading) return;
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            event.setCanceled(true);
        }
    }

    // ── Left-click in-game while holding a mag: load one bullet (tick-based) ──

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        // NOTE: LeftClickEmpty is NOT cancellable — do not call setCanceled.
        if (!MechanicsConfig.TICK_BASED.get()) return;

        ItemStack held = event.getEntity().getMainHandItem();
        if (!(held.getItem() instanceof MagazineItem magItem)) return;

        int maxCap  = MagazineItem.getMaxCapacity(held);
        int current = magItem.getAmmoCount(held);
        if (current >= maxCap) return;

        // Quick client-side check: does the player have any compatible ammo?
        String familyId = MagazineItem.getMagazineFamilyId(held);
        if (familyId == null) return;
        ResourceLocation familyAmmo = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
        if (familyAmmo == null) return;

        ResourceLocation magAmmoId = magItem.getAmmoId(held);
        LocalPlayer player = (LocalPlayer) event.getEntity();
        boolean hasAmmo = false;
        for (ItemStack s : player.getInventory().items) {
            if (s.isEmpty() || !(s.getItem() instanceof IAmmo iAmmo)) continue;
            ResourceLocation ammoId = iAmmo.getAmmoId(s);
            if (DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) continue;
            if (!familyAmmo.equals(ammoId)) continue;
            if (!DefaultAssets.EMPTY_AMMO_ID.equals(magAmmoId) && !magAmmoId.equals(ammoId)) continue;
            hasAmmo = true;
            break;
        }
        if (!hasAmmo) return;

        PacketHandler.CHANNEL.sendToServer(new com.raiiiden.taczmagazines.network.LoadOneFromHandPacket());
    }

    // ── Key input ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        // Cancel any active session when the player presses Escape
        if (active && event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return;
        }

        // Unload magazine from held gun via the configured keybind (checks key + modifier)
        if (ModKeybinds.UNLOAD_MAG.isActiveAndMatches(
                com.mojang.blaze3d.platform.InputConstants.getKey(event.getKey(), event.getScanCode()))) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || player.isSpectator() || !InputExtraCheck.isInGame()) return;
            ItemStack held = player.getMainHandItem();
            if (held.getItem() instanceof IGun) {
                PacketHandler.CHANNEL.sendToServer(new UnloadGunMagPacket());
            }
        }
    }
}
