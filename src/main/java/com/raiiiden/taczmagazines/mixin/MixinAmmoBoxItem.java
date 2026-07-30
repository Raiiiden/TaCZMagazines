package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.AmmoBoxMagazineStorage;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.item.AmmoBoxItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.List;

@Mixin(value = AmmoBoxItem.class, remap = false)
public abstract class MixinAmmoBoxItem {

    @Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true, remap = true)
    private void storeHeldMagazine(ItemStack box, ItemStack heldStack, Slot slot,
                                   ClickAction action, Player player, SlotAccess heldAccess,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (action != ClickAction.SECONDARY) return;

        if (heldStack.isEmpty() && AmmoBoxMagazineStorage.count(box) > 0) {
            if (shouldMutateInventory(player)) {
                ItemStack extracted = AmmoBoxMagazineStorage.extractLastStack(box);
                if (!extracted.isEmpty()) {
                    heldAccess.set(extracted);
                    slot.setChanged();
                }
            }
            playRemoveSound(player);
            cir.setReturnValue(true);
            return;
        }

        // Storage follows TaCZ's normal direction: hold the ammo box on the
        // cursor and right-click the item stack to put it into the box.
    }

    @Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true, remap = true)
    private void storeSlottedMagazine(ItemStack box, Slot slot, ClickAction action,
                                      Player player, CallbackInfoReturnable<Boolean> cir) {
        if (action != ClickAction.SECONDARY) return;

        ItemStack target = slot.getItem();
        if (target.isEmpty() && AmmoBoxMagazineStorage.count(box) > 0) {
            if (shouldMutateInventory(player)) {
                ItemStack extracted = AmmoBoxMagazineStorage.extractLastStack(box);
                ItemStack leftover = slot.safeInsert(extracted);
                if (!leftover.isEmpty()) {
                    AmmoBoxMagazineStorage.insertFromStack(box, leftover);
                }
            }
            playRemoveSound(player);
            cir.setReturnValue(true);
            return;
        }

        if (target.getItem() instanceof MagazineItem) {
            boolean canInsert = AmmoBoxMagazineStorage.canInsert(box, target);
            if (shouldMutateInventory(player)) {
                int inserted = AmmoBoxMagazineStorage.insertFromStack(box, target);
                if (inserted > 0) {
                    target.shrink(inserted);
                    slot.setChanged();
                }
            }
            if (canInsert) playInsertSound(player);
            cir.setReturnValue(true);
            return;
        }

        // TaCZ normally computes bullet capacity without knowing about our
        // magazine entries. Once magazines occupy slots, cap ammo insertion to
        // the remaining shared stack slots.
        if (AmmoBoxMagazineStorage.count(box) <= 0
                || !(target.getItem() instanceof IAmmo ammo)
                || !(box.getItem() instanceof IAmmoBox ammoBox)) return;

        if (MechanicsConfig.SEPARATE_AMMO_BOX_CONTENTS.get()) {
            cir.setReturnValue(true);
            return;
        }

        if (ammoBox.isCreative(box) || ammoBox.isAllTypeCreative(box)) return;

        ResourceLocation targetAmmoId = ammo.getAmmoId(target);
        if (DefaultAssets.EMPTY_AMMO_ID.equals(targetAmmoId)) return;
        ResourceLocation boxAmmoId = ammoBox.getAmmoId(box);
        if (!DefaultAssets.EMPTY_AMMO_ID.equals(boxAmmoId) && !boxAmmoId.equals(targetAmmoId)) return;

        int ammoStackSize = TimelessAPI.getCommonAmmoIndex(targetAmmoId)
                .map(index -> Math.max(1, index.getStackSize()))
                .orElse(1);
        int ammoSlots = Math.max(0,
                AmmoBoxMagazineStorage.totalSlots(box)
                        - AmmoBoxMagazineStorage.magazineSlotsUsed(box));
        int room = ammoSlots * ammoStackSize - ammoBox.getAmmoCount(box);

        if (!player.level().isClientSide && room > 0) {
            int inserted = Math.min(room, target.getCount());
            if (DefaultAssets.EMPTY_AMMO_ID.equals(boxAmmoId)) {
                ammoBox.setAmmoId(box, targetAmmoId);
            }
            ammoBox.setAmmoCount(box, ammoBox.getAmmoCount(box) + inserted);
            target.shrink(inserted);
            slot.setChanged();
        }
        if (room > 0) playInsertSound(player);
        cir.setReturnValue(true);
    }

    @Inject(method = "isBarVisible", at = @At("RETURN"), cancellable = true, remap = true)
    private void showBarForStoredMagazines(ItemStack box,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (AmmoBoxMagazineStorage.count(box) <= 0) return;
        if (box.getItem() instanceof IAmmoBox ammoBox
                && (ammoBox.isCreative(box) || ammoBox.isAllTypeCreative(box))) return;
        cir.setReturnValue(true);
    }

    @Inject(method = "getBarWidth", at = @At("RETURN"), cancellable = true, remap = true)
    private void includeMagazinesInBarWidth(ItemStack box,
                                            CallbackInfoReturnable<Integer> cir) {
        if (AmmoBoxMagazineStorage.count(box) <= 0) return;
        int totalSlots = AmmoBoxMagazineStorage.totalSlots(box);
        if (totalSlots <= 0) return;
        double fullness = AmmoBoxMagazineStorage.usedSlotFill(box) / totalSlots;
        cir.setReturnValue((int) Math.min(1.0 + 12.0 * fullness, 13.0));
    }

    @Inject(method = "appendHoverText", at = @At("TAIL"), remap = true)
    private void appendStoredMagazineCount(ItemStack box, @Nullable Level level,
                                           List<Component> tooltip, TooltipFlag flag,
                                           CallbackInfo ci) {
        int stored = AmmoBoxMagazineStorage.count(box);
        if (stored <= 0) return;
        tooltip.add(Component.literal("Magazines: " + stored + "/" + AmmoBoxMagazineStorage.capacity(box))
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("Used slots: "
                        + (AmmoBoxMagazineStorage.bulletSlotsUsed(box)
                        + AmmoBoxMagazineStorage.magazineSlotsUsed(box))
                        + "/" + AmmoBoxMagazineStorage.totalSlots(box))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void playInsertSound(Player player) {
        player.playSound(SoundEvents.BUNDLE_INSERT, 0.8F,
                0.8F + player.level().getRandom().nextFloat() * 0.4F);
    }

    private static void playRemoveSound(Player player) {
        player.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8F,
                0.8F + player.level().getRandom().nextFloat() * 0.4F);
    }

    private static boolean shouldMutateInventory(Player player) {
        return !player.level().isClientSide || player.getAbilities().instabuild;
    }
}
