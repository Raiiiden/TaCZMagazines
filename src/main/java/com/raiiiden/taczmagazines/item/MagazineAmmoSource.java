package com.raiiiden.taczmagazines.item;

import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// Resolves and consumes ammunition used to load magazines.
public final class MagazineAmmoSource {
    private MagazineAmmoSource() {}

    public static ResourceLocation compatibleAmmoId(ItemStack source, ResourceLocation requiredAmmo) {
        if (source.isEmpty()) return null;

        if (source.getItem() instanceof IAmmo ammo) {
            ResourceLocation ammoId = ammo.getAmmoId(source);
            return requiredAmmo.equals(ammoId) ? ammoId : null;
        }

        if (MechanicsConfig.LOAD_MAGAZINES_FROM_AMMO_BOXES.get()
                && AmmoBoxMagazineStorage.isExternalAmmoBox(source)
                && source.getItem() instanceof IAmmoBox box) {
            if (box.isAllTypeCreative(source)) return requiredAmmo;
            ResourceLocation ammoId = box.getAmmoId(source);
            if (!requiredAmmo.equals(ammoId)) return null;
            if (box.getAmmoCount(source) <= 0 && !box.isCreative(source)) return null;
            return ammoId;
        }

        return null;
    }

    public static int available(ItemStack source) {
        if (source.getItem() instanceof IAmmo) return source.getCount();
        if (AmmoBoxMagazineStorage.isExternalAmmoBox(source)
                && source.getItem() instanceof IAmmoBox box) {
            if (box.isCreative(source) || box.isAllTypeCreative(source)) return Integer.MAX_VALUE;
            return Math.max(0, box.getAmmoCount(source));
        }
        return 0;
    }

    public static void consume(ItemStack source, int amount) {
        if (amount <= 0) return;
        if (source.getItem() instanceof IAmmo) {
            source.shrink(amount);
            return;
        }
        if (AmmoBoxMagazineStorage.isExternalAmmoBox(source)
                && source.getItem() instanceof IAmmoBox box
                && !box.isCreative(source)
                && !box.isAllTypeCreative(source)) {
            int remaining = Math.max(0, box.getAmmoCount(source) - amount);
            box.setAmmoCount(source, remaining);
            if (remaining == 0) box.setAmmoId(source, DefaultAssets.EMPTY_AMMO_ID);
        }
    }

    public static boolean takeOneFromInventory(Player player, ResourceLocation requiredAmmo) {
        if (player.getAbilities().instabuild) return true;

        boolean looseFirst = MechanicsConfig.PREFER_PLAYER_INVENTORY.get();
        if (looseFirst && takeLoose(player, requiredAmmo)) return true;
        if (takeFromBox(player, requiredAmmo)) return true;
        return !looseFirst && takeLoose(player, requiredAmmo);
    }

    private static boolean takeLoose(Player player, ResourceLocation requiredAmmo) {
        for (ItemStack stack : player.getInventory().items) {
            if (!(stack.getItem() instanceof IAmmo)) continue;
            if (compatibleAmmoId(stack, requiredAmmo) == null) continue;
            stack.shrink(1);
            player.getInventory().setChanged();
            return true;
        }
        return false;
    }

    private static boolean takeFromBox(Player player, ResourceLocation requiredAmmo) {
        if (!MechanicsConfig.LOAD_MAGAZINES_FROM_AMMO_BOXES.get()) return false;
        for (ItemStack stack : player.getInventory().items) {
            if (!AmmoBoxMagazineStorage.isExternalAmmoBox(stack)) continue;
            if (compatibleAmmoId(stack, requiredAmmo) == null || available(stack) <= 0) continue;
            consume(stack, 1);
            player.getInventory().setChanged();
            return true;
        }
        return false;
    }
}
