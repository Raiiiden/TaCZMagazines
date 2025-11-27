package com.raiiiden.taczmagazines.item;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

public class MagazineItem extends Item implements IAmmoBox {
    private static final String AMMO_ID_TAG = "AmmoId";
    private static final String AMMO_COUNT_TAG = "AmmoCount";
    private static final String GUN_ID_TAG = "GunId";
    private static final String MAX_CAPACITY_TAG = "MaxCapacity";

    public MagazineItem(Properties properties) {
        super(properties);
    }

    public static ResourceLocation getMagazineGunId(ItemStack magazine) {
        CompoundTag tag = magazine.getTag();
        if (tag != null && tag.contains(GUN_ID_TAG, Tag.TAG_STRING)) {
            return new ResourceLocation(tag.getString(GUN_ID_TAG));
        }
        return null;
    }

    public static int getMaxCapacity(ItemStack magazine) {
        CompoundTag tag = magazine.getTag();
        if (tag != null && tag.contains(MAX_CAPACITY_TAG, Tag.TAG_INT)) {
            return tag.getInt(MAX_CAPACITY_TAG);
        }
        ResourceLocation gunId = getMagazineGunId(magazine);
        if (gunId != null) {
            return lookupGunCapacity(gunId);
        }
        return 30;
    }

    private static int lookupGunCapacity(ResourceLocation gunId) {
        CommonAssetsManager resourceProvider = CommonAssetsManager.getInstance();
        if (resourceProvider != null) {
            CommonGunIndex index = resourceProvider.getGunIndex(gunId);
            if (index != null && index.getGunData() != null) {
                return index.getGunData().getAmmoAmount();
            }
        }
        return 30;
    }

    @Override
    public ResourceLocation getAmmoId(ItemStack magazine) {
        CompoundTag tag = magazine.getTag();
        if (tag != null && tag.contains(AMMO_ID_TAG, Tag.TAG_STRING)) {
            return new ResourceLocation(tag.getString(AMMO_ID_TAG));
        }
        return DefaultAssets.EMPTY_AMMO_ID;
    }

    @Override
    public int getAmmoCount(ItemStack magazine) {
        CompoundTag tag = magazine.getTag();
        if (tag != null && tag.contains(AMMO_COUNT_TAG, Tag.TAG_INT)) {
            return tag.getInt(AMMO_COUNT_TAG);
        }
        return 0;
    }

    @Override
    public void setAmmoId(ItemStack magazine, ResourceLocation ammoId) {
        CompoundTag tag = magazine.getOrCreateTag();
        if (ammoId == null || ammoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
            tag.remove(AMMO_ID_TAG);
        } else {
            tag.putString(AMMO_ID_TAG, ammoId.toString());
        }
    }

    @Override
    public void setAmmoCount(ItemStack magazine, int count) {
        CompoundTag tag = magazine.getOrCreateTag();
        int maxCapacity = getMaxCapacity(magazine);
        tag.putInt(AMMO_COUNT_TAG, Math.max(0, Math.min(count, maxCapacity)));
    }

    @Override
    public boolean isAmmoBoxOfGun(ItemStack gun, ItemStack magazine) {
        if (!(gun.getItem() instanceof IGun iGun)) {
            return false;
        }

        ResourceLocation gunIdFromGun = iGun.getGunId(gun);
        ResourceLocation magazineGunId = getMagazineGunId(magazine);

        return magazineGunId != null && gunIdFromGun.equals(magazineGunId);
    }

    @Override
    public ItemStack setAmmoLevel(ItemStack magazine, int ammoLevel) {
        return magazine;
    }

    @Override
    public int getAmmoLevel(ItemStack magazine) {
        return 0;
    }

    @Override
    public boolean isCreative(ItemStack magazine) {
        return false;
    }

    @Override
    public boolean isAllTypeCreative(ItemStack magazine) {
        return false;
    }

    @Override
    public ItemStack setCreative(ItemStack magazine, boolean isAllType) {
        return magazine;
    }

    @Override
    public boolean overrideStackedOnOther(ItemStack magazine, Slot slot, ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY) {
            return false;
        }

        ItemStack slotItem = slot.getItem();
        ResourceLocation magAmmoId = this.getAmmoId(magazine);
        int maxCapacity = getMaxCapacity(magazine);

        // Empty slot - take ammo out
        if (slotItem.isEmpty()) {
            // Only allow emptying if we have a single magazine
            if (magazine.getCount() > 1) {
                return false;
            }

            if (magAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
                return false;
            }

            int magAmmoCount = this.getAmmoCount(magazine);
            if (magAmmoCount <= 0) {
                return false;
            }

            TimelessAPI.getCommonAmmoIndex(magAmmoId).ifPresent(index -> {
                int takeCount = Math.min(index.getStackSize(), magAmmoCount);
                ItemStack takeAmmo = AmmoItemBuilder.create().setId(magAmmoId).setCount(takeCount).build();
                slot.safeInsert(takeAmmo);

                int remainCount = magAmmoCount - takeCount;
                this.setAmmoCount(magazine, remainCount);
                if (remainCount <= 0) {
                    this.setAmmoId(magazine, DefaultAssets.EMPTY_AMMO_ID);
                }
                this.playRemoveOneSound(player);
            });
            return true;
        }

