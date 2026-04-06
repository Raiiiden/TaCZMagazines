package com.raiiiden.taczmagazines.network;

import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Ejects the magazine currently stored in the held gun back to the player's inventory, writing the remaining gun ammo count into it first.
public class UnloadGunMagPacket {

    public static void encode(UnloadGunMagPacket msg, FriendlyByteBuf buf) {}

    public static UnloadGunMagPacket decode(FriendlyByteBuf buf) {
        return new UnloadGunMagPacket();
    }

    public static void handle(UnloadGunMagPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack gun = player.getMainHandItem();
            if (!(gun.getItem() instanceof IGun iGun)) return;
            if (!(gun.getItem() instanceof AbstractGunItem abstractGun)) return;

            ResourceLocation gunId = iGun.getGunId(gun);
            if (MagazineFamilySystem.getFamilyForGun(gunId) == null) return;

            gun.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
                if (!magCap.hasMagazine()) return;

                ItemStack storedMag = magCap.getStoredMagazine();

                // Write the remaining in-gun ammo back into the magazine before ejecting
                if (storedMag.getItem() instanceof MagazineItem magItem) {
                    int remaining = abstractGun.getCurrentAmmoCount(gun);
                    final ItemStack magForLambda = storedMag;
                    if (remaining > 0) {
                        TimelessAPI.getCommonGunIndex(gunId).ifPresent(idx -> {
                            ResourceLocation ammoId = idx.getGunData().getAmmoId();
                            if (!DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) {
                                magItem.setAmmoId(magForLambda, ammoId);
                                magItem.setAmmoCount(magForLambda, remaining);
                            }
                        });
                    } else {
                        magItem.setAmmoCount(storedMag, 0);
                        magItem.setAmmoId(storedMag, DefaultAssets.EMPTY_AMMO_ID);
                    }
                    magCap.setStoredMagazine(storedMag);
                    storedMag = magCap.getStoredMagazine(); // re-read in case cap copies
                }

                abstractGun.setCurrentAmmoCount(gun, 0);
                magCap.clearMagazine();

                if (!player.getInventory().add(storedMag)) {
                    player.drop(storedMag, false);
                }
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
