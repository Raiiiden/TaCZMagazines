package com.raiiiden.taczmagazines.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SelectMagazinePacket {

    private final int slot;

    public SelectMagazinePacket(int slot) {
        this.slot = slot;
    }

    public static void encode(SelectMagazinePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.slot);
    }

    public static SelectMagazinePacket decode(FriendlyByteBuf buf) {
        return new SelectMagazinePacket(buf.readVarInt());
    }

    public static void handle(SelectMagazinePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Always unblock atomically regardless of slot value.
            OpenSelectorPacket.SELECTING_PLAYERS.remove(player.getUUID());

            // Only write the slot tag when a real slot was selected (hold + pick).
            // slot = -1 means tap reload — no slot tag needed.
            ItemStack gun = player.getMainHandItem();
            if (!gun.isEmpty()) {
                if (msg.slot >= 0) {
                    gun.getOrCreateTag().putInt("TaCZMag_SelectedSlot", msg.slot);
                } else {
                    gun.getOrCreateTag().remove("TaCZMag_SelectedSlot");
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
