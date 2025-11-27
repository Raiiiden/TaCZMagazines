package com.raiiiden.taczmagazines.capability;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Capability to store a magazine inside a gun
 */
public class GunMagazineCapability {
    private ItemStack storedMagazine = ItemStack.EMPTY;

    public ItemStack getStoredMagazine() {
        return storedMagazine;
    }

    public void setStoredMagazine(ItemStack magazine) {
        this.storedMagazine = magazine;
    }

    public boolean hasMagazine() {
        return !storedMagazine.isEmpty();
    }

    public void clearMagazine() {
        this.storedMagazine = ItemStack.EMPTY;
    }

    public void copyFrom(GunMagazineCapability source) {
        this.storedMagazine = source.storedMagazine.copy();
    }

    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        if (!storedMagazine.isEmpty()) {
            nbt.put("StoredMagazine", storedMagazine.save(new CompoundTag()));
        }
        return nbt;
    }

    public void deserializeNBT(CompoundTag nbt) {
        if (nbt.contains("StoredMagazine")) {
            this.storedMagazine = ItemStack.of(nbt.getCompound("StoredMagazine"));
        } else {
            this.storedMagazine = ItemStack.EMPTY;
        }
    }
}