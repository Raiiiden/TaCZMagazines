package com.raiiiden.taczmagazines.network;

import com.raiiiden.taczmagazines.TaCZMagazines;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TaCZMagazines.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int id = 0;

    public static void register() {
        CHANNEL.registerMessage(id++, SelectMagazinePacket.class,
                SelectMagazinePacket::encode,
                SelectMagazinePacket::decode,
                SelectMagazinePacket::handle);

        CHANNEL.registerMessage(id++, OpenSelectorPacket.class,
                OpenSelectorPacket::encode,
                OpenSelectorPacket::decode,
                OpenSelectorPacket::handle);
    }
}