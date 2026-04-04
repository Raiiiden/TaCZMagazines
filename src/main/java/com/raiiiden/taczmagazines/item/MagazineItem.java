package com.raiiiden.taczmagazines.item;

import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MagazineItem extends Item implements IAmmoBox {
    private static final String AMMO_ID_TAG = "AmmoId";
    private static final String AMMO_COUNT_TAG = "AmmoCount";
    private static final String FAMILY_ID_TAG = "MagazineFamily";
    private static final String MAX_CAPACITY_TAG = "MaxCapacity";

    public MagazineItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return com.raiiiden.taczmagazines.client.MagazineItemRenderer.getInstance();
            }
        });
    }

    // ========== MAGAZINE FAMILY METHODS ==========

    public static String getMagazineFamilyId(ItemStack magazine) {
        CompoundTag tag = magazine.getTag();
        if (tag != null && tag.contains(FAMILY_ID_TAG, Tag.TAG_STRING)) {
            return tag.getString(FAMILY_ID_TAG);
        }
        return null;
    }

    public static int getMaxCapacity(ItemStack magazine) {
        CompoundTag tag = magazine.getTag();
        if (tag != null && tag.contains(MAX_CAPACITY_TAG, Tag.TAG_INT)) {
            return tag.getInt(MAX_CAPACITY_TAG);
        }

        String familyId = getMagazineFamilyId(magazine);
        if (familyId != null) {
            return MagazineFamilySystem.getCapacityForFamily(familyId);
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

        ResourceLocation gunId = iGun.getGunId(gun);
        String magazineFamilyId = getMagazineFamilyId(magazine);

        if (magazineFamilyId == null) {
            return false;
        }

        return MagazineFamilySystem.isMagazineCompatibleWithGun(magazineFamilyId, gunId);
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

    /**
     * Called when another item (the cursor stack) is right-clicked onto a slot containing
     * this magazine.  This implements "hold ammo, right-click the magazine slot to fill it."
     *
     * Unloading: hold nothing (empty cursor), right-click the mag slot → dumps one stack
     * of ammo from the magazine onto the cursor is NOT needed here; players can just use
     * the normal drag-to-empty-slot mechanic via overrideStackedOnOther if desired.
     */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack magazine, ItemStack heldStack,
                                             Slot slot, ClickAction action,
                                             Player player, SlotAccess heldAccess) {
        if (action != ClickAction.SECONDARY) return false;

        // ── Empty cursor: right-click a magazine slot to unload ONE magazine ──
        if (heldStack.isEmpty()) {
            ResourceLocation ammoId = this.getAmmoId(magazine);
            if (ammoId.equals(DefaultAssets.EMPTY_AMMO_ID) || this.getAmmoCount(magazine) <= 0) return false;
            if (player.level().isClientSide) return true; // client: absorb the click visually

            // Read everything BEFORE shrinking — shrink-to-empty makes isEmpty()=true which
            // breaks hasTag() checks on some code paths.
            int ammoCount = this.getAmmoCount(magazine);
            CompoundTag srcTag = magazine.getTag();
            String familyId = srcTag != null && srcTag.contains(FAMILY_ID_TAG) ? srcTag.getString(FAMILY_ID_TAG) : null;
            int maxCap = srcTag != null && srcTag.contains(MAX_CAPACITY_TAG) ? srcTag.getInt(MAX_CAPACITY_TAG) : -1;

            // Remove ONE magazine from the slot (leave the rest untouched)
            magazine.shrink(1);

            // Build an empty copy to return to inventory
            ItemStack singleMag = new ItemStack(this);
            CompoundTag singleTag = singleMag.getOrCreateTag();
            if (familyId != null) singleTag.putString(FAMILY_ID_TAG, familyId);
            if (maxCap >= 0) singleTag.putInt(MAX_CAPACITY_TAG, maxCap);

            // Dump ammo into player inventory
            int remaining = ammoCount;
            while (remaining > 0) {
                int give = Math.min(remaining, 64);
                ItemStack ammoStack = AmmoItemBuilder.create().setId(ammoId).setCount(give).build();
                if (!player.getInventory().add(ammoStack)) {
                    player.drop(ammoStack, false);
                }
                remaining -= give;
            }
            // Return empty mag to inventory
            if (!player.getInventory().add(singleMag)) {
                player.drop(singleMag, false);
            }

            this.playRemoveOneSound(player);
            return true;
        }

        // ── Held ammo: right-click a magazine slot to fill it ──
        if (!(heldStack.getItem() instanceof IAmmo iAmmo)) return false;

        ResourceLocation heldAmmoId = iAmmo.getAmmoId(heldStack);
        if (heldAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID)) return false;

        String familyId = getMagazineFamilyId(magazine);
        if (familyId == null) return false;

        ResourceLocation familyAmmoType = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
        if (familyAmmoType == null || !familyAmmoType.equals(heldAmmoId)) return false;

        ResourceLocation magAmmoId = this.getAmmoId(magazine);
        if (!magAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID) && !heldAmmoId.equals(magAmmoId)) return false;

        int maxCapacity = getMaxCapacity(magazine);
        int magAmmoCount = this.getAmmoCount(magazine);
        int spacePerMag = maxCapacity - magAmmoCount;
        if (spacePerMag <= 0) return false;

        int transfer = Math.min(heldStack.getCount(), spacePerMag);
        if (transfer <= 0) return false;

        if (magazine.getCount() == 1) {
            // Single mag in slot — fill it in-place.
            this.setAmmoId(magazine, heldAmmoId);
            this.setAmmoCount(magazine, magAmmoCount + transfer);
            heldStack.shrink(transfer);
        } else {
            // Stack of mags — pull ONE off, fill it, move to a free inventory slot.
            // If no free slot exists, refuse the operation so nothing is lost.
            ItemStack filledMag = magazine.copyWithCount(1);
            this.setAmmoId(filledMag, heldAmmoId);
            this.setAmmoCount(filledMag, magAmmoCount + transfer);
            if (!player.getInventory().add(filledMag)) return false;
            magazine.shrink(1);
            heldStack.shrink(transfer);
        }

        this.playInsertSound(player);
        return true;
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
        String familyId = getMagazineFamilyId(stack);
        if (familyId == null) {
            return Component.literal("Magazine");
        }

        return Component.literal(MagazineFamilySystem.getFamilyDisplayName(familyId));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int ammoCount = this.getAmmoCount(stack);
        ResourceLocation ammoId = this.getAmmoId(stack);
        int maxCapacity = getMaxCapacity(stack);

        if (!ammoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
            tooltip.add(Component.literal("Ammo: " + ammoCount + "/" + maxCapacity)
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("Empty (" + maxCapacity + " capacity)")
                    .withStyle(ChatFormatting.GRAY));
        }

        String familyId = getMagazineFamilyId(stack);
        if (familyId != null) {
            Set<ResourceLocation> compatibleGuns = MagazineFamilySystem.getCompatibleGuns(familyId);

            // Check if SHIFT is held
            if (Screen.hasShiftDown()) {
                // List all compatible guns
                if (!compatibleGuns.isEmpty()) {
                    tooltip.add(Component.literal("Compatible Guns:")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));

                    List<String> gunNames = compatibleGuns.stream()
                            .map(loc -> loc.getPath())
                            .sorted()
                            .collect(Collectors.toList());

                    for (String gunName : gunNames) {
                        tooltip.add(Component.literal("  • " + gunName)
                                .withStyle(ChatFormatting.GRAY));
                    }
                }
            } else {
                // Just show count
                if (!compatibleGuns.isEmpty()) {
                    tooltip.add(Component.literal("Compatible: " + compatibleGuns.size() + " Gun(s)")
                            .withStyle(ChatFormatting.AQUA));
                    tooltip.add(Component.literal("[Hold SHIFT for list]")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                }
            }
        }

        tooltip.add(Component.literal("Hold ammo + right-click magazine to fill  |  Right-click in hand to unload")
                .withStyle(ChatFormatting.GOLD));
    }

    // ========== RIGHT-CLICK TO UNLOAD ==========

    /**
     * Right-clicking a magazine while holding it in hand dumps its ammo into the
     * player's inventory as loose ammo items (up to a full stack at a time).
     * The magazine is left empty.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack heldStack = player.getItemInHand(hand);
        ResourceLocation ammoId = this.getAmmoId(heldStack);

        if (ammoId.equals(DefaultAssets.EMPTY_AMMO_ID) || this.getAmmoCount(heldStack) <= 0) {
            return InteractionResultHolder.pass(heldStack);
        }

        if (level.isClientSide) {
            return InteractionResultHolder.success(heldStack);
        }

        // Split ONE magazine off the stack so we only unload a single mag.
        // All magazines in a stack share the same NBT, so we must isolate one
        // before mutating the ammo count — otherwise the whole stack goes empty.
        int ammoCount = this.getAmmoCount(heldStack);
        ItemStack singleMag = heldStack.copyWithCount(1);
        heldStack.shrink(1);

        // Drain ammo from the single mag into the player's inventory
        int remaining = ammoCount;
        while (remaining > 0) {
            int give = Math.min(remaining, 64);
            ItemStack ammoStack = AmmoItemBuilder.create().setId(ammoId).setCount(give).build();
            if (!player.getInventory().add(ammoStack)) {
                player.drop(ammoStack, false);
            }
            remaining -= give;
        }

        this.setAmmoCount(singleMag, 0);
        this.setAmmoId(singleMag, DefaultAssets.EMPTY_AMMO_ID);

        // Return the now-empty single mag to the player's inventory
        if (!player.getInventory().add(singleMag)) {
            player.drop(singleMag, false);
        }

        playRemoveOneSound(player);
        // Return the (now-smaller) held stack; if it was count=1, it is now empty → hand cleared
        return InteractionResultHolder.success(heldStack);
    }

    // ========== FACTORY METHODS ==========

    public static ItemStack createMagazineByFamily(Item magazineItem, String familyId, int ammoCount) {
        ItemStack stack = new ItemStack(magazineItem);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(FAMILY_ID_TAG, familyId);

        int capacity = MagazineFamilySystem.getCapacityForFamily(familyId);
        tag.putInt(MAX_CAPACITY_TAG, capacity);
        tag.putInt(AMMO_COUNT_TAG, Math.min(ammoCount, capacity));

        return stack;
    }

    public static ItemStack createMagazineByFamily(Item magazineItem, String familyId, int ammoCount, ResourceLocation ammoId) {
        ItemStack stack = createMagazineByFamily(magazineItem, familyId, ammoCount);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(AMMO_ID_TAG, ammoId.toString());
        return stack;
    }
}