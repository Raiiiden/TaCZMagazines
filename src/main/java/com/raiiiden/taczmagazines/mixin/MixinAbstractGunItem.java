package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.client.ClientReloadKeyHandler;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.item.MagazineReloadSource;
import com.raiiiden.taczmagazines.item.LooseAmmoOnlyInventory;
import com.raiiiden.taczmagazines.magazine.SplitReloadGuns;
import com.raiiiden.taczmagazines.magazine.GunMagazineInitializer;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.raiiiden.taczmagazines.network.OpenSelectorPacket;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractGunItem.class, remap = false)
public abstract class MixinAbstractGunItem {

    // Hides this mod's magazines from TaCZ's round-by-round search so it can't strip clips a round at a time.
    @ModifyVariable(method = "findAndExtractInventoryAmmo", at = @At("HEAD"), argsOnly = true, index = 1)
    private IItemHandler taczmagazines$hideMagazinesFromRoundFeed(IItemHandler inventory,
                                                                  IItemHandler unused,
                                                                  ItemStack gun, int needAmmoCount) {
        if (inventory instanceof LooseAmmoOnlyInventory) return inventory;
        if (!SplitReloadGuns.isSplitReload(gun)) return inventory;
        if (SplitReloadGuns.enabled() && SplitReloadGuns.isBulkReload(gun)) return inventory;
        return new LooseAmmoOnlyInventory(inventory);
    }

    @Inject(method = "findAndExtractInventoryAmmo", at = @At("HEAD"), cancellable = true)
    private void onFindAndExtractInventoryAmmo(IItemHandler inventory, ItemStack gun,
                                               int needAmmoCount,
                                               CallbackInfoReturnable<Integer> cir) {
        GunMagazineInitializer.ensureMagazineForLoadedGun(gun);
        AbstractGunItem gunItem = (AbstractGunItem) (Object) this;
        CommonGunIndex gunIndex = getManagedGunIndex(gunItem, gun);
        if (gunIndex == null) return;

        if (ClientReloadKeyHandler.isSelectorOpen()) {
            cir.setReturnValue(0);
            return;
        }

        int selectedSlot = -1;
        if (gun.hasTag() && gun.getTag().contains(SplitReloadGuns.SELECTED_SLOT_TAG)) {
            selectedSlot = gun.getTag().getInt(SplitReloadGuns.SELECTED_SLOT_TAG);
            gun.getTag().remove(SplitReloadGuns.SELECTED_SLOT_TAG);
        }
        boolean creativeReload = gun.hasTag()
                && gun.getTag().getBoolean("TaCZMag_CreativeReload");
        if (gun.hasTag()) gun.getTag().remove("TaCZMag_CreativeReload");

        // Only the bulk insert is ours; TaCZ handles the round-by-round path.
        boolean splitReload = SplitReloadGuns.isSplitReload(gun);
        if (splitReload) {
            if (!SplitReloadGuns.enabled() || !SplitReloadGuns.isBulkReload(gun)) {
                // TaCZ feeds this reload; drop any selector pick so it doesn't leak into the next one.
                clearSelectionMarkers(gun);
                return;
            }
            // The device is drained in place; nothing is stored in the gun or consumed.
            cir.setReturnValue(
                    SplitReloadGuns.drawFromLoadingDevice(inventory, gun, needAmmoCount, selectedSlot));
            return;
        }

        GunMagazineCapability cap = gun.getCapability(GunMagazineProvider.GUN_MAGAZINE).orElse(null);
        if (cap == null) return;

        // Current magazine with live round count; stays in the gun until a replacement is found so it isn't re-picked.
        ItemStack installed = ItemStack.EMPTY;
        int installedAmmo = 0;
        if (cap.hasMagazine()) {
            installed = cap.getStoredMagazine();
            writeGunAmmoToMagazine(installed, gunItem.getCurrentAmmoCount(gun),
                    gunIndex.getGunData().getAmmoId());
            clearLegacyCreativeSourceMarker(installed);
            if (installed.getItem() instanceof MagazineItem installedItem) {
                installedAmmo = installedItem.getAmmoCount(installed);
            }

            // Nowhere to stow the old magazine — bail out before anything leaves the pool.
            if (!ItemHandlerHelper.insertItemStacked(inventory, installed.copy(), true).isEmpty()) {
                cir.setReturnValue(0);
                return;
            }
        }

        ItemStack magazine = creativeReload
                ? MagazineReloadSource.createCreativeReloadMagazine(inventory, gun, selectedSlot)
                : MagazineReloadSource.extract(inventory, gun, selectedSlot, installedAmmo);
        if (!(magazine.getItem() instanceof MagazineItem magItem)) {
            // Nothing worth swapping to — leave the gun exactly as it was.
            cir.setReturnValue(0);
            return;
        }

        if (!installed.isEmpty()) {
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(inventory, installed, false);
            if (!leftover.isEmpty()) {
                // Unreachable (insert was simulated); keep both magazines rather than voiding one.
                cap.setStoredMagazine(leftover);
                ItemHandlerHelper.insertItemStacked(inventory, magazine, false);
                cir.setReturnValue(0);
                return;
            }
            cap.clearMagazine();
        }

        int ammo = magItem.getAmmoCount(magazine);
        gunItem.setCurrentAmmoCount(gun, 0);
        cap.setStoredMagazine(magazine);
        TaCZMagazines.LOGGER.debug(
                "Reload: loaded {} ({} rounds) over a magazine holding {}; selectedSlot={}",
                MagazineItem.getMagazineFamilyId(magazine), ammo, installedAmmo, selectedSlot);

        cir.setReturnValue(ammo);
    }

