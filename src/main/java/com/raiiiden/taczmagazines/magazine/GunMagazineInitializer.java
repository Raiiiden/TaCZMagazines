package com.raiiiden.taczmagazines.magazine;

import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.item.MagazineRegistrar;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GunMagazineInitializer {

    private GunMagazineInitializer() {
    }

    public static void ensureMagazineForLoadedGun(ItemStack gun) {
        if (gun.isEmpty() || !(gun.getItem() instanceof IGun iGun)) return;

        ResourceLocation gunId = iGun.getGunId(gun);
        CommonGunIndex gunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) return;
        if (gunIndex.getGunData().getReloadData().getType() != FeedType.MAGAZINE) return;

        String baseFamily = MagazineFamilySystem.getFamilyForGun(gunId);
        if (baseFamily == null) return;

        int currentAmmo = iGun.getCurrentAmmoCount(gun);
        if (currentAmmo <= 0) return;

        gun.getCapability(GunMagazineProvider.GUN_MAGAZINE).ifPresent(magCap -> {
            if (magCap.hasMagazine()) return;

            int activeCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(gun, gunIndex.getGunData());
            String familyId = chooseFamily(baseFamily, currentAmmo, activeCapacity);
            ItemStack magazine = MagazineItem.createMagazineByFamily(
                    MagazineRegistrar.MAGAZINE.get(),
                    familyId,
                    currentAmmo,
                    gunIndex.getGunData().getAmmoId());

            magCap.setStoredMagazine(magazine);
        });
    }

    private static String chooseFamily(String baseFamily, int currentAmmo, int activeCapacity) {
        List<String> candidates = new ArrayList<>();
        candidates.add(baseFamily);
        candidates.addAll(MagazineFamilySystem.getExtendedFamiliesForBaseFamily(baseFamily));

        return candidates.stream()
                .filter(family -> MagazineFamilySystem.getCapacityForFamily(family) >= currentAmmo)
                .min(Comparator
                        .comparingInt((String family) -> Math.abs(MagazineFamilySystem.getCapacityForFamily(family) - activeCapacity))
                        .thenComparingInt(MagazineFamilySystem::getCapacityForFamily))
                .orElse(baseFamily);
    }
}
