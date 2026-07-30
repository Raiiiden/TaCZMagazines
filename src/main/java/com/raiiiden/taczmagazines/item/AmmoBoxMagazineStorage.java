package com.raiiiden.taczmagazines.item;

import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.config.sync.SyncConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Stores magazines and their NBT inside TaCZ ammo boxes.
public final class AmmoBoxMagazineStorage {
    public static final String MAGAZINES_TAG = "TaCZMagazinesStored";
    private static final String AMMO_BOX_LEVEL_TAG = "Level";

    private AmmoBoxMagazineStorage() {}

    public static boolean isExternalAmmoBox(ItemStack stack) {
        return !stack.isEmpty()
                && !(stack.getItem() instanceof MagazineItem)
                && stack.getItem() instanceof IAmmoBox;
    }

    public static int capacity(ItemStack box) {
        int availableSlots = Math.max(0, totalSlots(box) - bulletSlotsUsed(box));
        int magazineStackSize = new ItemStack(MagazineRegistrar.MAGAZINE.get()).getMaxStackSize();
        return availableSlots * magazineStackSize;
    }

    public static int totalSlots(ItemStack box) {
        if (!(box.getItem() instanceof IAmmoBox ammoBox)) return 0;
        return (ammoBoxTier(box, ammoBox) + 1) * SyncConfig.AMMO_BOX_STACK_SIZE.get();
    }

    public static int bulletSlotsUsed(ItemStack box) {
        if (!(box.getItem() instanceof IAmmoBox ammoBox)) return 0;
        if (ammoBox.isCreative(box) || ammoBox.isAllTypeCreative(box)) return 0;

        int ammoCount = Math.max(0, ammoBox.getAmmoCount(box));
        ResourceLocation ammoId = ammoBox.getAmmoId(box);
        if (ammoCount <= 0 || DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) return 0;

        int stackSize = TimelessAPI.getCommonAmmoIndex(ammoId)
                .map(index -> Math.max(1, index.getStackSize()))
                .orElse(1);
        return divideCeil(ammoCount, stackSize);
    }

    public static int magazineSlotsUsed(ItemStack box) {
        return packedMagazineSlots(getStoredMagazines(box));
    }

    public static double usedSlotFill(ItemStack box) {
        return bulletSlotFill(box) + magazineSlotFill(box);
    }

    public static int freeSlots(ItemStack box) {
        return Math.max(0, totalSlots(box) - bulletSlotsUsed(box) - magazineSlotsUsed(box));
    }

    public static int count(ItemStack box) {
        return getList(box).size();
    }

    public static boolean canInsert(ItemStack box, ItemStack magazine) {
        return MechanicsConfig.MAGAZINES_IN_AMMO_BOXES.get()
                && isExternalAmmoBox(box)
                && magazine.getItem() instanceof MagazineItem
                && (!MechanicsConfig.SEPARATE_AMMO_BOX_CONTENTS.get()
                    || bulletSlotsUsed(box) == 0)
                && slotsAfterAdding(box, magazine) <= totalSlots(box) - bulletSlotsUsed(box);
    }

    // Reads the numeric tier tag so iron, gold, and diamond boxes keep their correct capacities.
    private static int ammoBoxTier(ItemStack box, IAmmoBox ammoBox) {
        CompoundTag tag = box.getTag();
        int level = tag != null && tag.contains(AMMO_BOX_LEVEL_TAG, Tag.TAG_ANY_NUMERIC)
                ? tag.getInt(AMMO_BOX_LEVEL_TAG)
                : ammoBox.getAmmoLevel(box);
        return Math.max(0, Math.min(2, level));
    }

    public static boolean insertOne(ItemStack box, ItemStack magazine) {
        if (!canInsert(box, magazine)) return false;
        ListTag list = getOrCreateList(box);
        list.add(magazine.copyWithCount(1).save(new CompoundTag()));
        box.getOrCreateTag().put(MAGAZINES_TAG, list);
        return true;
    }

    public static int insertFromStack(ItemStack box, ItemStack magazines) {
        int inserted = 0;
        int requested = magazines.getCount();
        while (inserted < requested && canInsert(box, magazines)) {
            if (!insertOne(box, magazines)) break;
            inserted++;
        }
        return inserted;
    }

