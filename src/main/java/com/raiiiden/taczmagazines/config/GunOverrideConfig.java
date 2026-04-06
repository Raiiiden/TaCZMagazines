package com.raiiiden.taczmagazines.config;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Manages per-gun magazine overrides loaded from taczmagazines-gun-overrides.toml.
// Changes take effect after reloading datapacks (F3+T in-game).

public class GunOverrideConfig {

    private static final Path CONFIG_FILE =
            FMLPaths.CONFIGDIR.get().resolve("taczmagazines-gun-overrides.toml");

    private static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENTRIES_VALUE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("gun_overrides")
               .comment("Override which magazine family a gun uses, or exclude a gun from the magazine system.")
               .comment("")
               .comment("Format:")
               .comment("  modid:gun_id = family_id   Force the gun to require a specific magazine family.")
               .comment("  modid:gun_id = none        Exclude the gun entirely — it uses TaCZ's default ammo behaviour.")
               .comment("")
               .comment("EXAMPLE — force a gun to use a specific magazine family:")
               .comment("  \"tacz:example_pistol = 9x19mm_17\"")
               .comment("  This makes tacz:example_pistol only accept 9x19mm_17 magazines,")
               .comment("  overriding whatever family was auto-discovered for it.")
               .comment("")
               .comment("EXAMPLE — exclude a gun from the magazine system:")
               .comment("  \"tacz:example_revolver = none\"")
               .comment("  This removes tacz:example_revolver from the magazine system entirely.")
               .comment("  It will reload using TaCZ's normal ammo pickup behaviour, no magazine required.")
               .comment("")
               .comment("Family IDs are printed to the log on startup/datapack reload (search for 'Discovered magazine family').")
               .comment("Gun IDs follow the format modid:gun_name (e.g. tacz:m9, tacz:ak47).")
               .comment("Changes take effect after reloading datapacks (F3+T in-game).");
        ENTRIES_VALUE = builder.defineListAllowEmpty(
                "entries",
                Collections.emptyList(),
                e -> e instanceof String s && s.contains("=")
        );
        builder.pop();
        SPEC = builder.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "taczmagazines-gun-overrides.toml");
    }

    //Reads the config and applies all overrides/exclusions to the live family maps.
    public static void apply() {
        Map<String, String> entries = readFromDisk();
        if (entries.isEmpty()) return;

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            ResourceLocation gunId;
            try {
                gunId = new ResourceLocation(entry.getKey().strip());
            } catch (Exception ex) {
                TaCZMagazines.LOGGER.warn("[GunOverride] Invalid gun ID '{}' — skipping", entry.getKey());
                continue;
            }

            String value = entry.getValue().strip();

            if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("false")
                    || value.equalsIgnoreCase("exclude")) {
                MagazineFamilySystem.excludeGun(gunId);
                TaCZMagazines.LOGGER.info("[GunOverride] '{}' excluded from magazine system", gunId);
            } else {
                // Value is a family ID
                String familyId = value;
                if (!MagazineFamilySystem.getAllFamilies().contains(familyId)) {
                    TaCZMagazines.LOGGER.warn("[GunOverride] Family '{}' does not exist (gun '{}') — skipping", familyId, gunId);
                    continue;
                }
                MagazineFamilySystem.overrideGunFamily(gunId, familyId);
                TaCZMagazines.LOGGER.info("[GunOverride] '{}' → family '{}'", gunId, familyId);
            }
        }
    }

    // ── Config file parsing ───────────────────────────────────────────────────
    // Follows the same disk-read pattern as FamilyConfigManager to avoid
    // ForgeConfigSpec in-memory caching eating user edits.

    private static final Pattern ENTRIES_KEY =
            Pattern.compile("^\\s*entries\\s*=\\s*\\[", Pattern.MULTILINE);

    private static Map<String, String> readFromDisk() {
        if (!Files.exists(CONFIG_FILE)) return new LinkedHashMap<>();
        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            Matcher m = ENTRIES_KEY.matcher(content);
            if (!m.find()) return new LinkedHashMap<>();
            int arrayStart = m.end() - 1;
            int arrayEnd   = content.indexOf(']', arrayStart);
            if (arrayEnd < 0) return new LinkedHashMap<>();
            return parseArrayContent(content.substring(arrayStart + 1, arrayEnd));
        } catch (IOException e) {
            TaCZMagazines.LOGGER.warn("[GunOverride] Could not read config: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, String> parseArrayContent(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : raw.split(",\\s*")) {
            item = item.strip();
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
}