    @Inject(method = "canReload", at = @At("HEAD"), cancellable = true)
    private void onCanReload(LivingEntity shooter, ItemStack gun,
                             CallbackInfoReturnable<Boolean> cir) {
        GunMagazineInitializer.ensureMagazineForLoadedGun(gun);
        AbstractGunItem gunItem = (AbstractGunItem) (Object) this;
        CommonGunIndex gunIndex = getManagedGunIndex(gunItem, gun);
        if (gunIndex == null) return;

        if (shooter instanceof ServerPlayer serverPlayer
                && OpenSelectorPacket.SELECTING_PLAYERS.contains(serverPlayer.getUUID())) {
            cir.setReturnValue(false);
            return;
        }

        if (gunItem.useInventoryAmmo(gun)) {
            cir.setReturnValue(false);
            return;
        }

        if (shooter instanceof Player player && player.getAbilities().instabuild) {
            cir.setReturnValue(true);
            return;
        }

        if (gunIndex.getGunData().getReloadData().isInfinite()) {
            cir.setReturnValue(true);
            return;
        }

        if (gunItem.useDummyAmmo(gun)) {
            cir.setReturnValue(gunItem.getDummyAmmoAmount(gun) > 0);
            return;
        }

        // Split-reload guns never swap their stored magazine; it stands for rounds in the gun.
        boolean splitReload = SplitReloadGuns.isSplitReload(gun);

        int current = gunItem.getCurrentAmmoCount(gun);
        int maximum = com.tacz.guns.util.AttachmentDataUtils
                .getAmmoCountWithAttachment(gun, gunIndex.getGunData());
        if (current >= maximum && !splitReload) {
            boolean[] hasStored = {false};
            gun.getCapability(GunMagazineProvider.GUN_MAGAZINE)
                    .ifPresent(cap -> hasStored[0] = cap.hasMagazine());
            if (hasStored[0]) {
                cir.setReturnValue(true);
                return;
            }
        }

        boolean[] storedHasAmmo = {false};
        gun.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(cap -> {
            if (!cap.hasMagazine()) return;
            ItemStack stored = cap.getStoredMagazine();
            if (stored.getItem() instanceof MagazineItem magItem) {
                storedHasAmmo[0] = magItem.getAmmoCount(stored) > 0;
            }
        });
        if (storedHasAmmo[0] && !splitReload) {
            cir.setReturnValue(true);
            return;
        }

        boolean available = shooter.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null)
                .map(handler -> MagazineReloadSource.hasUsableMagazine(handler, gun))
                .orElse(false);
        if (available) {
            cir.setReturnValue(true);
            return;
        }
        // No magazine, but a split-reload gun can still top up from loose ammo; defer to TaCZ.
        if (splitReload) return;
        cir.setReturnValue(false);
    }

    @Inject(method = "dropAllAmmo", at = @At("HEAD"), cancellable = true)
    private void onDropAllAmmo(Player player, ItemStack gun, CallbackInfo ci) {
        GunMagazineInitializer.ensureMagazineForLoadedGun(gun);
        AbstractGunItem gunItem = (AbstractGunItem) (Object) this;
        CommonGunIndex gunIndex = getManagedGunIndex(gunItem, gun);
        if (gunIndex == null) return;

        if (player instanceof ServerPlayer serverPlayer
                && OpenSelectorPacket.SELECTING_PLAYERS.contains(serverPlayer.getUUID())) {
            ci.cancel();
            return;
        }

        int remaining = gunItem.getCurrentAmmoCount(gun);
        ResourceLocation ammoId = gunIndex.getGunData().getAmmoId();
        boolean[] hadMagazine = {false};

        // No magazine means loose rounds; let TaCZ hand them back instead of deleting them.
        if (SplitReloadGuns.isSplitReload(gun)) {
            boolean hasStored = gun.getCapability(GunMagazineProvider.GUN_MAGAZINE)
                    .map(GunMagazineCapability::hasMagazine).orElse(false);
            if (!hasStored) return;
        }

        gun.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(cap -> {
            if (!cap.hasMagazine()) return;
            hadMagazine[0] = true;
            ItemStack stored = cap.getStoredMagazine();

            gunItem.setCurrentAmmoCount(gun, 0);
            cap.clearMagazine();

            writeGunAmmoToMagazine(stored, remaining, ammoId);
            clearLegacyCreativeSourceMarker(stored);
            if (!player.getInventory().add(stored)) player.drop(stored, false);
        });

        if (!hadMagazine[0] && remaining > 0) {
            gunItem.setCurrentAmmoCount(gun, 0);
        }
        ci.cancel();
    }

    private static CommonGunIndex getManagedGunIndex(AbstractGunItem gunItem, ItemStack gun) {
        ResourceLocation gunId = gunItem.getGunId(gun);
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (index == null) return null;
        if (index.getGunData().getReloadData().getType() != FeedType.MAGAZINE) return null;
        return MagazineFamilySystem.getFamilyForGun(gunId) == null ? null : index;
    }

    private static void writeGunAmmoToMagazine(ItemStack magazine, int ammo,
                                               ResourceLocation ammoId) {
        if (!(magazine.getItem() instanceof MagazineItem magItem)) return;
        if (ammo > 0 && !DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) {
            magItem.setAmmoId(magazine, ammoId);
            magItem.setAmmoCount(magazine, ammo);
        } else {
            magItem.setAmmoCount(magazine, 0);
            magItem.setAmmoId(magazine, DefaultAssets.EMPTY_AMMO_ID);
        }
    }

    private static void clearSelectionMarkers(ItemStack gun) {
        if (!gun.hasTag()) return;
        gun.getTag().remove(SplitReloadGuns.SELECTED_SLOT_TAG);
        gun.getTag().remove("TaCZMag_CreativeReload");
    }

    private static void clearLegacyCreativeSourceMarker(ItemStack magazine) {
        if (magazine.hasTag()) {
            magazine.getTag().remove("TaCZMagazinesCreativeSource");
        }
    }
}