        // Slot has ammo - try to insert (this can work with stacks)
        if (slotItem.getItem() instanceof IAmmo iAmmo) {
            ResourceLocation slotAmmoId = iAmmo.getAmmoId(slotItem);

            if (slotAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
                return false;
            }

            ResourceLocation magazineGunId = getMagazineGunId(magazine);
            if (magazineGunId == null || !isAmmoCompatibleWithGun(slotAmmoId, magazineGunId)) {
                return false;
            }

            if (magAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
                this.setAmmoId(magazine, slotAmmoId);
            } else if (!slotAmmoId.equals(magAmmoId)) {
                return false;
            }

            TimelessAPI.getCommonAmmoIndex(slotAmmoId).ifPresent(index -> {
                int magAmmoCount = this.getAmmoCount(magazine);
                int needCount = maxCapacity - magAmmoCount;
                ItemStack takeItem = slot.safeTake(slotItem.getCount(), needCount, player);
                this.setAmmoCount(magazine, magAmmoCount + takeItem.getCount());
            });

            this.playInsertSound(player);
            return true;
        }

        return false;
    }

    private boolean isAmmoCompatibleWithGun(ResourceLocation ammoId, ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId().equals(ammoId))
                .orElse(false);
    }

    private void playRemoveOneSound(Entity entity) {
        entity.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8F, 0.8F + entity.level().getRandom().nextFloat() * 0.4F);
    }

    private void playInsertSound(Entity entity) {
        entity.playSound(SoundEvents.BUNDLE_INSERT, 0.8F, 0.8F + entity.level().getRandom().nextFloat() * 0.4F);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return 6;
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return true;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return !this.getAmmoId(stack).equals(DefaultAssets.EMPTY_AMMO_ID) && this.getAmmoCount(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int ammoCount = this.getAmmoCount(stack);
        int maxCapacity = getMaxCapacity(stack);
        double widthPercent = (double) ammoCount / (double) maxCapacity;
        return (int) Math.min(1 + 12 * widthPercent, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb(0.1f, 0.8F, 1.0F);
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation gunId = getMagazineGunId(stack);
        if (gunId == null) {
            return Component.literal("Magazine");
        }

        String gunName = getGunDisplayName(gunId);
        return Component.literal(gunName + " Magazine");
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int ammoCount = this.getAmmoCount(stack);
        ResourceLocation ammoId = this.getAmmoId(stack);
        int maxCapacity = getMaxCapacity(stack);

        if (!ammoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
            tooltip.add(Component.literal("Ammo: " + ammoCount + "/" + maxCapacity)
                    .withStyle(ChatFormatting.GRAY));

            TimelessAPI.getCommonAmmoIndex(ammoId).ifPresent(index -> {
                tooltip.add(Component.literal("Type: " + ammoId.getPath())
                        .withStyle(ChatFormatting.DARK_GRAY));
            });
        } else {
            tooltip.add(Component.literal("Empty (" + maxCapacity + " capacity)")
                    .withStyle(ChatFormatting.GRAY));
        }

        ResourceLocation gunId = getMagazineGunId(stack);
        if (gunId != null) {
            tooltip.add(Component.literal("Compatible with: " + getGunDisplayName(gunId))
                    .withStyle(ChatFormatting.AQUA));
        }

        tooltip.add(Component.literal("Right-click on ammo to fill")
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("Right-click on empty slot to empty")
                .withStyle(ChatFormatting.GOLD));
    }

    private static String getGunDisplayName(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> {
                    String translationKey = index.getPojo().getName();
                    if (translationKey != null && !translationKey.isEmpty()) {
                        return Component.translatable(translationKey).getString();
                    }
                    return gunId.getPath();
                })
                .orElse(gunId.getPath());
    }

    public static ItemStack createMagazine(Item magazineItem, ResourceLocation gunId, int ammoCount) {
        ItemStack stack = new ItemStack(magazineItem);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(GUN_ID_TAG, gunId.toString());

        int capacity = lookupGunCapacity(gunId);
        tag.putInt(MAX_CAPACITY_TAG, capacity);
        tag.putInt(AMMO_COUNT_TAG, ammoCount);

        return stack;
    }

    public static ItemStack createMagazine(Item magazineItem, ResourceLocation gunId, int ammoCount, ResourceLocation ammoId) {
        ItemStack stack = createMagazine(magazineItem, gunId, ammoCount);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(AMMO_ID_TAG, ammoId.toString());
        return stack;
    }
}