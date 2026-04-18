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
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ISOLATED_GUNS_VALUE;

    // Errors collected during apply() — shown to the player in red on world load.
    // Cleared at the start of each apply() so stale errors don't persist.
    private static final List<String> CONFIG_ERRORS = new ArrayList<>();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("gun_overrides")
               .comment("Override which magazine family a gun uses, or exclude a gun from the magazine system.")
               .comment("")
               .comment("Format:")
               .comment("  \"modid:gun_id = family_id\"  — force the gun into a specific magazine family.")
               .comment("  \"modid:gun_id = none\"       — exclude the gun (uses TaCZ default ammo behaviour).")
               .comment("")
               .comment("Example: \"tacz:example_pistol = 9x19mm_17\"")
               .comment("Example: \"tacz:example_revolver = none\"")
               .comment("")
               .comment("Family IDs are printed to the log on startup/datapack reload")
               .comment("(search for 'Discovered magazine family').")
               .comment("Changes take effect after F3+T.");
        // Permissive validator — we do our own validation in apply() and report errors as
        // red chat messages instead of letting Forge crash on a malformed config file.
        ENTRIES_VALUE = builder.defineListAllowEmpty("entries", Collections.emptyList(), e -> e instanceof String);
        builder.pop();

        builder.push("isolated_guns")
               .comment("Guns listed here generate their OWN private magazine family instead of sharing")
               .comment("with other guns of the same ammo type and capacity.")
               .comment("Their magazine item will use that gun's own 3D model for rendering.")
               .comment("")
               .comment("Format: [\"modid:gun_name\", \"modid:gun_name2\", ...]")
               .comment("Example: [\"tacz:vector45\", \"tacz:m1911\"]")
               .comment("")
               .comment("Changes take effect after F3+T.");
        ISOLATED_GUNS_VALUE = builder.defineListAllowEmpty("guns", Collections.emptyList(), e -> e instanceof String);
        builder.pop();

        SPEC = builder.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "taczmagazines-gun-overrides.toml");
    }

    // Applies all overrides/exclusions and isolated-gun config to the live family maps.
    // Must be called after MagazineFamilySystem.discoverMagazineFamilies().
    public static void apply() {
        CONFIG_ERRORS.clear();
        applyEntries();
        applyIsolatedGuns();
    }

    // Returns errors found during the last apply().  Not cleared until next apply().
    public static List<String> getErrors() {
        return Collections.unmodifiableList(CONFIG_ERRORS);
    }

    // ── [gun_overrides] entries ───────────────────────────────────────────────

    private static void applyEntries() {
        Map<String, String> entries = readKeyValueSection(ENTRIES_PATTERN);
        for (Map.Entry<String, String> kv : entries.entrySet()) {
            String rawKey = kv.getKey();
            String value  = kv.getValue();

            if (!rawKey.contains(":")) {
                addError("gun-overrides.toml [gun_overrides]: \"" + rawKey + " = " + value + "\" — "
                       + "invalid gun ID (missing namespace).  Expected:  modid:gun_name = family_id  "
                       + "e.g.  tacz:m9 = none");
                continue;
            }

            ResourceLocation gunId;
            try { gunId = new ResourceLocation(rawKey); }
            catch (Exception ex) {
                addError("gun-overrides.toml [gun_overrides]: \"" + rawKey + "\" — not a valid resource location");
                continue;
            }

            if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("false")
                    || value.equalsIgnoreCase("exclude")) {
                MagazineFamilySystem.excludeGun(gunId);
                TaCZMagazines.LOGGER.info("[GunOverride] '{}' excluded from magazine system", gunId);
            } else {
                if (!MagazineFamilySystem.getAllFamilies().contains(value)) {
                    addError("gun-overrides.toml [gun_overrides]: family \"" + value
                           + "\" not found for gun \"" + gunId
                           + "\" — check the log for valid family IDs (search 'Discovered magazine family')");
                    continue;
                }
                MagazineFamilySystem.overrideGunFamily(gunId, value);
                TaCZMagazines.LOGGER.info("[GunOverride] '{}' → family '{}'", gunId, value);
            }
        }
    }

    // ── [isolated_guns] guns ─────────────────────────────────────────────────

    private static void applyIsolatedGuns() {
        List<String> rawList = readStringListSection(ISOLATED_GUNS_PATTERN);
        Set<ResourceLocation> isolatedGuns = new LinkedHashSet<>();

        for (String raw : rawList) {
            if (raw.isEmpty()) continue;
            if (!raw.contains(":")) {
                addError("gun-overrides.toml [isolated_guns]: \"" + raw + "\" — "
                       + "invalid gun ID (missing namespace).  Expected:  \"modid:gun_name\"  "
                       + "e.g.  \"tacz:vector45\"");
                continue;
            }
            try {
                isolatedGuns.add(new ResourceLocation(raw));
            } catch (Exception ex) {
                addError("gun-overrides.toml [isolated_guns]: \"" + raw + "\" — not a valid resource location");
            }
        }

        if (!isolatedGuns.isEmpty()) {
            MagazineFamilySystem.applyIsolatedGuns(isolatedGuns);
        }
    }

    private static void addError(String msg) {
        CONFIG_ERRORS.add(msg);
        TaCZMagazines.LOGGER.warn("[GunOverride] Config error: {}", msg);
    }

    // ── Disk reading ──────────────────────────────────────────────────────────

    private static final Pattern ENTRIES_PATTERN =
            Pattern.compile("^\\s*entries\\s*=\\s*\\[", Pattern.MULTILINE);
    private static final Pattern ISOLATED_GUNS_PATTERN =
            Pattern.compile("^\\s*guns\\s*=\\s*\\[", Pattern.MULTILINE);

    private static Map<String, String> readKeyValueSection(Pattern keyPattern) {
        if (!Files.exists(CONFIG_FILE)) return new LinkedHashMap<>();
        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            Matcher m = keyPattern.matcher(content);
            if (!m.find()) return new LinkedHashMap<>();
            int start = m.end() - 1;
            int end   = content.indexOf(']', start);
            if (end < 0) return new LinkedHashMap<>();
            return parseKeyValues(content.substring(start + 1, end));
        } catch (IOException e) {
            TaCZMagazines.LOGGER.warn("[GunOverride] Could not read config file: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static List<String> readStringListSection(Pattern keyPattern) {
        if (!Files.exists(CONFIG_FILE)) return new ArrayList<>();
        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            Matcher m = keyPattern.matcher(content);
            if (!m.find()) return new ArrayList<>();
            int start = m.end() - 1;
            int end   = content.indexOf(']', start);
            if (end < 0) return new ArrayList<>();
            return parseStringList(content.substring(start + 1, end));
        } catch (IOException e) {
            TaCZMagazines.LOGGER.warn("[GunOverride] Could not read config file: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static Map<String, String> parseKeyValues(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : raw.split(",\\s*")) {
            item = stripQuotes(item.strip());
            int eq = item.indexOf('=');
            if (eq < 0) {
                if (!item.isEmpty()) {
                    addError("gun-overrides.toml [gun_overrides]: \"" + item
                           + "\" — missing '='.  Expected format:  modid:gun_id = family_id  "
                           + "or  modid:gun_id = none");
                }
                continue;
            }
            String key = item.substring(0, eq).strip();
            String val = item.substring(eq + 1).strip();
            if (!key.isEmpty() && !val.isEmpty()) result.put(key, val);
        }
        return result;
    }

    private static List<String> parseStringList(String raw) {
        List<String> result = new ArrayList<>();
        for (String item : raw.split(",\\s*")) {
            item = stripQuotes(item.strip());
            if (!item.isEmpty()) result.add(item);
        }
        return result;
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"")) s = s.substring(1);
        if (s.endsWith("\""))   s = s.substring(0, s.length() - 1);
        return s;
    }
}
