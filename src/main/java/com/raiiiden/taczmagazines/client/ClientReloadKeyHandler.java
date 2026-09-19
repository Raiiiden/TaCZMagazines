package com.raiiiden.taczmagazines.client;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.magazine.SplitReloadGuns;
import com.raiiiden.taczmagazines.network.OpenSelectorPacket;
import com.raiiiden.taczmagazines.network.PacketHandler;
import com.raiiiden.taczmagazines.network.SelectMagazinePacket;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.input.ReloadKey;
import com.tacz.guns.util.InputExtraCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, value = Dist.CLIENT)
public class ClientReloadKeyHandler {

    private static final long HOLD_THRESHOLD_MS = 400;

    private static long keyDownAt = 0;
    private static boolean keyDown = false;
    private static boolean inSelectorMode = false;
    private static boolean cancelled = false;
    private static boolean serverBlocked = false;

    // Frozen ext level from magazine commit until its reload ends; -1 = unset.
    // Driven by client tick, since the synced reload state lags a server round trip.
    private static final long FREEZE_ARM_TIMEOUT_MS = 1500;
    private static final long FREEZE_RELEASE_GRACE_MS = 250;

    private static int frozenExtLevel = -1;
    // Rounds in the committed magazine so a part-full clip renders part-full; -1 = unknown.
    private static int frozenAmmoCount = -1;
    // Whether the magazine was picked from the selector; client copy keeps the animation on the server's branch.
    private static boolean frozenFromSelector = false;
    // Whether this reload seats a whole clip or speed loader rather than feeding single rounds.
    private static boolean frozenBulkReload = false;
    // Rounds (chamber included) still in the gun once the device goes in; a dumping speed loader leaves none.
    private static int frozenGunRounds = 0;
    // Whether the animation has emptied the cylinder; until then the gun's own rounds are drawn as-is.
    private static boolean frozenCylinderEmptied = false;
    private static long frozenAt = 0;
    private static boolean frozenSawReload = false;
    private static long frozenEndedAt = 0;

    public static int getFrozenExtLevel() { return frozenExtLevel; }

    public static int getFrozenAmmoCount() { return frozenAmmoCount; }

    public static boolean isFrozenFromSelector() { return frozenFromSelector; }

    public static boolean isFrozenBulkReload() { return frozenBulkReload; }

    public static int getFrozenGunRounds() { return frozenGunRounds; }

    public static boolean isFrozenCylinderEmptied() { return frozenCylinderEmptied; }

    public static void markFrozenCylinderEmptied() { frozenCylinderEmptied = true; }

    public static void clearFrozenExtLevel() {
        frozenExtLevel = -1;
        frozenAmmoCount = -1;
        frozenGunRounds = 0;
        frozenCylinderEmptied = false;
        frozenFromSelector = false;
        frozenBulkReload = false;
        frozenSawReload = false;
        frozenEndedAt = 0;
    }

    private static void freezeExtLevel(int level) {
        frozenExtLevel = level;
        frozenAt = System.currentTimeMillis();
        frozenCylinderEmptied = false;
        frozenSawReload = false;
        frozenEndedAt = 0;
    }

    private static void freezeMagazine(ItemStack mag, boolean fromSelector) {
        freezeExtLevel(resolveExtLevel(mag));
        frozenAmmoCount = resolveAmmoCount(mag);
        LocalPlayer player = Minecraft.getInstance().player;
        ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        frozenGunRounds = SplitReloadGuns.dumpsOnBulkLoad(held) ? 0 : SplitReloadGuns.roundsInGun(held);
        frozenFromSelector = fromSelector;
        frozenBulkReload = decideBulkReload(fromSelector);
    }

