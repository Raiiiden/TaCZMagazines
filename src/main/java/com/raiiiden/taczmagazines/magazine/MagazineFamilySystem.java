package com.raiiiden.taczmagazines.magazine;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.ICommonResourceProvider;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

// Dynamically discovers and manages magazine families based on gun compatibility. Guns that share the same ammo type and capacity are grouped into magazine families.
public class MagazineFamilySystem {

    // Magazine family ID format:
    //   base:     "ammoname_capacity"      e.g. "9x19mm_17"
    //   extended: "ammoname_capacity_extN" e.g. "9x19mm_45_ext1"
    private static final Map<String, Set<ResourceLocation>> MAGAZINE_FAMILIES = new HashMap<>();
    private static final Map<ResourceLocation, String> GUN_TO_FAMILY = new HashMap<>();
    private static final Map<String, ResourceLocation> FAMILY_REPRESENTATIVE = new HashMap<>();

    private static final Set<String> EXTENDED_FAMILIES = new HashSet<>();
    private static final Map<String, Integer> EXT_LEVEL_MAP = new HashMap<>();
    // One gun can belong to multiple extended families (one per level)
    private static final Map<ResourceLocation, List<String>> GUN_TO_EXT_FAMILIES = new HashMap<>();

    public static void discoverMagazineFamilies() {
        MAGAZINE_FAMILIES.clear();
        GUN_TO_FAMILY.clear();
        FAMILY_REPRESENTATIVE.clear();
        EXTENDED_FAMILIES.clear();
        EXT_LEVEL_MAP.clear();
        GUN_TO_EXT_FAMILIES.clear();

        // Use get() instead of getInstance() so that on a dedicated-server client the
        // CommonNetworkCache (populated via ServerMessageSyncGunPack) is used rather
        // than CommonAssetsManager.INSTANCE which is only set server-side.
        ICommonResourceProvider assetsManager = CommonAssetsManager.get();
        if (assetsManager.getAllGuns().isEmpty()) {
            TaCZMagazines.LOGGER.warn("Cannot discover magazine families - no gun data available yet");
            return;
        }

        Map<String, List<ResourceLocation>> groupedGuns = new HashMap<>();
        // Extended: familyId -> guns, familyId -> level
        Map<String, List<ResourceLocation>> extGroupedGuns = new HashMap<>();
        Map<String, Integer> extFamilyLevels = new HashMap<>();

        for (Map.Entry<ResourceLocation, CommonGunIndex> entry : assetsManager.getAllGuns()) {
            ResourceLocation gunId = entry.getKey();
            CommonGunIndex index = entry.getValue();

            if (!isCompatibleGun(index)) continue;

            // Base family
            String familyId = createFamilyId(index);
            groupedGuns.computeIfAbsent(familyId, k -> new ArrayList<>()).add(gunId);

            // Extended families — one per level that meaningfully exceeds the base capacity
            int baseCapacity = index.getGunData().getAmmoAmount();
            int[] extAmounts = index.getGunData().getExtendedMagAmmoAmount();
            if (extAmounts != null) {
                for (int i = 0; i < extAmounts.length && i < 3; i++) {
                    int extCapacity = extAmounts[i];
                    if (extCapacity <= baseCapacity) continue; // not a real upgrade
                    String extFamilyId = createExtFamilyId(index, extCapacity, i + 1);
                    extGroupedGuns.computeIfAbsent(extFamilyId, k -> new ArrayList<>()).add(gunId);
                    extFamilyLevels.put(extFamilyId, i + 1);
                }
            }
        }

        // Register base families
        for (Map.Entry<String, List<ResourceLocation>> entry : groupedGuns.entrySet()) {
            String familyId = entry.getKey();
            List<ResourceLocation> guns = entry.getValue();
            if (guns.isEmpty()) continue;

            MAGAZINE_FAMILIES.put(familyId, new HashSet<>(guns));
            for (ResourceLocation gunId : guns) GUN_TO_FAMILY.put(gunId, familyId);

            guns.sort(Comparator.comparing(ResourceLocation::toString));
            FAMILY_REPRESENTATIVE.put(familyId, guns.get(0));

            TaCZMagazines.LOGGER.info("Discovered magazine family '{}' with {} compatible guns", familyId, guns.size());
        }

        // Register extended families
        for (Map.Entry<String, List<ResourceLocation>> entry : extGroupedGuns.entrySet()) {
            String extFamilyId = entry.getKey();
            List<ResourceLocation> guns = entry.getValue();
            if (guns.isEmpty()) continue;

            int level = extFamilyLevels.get(extFamilyId);
            MAGAZINE_FAMILIES.put(extFamilyId, new HashSet<>(guns));
            EXTENDED_FAMILIES.add(extFamilyId);
            EXT_LEVEL_MAP.put(extFamilyId, level);

            for (ResourceLocation gunId : guns) {
                GUN_TO_EXT_FAMILIES.computeIfAbsent(gunId, k -> new ArrayList<>()).add(extFamilyId);
            }

            guns.sort(Comparator.comparing(ResourceLocation::toString));
            FAMILY_REPRESENTATIVE.put(extFamilyId, guns.get(0));

            TaCZMagazines.LOGGER.info("Discovered extended mag family '{}' (level {}) with {} compatible guns", extFamilyId, level, guns.size());
        }

        TaCZMagazines.LOGGER.info("Total magazine families: {} base + {} extended",
                groupedGuns.size(), EXTENDED_FAMILIES.size());

        // Sync config after discovery so the file reflects the current family list
        com.raiiiden.taczmagazines.config.FamilyConfigManager.sync();
    }

