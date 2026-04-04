package com.raiiiden.taczmagazines.magazine;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

// Dynamically discovers and manages magazine families based on gun compatibility. Guns that share the same ammo type and capacity are grouped into magazine families.
public class MagazineFamilySystem {

    // Magazine family ID format: "ammo_capacity" (e.g., "9x19mm_17", "556x45mm_30")
    // Maps magazine family ID -> list of compatible gun IDs
    private static final Map<String, Set<ResourceLocation>> MAGAZINE_FAMILIES = new HashMap<>();

    // Maps gun ID -> magazine family ID
    private static final Map<ResourceLocation, String> GUN_TO_FAMILY = new HashMap<>();

    // Maps magazine family ID -> representative gun (for fallback purposes)
    private static final Map<String, ResourceLocation> FAMILY_REPRESENTATIVE = new HashMap<>();

    // Discovers magazine families from all loaded guns. Called during datapack sync.
    public static void discoverMagazineFamilies() {
        MAGAZINE_FAMILIES.clear();
        GUN_TO_FAMILY.clear();
        FAMILY_REPRESENTATIVE.clear();

        CommonAssetsManager assetsManager = CommonAssetsManager.getInstance();
        if (assetsManager == null) {
            TaCZMagazines.LOGGER.warn("Cannot discover magazine families - CommonAssetsManager is null");
            return;
        }

        // Group guns by ammo type and capacity
        Map<String, List<ResourceLocation>> groupedGuns = new HashMap<>();

        for (Map.Entry<ResourceLocation, CommonGunIndex> entry : assetsManager.getAllGuns()) {
            ResourceLocation gunId = entry.getKey();
            CommonGunIndex index = entry.getValue();

            if (!isCompatibleGun(index)) {
                continue;
            }

            // Create family ID from ammo type and capacity
            String familyId = createFamilyId(index);

            groupedGuns.computeIfAbsent(familyId, k -> new ArrayList<>()).add(gunId);
        }

        // Register magazine families
        for (Map.Entry<String, List<ResourceLocation>> entry : groupedGuns.entrySet()) {
            String familyId = entry.getKey();
            List<ResourceLocation> compatibleGuns = entry.getValue();

            if (compatibleGuns.isEmpty()) {
                continue;
            }

            // Store the family
            Set<ResourceLocation> gunSet = new HashSet<>(compatibleGuns);
            MAGAZINE_FAMILIES.put(familyId, gunSet);

            // Map each gun to its family
            for (ResourceLocation gunId : compatibleGuns) {
                GUN_TO_FAMILY.put(gunId, familyId);
            }

            // Pick a representative gun (first in alphabetical order)
            compatibleGuns.sort(Comparator.comparing(ResourceLocation::toString));
            FAMILY_REPRESENTATIVE.put(familyId, compatibleGuns.get(0));

            TaCZMagazines.LOGGER.info("Discovered magazine family '{}' with {} compatible guns",
                    familyId, compatibleGuns.size());
        }

        TaCZMagazines.LOGGER.info("Total magazine families discovered: {}", MAGAZINE_FAMILIES.size());
    }

    // Creates a magazine family ID from gun data.
    private static String createFamilyId(CommonGunIndex gunIndex) {
        ResourceLocation ammoId = gunIndex.getGunData().getAmmoId();
        int capacity = gunIndex.getGunData().getAmmoAmount();

        // Clean up ammo ID for readability
        String ammoName = ammoId.getPath().replace("_", "").toLowerCase();

        return String.format("%s_%d", ammoName, capacity);
    }

    // Checks if a gun is compatible with the magazine system.
    private static boolean isCompatibleGun(CommonGunIndex index) {
        if (index == null || index.getGunData() == null) {
            return false;
        }

        var reloadData = index.getGunData().getReloadData();
        if (reloadData.getType() != FeedType.MAGAZINE) {
            return false;
        }

        if (reloadData.isInfinite()) {
            return false;
        }

        int ammoAmount = index.getGunData().getAmmoAmount();
        if (ammoAmount <= 2) {
            return false;
        }

        // Filter out tube-fed shotguns by reload time
        float emptyTime = reloadData.getFeed().getEmptyTime();
        float tacticalTime = reloadData.getFeed().getTacticalTime();
        if (emptyTime < 1.0f || tacticalTime < 1.0f) {
            return false;
        }

        return true;
    }

