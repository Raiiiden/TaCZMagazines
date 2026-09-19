package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.magazine.SplitReloadGuns;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.item.ModernKineticGunItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Picks a split-reload gun's reload branch by rewriting the reload state before startReload.
// Tactical runs the script's round-by-round loop; empty runs its bulk insert.
@Mixin(value = ModernKineticGunItem.class, remap = false)
public abstract class MixinModernKineticGunItem {

    @Inject(method = "startReload", at = @At("HEAD"))
    private void taczmagazines$chooseBulkOrRounds(ShooterDataHolder dataHolder, ItemStack gun,
                                                  LivingEntity shooter,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (shooter == null || shooter.level().isClientSide()) return;

        // Markers only live for the length of one reload; a new one starts from nothing.
        SplitReloadGuns.clearReloadMarkers(gun, shooter);
        if (!SplitReloadGuns.managed(gun)) return;

        // The script refuses its own bulk insert here, so there is nothing to take over.
        if (SplitReloadGuns.scopeBlocksBulkLoad(gun)) return;

        if (dataHolder.reloadStateType == ReloadState.StateType.EMPTY_RELOAD_FEEDING) {
            if (SplitReloadGuns.hasUsableMagazine(shooter, gun)) {
                SplitReloadGuns.markBulkReload(gun);
                return;
            }

            // Empty gun with no magazine: report tactical so the script loads single rounds (mirrored client-side).
            dataHolder.reloadStateType = ReloadState.StateType.TACTICAL_RELOAD_FEEDING;
            TaCZMagazines.LOGGER.debug(
                    "Split reload: no magazine for {}, falling back to the round-by-round reload",
                    gun.getItem());
            return;
        }

        // Gun still has rounds so TaCZ goes tactical, but a clip fits just as well.
        if (dataHolder.reloadStateType != ReloadState.StateType.TACTICAL_RELOAD_FEEDING) return;
        if (!MechanicsConfig.ALLOW_BULK_RELOAD_WHEN_LOADED.get()) return;

        // A selector pick settles it (frozen client-side in ClientReloadKeyHandler).
        if (!SplitReloadGuns.deviceWasChosen(shooter, gun)) {
            if (!SplitReloadGuns.hasUsableMagazine(shooter, gun)) return;
            // A picked clip goes in whenever there are no loose rounds left to feed.
            if (MechanicsConfig.PARTIAL_BULK_RELOAD_REQUIRES_SELECTOR.get()
                    && SplitReloadGuns.hasLooseAmmo(shooter, gun)) return;
        }

        dataHolder.reloadStateType = ReloadState.StateType.EMPTY_RELOAD_FEEDING;
        SplitReloadGuns.markBulkReload(gun);
        // The bulk insert assumes an empty chamber; make it so without losing the round in it.
        SplitReloadGuns.unchamberForTopUp(gun);
        // Speed loaders fill an emptied cylinder; dump before the script counts rounds.
        SplitReloadGuns.dumpForSpeedLoader(gun, shooter);
        TaCZMagazines.LOGGER.debug("Split reload: loading {} from a magazine over rounds it still holds", gun.getItem());
    }

    @Inject(method = "tickReload", at = @At("RETURN"))
    private void taczmagazines$clearMarkersWhenDone(ShooterDataHolder dataHolder, ItemStack gun,
                                                    LivingEntity shooter,
                                                    CallbackInfoReturnable<ReloadState> cir) {
        if (shooter == null || shooter.level().isClientSide()) return;
        ReloadState state = cir.getReturnValue();
        if (state != null && state.getStateType().isReloading()) return;
        SplitReloadGuns.clearReloadMarkers(gun, shooter);
    }

    @Inject(method = "interruptReload", at = @At("HEAD"))
    private void taczmagazines$clearMarkersOnInterrupt(ShooterDataHolder dataHolder, ItemStack gun,
                                                       LivingEntity shooter, CallbackInfo ci) {
        if (shooter == null || shooter.level().isClientSide()) return;
        SplitReloadGuns.clearReloadMarkers(gun, shooter);
    }
}
