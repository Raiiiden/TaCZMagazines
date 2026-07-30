package com.raiiiden.taczmagazines.item;

import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

// Resolves reload magazines from inventory, ammo boxes, or creative boxes.
public final class MagazineReloadSource {
    private MagazineReloadSource() {}

    public static ItemStack extract(IItemHandler inventory, ItemStack gun, int selectedSlot) {
        ItemStack selected = extractSelected(inventory, gun, selectedSlot);
        if (!selected.isEmpty()) return selected;

        if (MechanicsConfig.PREFER_PLAYER_INVENTORY.get()) {
            ItemStack direct = extractBestDirect(inventory, gun);
            return direct.isEmpty() ? extractBestBoxed(inventory, gun) : direct;
        }

        ItemStack boxed = extractBestBoxed(inventory, gun);
        return boxed.isEmpty() ? extractBestDirect(inventory, gun) : boxed;
    }

    public static boolean hasUsableMagazine(IItemHandler inventory, ItemStack gun) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (isUsableDirect(stack, gun)) return true;
            if (isAllTypeCreativeBox(stack)) return true;
            if (!AmmoBoxMagazineStorage.peekBestCompatible(stack, gun).isEmpty()) return true;
        }
        return false;
    }

    public static ItemStack createFullMagazineForGun(ItemStack gun) {
        if (!(gun.getItem() instanceof IGun iGun)) return ItemStack.EMPTY;
        ResourceLocation gunId = iGun.getGunId(gun);
        String family = MagazineFamilySystem.getFamilyForGun(gunId);
        if (family == null) return ItemStack.EMPTY;

        ResourceLocation ammoId = MagazineFamilySystem.getAmmoTypeForFamily(family);
        if (ammoId == null) return ItemStack.EMPTY;
        int capacity = MagazineFamilySystem.getCapacityForFamily(family);
        return MagazineItem.createMagazineByFamily(
                MagazineRegistrar.MAGAZINE.get(), family, capacity, ammoId);
    }

    public static ItemStack createCreativeReloadMagazine(IItemHandler inventory, ItemStack gun,
                                                         int selectedSlot) {
        ItemStack magazine = ItemStack.EMPTY;
        if (selectedSlot >= 0 && selectedSlot < inventory.getSlots()) {
            ItemStack selected = inventory.getStackInSlot(selectedSlot);
            if (isUsableDirect(selected, gun)) {
                magazine = selected.copyWithCount(1);
            } else if (!isAllTypeCreativeBox(selected)) {
                magazine = AmmoBoxMagazineStorage.peekBestCompatible(selected, gun);
            }
        }

        if (!(magazine.getItem() instanceof MagazineItem magItem)) {
            return createFullMagazineForGun(gun);
        }

        String family = MagazineItem.getMagazineFamilyId(magazine);
        ResourceLocation ammoId = family == null
                ? null
                : MagazineFamilySystem.getAmmoTypeForFamily(family);
        if (ammoId == null) return createFullMagazineForGun(gun);

        magItem.setAmmoId(magazine, ammoId);
        magItem.setAmmoCount(magazine, MagazineItem.getMaxCapacity(magazine));
        return magazine;
    }

    private static ItemStack extractSelected(IItemHandler inventory, ItemStack gun, int slot) {
        if (slot < 0 || slot >= inventory.getSlots()) return ItemStack.EMPTY;
        ItemStack stack = inventory.getStackInSlot(slot);
        if (isUsableDirect(stack, gun)) return inventory.extractItem(slot, 1, false);
        if (isAllTypeCreativeBox(stack)) return createFullMagazineForGun(gun);

        ItemStack result = AmmoBoxMagazineStorage.extractBestCompatible(stack, gun);
        if (!result.isEmpty() && inventory instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(slot, stack);
        }
        return result;
    }

    private static ItemStack extractBestDirect(IItemHandler inventory, ItemStack gun) {
        int bestSlot = -1;
        int bestAmmo = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!isUsableDirect(stack, gun)) continue;
            int ammo = ((MagazineItem) stack.getItem()).getAmmoCount(stack);
            if (ammo > bestAmmo) {
                bestAmmo = ammo;
                bestSlot = i;
            }
        }
        return bestSlot < 0 ? ItemStack.EMPTY : inventory.extractItem(bestSlot, 1, false);
    }

    private static ItemStack extractBestBoxed(IItemHandler inventory, ItemStack gun) {
        int bestBoxSlot = -1;
        int bestAmmo = 0;

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack box = inventory.getStackInSlot(i);
            if (isAllTypeCreativeBox(box)) {
                return createFullMagazineForGun(gun);
            }

            ItemStack magazine = AmmoBoxMagazineStorage.peekBestCompatible(box, gun);
            if (!(magazine.getItem() instanceof MagazineItem magItem)) continue;
            int ammo = magItem.getAmmoCount(magazine);
            if (ammo > bestAmmo) {
                bestAmmo = ammo;
                bestBoxSlot = i;
            }
        }

        if (bestBoxSlot < 0) return ItemStack.EMPTY;
        ItemStack box = inventory.getStackInSlot(bestBoxSlot);
        ItemStack result = AmmoBoxMagazineStorage.extractBestCompatible(box, gun);
        if (inventory instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(bestBoxSlot, box);
        }
        return result;
    }

    private static boolean isUsableDirect(ItemStack stack, ItemStack gun) {
        if (!(stack.getItem() instanceof MagazineItem magItem)) return false;
        return magItem.isAmmoBoxOfGun(gun, stack) && magItem.getAmmoCount(stack) > 0;
    }

    private static boolean isAllTypeCreativeBox(ItemStack stack) {
        return AmmoBoxMagazineStorage.isExternalAmmoBox(stack)
                && stack.getItem() instanceof IAmmoBox box
                && box.isAllTypeCreative(stack);
    }
}