    // Decides once, at commit, whether this reload seats a clip/speed loader (mirrors MixinModernKineticGunItem).
    // Freezing the answer keeps the animation and the server reload on the same branch.
    private static boolean decideBulkReload(boolean fromSelector) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;

        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof IGun iGun)) return false;
        // With the split reload turned off no gun is registered as one, so this covers both.
        if (!SplitReloadGuns.managed(gun)) return false;

        if (frozenAmmoCount <= 0) {
            // No clip on a tap reload is normal; only report it when the player picked one.
            if (fromSelector) {
                return refuse(gun, "the magazine that was picked reports " + frozenAmmoCount + " rounds");
            }
            return false;
        }

        // The script refuses its own bulk insert with a scope fitted.
        if (SplitReloadGuns.scopeBlocksBulkLoad(gun)) return refuse(gun, "a scope is fitted");

        // An empty gun (chambered round counts, as on the server) always takes the device.
        if (SplitReloadGuns.roundsInGun(gun) <= 0) return true;

        // A selector pick settles it; the rules below only apply to unaimed reloads.
        if (fromSelector) {
            if (MechanicsConfig.ALLOW_BULK_RELOAD_WHEN_LOADED.get()) return true;
            return refuse(gun, "allow_bulk_reload_when_loaded is off and the gun still has rounds");
        }

        if (!MechanicsConfig.ALLOW_BULK_RELOAD_WHEN_LOADED.get()) return false;
        if (!MechanicsConfig.PARTIAL_BULK_RELOAD_REQUIRES_SELECTOR.get()) return true;
        return !SplitReloadGuns.hasLooseAmmo(player, gun);
    }

    // Logs once per reload why a split-reload gun with a magazine to hand isn't using it.
    private static boolean refuse(ItemStack gun, String reason) {
        // Every TaCZ gun is the same item, so name the gun itself.
        ResourceLocation gunId = gun.getItem() instanceof IGun iGun ? iGun.getGunId(gun) : null;
        TaCZMagazines.LOGGER.info("Split reload: loading {} round by round — {}", gunId, reason);
        return false;
    }

    // Runs every client tick while a level is frozen.
    private static void tickFrozenExtLevel() {
        if (frozenExtLevel < 0) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            clearFrozenExtLevel();
            return;
        }

        IGunOperator gunOp = IGunOperator.fromLivingEntity(player);
        boolean reloading = gunOp != null
                && gunOp.getSynReloadState().getStateType().isReloading();
        long now = System.currentTimeMillis();

        if (reloading) {
            frozenSawReload = true;
            frozenEndedAt = 0;
            return;
        }

        // Branch decision only applies to its own reload; stop reporting an empty gun once the clip is in.
        if (frozenSawReload) frozenBulkReload = false;

        if (!frozenSawReload) {
            // Server never confirmed the reload (refused/cancelled/swapped) — drop the stale level.
            if (now - frozenAt > FREEZE_ARM_TIMEOUT_MS) clearFrozenExtLevel();
            return;
        }

        // Hold briefly after the reload so the stored magazine's NBT arrives before the model snaps back.
        if (frozenEndedAt == 0) {
            frozenEndedAt = now;
        } else if (now - frozenEndedAt > FREEZE_RELEASE_GRACE_MS) {
            clearFrozenExtLevel();
        }
    }

    public static boolean isSelectorOpen() {
        return inSelectorMode;
    }

    public static boolean onReloadKeyPressed() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isSpectator()) return false;
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof IGun iGun)) return false;
        if (iGun.useInventoryAmmo(gun)) return false;

        ResourceLocation gunId = iGun.getGunId(gun);
        com.tacz.guns.resource.index.CommonGunIndex gunIndex =
                com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) return false;
        if (!gunIndex.getGunData().getReloadData().getType()
                .equals(com.tacz.guns.resource.pojo.data.gun.FeedType.MAGAZINE)) return false;
        if (com.raiiiden.taczmagazines.magazine.MagazineFamilySystem.getFamilyForGun(gunId) == null) return false;

        keyDown = true;
        keyDownAt = System.currentTimeMillis();
        inSelectorMode = false;
        cancelled = false;
        serverBlocked = true;

        PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(true));
        return true;
    }

    public static void onReloadKeyReleased() {
        if (!keyDown) return;
        keyDown = false;

        if (cancelled) {
            cancelled = false;
            if (serverBlocked) {
                serverBlocked = false;
                PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(false));
            }
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            if (serverBlocked) {
                serverBlocked = false;
                PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(false));
            }
            return;
        }

        if (inSelectorMode) {
            inSelectorMode = false;
            int selectedSlot = MagazineSelectorOverlay.getSelectedSlot();
            MagazineSelectorOverlay.close();
            serverBlocked = false;
            if (selectedSlot >= 0) {
                ItemStack mag = player.getInventory().getItem(selectedSlot);
                freezeMagazine(mag, true);
                PacketHandler.CHANNEL.sendToServer(new SelectMagazinePacket(selectedSlot));
                IClientPlayerGunOperator.fromLocalPlayer(player).reload();
            } else {
                PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(false));
            }
        } else {
            // Tap reload — freeze from first compatible mag in inventory
            ItemStack gun = player.getMainHandItem();
            freezeMagazine(firstCompatibleMagazine(gun, player.getInventory()), false);
            serverBlocked = false;
            PacketHandler.CHANNEL.sendToServer(new SelectMagazinePacket(-1));
            IClientPlayerGunOperator.fromLocalPlayer(player).reload();
        }
    }

    public static void onRightClickCancel() {
        if (!inSelectorMode) return;
        cancelled = true;
        inSelectorMode = false;
        MagazineSelectorOverlay.close();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickFrozenExtLevel();

        if (inSelectorMode) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.keyUse.isDown()) {
                onRightClickCancel();
            }
            if (!InputExtraCheck.isInGame()) {
                inSelectorMode = false;
                MagazineSelectorOverlay.close();
                keyDown = false;
                if (serverBlocked) {
                    serverBlocked = false;
                    PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(false));
                }
            }
            return;
        }

        if (!keyDown) return;
        if (!InputExtraCheck.isInGame()) {
            keyDown = false;
            return;
        }

        if (!ReloadKey.RELOAD_KEY.isDown()) {
            onReloadKeyReleased();
            return;
        }

        if (cancelled) return;

        long heldMs = System.currentTimeMillis() - keyDownAt;
        if (heldMs >= HOLD_THRESHOLD_MS) {
            inSelectorMode = true;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                MagazineSelectorOverlay.open(player);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int resolveExtLevel(ItemStack picked) {
        ItemStack mag = resolveMagazine(picked);
        if (mag.isEmpty() || !(mag.getItem() instanceof com.raiiiden.taczmagazines.item.MagazineItem)) return 0;
        String fid = com.raiiiden.taczmagazines.item.MagazineItem.getMagazineFamilyId(mag);
        if (fid != null && com.raiiiden.taczmagazines.magazine.MagazineFamilySystem.isExtendedFamily(fid))
            return com.raiiiden.taczmagazines.magazine.MagazineFamilySystem.getExtLevelForFamily(fid);
        return 0;
    }

    private static int resolveAmmoCount(ItemStack mag) {
        ItemStack resolved = resolveMagazine(mag);
        if (!(resolved.getItem() instanceof com.raiiiden.taczmagazines.item.MagazineItem magItem)) return -1;
        return magItem.getAmmoCount(resolved);
    }

    // Resolves a selector entry (magazine or ammo box) to the magazine that will come out of it.
    private static ItemStack resolveMagazine(ItemStack picked) {
        if (picked.isEmpty()) return ItemStack.EMPTY;
        if (picked.getItem() instanceof com.raiiiden.taczmagazines.item.MagazineItem) return picked;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        return com.raiiiden.taczmagazines.item.AmmoBoxMagazineStorage
                .peekBestCompatible(picked, player.getMainHandItem());
    }

    // The magazine a tap reload will pick, using the server's first-compatible scan.
    private static ItemStack firstCompatibleMagazine(ItemStack gun, Inventory inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!(s.getItem() instanceof com.raiiiden.taczmagazines.item.MagazineItem magItem)) continue;
            if (!magItem.isAmmoBoxOfGun(gun, s)) continue;
            return s;
        }
        return ItemStack.EMPTY;
    }
}
