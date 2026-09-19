package com.raiiiden.taczmagazines.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.client.ClientReloadKeyHandler;
import com.raiiiden.taczmagazines.client.MagazineItemRenderer;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.SplitReloadGuns;
import com.raiiiden.taczmagazines.magazine.GunMagazineInitializer;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.GETFIELD;

@OnlyIn(Dist.CLIENT)
@Mixin(value = BedrockGunModel.class, remap = false)
public class MixinBedrockGunModel {

    @Shadow
    private int currentExtendMagLevel;

    @Shadow
    private ItemStack currentGunItem;

    @Shadow
    private BedrockPart magazineNode;

    // Per-render saved visibility so we can restore after the frame.
    @Unique
    private boolean taczmagazines$savedMagNodeVisible = true;

    // Round bones this frame hid, so the next frame starts from the animation's own state.
    @Unique
    private final List<BedrockPart> taczmagazines$hiddenRounds = new ArrayList<>();

    // Chambers in the order the loader's rounds enter them, last measured while drawn; null until measured.
    @Unique
    private List<BedrockPart> taczmagazines$posedChambers;

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

        // Feature: hide mag bone when no magazine is loaded
        GunMagazineInitializer.ensureMagazineForLoadedGun(gunItem);
        if (currentGunItem != null && currentGunItem != gunItem) {
            GunMagazineInitializer.ensureMagazineForLoadedGun(currentGunItem);
        }

        taczmagazines$savedMagNodeVisible = magazineNode != null && magazineNode.visible;
        taczmagazines$hideMagBoneIfNeeded();
        taczmagazines$hideUnfilledRounds();

