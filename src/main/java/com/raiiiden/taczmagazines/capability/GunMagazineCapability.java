package com.raiiiden.taczmagazines.capability;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public class GunMagazineCapability {

    private static final String TAG_KEY = "TaCZMag_StoredMagazine";

    // The ItemStack this capability is attached to — we write directly to its tag.
    private final ItemStack gunStack;

    public GunMagazineCapability(ItemStack gunStack) {
        this.gunStack = gunStack;
    }

    public ItemStack getStoredMagazine() {
        CompoundTag tag = gunStack.getTag();
        if (tag == null || !tag.contains(TAG_KEY)) return ItemStack.EMPTY;
        return ItemStack.of(tag.getCompound(TAG_KEY));
    }

    public void setStoredMagazine(ItemStack magazine) {
        if (magazine.isEmpty()) {
            clearMagazine();
            return;
        }
        CompoundTag tag = gunStack.getOrCreateTag();
        tag.put(TAG_KEY, magazine.save(new CompoundTag()));
    }

    public boolean hasMagazine() {
        CompoundTag tag = gunStack.getTag();
        return tag != null && tag.contains(TAG_KEY);
    }

    public void clearMagazine() {
        CompoundTag tag = gunStack.getTag();
        if (tag != null) tag.remove(TAG_KEY);
    }

    // These are now no-ops since we persist directly to the gun's NBT.
    public CompoundTag serializeNBT() {
        return new CompoundTag();
    }

    public void deserializeNBT(CompoundTag nbt) {
    }
}