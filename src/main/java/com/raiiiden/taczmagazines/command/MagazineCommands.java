package com.raiiiden.taczmagazines.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public class MagazineCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("magazine")
                .then(Commands.literal("eject")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            ItemStack mainHand = player.getMainHandItem();

                            if (!(mainHand.getItem() instanceof IGun iGun)) {
                                player.sendSystemMessage(Component.literal("§cYou must be holding a gun!"));
                                return 0;
                            }

                            mainHand.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
                                if (magCap.hasMagazine()) {
                                    ItemStack storedMag = magCap.getStoredMagazine();

                                    // Get gun data
                                    ResourceLocation gunId = iGun.getGunId(mainHand);
                                    TimelessAPI.getCommonGunIndex(gunId).ifPresent(gunIndex -> {
                                        // Put remaining ammo back into the magazine
                                        if (storedMag.getItem() instanceof MagazineItem magItem) {
                                            int currentAmmoInGun = iGun.getCurrentAmmoCount(mainHand);
                                            int currentAmmoInMag = magItem.getAmmoCount(storedMag);

                                            // **FIX: Get the ammo ID from the gun's data**
                                            ResourceLocation gunAmmoId = gunIndex.getGunData().getAmmoId();
                                            if (currentAmmoInGun > 0 && !gunAmmoId.equals(DefaultAssets.EMPTY_AMMO_ID)) {
                                                magItem.setAmmoId(storedMag, gunAmmoId);
                                            }

                                            // Add gun's ammo back to magazine
                                            magItem.setAmmoCount(storedMag, currentAmmoInMag + currentAmmoInGun);
                                        }

                                        // Get magazine info (after updating ammo count)
                                        String magInfo = "Empty Magazine";
                                        if (storedMag.getItem() instanceof MagazineItem magItem) {
                                            int ammoCount = magItem.getAmmoCount(storedMag);
                                            magInfo = "Magazine (" + ammoCount + " rounds)";
                                        }

                                        // Clear gun ammo and magazine FIRST
                                        iGun.setCurrentAmmoCount(mainHand, 0);
                                        magCap.clearMagazine();

                                        // THEN return to player
                                        boolean added = player.getInventory().add(storedMag);
                                        if (!added) {
                                            player.drop(storedMag, false);
                                        }

                                        player.sendSystemMessage(Component.literal("§aEjected: " + magInfo));
                                    });
                                } else {
                                    player.sendSystemMessage(Component.literal("§eNo magazine in gun!"));
                                }
                            });

                            return Command.SINGLE_SUCCESS;
                        })
                );
    }
}