        // Feature: freeze extended-mag level during reload animation
        if (!MechanicsConfig.ALLOW_EXTENDED_WITHOUT_ATTACHMENT.get()) return;
        if (currentGunItem == null || currentGunItem.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean isLocalGun = currentGunItem.getItem() instanceof IGun iGunCheck
                && mc.player.getMainHandItem().getItem() instanceof IGun localIGun
                && iGunCheck.getGunId(currentGunItem).equals(localIGun.getGunId(mc.player.getMainHandItem()));

        // Applies from the pick, not server confirmation, so the model swaps as the animation starts.
        int frozen = ClientReloadKeyHandler.getFrozenExtLevel();
        if (frozen >= 0 && isLocalGun) {
            currentExtendMagLevel = frozen;
            return;
        }

        // Nothing frozen — capability is the source of truth
        currentGunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (!magCap.hasMagazine()) return;
            ItemStack storedMag = magCap.getStoredMagazine();
            if (!(storedMag.getItem() instanceof MagazineItem)) return;
            String familyId = MagazineItem.getMagazineFamilyId(storedMag);
            if (familyId == null || !MagazineFamilySystem.isExtendedFamily(familyId)) return;
            currentExtendMagLevel = MagazineFamilySystem.getExtLevelForFamily(familyId);
        });
    }

    @Inject(
            method = "render",
            at = @At("TAIL")
    )
    private void onAfterRender(PoseStack poseStack, ItemStack gunItem,
                               ItemDisplayContext displayContext, RenderType renderType,
                               int packedLight, int packedOverlay, CallbackInfo ci) {
        // Restore mag bone visibility so the next frame starts from the correct state.
        if (magazineNode != null) {
            magazineNode.visible = taczmagazines$savedMagNodeVisible;
        }
        for (BedrockPart round : taczmagazines$hiddenRounds) {
            round.visible = true;
        }
        taczmagazines$hiddenRounds.clear();
    }

    @Unique
    private void taczmagazines$hideMagBoneIfNeeded() {
        if (magazineNode == null || currentGunItem == null || currentGunItem.isEmpty()) return;
        GunMagazineInitializer.ensureMagazineForLoadedGun(currentGunItem);
        if (!(currentGunItem.getItem() instanceof IGun iGun)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ResourceLocation gunId = iGun.getGunId(currentGunItem);
        // Only apply to guns that participate in the magazine system.
        if (MagazineFamilySystem.getFamilyForGun(gunId) == null) return;
        // A split-reload gun's "magazine" bone is its floorplate/cylinder; don't hide it when empty.
        if (SplitReloadGuns.isSplitReload(gunId)) return;

        // For the local player's gun, skip during reload to let TaCZ's animation run.
        boolean isLocalGun = mc.player.getMainHandItem().getItem() instanceof IGun localIGun
                && gunId.equals(localIGun.getGunId(mc.player.getMainHandItem()));
        if (isLocalGun) {
            IGunOperator gunOp = IGunOperator.fromLivingEntity(mc.player);
            if (gunOp != null && gunOp.getSynReloadState().getStateType().isReloading()) return;
        }

        boolean hasMag = currentGunItem.getCapability(GunMagazineProvider.GUN_MAGAZINE)
                .map(GunMagazineCapability::hasMagazine).orElse(false);

        if (!hasMag) {
            magazineNode.visible = false;
        }
    }

    // Hides surplus round bones so a part-full clip/speed loader looks part-full.
    // Only ever hides, never re-shows what the animation removed.
    @Unique
    private void taczmagazines$hideUnfilledRounds() {
        taczmagazines$hiddenRounds.clear();
        if (!ClientReloadKeyHandler.isFrozenBulkReload()) taczmagazines$posedChambers = null;
        if (currentGunItem == null || currentGunItem.isEmpty()) return;
        if (!(currentGunItem.getItem() instanceof IGun iGun)) return;

        ResourceLocation gunId = iGun.getGunId(currentGunItem);
        if (!SplitReloadGuns.isSplitReload(gunId)) return;

        // Local player only: the count comes from their own picked magazine.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!(mc.player.getMainHandItem().getItem() instanceof IGun heldGun)) return;
        if (!gunId.equals(heldGun.getGunId(mc.player.getMainHandItem()))) return;

        int loaded = ClientReloadKeyHandler.getFrozenAmmoCount();
        if (loaded < 0) return;

        MagazineItemRenderer renderer = MagazineItemRenderer.getInstance();
        BedrockGunModel model = (BedrockGunModel) (Object) this;
        List<BedrockPart> rounds = renderer.getRoundBones(gunId, model);
        taczmagazines$hideFrom(rounds, loaded);

        // Empty the chambers matching hidden loader rounds; kept rounds fill the next ones.
        // Speed-loader reload only, and only after the animation has emptied the cylinder.
        if (!ClientReloadKeyHandler.isFrozenBulkReload()) return;
        List<BedrockPart> chambers = renderer.getChamberBones(gunId, model);
        if (chambers.isEmpty()) return;
        // Chamber pairing is read off the pose while the loader is visible; the last reading wins.
        if (taczmagazines$isDrawn(rounds.get(0).getParent())) {
            List<BedrockPart> posed = MagazineItemRenderer.pairChambersByPose(rounds, chambers);
            if (posed != null) taczmagazines$posedChambers = posed;
        }
        if (taczmagazines$posedChambers != null) chambers = taczmagazines$posedChambers;
        if (!ClientReloadKeyHandler.isFrozenCylinderEmptied()) {
            if (!taczmagazines$noneDrawn(chambers)) return;
            ClientReloadKeyHandler.markFrozenCylinderEmptied();
        }
        int filled = loaded + Math.max(0, ClientReloadKeyHandler.getFrozenGunRounds());
        taczmagazines$hideFrom(chambers, filled);
    }

    // Whether the animation hides all these bones (invisible or scaled to zero, directly or via a parent).
    @Unique
    private static boolean taczmagazines$noneDrawn(List<BedrockPart> bones) {
        for (BedrockPart bone : bones) {
            if (taczmagazines$isDrawn(bone)) return false;
        }
        return true;
    }

    @Unique
    private static boolean taczmagazines$isDrawn(BedrockPart bone) {
        for (BedrockPart part = bone; part != null; part = part.getParent()) {
            if (!part.visible) return false;
            if (Math.abs(part.xScale) < 1.0e-4f || Math.abs(part.yScale) < 1.0e-4f
                    || Math.abs(part.zScale) < 1.0e-4f) return false;
        }
        return true;
    }

    @Unique
    private void taczmagazines$hideFrom(List<BedrockPart> bones, int keep) {
        for (int i = Math.max(0, keep); i < bones.size(); i++) {
            BedrockPart bone = bones.get(i);
            if (!bone.visible) continue;
            bone.visible = false;
            taczmagazines$hiddenRounds.add(bone);
        }
    }
}
