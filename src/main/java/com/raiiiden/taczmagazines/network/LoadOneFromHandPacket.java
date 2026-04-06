package com.raiiiden.taczmagazines.network;

import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IAmmo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Loads exactly one bullet from the player's inventory
public class LoadOneFromHandPacket {

    public static void encode(LoadOneFromHandPacket msg, FriendlyByteBuf buf) {}

    public static LoadOneFromHandPacket decode(FriendlyByteBuf buf) {
        return new LoadOneFromHandPacket();
    }

    public static void handle(LoadOneFromHandPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack held = player.getMainHandItem();
            if (held.isEmpty() || !(held.getItem() instanceof MagazineItem magItem)) return;

            String familyId = MagazineItem.getMagazineFamilyId(held);
            if (familyId == null) return;

            ResourceLocation familyAmmo = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
            if (familyAmmo == null) return;

            int maxCap  = MagazineItem.getMaxCapacity(held);
            int current = magItem.getAmmoCount(held);
            if (current >= maxCap) return;

            ResourceLocation magAmmoId = magItem.getAmmoId(held);

            // Find the first compatible ammo stack in the player's inventory
            int foundSlot = -1;
            ResourceLocation foundAmmoId = null;
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack s = player.getInventory().items.get(i);
                if (s.isEmpty() || !(s.getItem() instanceof IAmmo iAmmo)) continue;
                ResourceLocation ammoId = iAmmo.getAmmoId(s);
                if (DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) continue;
                if (!familyAmmo.equals(ammoId)) continue;
                if (!DefaultAssets.EMPTY_AMMO_ID.equals(magAmmoId) && !magAmmoId.equals(ammoId)) continue;
                foundSlot   = i;
                foundAmmoId = ammoId;
                break;
            }
            if (foundSlot == -1) return;

            // Split off extras BEFORE modifying NBT so the different ammo-count
            // prevents the inventory merge from folding them back into the held slot.
            ItemStack extras = ItemStack.EMPTY;
            if (held.getCount() > 1) {
                extras = held.split(held.getCount() - 1); // held is now count=1 in-place
            }

            magItem.setAmmoId(held, foundAmmoId);
            magItem.setAmmoCount(held, current + 1);
            player.getInventory().items.get(foundSlot).shrink(1);

            // Now safe to return the remainder — NBT differs from the modified held mag
            if (!extras.isEmpty()) {
                if (!player.getInventory().add(extras)) player.drop(extras, false);
            }

            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        });
        ctx.get().setPacketHandled(true);
    }
}
