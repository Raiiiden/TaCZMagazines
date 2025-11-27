package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * Intercept findAndExtractInventoryAmmo to handle magazine system
     */
    @Inject(method = "findAndExtractInventoryAmmo", at = @At("HEAD"), cancellable = true)
    private void onFindAndExtractInventoryAmmo(IItemHandler itemHandler, ItemStack gunItem, int needAmmoCount, CallbackInfoReturnable<Integer> cir) {
        AbstractGunItem gunItemInstance = (AbstractGunItem) (Object) this;
        ResourceLocation gunId = gunItemInstance.getGunId(gunItem);

        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) {
            return;
        }

        FeedType feedType = gunIndex.getGunData().getReloadData().getType();

        // Only intercept for MAGAZINE feed type
        if (!feedType.equals(FeedType.MAGAZINE)) {
            return;
        }

        // Check if gun already has a magazine stored
        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (magCap.hasMagazine()) {
                ItemStack storedMag = magCap.getStoredMagazine();
                if (storedMag.getItem() instanceof MagazineItem magItem) {
                    int ammoInMag = magItem.getAmmoCount(storedMag);
                    int extracted = Math.min(ammoInMag, needAmmoCount);

                    // Consume ammo from stored magazine
                    magItem.setAmmoCount(storedMag, ammoInMag - extracted);
                    if (ammoInMag - extracted <= 0) {
                        magItem.setAmmoId(storedMag, DefaultAssets.EMPTY_AMMO_ID);
                    }

                    cir.setReturnValue(extracted);
                    return;
                }
            }
        });

        // No magazine in gun - try to find one in inventory
        // **FIX: First pass - look for magazines with ammo**
        int cnt = needAmmoCount;
        ItemStack bestMagazine = ItemStack.EMPTY;
        int bestMagazineSlot = -1;
        int bestMagazineAmmo = 0;

        // Find the magazine with the most ammo
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack checkStack = itemHandler.getStackInSlot(i);
            Item item = checkStack.getItem();

            if (item instanceof MagazineItem) {
                IAmmoBox iAmmoBox = (IAmmoBox) item;
                if (iAmmoBox.isAmmoBoxOfGun(gunItem, checkStack)) {
                    int ammoInMag = iAmmoBox.getAmmoCount(checkStack);

                    // Prioritize magazines with ammo
                    if (ammoInMag > bestMagazineAmmo) {
                        bestMagazine = checkStack;
                        bestMagazineSlot = i;
                        bestMagazineAmmo = ammoInMag;
                    }
                }
            }
        }

        // If we found a magazine with ammo, use it
        if (bestMagazineSlot != -1 && bestMagazineAmmo > 0) {
            ItemStack extractedMag = itemHandler.extractItem(bestMagazineSlot, 1, false);

            if (!extractedMag.isEmpty()) {
                // Store magazine in gun
                gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
                    magCap.setStoredMagazine(extractedMag);
                });

                // Get ammo from the magazine
                if (extractedMag.getItem() instanceof MagazineItem magItem) {
                    int ammoInMag = magItem.getAmmoCount(extractedMag);
                    int extracted = Math.min(ammoInMag, cnt);

                    magItem.setAmmoCount(extractedMag, ammoInMag - extracted);
                    if (ammoInMag - extracted <= 0) {
                        magItem.setAmmoId(extractedMag, DefaultAssets.EMPTY_AMMO_ID);
                    }

                    cnt -= extracted;
                }
            }
        }

        cir.setReturnValue(needAmmoCount - cnt);
    }

    /**
     * Intercept canReload to check for magazines
     */
    @Inject(method = "canReload", at = @At("HEAD"), cancellable = true)
    private void onCanReload(LivingEntity shooter, ItemStack gunItem, CallbackInfoReturnable<Boolean> cir) {
        AbstractGunItem gunItemInstance = (AbstractGunItem) (Object) this;
        ResourceLocation gunId = gunItemInstance.getGunId(gunItem);

        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) {
            cir.setReturnValue(false);
            return;
        }

        FeedType feedType = gunIndex.getGunData().getReloadData().getType();

        // Only intercept for MAGAZINE feed type
        if (!feedType.equals(FeedType.MAGAZINE)) {
            return;
        }

        int currentAmmoCount = gunItemInstance.getCurrentAmmoCount(gunItem);
        int maxAmmoCount = com.tacz.guns.util.AttachmentDataUtils.getAmmoCountWithAttachment(gunItem, gunIndex.getGunData());

        if (currentAmmoCount >= maxAmmoCount) {
            cir.setReturnValue(false);
            return;
        }

        if (gunItemInstance.useInventoryAmmo(gunItem)) {
            cir.setReturnValue(false);
            return;
        }

        if (gunIndex.getGunData().getReloadData().isInfinite()) {
            cir.setReturnValue(true);
            return;
        }

        if (gunItemInstance.useDummyAmmo(gunItem)) {
            cir.setReturnValue(gunItemInstance.getDummyAmmoAmount(gunItem) > 0);
            return;
        }

        // Check if gun has a magazine with ammo OR if player has magazines in inventory
        boolean[] canReload = {false};

        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (magCap.hasMagazine()) {
                ItemStack storedMag = magCap.getStoredMagazine();
                if (storedMag.getItem() instanceof MagazineItem magItem) {
                    if (magItem.getAmmoCount(storedMag) > 0) {
                        canReload[0] = true;
                        return;
                    }
                }
            }
        });

        if (canReload[0]) {
            cir.setReturnValue(true);
            return;
        }

        // Check for magazines in inventory
        Boolean hasMagazine = shooter.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null).map(cap -> {
            for (int i = 0; i < cap.getSlots(); i++) {
                ItemStack checkStack = cap.getStackInSlot(i);
                Item item = checkStack.getItem();

                if (item instanceof MagazineItem) {
                    IAmmoBox iAmmoBox = (IAmmoBox) item;
                    if (iAmmoBox.isAmmoBoxOfGun(gunItem, checkStack) && iAmmoBox.getAmmoCount(checkStack) > 0) {
                        return true;
                    }
                }
            }
            return false;
        }).orElse(false);

        cir.setReturnValue(hasMagazine);
    }

    /**
     * Intercept dropAllAmmo to eject the stored magazine
     */
    @Inject(method = "dropAllAmmo", at = @At("HEAD"), cancellable = true)
    private void onDropAllAmmo(Player player, ItemStack gunItem, CallbackInfo ci) {
        AbstractGunItem gunItemInstance = (AbstractGunItem)(Object) this;
        ResourceLocation gunId = gunItemInstance.getGunId(gunItem);

        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) {
            return;
        }

        FeedType feedType = gunIndex.getGunData().getReloadData().getType();
        if (!feedType.equals(FeedType.MAGAZINE)) {
            return;
        }

        gunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (magCap.hasMagazine()) {
                ItemStack storedMag = magCap.getStoredMagazine();

                // Put remaining ammo back into the magazine before ejecting
                if (storedMag.getItem() instanceof MagazineItem magItem) {
                    int currentAmmoInGun = gunItemInstance.getCurrentAmmoCount(gunItem);
                    int currentAmmoInMag = magItem.getAmmoCount(storedMag);

                    // **FIX: Get the ammo ID from the gun's data**
                    ResourceLocation gunAmmoId = gunIndex.getGunData().getAmmoId();
                    if (currentAmmoInGun > 0 && !gunAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
                        magItem.setAmmoId(storedMag, gunAmmoId);
                    }

                    // Add gun's ammo back to magazine
                    magItem.setAmmoCount(storedMag, currentAmmoInMag + currentAmmoInGun);
                }

                // Clear gun and capability FIRST
                gunItemInstance.setCurrentAmmoCount(gunItem, 0);
                magCap.clearMagazine();

                // THEN give the modified magazine to player
                boolean added = player.getInventory().add(storedMag);
                if (!added) {
                    player.drop(storedMag, false);
                }

                TaCZMagazines.LOGGER.debug("Ejected magazine from gun");
            }
        });

        ci.cancel();
    }
}