    public static ItemStack extractLastStack(ItemStack box) {
        ListTag list = getList(box);
        if (list.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = ItemStack.of(list.getCompound(list.size() - 1));
        if (template.isEmpty() || !(template.getItem() instanceof MagazineItem)) return ItemStack.EMPTY;
        int maxStackSize = template.getMaxStackSize();
        ItemStack result = template.copyWithCount(1);
        list.remove(list.size() - 1);

        for (int i = list.size() - 1; i >= 0 && result.getCount() < maxStackSize; i--) {
            ItemStack candidate = ItemStack.of(list.getCompound(i));
            if (!ItemStack.isSameItemSameTags(template, candidate)) continue;
            list.remove(i);
            result.grow(1);
        }
        writeList(box, list);
        return result;
    }

    public static ItemStack peekBestCompatible(ItemStack box, ItemStack gun) {
        StoredMagazine best = findBestCompatible(box, gun);
        return best == null ? ItemStack.EMPTY : best.stack().copy();
    }

    public static ItemStack extractBestCompatible(ItemStack box, ItemStack gun) {
        StoredMagazine best = findBestCompatible(box, gun);
        if (best == null) return ItemStack.EMPTY;

        ListTag list = getList(box);
        ItemStack result = ItemStack.of(list.getCompound(best.index()));
        list.remove(best.index());
        writeList(box, list);
        return result;
    }

    public static List<ItemStack> getStoredMagazines(ItemStack box) {
        List<ItemStack> result = new ArrayList<>();
        ListTag list = getList(box);
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty() && stack.getItem() instanceof MagazineItem) {
                result.add(stack);
            }
        }
        return result;
    }

    private static StoredMagazine findBestCompatible(ItemStack box, ItemStack gun) {
        if (!MechanicsConfig.MAGAZINES_IN_AMMO_BOXES.get() || !isExternalAmmoBox(box)) return null;

        StoredMagazine best = null;
        ListTag list = getList(box);
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!(stack.getItem() instanceof MagazineItem magItem)) continue;
            if (!magItem.isAmmoBoxOfGun(gun, stack)) continue;
            int ammo = magItem.getAmmoCount(stack);
            if (ammo <= 0 || (best != null && ammo <= best.ammo())) continue;
            best = new StoredMagazine(i, stack, ammo);
        }
        return best;
    }

    private static int slotsAfterAdding(ItemStack box, ItemStack magazine) {
        List<ItemStack> stored = getStoredMagazines(box);
        stored.add(magazine.copyWithCount(1));
        return packedMagazineSlots(stored);
    }

    private static int packedMagazineSlots(List<ItemStack> magazines) {
        return packMagazines(magazines).size();
    }

    private static double bulletSlotFill(ItemStack box) {
        if (!(box.getItem() instanceof IAmmoBox ammoBox)) return 0.0;
        if (ammoBox.isCreative(box) || ammoBox.isAllTypeCreative(box)) return 0.0;
        int ammoCount = Math.max(0, ammoBox.getAmmoCount(box));
        ResourceLocation ammoId = ammoBox.getAmmoId(box);
        if (ammoCount <= 0 || DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) return 0.0;
        int stackSize = TimelessAPI.getCommonAmmoIndex(ammoId)
                .map(index -> Math.max(1, index.getStackSize()))
                .orElse(1);
        return (double) ammoCount / stackSize;
    }

    private static double magazineSlotFill(ItemStack box) {
        double fill = 0.0;
        for (ItemStack stack : packMagazines(getStoredMagazines(box))) {
            fill += (double) stack.getCount() / stack.getMaxStackSize();
        }
        return fill;
    }

    private static List<ItemStack> packMagazines(List<ItemStack> magazines) {
        List<ItemStack> packed = new ArrayList<>();
        for (ItemStack magazine : magazines) {
            boolean merged = false;
            for (ItemStack stack : packed) {
                if (!ItemStack.isSameItemSameTags(stack, magazine)
                        || stack.getCount() >= stack.getMaxStackSize()) continue;
                stack.grow(1);
                merged = true;
                break;
            }
            if (!merged) packed.add(magazine.copyWithCount(1));
        }
        return packed;
    }

    private static int divideCeil(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static ListTag getList(ItemStack box) {
        CompoundTag tag = box.getTag();
        if (tag == null || !tag.contains(MAGAZINES_TAG, Tag.TAG_LIST)) return new ListTag();
        return tag.getList(MAGAZINES_TAG, Tag.TAG_COMPOUND).copy();
    }

    private static ListTag getOrCreateList(ItemStack box) {
        CompoundTag tag = box.getOrCreateTag();
        if (!tag.contains(MAGAZINES_TAG, Tag.TAG_LIST)) {
            tag.put(MAGAZINES_TAG, new ListTag());
        }
        return tag.getList(MAGAZINES_TAG, Tag.TAG_COMPOUND);
    }

    private static void writeList(ItemStack box, ListTag list) {
        CompoundTag tag = box.getOrCreateTag();
        if (list.isEmpty()) {
            tag.remove(MAGAZINES_TAG);
        } else {
            tag.put(MAGAZINES_TAG, list);
        }
    }

    private record StoredMagazine(int index, ItemStack stack, int ammo) {}
}
