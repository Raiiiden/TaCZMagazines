package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.client.ClientReloadKeyHandler;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyIn(Dist.CLIENT)
@Mixin(value = GunAnimationStateContext.class, remap = false)
public class MixinGunAnimationStateContext {

    @Shadow
    private ItemStack currentGunItem;

    @Inject(method = "getMagExtentLevel", at = @At("RETURN"), cancellable = true)
    private void onGetMagExtentLevelReturn(CallbackInfoReturnable<Integer> cir) {
        if (!MechanicsConfig.ALLOW_EXTENDED_WITHOUT_ATTACHMENT.get()) return;
        if (currentGunItem == null || currentGunItem.isEmpty()) return;
        if (!(currentGunItem.getItem() instanceof IGun iGun)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        IGunOperator gunOp = IGunOperator.fromLivingEntity(mc.player);
        boolean isReloading = gunOp != null && gunOp.getSynReloadState().getStateType().isReloading();
        boolean isLocalGun = mc.player.getMainHandItem().getItem() instanceof IGun localIGun
                && iGun.getGunId(currentGunItem).equals(localIGun.getGunId(mc.player.getMainHandItem()));

        if (isReloading && isLocalGun) {
            // Use the frozen level set at reload-start — never re-scan mid-animation
            int frozen = ClientReloadKeyHandler.getFrozenExtLevel();
            if (frozen >= 0) {
                cir.setReturnValue(frozen);
            }
            return;
        }

        // Not reloading — clear frozen level and fall back to stored capability
        ClientReloadKeyHandler.clearFrozenExtLevel();
        if (cir.getReturnValue() > 0) return;
        currentGunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (!magCap.hasMagazine()) return;
            ItemStack storedMag = magCap.getStoredMagazine();
            if (!(storedMag.getItem() instanceof MagazineItem)) return;
            String familyId = MagazineItem.getMagazineFamilyId(storedMag);
            if (familyId == null || !MagazineFamilySystem.isExtendedFamily(familyId)) return;
            cir.setReturnValue(MagazineFamilySystem.getExtLevelForFamily(familyId));
        });
    }
}