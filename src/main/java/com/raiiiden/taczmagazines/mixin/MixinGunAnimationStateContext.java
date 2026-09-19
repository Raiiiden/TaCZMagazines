package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.client.ClientReloadKeyHandler;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.SplitReloadGuns;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyIn(Dist.CLIENT)
@Mixin(value = GunAnimationStateContext.class, remap = false)
public class MixinGunAnimationStateContext {

    @Shadow
    private ItemStack currentGunItem;

    // Mirrors MixinModernKineticGunItem's branch pick by steering isNoAmmo() (chamber + loaded) for split-reload guns.
    // Non-split-reload guns are untouched.
    @Unique
    private static long taczmagazines$magCacheTick = Long.MIN_VALUE;
    @Unique
    private static boolean taczmagazines$magCacheResult = false;

    @Inject(method = "hasBulletInBarrel", at = @At("RETURN"), cancellable = true)
    private void onHasBulletInBarrelReturn(CallbackInfoReturnable<Boolean> cir) {
        if (!taczmagazines$isLocalSplitReloadGun()) return;
        if (!(currentGunItem.getItem() instanceof IGun iGun)) return;

        // With a scope fitted the script loads single rounds regardless, so leave it be.
        if (SplitReloadGuns.scopeBlocksBulkLoad(currentGunItem)) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        // A clip is going in: report an empty chamber; the server already moved that round into the magazine.
        if (taczmagazines$toppingUpFromMagazine()) {
            cir.setReturnValue(false);
            return;
        }

        // Only an entirely empty gun takes the clip branch; read the stack count, not overridden getAmmoCount().
        if (Boolean.TRUE.equals(cir.getReturnValue())) return;
        if (iGun.getCurrentAmmoCount(currentGunItem) > 0) return;
        if (taczmagazines$hasMagazine(player, currentGunItem)) return;
        cir.setReturnValue(true);
    }

    // Other half of isNoAmmo(): reports empty only while a clip goes into a gun that still has rounds.
    @Inject(method = "getAmmoCount", at = @At("RETURN"), cancellable = true)
    private void onGetAmmoCountReturn(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValue() <= 0) return;
        if (!taczmagazines$isLocalSplitReloadGun()) return;
        if (SplitReloadGuns.scopeBlocksBulkLoad(currentGunItem)) return;
        if (taczmagazines$toppingUpFromMagazine()) cir.setReturnValue(0);
    }

    // Reads the single frozen decision from ClientReloadKeyHandler.decideBulkReload.
    @Unique
    private boolean taczmagazines$toppingUpFromMagazine() {
        return ClientReloadKeyHandler.isFrozenBulkReload();
    }

    @Unique
    private boolean taczmagazines$isLocalSplitReloadGun() {
        if (currentGunItem == null || currentGunItem.isEmpty()) return false;
        if (!(currentGunItem.getItem() instanceof IGun iGun)) return false;
        if (!SplitReloadGuns.managed(currentGunItem)) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        return iGun.getGunId(currentGunItem).equals(taczmagazines$mainHandGunId(player));
    }

    @Unique
    private static ResourceLocation taczmagazines$mainHandGunId(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        return held.getItem() instanceof IGun heldGun ? heldGun.getGunId(held) : null;
    }

    // Inventory sweep is cached per tick since hasBulletInBarrel is polled every frame.
    @Unique
    private static boolean taczmagazines$hasMagazine(LocalPlayer player, ItemStack gun) {
        long tick = player.tickCount;
        if (tick != taczmagazines$magCacheTick) {
            taczmagazines$magCacheTick = tick;
            taczmagazines$magCacheResult = SplitReloadGuns.hasUsableMagazine(player, gun);
        }
        return taczmagazines$magCacheResult;
    }

    @Inject(method = "getMagExtentLevel", at = @At("RETURN"), cancellable = true)
    private void onGetMagExtentLevelReturn(CallbackInfoReturnable<Integer> cir) {
        if (!MechanicsConfig.ALLOW_EXTENDED_WITHOUT_ATTACHMENT.get()) return;
        if (currentGunItem == null || currentGunItem.isEmpty()) return;
        if (!(currentGunItem.getItem() instanceof IGun iGun)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean isLocalGun = mc.player.getMainHandItem().getItem() instanceof IGun localIGun
                && iGun.getGunId(currentGunItem).equals(localIGun.getGunId(mc.player.getMainHandItem()));

        // Frozen level covers pick through reload end; managed on client tick, never cleared from a read.
        int frozen = ClientReloadKeyHandler.getFrozenExtLevel();
        if (frozen >= 0 && isLocalGun) {
            cir.setReturnValue(frozen);
            return;
        }

        // Nothing frozen — the stored magazine is the source of truth.
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