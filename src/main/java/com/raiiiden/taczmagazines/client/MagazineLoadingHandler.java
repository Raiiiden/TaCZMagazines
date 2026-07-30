package com.raiiiden.taczmagazines.client;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.config.GunOverrideConfig;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.crafting.GunsmithIntegration;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.item.MagazineAmmoSource;
import com.raiiiden.taczmagazines.item.SoundRegistrar;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.raiiiden.taczmagazines.network.BulletTransferPacket;
import com.raiiiden.taczmagazines.network.PacketHandler;
import com.raiiiden.taczmagazines.network.UnloadGunMagPacket;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.util.InputExtraCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
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

    // ── Deferred discovery (dedicated server) ─────────────────────────────────
    // On a dedicated server, CommonNetworkCache is populated by a play-phase packet
    // (ServerMessageSyncGunPack) that arrives AFTER RecipesUpdatedEvent fires.
    // We watch each tick until gun data is available, then run discovery.

    private static boolean pendingDiscovery = false;

    public static void scheduleDeferredDiscovery() {
        pendingDiscovery = true;
    }

    // ── Inventory session state ───────────────────────────────────────────────

    private static boolean active    = false;
    private static boolean unloading = false;
    // Index in Player#getInventory(), not an AbstractContainerMenu slot index.
    // Creative's inventory screen is client-only and does not share the
    // survival container's slot numbering.
    private static int containerSlot = -1;
    private static int tickCounter   = 0;
    private static int totalTicks    = 1;

    // 0.0 → 1.0 progress within the current bullet-interval. Used by the overlay.
    public static float progress = 0f;

    // ── In-hand session state ─────────────────────────────────────────────────

    private static boolean inHandActive    = false;
    private static boolean inHandUnloading = false;
    private static int     inHandTick      = 0;
    private static int     inHandTotal     = 1;
    private static boolean unloadActiveBeforeMousePress = false;

    // 0.0 → 1.0 progress for the in-hand arc drawn on the hotbar slot.
    public static float inHandProgress = 0f;

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isActive()        { return active; }
    public static boolean isUnloading()     { return unloading; }
    public static int    getContainerSlot() { return containerSlot; }

    public static boolean isInHandActive()    { return inHandActive; }
    public static boolean isInHandUnloading() { return inHandUnloading; }

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

    public static void startInHandLoading() {
        inHandActive    = true;
        inHandUnloading = false;
        inHandTotal     = MechanicsConfig.effectiveLoadTicks();
        inHandTick      = inHandTotal;
        inHandProgress  = 0f;
    }

    public static void startInHandUnloading() {
        inHandActive    = true;
        inHandUnloading = true;
        inHandTotal     = MechanicsConfig.effectiveUnloadTicks();
        inHandTick      = inHandTotal;
        inHandProgress  = 0f;
    }

    public static void cancelInHand() {
        inHandActive   = false;
        inHandProgress = 0f;
    }

    // ── Client tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Deferred discovery: wait for TaCZ's CommonNetworkCache to be populated
        // (happens when ServerMessageSyncGunPack arrives, which is a play-phase packet)
        if (pendingDiscovery && MagazineFamilySystem.getAllFamilies().isEmpty()) {
            if (!CommonAssetsManager.get().getAllGuns().isEmpty()) {
                pendingDiscovery = false;
                MagazineFamilySystem.discoverMagazineFamilies();
                GunOverrideConfig.apply();
                GunsmithIntegration.injectTabClientSide();
                MagazineItemRenderer.invalidateCache();
                TaCZMagazines.LOGGER.info("[MagazineLoadingHandler] Deferred client discovery complete");
            }
            return;
        }
        pendingDiscovery = false; // families found, no longer needed

        Minecraft mc       = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // ── In-hand session tick ──────────────────────────────────────────────
        if (inHandActive) {
            if (player == null || !isInHandSessionStillValid(player)) {
                cancelInHand();
            } else {
                inHandTick--;
                inHandProgress = 1f - (float) inHandTick / (float) inHandTotal;

                if (inHandTick <= 0) {
                    if (player.getAbilities().instabuild) {
                        if (inHandUnloading) {
                            creativeUnloadOneFromHand(player);
                        } else {
                            creativeLoadOneInHand(player);
                        }
                    } else if (inHandUnloading) {
                        PacketHandler.CHANNEL.sendToServer(new com.raiiiden.taczmagazines.network.UnloadOneFromHandPacket());
                    } else {
                        PacketHandler.CHANNEL.sendToServer(new com.raiiiden.taczmagazines.network.LoadOneFromHandPacket());
                    }
                    inHandTotal    = inHandUnloading ? MechanicsConfig.effectiveUnloadTicks()
                                                     : MechanicsConfig.effectiveLoadTicks();
                    inHandTick     = inHandTotal;
                    // Preserve the completed circle for the render frame after
                    // the transfer. The next tick advances the new interval.
                    inHandProgress = 1f;
                }
            }
        }

        if (!active) return;

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
            if (player.getAbilities().instabuild) {
                creativeTransferInventoryRound(player);
            } else {
                PacketHandler.CHANNEL.sendToServer(new BulletTransferPacket(containerSlot, unloading));
            }

            // Reset counter for next bullet
            totalTicks  = unloading ? MechanicsConfig.effectiveUnloadTicks()
                                     : MechanicsConfig.effectiveLoadTicks();
            tickCounter = totalTicks;
            // Preserve the completed circle for the render frame after the
            // transfer. The next tick advances the new interval.
            progress    = 1f;
        }
    }

    // Applies one creative transfer per timer interval and synchronizes it.
    private static void creativeTransferInventoryRound(LocalPlayer player) {
        if (containerSlot < 0 || containerSlot >= player.getInventory().items.size()) return;
        ItemStack magazine = player.getInventory().getItem(containerSlot);
        if (!(magazine.getItem() instanceof MagazineItem magItem)
                || magazine.isEmpty()) return;

        ItemStack extras;
        if (unloading) {
            int current = magItem.getAmmoCount(magazine);
            ResourceLocation ammoId = magItem.getAmmoId(magazine);
            if (current <= 0 || DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) return;

            extras = splitMagazineStack(magazine);
            magItem.setAmmoCount(magazine, current - 1);
            if (current == 1) magItem.setAmmoId(magazine, DefaultAssets.EMPTY_AMMO_ID);
            returnCreativeInventoryRound(player, ammoId);
            SoundRegistrar.playMagazineUnload(player);
        } else {
            String familyId = MagazineItem.getMagazineFamilyId(magazine);
            ResourceLocation familyAmmo = familyId == null
                    ? null
                    : MagazineFamilySystem.getAmmoTypeForFamily(familyId);
            int current = magItem.getAmmoCount(magazine);
            if (familyAmmo == null || current >= MagazineItem.getMaxCapacity(magazine)) return;

            ResourceLocation loadedAmmo = magItem.getAmmoId(magazine);
            if (!DefaultAssets.EMPTY_AMMO_ID.equals(loadedAmmo)
                    && !loadedAmmo.equals(familyAmmo)) return;

            extras = splitMagazineStack(magazine);
            magItem.setAmmoId(magazine, familyAmmo);
            magItem.setAmmoCount(magazine, current + 1);
            SoundRegistrar.playMagazineLoad(player);
        }

        returnSplitMagazines(player, extras);
        syncCreativeInventory(player, containerSlot, !extras.isEmpty());
    }

    private static void creativeLoadOneInHand(LocalPlayer player) {
        ItemStack magazine = player.getMainHandItem();
        if (!(magazine.getItem() instanceof MagazineItem magItem)) return;

        String familyId = MagazineItem.getMagazineFamilyId(magazine);
        ResourceLocation familyAmmo = familyId == null
                ? null
                : MagazineFamilySystem.getAmmoTypeForFamily(familyId);
        int current = magItem.getAmmoCount(magazine);
        if (familyAmmo == null || current >= MagazineItem.getMaxCapacity(magazine)) return;

        ResourceLocation loadedAmmo = magItem.getAmmoId(magazine);
        if (!DefaultAssets.EMPTY_AMMO_ID.equals(loadedAmmo)
                && !loadedAmmo.equals(familyAmmo)) return;

        ItemStack extras = splitMagazineStack(magazine);
        magItem.setAmmoId(magazine, familyAmmo);
        magItem.setAmmoCount(magazine, current + 1);
        returnSplitMagazines(player, extras);
        syncCreativeInventory(player, player.getInventory().selected, !extras.isEmpty());
        SoundRegistrar.playMagazineLoad(player);
    }

    private static void creativeUnloadOneFromHand(LocalPlayer player) {
        ItemStack magazine = player.getMainHandItem();
        if (!(magazine.getItem() instanceof MagazineItem magItem)) return;

        int current = magItem.getAmmoCount(magazine);
        ResourceLocation ammoId = magItem.getAmmoId(magazine);
        if (current <= 0 || DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) return;

        ItemStack extras = splitMagazineStack(magazine);
        magItem.setAmmoCount(magazine, current - 1);
        if (current == 1) magItem.setAmmoId(magazine, DefaultAssets.EMPTY_AMMO_ID);
        returnSplitMagazines(player, extras);

        ItemStack bullet = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
        if (!player.getInventory().add(bullet)) player.drop(bullet, false);
        syncCreativeInventory(player, player.getInventory().selected, true);
        SoundRegistrar.playMagazineUnload(player);
    }

    private static ItemStack splitMagazineStack(ItemStack magazine) {
        if (magazine.getCount() <= 1) return ItemStack.EMPTY;
        return magazine.split(magazine.getCount() - 1);
    }

    private static void returnSplitMagazines(LocalPlayer player, ItemStack extras) {
        if (!extras.isEmpty() && !player.getInventory().add(extras)) {
            player.drop(extras, false);
        }
    }

    private static void returnCreativeInventoryRound(LocalPlayer player, ResourceLocation ammoId) {
        AbstractContainerMenu menu = getVisibleMenu(player);
        if (menu == null) return;

        ItemStack bullet = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
        ItemStack cursor = menu.getCarried();
        if (cursor.isEmpty()) {
            menu.setCarried(bullet);
        } else if (ItemStack.isSameItemSameTags(cursor, bullet)
                && cursor.getCount() < cursor.getMaxStackSize()) {
            cursor.grow(1);
            menu.setCarried(cursor);
        } else if (!player.getInventory().add(bullet)) {
            player.drop(bullet, false);
        }
    }

    private static AbstractContainerMenu getVisibleMenu(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> screen) {
            return screen.getMenu();
        }
        return player.containerMenu;
    }

    private static void syncCreativeInventory(LocalPlayer player, int changedSlot, boolean syncAll) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) return;

        if (syncAll) {
            for (int inventorySlot = 0; inventorySlot < player.getInventory().items.size(); inventorySlot++) {
                mc.gameMode.handleCreativeModeItemAdd(
                        player.getInventory().getItem(inventorySlot).copy(),
                        inventoryMenuSlot(inventorySlot));
            }
        } else {
            mc.gameMode.handleCreativeModeItemAdd(
                    player.getInventory().getItem(changedSlot).copy(),
                    inventoryMenuSlot(changedSlot));
        }
        player.getInventory().setChanged();
    }

    private static int inventoryMenuSlot(int inventorySlot) {
        return inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
    }

    // Checks client-side inventory state to decide whether the session should continue.
    private static boolean isSessionStillValid(LocalPlayer player) {
        if (containerSlot < 0 || containerSlot >= player.getInventory().items.size()) return false;
        ItemStack mag = player.getInventory().getItem(containerSlot);
        if (mag.isEmpty() || !(mag.getItem() instanceof MagazineItem magItem)) return false;

        if (unloading) {
            // Session continues while the magazine still has ammo
            return magItem.getAmmoCount(mag) > 0;
        } else {
            // Loading: cursor must still have compatible ammo, magazine must have space
            String familyId = MagazineItem.getMagazineFamilyId(mag);
            if (familyId == null) return false;

            ResourceLocation familyAmmo = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
            if (familyAmmo == null) return false;
            AbstractContainerMenu menu = getVisibleMenu(player);
            if (menu == null) return false;
            if (!player.getAbilities().instabuild
                    && MagazineAmmoSource.compatibleAmmoId(menu.getCarried(), familyAmmo) == null) return false;

            return magItem.getAmmoCount(mag) < MagazineItem.getMaxCapacity(mag);
        }
    }

    // ── Validate in-hand session each tick ───────────────────────────────────

    private static boolean isInHandSessionStillValid(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof MagazineItem magItem)) return false;

        if (inHandUnloading) {
            return magItem.getAmmoCount(held) > 0;
        } else {
            int current = magItem.getAmmoCount(held);
            if (current >= MagazineItem.getMaxCapacity(held)) return false;
            return hasCompatibleAmmo(player, held, magItem);
        }
    }

    // Returns true if player has at least one ammo stack compatible with the held magazine.
    private static boolean hasCompatibleAmmo(LocalPlayer player, ItemStack mag, MagazineItem magItem) {
        if (player.getAbilities().instabuild) return true;
        String familyId = MagazineItem.getMagazineFamilyId(mag);
        if (familyId == null) return false;
        ResourceLocation familyAmmo = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
        if (familyAmmo == null) return false;
        ResourceLocation magAmmoId = magItem.getAmmoId(mag);

        for (ItemStack source : player.getInventory().items) {
            ResourceLocation ammoId = MagazineAmmoSource.compatibleAmmoId(source, familyAmmo);
            if (ammoId == null) continue;
            if (!DefaultAssets.EMPTY_AMMO_ID.equals(magAmmoId) && !magAmmoId.equals(ammoId)) continue;
            if (MagazineAmmoSource.available(source) > 0) return true;
        }
        return false;
    }

    // ── Cancel inventory unloading when the player clicks elsewhere ──────────

    @SubscribeEvent
    public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        unloadActiveBeforeMousePress = active && unloading;
    }

    @SubscribeEvent
    public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Post event) {
        // Waiting until Post lets a right-click on the active magazine use its
        // normal toggle handler first. A click on any other slot leaves the
        // session active, so cancel it here while allowing that click through.
        if (unloadActiveBeforeMousePress && active && unloading) {
            cancel();
        }
        unloadActiveBeforeMousePress = false;
    }

    // ── Attack key while holding a magazine: cancel block-break + arm-swing ──
    // Also handles starting/stopping the in-hand tick session (IN_HAND_TICK_BASED)
    // and the legacy single-bullet fire (TICK_BASED without IN_HAND_TICK_BASED).

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !InputExtraCheck.isInGame()) return;

        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof MagazineItem magItem)) return;

        // Cancel the attack unconditionally: prevents block breaking and arm swing.
        event.setCanceled(true);

        if (MechanicsConfig.IN_HAND_TICK_BASED.get()) {
            if (inHandActive && !inHandUnloading) {
                cancelInHand();
                return;
            }
            cancelInHand(); // stop any active unload session before starting load
            if (hasCompatibleAmmo((LocalPlayer) mc.player, held, magItem)
                    && magItem.getAmmoCount(held) < MagazineItem.getMaxCapacity(held)) {
                startInHandLoading();
            }
        } else if (MechanicsConfig.TICK_BASED.get()) {
            // Legacy: single bullet per click from inventory
            if (magItem.getAmmoCount(held) < MagazineItem.getMaxCapacity(held)
                    && hasCompatibleAmmo((LocalPlayer) mc.player, held, magItem)) {
                PacketHandler.CHANNEL.sendToServer(new com.raiiiden.taczmagazines.network.LoadOneFromHandPacket());
            }
        }
        // If neither config is on, the cancel above is still applied (no block break, no arm swing).
    }

    // ── Left-click empty (kept for safety; normally pre-empted by onInteractionKey) ──

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        // onInteractionKey cancels attack before this fires when holding a magazine.
        // This handler is kept as a safety net but should not execute for magazine holders.
    }

    // ── Key input ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        // Cancel any active session when the player presses Escape
        if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            boolean handled = active || inHandActive;
            if (active) cancel();
            if (inHandActive) cancelInHand();
            if (handled) return;
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