    // Gets all magazine families with their capacities, grouped by base ammo type. Returns map of baseAmmoType -> Set of capacities
    public static Map<String, Set<Integer>> getAllMagazineFamiliesWithCapacities() {
        Map<String, Set<Integer>> result = new HashMap<>();

        for (String familyId : MAGAZINE_FAMILIES.keySet()) {
            // Extract base ammo type and capacity from family ID
            int lastUnderscore = familyId.lastIndexOf('_');
            if (lastUnderscore > 0) {
                String baseAmmoType = familyId.substring(0, lastUnderscore);
                try {
                    int capacity = Integer.parseInt(familyId.substring(lastUnderscore + 1));
                    result.computeIfAbsent(baseAmmoType, k -> new HashSet<>()).add(capacity);
                } catch (NumberFormatException e) {
                    TaCZMagazines.LOGGER.warn("Invalid family ID format: {}", familyId);
                }
            }
        }

        return result;
    }

    // ets the magazine family ID for a gun.
    public static String getFamilyForGun(ResourceLocation gunId) {
        return GUN_TO_FAMILY.get(gunId);
    }

    // Gets all guns compatible with a magazine family.
    public static Set<ResourceLocation> getCompatibleGuns(String familyId) {
        return MAGAZINE_FAMILIES.getOrDefault(familyId, Collections.emptySet());
    }

    // Gets the representative gun for a magazine family (for fallback rendering).
    public static ResourceLocation getRepresentativeGun(String familyId) {
        return FAMILY_REPRESENTATIVE.get(familyId);
    }

    // Checks if a magazine is compatible with a gun.
    public static boolean isMagazineCompatibleWithGun(String magazineFamilyId, ResourceLocation gunId) {
        String gunFamily = GUN_TO_FAMILY.get(gunId);
        return magazineFamilyId.equals(gunFamily);
    }

    // Gets a human-readable name for a magazine family.
    public static String getFamilyDisplayName(String familyId) {
        int lastUnderscore = familyId.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String ammoType = familyId.substring(0, lastUnderscore).toUpperCase();
            String capacity = familyId.substring(lastUnderscore + 1);
            return String.format("%s %s-Round Magazine", ammoType, capacity);
        }
        return familyId;
    }

    // Gets all registered magazine families.
    public static Set<String> getAllFamilies() {
        return MAGAZINE_FAMILIES.keySet();
    }

    // Gets the ammo type from a family ID.
    public static ResourceLocation getAmmoTypeForFamily(String familyId) {
        ResourceLocation repGun = FAMILY_REPRESENTATIVE.get(familyId);
        if (repGun != null) {
            CommonGunIndex index = CommonAssetsManager.getInstance().getGunIndex(repGun);
            if (index != null && index.getGunData() != null) {
                return index.getGunData().getAmmoId();
            }
        }
        return null;
    }

    // Gets the capacity from a family ID.
    public static int getCapacityForFamily(String familyId) {
        int lastUnderscore = familyId.lastIndexOf('_');
        if (lastUnderscore > 0) {
            try {
                return Integer.parseInt(familyId.substring(lastUnderscore + 1));
            } catch (NumberFormatException e) {
                TaCZMagazines.LOGGER.warn("Could not parse capacity from family ID: {}", familyId);
            }
        }
        return 30; // Default
    }

    // Gets the base ammo type (without capacity) from a family ID.
    public static String getBaseAmmoType(String familyId) {
        int lastUnderscore = familyId.lastIndexOf('_');
        if (lastUnderscore > 0) {
            return familyId.substring(0, lastUnderscore);
        }
        return familyId;
    }
}