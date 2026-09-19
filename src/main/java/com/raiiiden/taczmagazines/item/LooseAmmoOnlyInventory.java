package com.raiiiden.taczmagazines.item;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

// Inventory view with this mod's magazines hidden, so TaCZ's round-by-round feed can't strip clips.
// TaCZ falls back to loose rounds and real ammo boxes.
public record LooseAmmoOnlyInventory(IItemHandler delegate) implements IItemHandler {

    private boolean hidden(int slot) {
        return delegate.getStackInSlot(slot).getItem() instanceof MagazineItem;
    }

    @Override
    public int getSlots() {
        return delegate.getSlots();
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        return hidden(slot) ? ItemStack.EMPTY : delegate.getStackInSlot(slot);
    }

    @Override
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        return delegate.insertItem(slot, stack, simulate);
    }

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
        return hidden(slot) ? ItemStack.EMPTY : delegate.extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return delegate.isItemValid(slot, stack);
    }
}
