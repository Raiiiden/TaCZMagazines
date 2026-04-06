package com.raiiiden.taczmagazines.config;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

// ForgeConfigSpec is registered so Configured can find and display the config.
// Reads are always done directly from the physical file so load-order issues with
// ForgeConfigSpec's in-memory state can't cause user edits to be silently overwritten.
public class FamilyConfigManager {

    private static final Path CONFIG_FILE =
            FMLPaths.CONFIGDIR.get().resolve("taczmagazines-families.toml");

    private static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> OVERRIDES_VALUE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("family_models")
               .comment("Controls which gun's magazine model is used for each magazine family.")
               .comment("Format:  familyId = modid:gun_id")
               .comment("Example: 9x19mm_17 = tacz:m9")
               .comment("Gun ID must be in the compatible list for that family (check logs on startup).")
               .comment("New families are added automatically when datapacks reload.")
               .comment("Changes take effect after reloading datapacks (F3+T).");
        OVERRIDES_VALUE = builder.defineListAllowEmpty(
                "overrides",
                Collections.emptyList(),
                e -> e instanceof String s && s.contains("=")
        );
        builder.pop();
        SPEC = builder.build();
    }

    private static final Map<String, ResourceLocation> RESOLVED = new LinkedHashMap<>();

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "taczmagazines-families.toml");
    }

    // Called after family discovery. Reads the file from disk, adds missing families
    // with their defaults, writes back only if something changed, then builds RESOLVED.
    public static void sync() {
        // Always read from disk so user edits (via Configured or text editor) are
        // never lost regardless of ForgeConfigSpec's in-memory state.
        Map<String, String> stored = readFromDisk();

        boolean changed = false;
        for (String familyId : new TreeSet<>(MagazineFamilySystem.getAllFamilies())) {
            if (!stored.containsKey(familyId)) {
                ResourceLocation rep = MagazineFamilySystem.getDefaultRepresentativeGun(familyId);
                if (rep != null) {
                    stored.put(familyId, rep.toString());
                    changed = true;
                }
            }
        }

        if (changed) {
            // Write through ForgeConfigSpec so the file format stays compatible with Configured.
            List<String> serialised = stored.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + " = " + e.getValue())
                    .collect(Collectors.toList());
            OVERRIDES_VALUE.set(serialised);
            SPEC.save();
        }

        buildResolved(stored);
    }

    public static ResourceLocation getOverride(String familyId) {
        return RESOLVED.get(familyId);
    }

    // -------------------------------------------------------------------------

    // Reads the physical TOML file and returns a familyId -> gunId map.
    // Uses a start-of-line regex so NightConfig's "#overrides" comment line above the
    // key never matches instead of the actual key, which would make us parse the wrong
    // content and then overwrite the file with defaults on every world load.
    private static final Pattern OVERRIDES_KEY =
            Pattern.compile("^\\s*overrides\\s*=\\s*\\[", Pattern.MULTILINE);

    private static Map<String, String> readFromDisk() {
        if (!Files.exists(CONFIG_FILE)) return new LinkedHashMap<>();

        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);

            Matcher m = OVERRIDES_KEY.matcher(content);
            if (!m.find()) return new LinkedHashMap<>();

            int arrayStart = m.end() - 1; // position of the opening '['
            int arrayEnd   = content.indexOf(']', arrayStart);
            if (arrayEnd < 0) return new LinkedHashMap<>();

            return parseArrayContent(content.substring(arrayStart + 1, arrayEnd));

        } catch (IOException e) {
            TaCZMagazines.LOGGER.warn("[FamilyConfig] Could not read config file: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // Parses the raw content between [ and ] into a familyId -> gunId map.
    // Items are quoted strings like: "9x19mm_17 = tacz:m9"
    private static Map<String, String> parseArrayContent(String raw) {
        Map<String, String> result = new LinkedHashMap<>();

        // Split on boundaries between quoted items: ," or ,\n"
        String[] items = raw.split(",\\s*");
        for (String item : items) {
            item = item.strip();
            // Strip surrounding quotes
            if (item.startsWith("\"")) item = item.substring(1);
            if (item.endsWith("\""))   item = item.substring(0, item.length() - 1);

            int eq = item.indexOf('=');
            if (eq < 0) continue;
            String key = item.substring(0, eq).strip();
            String val = item.substring(eq + 1).strip();
            if (!key.isEmpty() && !val.isEmpty()) result.put(key, val);
        }
        return result;
    }

    private static void buildResolved(Map<String, String> stored) {
        RESOLVED.clear();

        for (Map.Entry<String, String> entry : stored.entrySet()) {
            String familyId = entry.getKey();
            String rawGun   = entry.getValue();

            if (!MagazineFamilySystem.getAllFamilies().contains(familyId)) continue;

            ResourceLocation gunId;
            try { gunId = new ResourceLocation(rawGun); }
            catch (Exception e) {
                TaCZMagazines.LOGGER.warn("[FamilyConfig] Invalid gun ID '{}' for family '{}', using default", rawGun, familyId);
                continue;
            }

            if (!MagazineFamilySystem.getCompatibleGuns(familyId).contains(gunId)) {
                TaCZMagazines.LOGGER.warn("[FamilyConfig] '{}' is not compatible with '{}', using default", gunId, familyId);
                continue;
            }

            ResourceLocation def = MagazineFamilySystem.getDefaultRepresentativeGun(familyId);
            if (!gunId.equals(def)) {
                RESOLVED.put(familyId, gunId);
                TaCZMagazines.LOGGER.info("[FamilyConfig] '{}' → '{}'", familyId, gunId);
            }
        }
    }
}
