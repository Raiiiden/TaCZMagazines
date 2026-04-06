package com.raiiiden.taczmagazines.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.raiiiden.taczmagazines.TaCZMagazines;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModKeybinds {

    public static final KeyMapping UNLOAD_MAG = new KeyMapping(
            "key.taczmagazines.unload_mag",
            new net.minecraftforge.client.settings.IKeyConflictContext() {
                @Override
                public boolean isActive() {
                    return KeyConflictContext.IN_GAME.isActive();
                }

                @Override
                public boolean conflicts(net.minecraftforge.client.settings.IKeyConflictContext other) {
                    return this == other;
                }
            },
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.taczmagazines"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(UNLOAD_MAG);
    }
}
