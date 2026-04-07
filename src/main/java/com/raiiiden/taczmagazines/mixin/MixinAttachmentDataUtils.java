package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AttachmentDataUtils.class, remap = false)
public abstract class MixinAttachmentDataUtils {

    // When allow_extended_without_attachment is on, report the extended level of whatever
    // mag is currently stored in the gun's magazine capability so that:
    //   - the gun model shows the correct extended mag bone
    //   - getAmmoCountWithAttachment returns the extended capacity
    // Only fires for guns managed by our magazine system (capability present + mag stored).
    @Inject(method = "getMagExtendLevel", at = @At("HEAD"), cancellable = true)
    private static void onGetMagExtendLevel(ItemStack gunStack, GunData gunData,
                                            CallbackInfoReturnable<Integer> cir) {
        if (!MechanicsConfig.ALLOW_EXTENDED_WITHOUT_ATTACHMENT.get()) return;

        int[] result = {-1};
        gunStack.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (!magCap.hasMagazine()) return;
            ItemStack storedMag = magCap.getStoredMagazine();
            if (!(storedMag.getItem() instanceof MagazineItem)) return;

            String familyId = MagazineItem.getMagazineFamilyId(storedMag);
            if (familyId == null) return;
            if (!MagazineFamilySystem.isExtendedFamily(familyId)) return;

            result[0] = MagazineFamilySystem.getExtLevelForFamily(familyId);
        });

        if (result[0] > 0) {
            cir.setReturnValue(result[0]);
        }
    }
}
