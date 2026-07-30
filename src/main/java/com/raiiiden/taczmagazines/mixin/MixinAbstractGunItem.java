package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.client.ClientReloadKeyHandler;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.item.MagazineReloadSource;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractGunItem.class, remap = false)
public abstract class MixinAbstractGunItem {

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

        int currentAmmo = gunItem.getCurrentAmmoCount(gun);
        ResourceLocation gunAmmoId = gunIndex.getGunData().getAmmoId();
        boolean[] ejectFailed = {false};

        gun.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(cap -> {
            if (!cap.hasMagazine()) return;
            ItemStack stored = cap.getStoredMagazine();

            writeGunAmmoToMagazine(stored, currentAmmo, gunAmmoId);
            clearLegacyCreativeSourceMarker(stored);
            cap.setStoredMagazine(stored);
            stored = cap.getStoredMagazine();

            ItemStack leftover = ItemHandlerHelper.insertItemStacked(inventory, stored, false);
            if (!leftover.isEmpty()) {
                cap.setStoredMagazine(leftover);
                ejectFailed[0] = true;
                return;
            }

            cap.clearMagazine();
            gunItem.setCurrentAmmoCount(gun, 0);
        });

        if (ejectFailed[0]) {
            cir.setReturnValue(0);
            return;
        }

        int selectedSlot = -1;
        if (gun.hasTag() && gun.getTag().contains("TaCZMag_SelectedSlot")) {
            selectedSlot = gun.getTag().getInt("TaCZMag_SelectedSlot");
            gun.getTag().remove("TaCZMag_SelectedSlot");
        }

        boolean creativeReload = gun.hasTag()
                && gun.getTag().getBoolean("TaCZMag_CreativeReload");
        if (gun.hasTag()) gun.getTag().remove("TaCZMag_CreativeReload");

        ItemStack magazine = creativeReload
                ? MagazineReloadSource.createCreativeReloadMagazine(inventory, gun, selectedSlot)
                : MagazineReloadSource.extract(inventory, gun, selectedSlot);
        if (!(magazine.getItem() instanceof MagazineItem magItem)) {
            cir.setReturnValue(0);
            return;
        }

        int ammo = magItem.getAmmoCount(magazine);
        gun.getCapability(GunMagazineProvider.GUN_MAGAZINE)
                .ifPresent(cap -> cap.setStoredMagazine(magazine));
        TaCZMagazines.LOGGER.debug("Loaded magazine with {} rounds", ammo);
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

        int current = gunItem.getCurrentAmmoCount(gun);
        int maximum = com.tacz.guns.util.AttachmentDataUtils
                .getAmmoCountWithAttachment(gun, gunIndex.getGunData());
        if (current >= maximum) {
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
        if (storedHasAmmo[0]) {
            cir.setReturnValue(true);
            return;
        }

        boolean available = shooter.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null)
                .map(handler -> MagazineReloadSource.hasUsableMagazine(handler, gun))
                .orElse(false);
        cir.setReturnValue(available);
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

    private static void clearLegacyCreativeSourceMarker(ItemStack magazine) {
        if (magazine.hasTag()) {
            magazine.getTag().remove("TaCZMagazinesCreativeSource");
        }
    }
}
