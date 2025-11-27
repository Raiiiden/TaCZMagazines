package com.raiiiden.taczmagazines.capability;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GunMagazineProvider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
    public static final Capability<GunMagazineCapability> GUN_MAGAZINE = CapabilityManager.get(new CapabilityToken<>() {});

    private final GunMagazineCapability capability = new GunMagazineCapability();
    private final LazyOptional<GunMagazineCapability> holder = LazyOptional.of(() -> capability);

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == GUN_MAGAZINE) {
            return holder.cast();
        }
        return LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        return capability.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        capability.deserializeNBT(nbt);
    }

    public void invalidate() {
        holder.invalidate();
    }
}