package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.client.ClientReloadKeyHandler;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.network.OpenSelectorPacket;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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
    private void onFindAndExtractInventoryAmmo(IItemHandler itemHandler, ItemStack gunItem, int needAmmoCount, CallbackInfoReturnable<Integer> cir) {
        TaCZMagazines.LOGGER.debug("=== findAndExtractInventoryAmmo === needAmmoCount={} currentAmmo={}",
                needAmmoCount, ((AbstractGunItem)(Object)this).getCurrentAmmoCount(gunItem));

        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap ->
                TaCZMagazines.LOGGER.debug("  stored mag present={}, ammo={}",
                        magCap.hasMagazine(),
                        magCap.hasMagazine() && magCap.getStoredMagazine().getItem() instanceof MagazineItem m
                                ? m.getAmmoCount(magCap.getStoredMagazine()) : -1)
        );

        AbstractGunItem gunItemInstance = (AbstractGunItem) (Object) this;
        ResourceLocation gunId = gunItemInstance.getGunId(gunItem);

        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) {
            TaCZMagazines.LOGGER.debug("  no gun index — returning");
            return;
        }

        FeedType feedType = gunIndex.getGunData().getReloadData().getType();
        if (!feedType.equals(FeedType.MAGAZINE)) {
            TaCZMagazines.LOGGER.debug("  not magazine feed type — returning");
            return;
        }

        if (com.raiiiden.taczmagazines.magazine.MagazineFamilySystem.getFamilyForGun(gunId) == null) {
            TaCZMagazines.LOGGER.debug("  gun not in any magazine family — deferring to original");
            return;
        }

        if (ClientReloadKeyHandler.isSelectorOpen()) {
            TaCZMagazines.LOGGER.debug("  selector open — returning 0");
            cir.setReturnValue(0);
            return;
        }

        // Always eject the current stored mag back to inventory before loading a new one,
        // writing remainingInGun into it as the correct count. Works for both tap and select.
        int currentAmmo = gunItemInstance.getCurrentAmmoCount(gunItem);
        ResourceLocation gunAmmoId = gunIndex.getGunData().getAmmoId();

        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (!magCap.hasMagazine()) return;
            ItemStack storedMag = magCap.getStoredMagazine();
            if (storedMag.getItem() instanceof MagazineItem magItem) {
                if (currentAmmo > 0 && !gunAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
                    magItem.setAmmoId(storedMag, gunAmmoId);
                    magItem.setAmmoCount(storedMag, currentAmmo);
                } else {
                    magItem.setAmmoCount(storedMag, 0);
                    magItem.setAmmoId(storedMag, DefaultAssets.EMPTY_AMMO_ID);
                }
                magCap.setStoredMagazine(storedMag);
                storedMag = magCap.getStoredMagazine();
            }
            magCap.clearMagazine();
            ItemHandlerHelper.insertItemStacked(itemHandler, storedMag, false);
            TaCZMagazines.LOGGER.debug("  ejected stored mag back to inventory with ammo={}", currentAmmo);
            gunItemInstance.setCurrentAmmoCount(gunItem, 0);
        });

        // No stored mag — find one in inventory.
        int selectedSlot = -1;
        if (gunItem.hasTag() && gunItem.getTag().contains("TaCZMag_SelectedSlot")) {
            selectedSlot = gunItem.getTag().getInt("TaCZMag_SelectedSlot");
            gunItem.getTag().remove("TaCZMag_SelectedSlot");
            TaCZMagazines.LOGGER.debug("  using selected slot={}", selectedSlot);
        }

        int bestSlot = -1;
        int bestAmmo = 0;

        if (selectedSlot >= 0 && selectedSlot < itemHandler.getSlots()) {
            ItemStack checkStack = itemHandler.getStackInSlot(selectedSlot);
            if (checkStack.getItem() instanceof MagazineItem) {
                IAmmoBox iAmmoBox = (IAmmoBox) checkStack.getItem();
                if (iAmmoBox.isAmmoBoxOfGun(gunItem, checkStack) && iAmmoBox.getAmmoCount(checkStack) > 0) {
                    bestSlot = selectedSlot;
                    bestAmmo = iAmmoBox.getAmmoCount(checkStack);
                    TaCZMagazines.LOGGER.debug("  selected slot valid, bestAmmo={}", bestAmmo);
                } else {
                    TaCZMagazines.LOGGER.debug("  selected slot invalid or empty — falling back to search");
                }
            }
        }

        if (bestSlot == -1) {
            for (int i = 0; i < itemHandler.getSlots(); i++) {
                ItemStack checkStack = itemHandler.getStackInSlot(i);
                if (checkStack.getItem() instanceof MagazineItem) {
                    IAmmoBox iAmmoBox = (IAmmoBox) checkStack.getItem();
                    if (iAmmoBox.isAmmoBoxOfGun(gunItem, checkStack)) {
                        int ammo = iAmmoBox.getAmmoCount(checkStack);
                        if (ammo > bestAmmo) {
                            bestAmmo = ammo;
                            bestSlot = i;
                        }
                    }
                }
            }
            TaCZMagazines.LOGGER.debug("  inventory search done, bestSlot={} bestAmmo={}", bestSlot, bestAmmo);
        }

        if (bestSlot == -1 || bestAmmo <= 0) {
            TaCZMagazines.LOGGER.debug("  no usable mag found — returning 0");
            cir.setReturnValue(0);
            return;
        }

        ItemStack extractedMag = itemHandler.extractItem(bestSlot, 1, false);
        if (extractedMag.isEmpty()) {
            TaCZMagazines.LOGGER.debug("  extraction returned empty — returning 0");
            cir.setReturnValue(0);
            return;
        }

        if (!(extractedMag.getItem() instanceof MagazineItem magItem)) {
            TaCZMagazines.LOGGER.debug("  extracted item is not MagazineItem — returning 0");
            cir.setReturnValue(0);
            return;
        }

        int ammoInMag = magItem.getAmmoCount(extractedMag);
        TaCZMagazines.LOGGER.debug("  extracted mag from slot={}, ammoInMag={} — storing full, returning count", bestSlot, ammoInMag);

        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap ->
                magCap.setStoredMagazine(extractedMag)
        );

        cir.setReturnValue(ammoInMag);
    }

    @Inject(method = "canReload", at = @At("HEAD"), cancellable = true)
    private void onCanReload(LivingEntity shooter, ItemStack gunItem, CallbackInfoReturnable<Boolean> cir) {
        TaCZMagazines.LOGGER.debug("=== canReload === currentAmmo={}",
                ((AbstractGunItem)(Object)this).getCurrentAmmoCount(gunItem));

        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap ->
                TaCZMagazines.LOGGER.debug("  stored mag present={}, ammo={}",
                        magCap.hasMagazine(),
                        magCap.hasMagazine() && magCap.getStoredMagazine().getItem() instanceof MagazineItem m
                                ? m.getAmmoCount(magCap.getStoredMagazine()) : -1)
        );

        AbstractGunItem gunItemInstance = (AbstractGunItem) (Object) this;
        ResourceLocation gunId = gunItemInstance.getGunId(gunItem);

        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) {
            TaCZMagazines.LOGGER.debug("  no gun index — deferring to original");
            return;
        }

        FeedType feedType = gunIndex.getGunData().getReloadData().getType();
        if (!feedType.equals(FeedType.MAGAZINE)) {
            TaCZMagazines.LOGGER.debug("  not magazine feed type — deferring to original");
            return;
        }

        if (com.raiiiden.taczmagazines.magazine.MagazineFamilySystem.getFamilyForGun(gunId) == null) {
            TaCZMagazines.LOGGER.debug("  gun not in any magazine family — deferring to original");
            return;
        }

        // Block reload for MAGAZINE guns while the selector UI is open.
        if (shooter instanceof ServerPlayer sp &&
                OpenSelectorPacket.SELECTING_PLAYERS.contains(sp.getUUID())) {
            TaCZMagazines.LOGGER.debug("  player is selecting — returning false");
            cir.setReturnValue(false);
            return;
        }

        if (gunItemInstance.useInventoryAmmo(gunItem)) {
            TaCZMagazines.LOGGER.debug("  uses inventory ammo — returning false");
            cir.setReturnValue(false);
            return;
        }

        if (gunIndex.getGunData().getReloadData().isInfinite()) {
            TaCZMagazines.LOGGER.debug("  infinite ammo — returning true");
            cir.setReturnValue(true);
            return;
        }

        if (gunItemInstance.useDummyAmmo(gunItem)) {
            boolean result = gunItemInstance.getDummyAmmoAmount(gunItem) > 0;
            TaCZMagazines.LOGGER.debug("  dummy ammo — returning {}", result);
            cir.setReturnValue(result);
            return;
        }

        int currentAmmoCount = gunItemInstance.getCurrentAmmoCount(gunItem);
        int maxAmmoCount = com.tacz.guns.util.AttachmentDataUtils.getAmmoCountWithAttachment(gunItem, gunIndex.getGunData());
        TaCZMagazines.LOGGER.debug("  currentAmmo={} maxAmmo={}", currentAmmoCount, maxAmmoCount);

        if (currentAmmoCount >= maxAmmoCount) {
            boolean[] hasMag = {false};
            gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap ->
                    hasMag[0] = magCap.hasMagazine()
            );
            if (hasMag[0]) {
                // Gun is full and has a stored mag — always allow reload so the
                // selector-chosen mag can be swapped in. findAndExtractInventoryAmmo
                // will eject the current mag and pull the selected one.
                TaCZMagazines.LOGGER.debug("  gun full + has stored mag — allowing reload for potential swap");
                cir.setReturnValue(true);
                return;
            }
            // Full gun, no stored mag — fall through to inventory check.
        }

        boolean[] canReload = {false};
        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (magCap.hasMagazine()) {
                ItemStack storedMag = magCap.getStoredMagazine();
                if (storedMag.getItem() instanceof MagazineItem magItem) {
                    if (magItem.getAmmoCount(storedMag) > 0) {
                        canReload[0] = true;
                    }
                }
            }
        });

        if (canReload[0]) {
            TaCZMagazines.LOGGER.debug("  stored mag has ammo — returning true");
            cir.setReturnValue(true);
            return;
        }

        Boolean hasMagazine = shooter.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null).map(cap -> {
            for (int i = 0; i < cap.getSlots(); i++) {
                ItemStack checkStack = cap.getStackInSlot(i);
                if (checkStack.getItem() instanceof MagazineItem) {
                    IAmmoBox iAmmoBox = (IAmmoBox) checkStack.getItem();
                    if (iAmmoBox.isAmmoBoxOfGun(gunItem, checkStack) && iAmmoBox.getAmmoCount(checkStack) > 0) {
                        TaCZMagazines.LOGGER.debug("  found compatible mag in inventory at slot={}", i);
                        return true;
                    }
                }
            }
            return false;
        }).orElse(false);

        TaCZMagazines.LOGGER.debug("  inventory mag check result={}", hasMagazine);
        cir.setReturnValue(hasMagazine);
    }

    @Inject(method = "dropAllAmmo", at = @At("HEAD"), cancellable = true)
    private void onDropAllAmmo(Player player, ItemStack gunItem, CallbackInfo ci) {
        AbstractGunItem gunItemInstance = (AbstractGunItem)(Object) this;
        TaCZMagazines.LOGGER.debug("=== dropAllAmmo === currentAmmo={}", gunItemInstance.getCurrentAmmoCount(gunItem));

        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap ->
                TaCZMagazines.LOGGER.debug("  stored mag present={}, ammo={}",
                        magCap.hasMagazine(),
                        magCap.hasMagazine() && magCap.getStoredMagazine().getItem() instanceof MagazineItem m
                                ? m.getAmmoCount(magCap.getStoredMagazine()) : -1)
        );

        ResourceLocation gunId = gunItemInstance.getGunId(gunItem);

        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) {
            TaCZMagazines.LOGGER.debug("  no gun index — returning");
            return;
        }

        FeedType feedType = gunIndex.getGunData().getReloadData().getType();
        if (!feedType.equals(FeedType.MAGAZINE)) {
            TaCZMagazines.LOGGER.debug("  not magazine feed type — returning");
            return;
        }

        if (com.raiiiden.taczmagazines.magazine.MagazineFamilySystem.getFamilyForGun(gunId) == null) {
            TaCZMagazines.LOGGER.debug("  gun not in any magazine family — deferring to original");
            return;
        }

        // Keep stored magazine in gun while selector is open; the drop will happen
        // after selection when the actual reload fires.
        if (player instanceof ServerPlayer sp &&
                OpenSelectorPacket.SELECTING_PLAYERS.contains(sp.getUUID())) {
            TaCZMagazines.LOGGER.debug("  player is selecting — cancelling");
            ci.cancel();
            return;
        }


        int remainingInGun = gunItemInstance.getCurrentAmmoCount(gunItem);
        ResourceLocation gunAmmoId = gunIndex.getGunData().getAmmoId();

        boolean[] hadMag = {false};
        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (!magCap.hasMagazine()) return;
            hadMag[0] = true;

            ItemStack storedMag = magCap.getStoredMagazine();
            if (storedMag.getItem() instanceof MagazineItem magItem) {
                // remainingInGun is the ground truth — the mag's own count is stale
                // since we stored it full and TaCZ wrote the actual loaded amount to the gun.
                TaCZMagazines.LOGGER.debug("  writing remainingInGun={} back into mag (ignoring stale mag count)", remainingInGun);

                if (remainingInGun > 0 && !gunAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
                    magItem.setAmmoId(storedMag, gunAmmoId);
                    magItem.setAmmoCount(storedMag, remainingInGun);
                } else {
                    magItem.setAmmoCount(storedMag, 0);
                    magItem.setAmmoId(storedMag, DefaultAssets.EMPTY_AMMO_ID);
                }
                magCap.setStoredMagazine(storedMag);
                storedMag = magCap.getStoredMagazine();
            }

            gunItemInstance.setCurrentAmmoCount(gunItem, 0);
            magCap.clearMagazine();

            boolean added = player.getInventory().add(storedMag);
            TaCZMagazines.LOGGER.debug("  ejected mag with ammo={}, added to inventory={}", remainingInGun, added);
            if (!added) {
                player.drop(storedMag, false);
            }
        });

        if (!hadMag[0] && remainingInGun > 0) {
            TaCZMagazines.LOGGER.debug("  no stored mag but gun had ammo={} — zeroing", remainingInGun);
            gunItemInstance.setCurrentAmmoCount(gunItem, 0);
        }

        ci.cancel();
    }
}