    private static String createFamilyId(CommonGunIndex gunIndex) {
        ResourceLocation ammoId = gunIndex.getGunData().getAmmoId();
        int capacity = gunIndex.getGunData().getAmmoAmount();
        String ammoName = ammoId.getPath().replace("_", "").toLowerCase();
        return String.format("%s_%d", ammoName, capacity);
    }

    private static String createExtFamilyId(CommonGunIndex gunIndex, int extCapacity, int level) {
        ResourceLocation ammoId = gunIndex.getGunData().getAmmoId();
        String ammoName = ammoId.getPath().replace("_", "").toLowerCase();
        return String.format("%s_%d_ext%d", ammoName, extCapacity, level);
    }

    private static boolean isCompatibleGun(CommonGunIndex index) {
        if (index == null || index.getGunData() == null) return false;

        var reloadData = index.getGunData().getReloadData();
        if (reloadData.getType() != FeedType.MAGAZINE) return false;
        if (reloadData.isInfinite()) return false;

        int ammoAmount = index.getGunData().getAmmoAmount();
        if (ammoAmount <= 2) return false;

        // Filter out tube-fed shotguns by reload time
        float emptyTime = reloadData.getFeed().getEmptyTime();
        float tacticalTime = reloadData.getFeed().getTacticalTime();
        if (emptyTime < 1.0f || tacticalTime < 1.0f) return false;

        return true;
    }

    // -------------------------------------------------------------------------
    // Extended family helpers
    // -------------------------------------------------------------------------

    public static boolean isExtendedFamily(String familyId) {
        return EXTENDED_FAMILIES.contains(familyId);
    }

    public static int getExtLevelForFamily(String familyId) {
        return EXT_LEVEL_MAP.getOrDefault(familyId, 0);
    }

    public static Set<String> getExtendedFamilies() {
        return Collections.unmodifiableSet(EXTENDED_FAMILIES);
    }

