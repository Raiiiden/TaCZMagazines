package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.client.MagazineAmmoHudOverlay;
import com.raiiiden.taczmagazines.magazine.SplitReloadGuns;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(value = GunHudOverlay.class, remap = false)
public class MixinGunHudOverlay {

    @Shadow
    private static int cacheInventoryAmmoCount;

    // Replaces TaCZ's inventory ammo scan for magazine-system guns.
    @Inject(method = "handleInventoryAmmo", at = @At("HEAD"), cancellable = true)
    private static void onHandleInventoryAmmo(ItemStack gunItem, Inventory inventory, CallbackInfo ci) {
        if (!(gunItem.getItem() instanceof IGun iGun)) return;

        ResourceLocation gunId = iGun.getGunId(gunItem);
        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) return;

        FeedType feedType = gunIndex.getGunData().getReloadData().getType();
        if (!feedType.equals(FeedType.MAGAZINE)) return;

        if (MagazineFamilySystem.getFamilyForGun(gunId) == null) return;

        // Gun is in the magazine system: total rounds across compatible mags, respecting stack size.
        boolean splitReload = SplitReloadGuns.isSplitReload(gunId);
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.getItem() instanceof MagazineItem) {
                IAmmoBox iAmmoBox = (IAmmoBox) slot.getItem();
                if (!iAmmoBox.isAmmoBoxOfGun(gunItem, slot)) continue;
                int ammoPerMag = iAmmoBox.getAmmoCount(slot);
                if (ammoPerMag > 0) total += ammoPerMag * slot.getCount();
            } else if (splitReload && slot.getItem() instanceof IAmmo ammo
                    && ammo.isAmmoOfGun(gunItem, slot)) {
                // Split-reload guns chamber loose rounds directly, so count them in the reserve.
                total += slot.getCount();
            }
        }

        cacheInventoryAmmoCount = total;
        ci.cancel();
    }

    // When override_ammo_hud is on, blank the current-ammo "30" text (ordinal 0).
    // Passing "" to drawString renders nothing; the loaded-mag silhouette replaces it.
    @ModifyArg(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;FFIZ)I",
            ordinal = 0
        ),
        index = 1,
        remap = false
    )
    private String taczMag$blankCurrentAmmoText(String text) {
        return isMagHudActive() ? "" : text;
    }

    // When override_ammo_hud is on, blank the reserve-ammo "/90" text (ordinal 1).
    // The reserve-mag silhouettes replace it visually.
    @ModifyArg(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;FFIZ)I",
            ordinal = 1
        ),
        index = 1,
        remap = false
    )
    private String taczMag$blankReserveAmmoText(String text) {
        return isMagHudActive() ? "" : text;
    }

    private static boolean isMagHudActive() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return MagazineAmmoHudOverlay.overridesHudFor(mc.player.getMainHandItem());
    }
}
