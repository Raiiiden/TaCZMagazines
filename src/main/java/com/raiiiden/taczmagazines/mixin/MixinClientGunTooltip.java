package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.item.MagazineRegistrar;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.magazine.GunMagazineInitializer;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.tooltip.ClientGunTooltip;
import com.tacz.guns.inventory.tooltip.GunTooltip;
import com.tacz.guns.item.GunTooltipPart;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

@OnlyIn(Dist.CLIENT)
@Mixin(value = ClientGunTooltip.class, remap = false)
public class MixinClientGunTooltip {

    @Shadow private Component ammoName;
    @Shadow private MutableComponent ammoCountText;
    @Shadow @Nullable private List<FormattedCharSequence> desc;
    @Shadow private int maxWidth;

    @Unique private static Field taczMag$ammoField = null;
    @Unique private static boolean taczMag$reflectionInit = false;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void afterInit(GunTooltip tooltip, CallbackInfo ci) {
        ItemStack gunStack = tooltip.getGun();
        GunMagazineInitializer.ensureMagazineForLoadedGun(gunStack);

        IGun iGunLocal = tooltip.getIGun();
        CommonGunIndex gunIndex = tooltip.getGunIndex();

        if ((GunTooltipPart.getHideFlags(gunStack) & GunTooltipPart.AMMO_INFO.getMask()) != 0) return;
        if (gunIndex.getGunData().getReloadData().getType() != FeedType.MAGAZINE) return;

        ResourceLocation gunId = iGunLocal.getGunId(gunStack);
        String familyId = MagazineFamilySystem.getFamilyForGun(gunId);
        if (familyId == null) return;

        // The ammo row alternates between TaCZ's loose-round entry and the magazine on a wall-clock phase.
        if (!taczMag$magazinePhase()) return;

        ItemStack stored = taczMag$getStoredMagazine(gunStack);
        ItemStack magStack = stored.isEmpty()
                ? MagazineItem.createMagazineByFamily(MagazineRegistrar.MAGAZINE.get(), familyId, 0)
                : stored;

        // ammo is final — use reflection to write it before the object is used.
        // This is safe here because we're still inside the constructor call chain
        // and no other thread has a reference to this object yet.
        taczMag$writeAmmoField(magStack);

        ammoName = magStack.getHoverName();

        int current = iGunLocal.getCurrentAmmoCount(gunStack);
        int max = AttachmentDataUtils.getAmmoCountWithAttachment(gunStack, gunIndex.getGunData());
        ammoCountText = Component.literal(current + "/" + max);

        // Resize for the usually longer magazine name so the row stays inside the box.
        taczMag$growToFit();
    }

    // Which half of the cycle is showing. Shared by every tooltip on screen so they stay in step.
    @Unique
    private static boolean taczMag$magazinePhase() {
        int seconds = MechanicsConfig.TOOLTIP_CYCLE_SECONDS.get();
        if (seconds <= 0) return true;
        return (System.currentTimeMillis() / (seconds * 1000L)) % 2 == 0;
    }

    @Unique
    private void taczMag$growToFit() {
        Font font = Minecraft.getInstance().font;
        if (font == null) return;
        // Same 22px allowance for the item icon that getText() uses on this row.
        if (ammoName != null) maxWidth = Math.max(maxWidth, font.width(ammoName) + 22);
        if (ammoCountText != null) maxWidth = Math.max(maxWidth, font.width(ammoCountText) + 22);
    }

    @Unique
    private void taczMag$writeAmmoField(ItemStack value) {
        if (!taczMag$reflectionInit) {
            taczMag$reflectionInit = true;
            try {
                Field f = ClientGunTooltip.class.getDeclaredField("ammo");
                f.setAccessible(true);
                taczMag$ammoField = f;
            } catch (Exception e) {
                // field name may be obfuscated — try common SRG patterns
                for (Field f : ClientGunTooltip.class.getDeclaredFields()) {
                    if (f.getType() == ItemStack.class) {
                        // ammo is the only non-gun ItemStack field
                        // gun is also ItemStack but declared first — ammo is second
                        // We'll pick by checking if it's not named "gun" equivalent
                        f.setAccessible(true);
                        // store tentatively; we'll use the last ItemStack field that isn't gun
                        taczMag$ammoField = f;
                    }
                }
            }
        }
        if (taczMag$ammoField == null) return;
        try {
            taczMag$ammoField.set(this, value);
        } catch (Exception ignored) {}
    }

    @Unique
    private static ItemStack taczMag$getStoredMagazine(ItemStack gun) {
        ItemStack[] result = { ItemStack.EMPTY };
        gun.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(cap -> {
            if (cap.hasMagazine()) result[0] = cap.getStoredMagazine();
        });
        return result[0];
    }
}