    // Returns extended families that share at least one gun with the given base family, sorted by level then capacity
    public static List<String> getExtendedFamiliesForBaseFamily(String baseFamilyId) {
        Set<ResourceLocation> guns = getCompatibleGuns(baseFamilyId);
        Set<String> found = new LinkedHashSet<>();
        for (ResourceLocation gunId : guns) {
            List<String> extFamilies = GUN_TO_EXT_FAMILIES.get(gunId);
            if (extFamilies != null) found.addAll(extFamilies);
        }
        List<String> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(MagazineFamilySystem::getExtLevelForFamily)
                .thenComparingInt(MagazineFamilySystem::getCapacityForFamily));
        return sorted;
    }

    // -------------------------------------------------------------------------
    // Lookups
    // -------------------------------------------------------------------------

    public static String getFamilyForGun(ResourceLocation gunId) {
        return GUN_TO_FAMILY.get(gunId);
    }

    public static Set<ResourceLocation> getCompatibleGuns(String familyId) {
        return MAGAZINE_FAMILIES.getOrDefault(familyId, Collections.emptySet());
    }

    // ── Gun override / exclusion (applied by GunOverrideConfig after discovery) ──

    // Removes a gun from the magazine system so it falls back to TaCZ default behaviour.
    public static void excludeGun(ResourceLocation gunId) {
        String family = GUN_TO_FAMILY.remove(gunId);
        if (family != null) {
            Set<ResourceLocation> guns = MAGAZINE_FAMILIES.get(family);
            if (guns != null) guns.remove(gunId);
        }
        List<String> extFamilies = GUN_TO_EXT_FAMILIES.remove(gunId);
        if (extFamilies != null) {
            for (String extFamily : extFamilies) {
                Set<ResourceLocation> guns = MAGAZINE_FAMILIES.get(extFamily);
                if (guns != null) guns.remove(gunId);
            }
        }
    }

    // Forces a gun to belong to a specific magazine family, replacing any auto-discovered mapping. */
    public static void overrideGunFamily(ResourceLocation gunId, String targetFamilyId) {
        // Remove from whichever family it currently belongs to
        String currentFamily = GUN_TO_FAMILY.remove(gunId);
        if (currentFamily != null && !currentFamily.equals(targetFamilyId)) {
            Set<ResourceLocation> guns = MAGAZINE_FAMILIES.get(currentFamily);
            if (guns != null) guns.remove(gunId);
        }
        // Add to the target family
        MAGAZINE_FAMILIES.computeIfAbsent(targetFamilyId, k -> new HashSet<>()).add(gunId);
        GUN_TO_FAMILY.put(gunId, targetFamilyId);
    }

    // Returns the alphabetically-first gun discovered for this family (the raw default, ignoring config).
    public static ResourceLocation getDefaultRepresentativeGun(String familyId) {
        return FAMILY_REPRESENTATIVE.get(familyId);
    }

    // Returns the representative gun for rendering, respecting any user config override.
    public static ResourceLocation getRepresentativeGun(String familyId) {
        ResourceLocation override = com.raiiiden.taczmagazines.config.FamilyConfigManager.getOverride(familyId);
        return override != null ? override : FAMILY_REPRESENTATIVE.get(familyId);
    }

    // Checks base family AND any extended families the gun belongs to
    public static boolean isMagazineCompatibleWithGun(String magazineFamilyId, ResourceLocation gunId) {
        if (magazineFamilyId.equals(GUN_TO_FAMILY.get(gunId))) return true;
        List<String> extFamilies = GUN_TO_EXT_FAMILIES.get(gunId);
        return extFamilies != null && extFamilies.contains(magazineFamilyId);
    }

    // Returns all families (base only) grouped as ammoType -> Set<capacity>, used by the creative tab for base mags
    public static Map<String, Set<Integer>> getAllMagazineFamiliesWithCapacities() {
        Map<String, Set<Integer>> result = new HashMap<>();
        for (String familyId : MAGAZINE_FAMILIES.keySet()) {
            if (EXTENDED_FAMILIES.contains(familyId)) continue;
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

    // Returns all registered families (base + extended)
    public static Set<String> getAllFamilies() {
        return MAGAZINE_FAMILIES.keySet();
    }

    // Gets the ammo ResourceLocation for any family (base or extended)
    public static ResourceLocation getAmmoTypeForFamily(String familyId) {
        ResourceLocation repGun = FAMILY_REPRESENTATIVE.get(familyId);
        if (repGun != null) {
            CommonGunIndex index = CommonAssetsManager.get().getGunIndex(repGun);
            if (index != null && index.getGunData() != null) {
                return index.getGunData().getAmmoId();
            }
        }
        return null;
    }

    // Parses the capacity from a family ID — handles both base and extended formats
    public static int getCapacityForFamily(String familyId) {
        if (EXTENDED_FAMILIES.contains(familyId)) {
            // format: ammoName_extCapacity_extN — strip _extN suffix first
            int extIdx = familyId.lastIndexOf("_ext");
            if (extIdx > 0) {
                String base = familyId.substring(0, extIdx); // ammoName_extCapacity
                int lastUs = base.lastIndexOf('_');
                if (lastUs > 0) {
                    try {
                        return Integer.parseInt(base.substring(lastUs + 1));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        // Base format: ammoName_capacity
        int lastUnderscore = familyId.lastIndexOf('_');
        if (lastUnderscore > 0) {
            try {
                return Integer.parseInt(familyId.substring(lastUnderscore + 1));
            } catch (NumberFormatException e) {
                TaCZMagazines.LOGGER.warn("Could not parse capacity from family ID: {}", familyId);
            }
        }
        return 30;
    }

    // Returns the ammo-type portion of a family ID (no capacity, no ext suffix)
    public static String getBaseAmmoType(String familyId) {
        if (EXTENDED_FAMILIES.contains(familyId)) {
            int extIdx = familyId.lastIndexOf("_ext");
            String base = extIdx > 0 ? familyId.substring(0, extIdx) : familyId;
            int lastUs = base.lastIndexOf('_');
            return lastUs > 0 ? base.substring(0, lastUs) : base;
        }
        int lastUnderscore = familyId.lastIndexOf('_');
        if (lastUnderscore > 0) return familyId.substring(0, lastUnderscore);
        return familyId;
    }

    public static List<String> getFamiliesInCreativeTabOrder() {
        Map<String, Set<Integer>> baseFamiliesByAmmo = getAllMagazineFamiliesWithCapacities();

        List<String> sortedAmmoTypes = new ArrayList<>(baseFamiliesByAmmo.keySet());
        sortedAmmoTypes.sort(String::compareToIgnoreCase);

        List<String> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String ammoType : sortedAmmoTypes) {
            List<Integer> sortedCapacities = new ArrayList<>(baseFamiliesByAmmo.get(ammoType));
            Collections.sort(sortedCapacities);

            for (int capacity : sortedCapacities) {
                String familyId = ammoType + "_" + capacity;
                if (seen.add(familyId)) ordered.add(familyId);  // CHANGE THIS

                for (String extFamilyId : getExtendedFamiliesForBaseFamily(familyId)) {
                    if (seen.add(extFamilyId)) ordered.add(extFamilyId);  // CHANGE THIS
                }
            }
        }

        return ordered;
    }

    public static String getFamilyDisplayName(String familyId) {
        if (EXTENDED_FAMILIES.contains(familyId)) {
            int level = EXT_LEVEL_MAP.getOrDefault(familyId, 1);
            String[] roman = {"I", "II", "III"};
            int extIdx = familyId.lastIndexOf("_ext");
            String base = extIdx > 0 ? familyId.substring(0, extIdx) : familyId;
            int lastUs = base.lastIndexOf('_');
            if (lastUs > 0) {
                String ammoType = base.substring(0, lastUs).toUpperCase();
                String capacity = base.substring(lastUs + 1);
                return String.format("%s %s-Round Extended %s Magazine", ammoType, capacity, roman[level - 1]);
            }
        }
        int lastUnderscore = familyId.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String ammoType = familyId.substring(0, lastUnderscore).toUpperCase();
            String capacity = familyId.substring(lastUnderscore + 1);
            return String.format("%s %s-Round Magazine", ammoType, capacity);
        }
        return familyId;
    }
}
