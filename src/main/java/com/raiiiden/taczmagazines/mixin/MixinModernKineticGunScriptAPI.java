package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.magazine.SplitReloadGuns;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Makes Creative split-reload reloads fully free, since scripts don't check every draw.
@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class MixinModernKineticGunScriptAPI {

    @Shadow
    private ItemStack itemStack;

    @Shadow
    public abstract boolean isReloadingNeedConsumeAmmo();

    @Inject(method = "consumeAmmoFromPlayer", at = @At("HEAD"), cancellable = true)
    private void taczmagazines$freeInCreative(int neededAmount, CallbackInfoReturnable<Integer> cir) {
        if (itemStack == null || !SplitReloadGuns.managed(itemStack)) return;
        if (isReloadingNeedConsumeAmmo()) return;
        cir.setReturnValue(neededAmount);
    }
}
