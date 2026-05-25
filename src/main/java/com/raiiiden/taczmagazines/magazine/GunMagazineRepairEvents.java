package com.raiiiden.taczmagazines.magazine;

import com.raiiiden.taczmagazines.TaCZMagazines;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID)
public final class GunMagazineRepairEvents {

    private GunMagazineRepairEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        for (ItemStack stack : player.getInventory().items) {
            GunMagazineInitializer.ensureMagazineForLoadedGun(stack);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            GunMagazineInitializer.ensureMagazineForLoadedGun(stack);
        }
        for (ItemStack stack : player.getInventory().armor) {
            GunMagazineInitializer.ensureMagazineForLoadedGun(stack);
        }
    }
}
