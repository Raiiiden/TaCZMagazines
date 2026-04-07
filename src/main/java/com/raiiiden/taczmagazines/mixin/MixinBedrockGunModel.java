package com.raiiiden.taczmagazines.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.client.ClientReloadKeyHandler;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(value = BedrockGunModel.class, remap = false)
public class MixinBedrockGunModel {

    @Shadow
    private int currentExtendMagLevel;

    @Shadow
    private ItemStack currentGunItem;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/BedrockAnimatedModel;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V"
            )
    )
    private void onBeforeAnimatedRender(PoseStack poseStack, ItemStack gunItem,
                                        ItemDisplayContext displayContext, RenderType renderType,
                                        int packedLight, int packedOverlay, CallbackInfo ci) {
        if (!MechanicsConfig.ALLOW_EXTENDED_WITHOUT_ATTACHMENT.get()) return;
        if (currentGunItem == null || currentGunItem.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        IGunOperator gunOp = IGunOperator.fromLivingEntity(mc.player);
        boolean isReloading = gunOp != null && gunOp.getSynReloadState().getStateType().isReloading();
        boolean isLocalGun = currentGunItem.getItem() instanceof IGun iGunCheck
                && mc.player.getMainHandItem().getItem() instanceof IGun localIGun
                && iGunCheck.getGunId(currentGunItem).equals(localIGun.getGunId(mc.player.getMainHandItem()));

        if (isReloading && isLocalGun) {
            // Use the frozen level set at reload-start — never re-scan mid-animation
            int frozen = ClientReloadKeyHandler.getFrozenExtLevel();
            if (frozen >= 0) {
                currentExtendMagLevel = frozen;
            }
            return;
        }

        // Not reloading — capability is the source of truth
        currentGunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (!magCap.hasMagazine()) return;
            ItemStack storedMag = magCap.getStoredMagazine();
            if (!(storedMag.getItem() instanceof MagazineItem)) return;
            String familyId = MagazineItem.getMagazineFamilyId(storedMag);
            if (familyId == null || !MagazineFamilySystem.isExtendedFamily(familyId)) return;
            currentExtendMagLevel = MagazineFamilySystem.getExtLevelForFamily(familyId);
        });
    }
}