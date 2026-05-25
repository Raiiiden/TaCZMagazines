package com.raiiiden.taczmagazines.network;

import com.raiiiden.taczmagazines.item.MagazineItem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Removes exactly one bullet from the magazine held in the player's main hand.
// Fired by the in-hand tick-based unload session each interval.
public class UnloadOneFromHandPacket {

    public static void encode(UnloadOneFromHandPacket msg, FriendlyByteBuf buf) {}

    public static UnloadOneFromHandPacket decode(FriendlyByteBuf buf) {
        return new UnloadOneFromHandPacket();
    }

    public static void handle(UnloadOneFromHandPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack held = player.getMainHandItem();
            if (held.isEmpty() || !(held.getItem() instanceof MagazineItem magItem)) return;

            int ammoCount = magItem.getAmmoCount(held);
            if (ammoCount <= 0) return;

            ResourceLocation ammoId = magItem.getAmmoId(held);
            if (DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) return;

            // Split BEFORE modifying NBT; add extras AFTER — matching BulletTransferPacket's
            // ordering so the different ammo counts prevent Minecraft from merging the
            // extras back into the hand slot.
            ItemStack extras = ItemStack.EMPTY;
            if (held.getCount() > 1) {
                extras = held.split(held.getCount() - 1); // held is now count=1 in-place
                player.setItemInHand(InteractionHand.MAIN_HAND, held);
            }

            int newCount = ammoCount - 1;
            magItem.setAmmoCount(held, newCount);
            if (newCount == 0) magItem.setAmmoId(held, DefaultAssets.EMPTY_AMMO_ID);

            // Extras go to inventory only after held's NBT has changed — they now have
            // a different ammo count so Minecraft won't fold them back into the hand slot.
            if (!extras.isEmpty()) {
                if (!player.getInventory().add(extras)) player.drop(extras, false);
            }

            ItemStack bullet = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
            if (!player.getInventory().add(bullet)) player.drop(bullet, false);

            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        });
        ctx.get().setPacketHandled(true);
    }
}
