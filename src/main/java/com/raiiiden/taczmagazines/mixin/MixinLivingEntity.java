package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.item.MagazineItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    // Suppress arm swing entirely when the main hand holds a magazine.
    // Targets the two-arg overload that performs the actual state change.
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"), cancellable = true)
    private void onSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND) return;
        LivingEntity self = (LivingEntity)(Object)this;
        if (self.getMainHandItem().getItem() instanceof MagazineItem) {
            ci.cancel();
        }
    }
}
