package com.raiiiden.taczmagazines.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;


public class OpenSelectorPacket {

    // Server-side set of players currently in selector mode. Thread-safe read is fine here
    public static final Set<UUID> SELECTING_PLAYERS = Collections.synchronizedSet(new HashSet<>());

    private final boolean open;

    public OpenSelectorPacket(boolean open) {
        this.open = open;
    }

    public static void encode(OpenSelectorPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.open);
    }

    public static OpenSelectorPacket decode(FriendlyByteBuf buf) {
        return new OpenSelectorPacket(buf.readBoolean());
    }

    public static void handle(OpenSelectorPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (pkt.open) {
                SELECTING_PLAYERS.add(player.getUUID());
            } else {
                SELECTING_PLAYERS.remove(player.getUUID());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}