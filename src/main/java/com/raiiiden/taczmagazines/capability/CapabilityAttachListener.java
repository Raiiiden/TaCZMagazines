package com.raiiiden.taczmagazines.capability;

import com.tacz.guns.api.item.IGun;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.resources.ResourceLocation;

@Mod.EventBusSubscriber(modid = "taczmagazines")
public class CapabilityAttachListener {

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<ItemStack> event) {
        ItemStack stack = event.getObject();

        // Only attach to gun items
        if (stack.getItem() instanceof IGun) {
            GunMagazineProvider provider = new GunMagazineProvider();
            event.addCapability(new ResourceLocation("taczmagazines", "gun_magazine"), provider);
        }
    }
}