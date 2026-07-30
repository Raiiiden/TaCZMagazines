package com.raiiiden.taczmagazines.item;

import com.raiiiden.taczmagazines.TaCZMagazines;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class SoundRegistrar {
    private static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, TaCZMagazines.MODID);

    public static final RegistryObject<SoundEvent> MAG_LOADING =
            SOUND_EVENTS.register("magloading", () ->
                    SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(TaCZMagazines.MODID, "magloading")));

    public static final RegistryObject<SoundEvent> MAG_UNLOADING =
            SOUND_EVENTS.register("magunloading", () ->
                    SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(TaCZMagazines.MODID, "magunloading")));

    private SoundRegistrar() {}

    public static void register() {
        SOUND_EVENTS.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    public static void playMagazineLoad(Entity entity) {
        playForEveryone(entity, MAG_LOADING.get());
    }

    public static void playMagazineUnload(Entity entity) {
        playForEveryone(entity, MAG_UNLOADING.get());
    }

    private static void playForEveryone(Entity entity, SoundEvent sound) {
        if (entity.level().isClientSide) {
            entity.level().playLocalSound(
                    entity.getX(), entity.getY(), entity.getZ(),
                    sound, SoundSource.PLAYERS, 1.0F, 1.0F, false);
        } else {
            // A Player's normal playSound call excludes that same player
            // because vanilla assumes their client already played it. These
            // transfers are packet-driven, so broadcast with no exclusion.
            entity.level().playSound(
                    null, entity.getX(), entity.getY(), entity.getZ(),
                    sound, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }
}
