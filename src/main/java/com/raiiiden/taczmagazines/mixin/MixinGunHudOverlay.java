package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(value = GunHudOverlay.class, remap = false)
public class MixinGunHudOverlay {

    @Shadow
    private static int cacheInventoryAmmoCount;

    /**
     * Replaces TaCZ's inventory ammo scan for magazine-system guns.
     *
     * TaCZ's original logic adds `getAmmoCount(slot)` per slot — it never multiplies
     * by `slot.getCount()`, so a stack of N mags each holding X rounds is counted as X
     * instead of N×X. We cancel the original and do the correct calculation.
     */
    @Inject(method = "handleInventoryAmmo", at = @At("HEAD"), cancellable = true)
    private static void onHandleInventoryAmmo(ItemStack gunItem, Inventory inventory, CallbackInfo ci) {
        if (!(gunItem.getItem() instanceof IGun iGun)) return;

        ResourceLocation gunId = iGun.getGunId(gunItem);
        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) return;

        FeedType feedType = gunIndex.getGunData().getReloadData().getType();
        if (!feedType.equals(FeedType.MAGAZINE)) return;

        if (MagazineFamilySystem.getFamilyForGun(gunId) == null) return;

        // Gun is in the magazine system — compute total rounds across all compatible mags,
        // correctly accounting for stack size.
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (!(slot.getItem() instanceof MagazineItem)) continue;
            IAmmoBox iAmmoBox = (IAmmoBox) slot.getItem();
            if (!iAmmoBox.isAmmoBoxOfGun(gunItem, slot)) continue;
            int ammoPerMag = iAmmoBox.getAmmoCount(slot);
            if (ammoPerMag > 0) {
                total += ammoPerMag * slot.getCount();
            }
        }

        cacheInventoryAmmoCount = total;
        ci.cancel();
    }
}
