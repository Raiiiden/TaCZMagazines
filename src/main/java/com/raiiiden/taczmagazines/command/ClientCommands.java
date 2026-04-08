package com.raiiiden.taczmagazines.command;

import com.mojang.brigadier.Command;
import com.raiiiden.taczmagazines.debug.GunBoneDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;

@OnlyIn(Dist.CLIENT)
public class ClientCommands {

    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            net.minecraft.commands.Commands.literal("magazine")
                .then(net.minecraft.commands.Commands.literal("bonedebug")
                    .executes(ctx -> {
                        String result = GunBoneDebugger.run();
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(Component.literal("§e[Magazine Debug] §f" + result));
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                )
        );
    }
}
