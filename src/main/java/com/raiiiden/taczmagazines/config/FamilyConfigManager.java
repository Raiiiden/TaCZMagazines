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

// Controls which gun model is used to represent each magazine family.
// Read-only: never writes to the config file.  Users add entries manually;
// family IDs are printed to the log on startup for reference.
// Changes take effect after F3+T.
public class FamilyConfigManager {

    private static final Path CONFIG_FILE =
            FMLPaths.CONFIGDIR.get().resolve("taczmagazines-families.toml");

    private static final ForgeConfigSpec SPEC;
    @SuppressWarnings("unused") // kept so ForgeConfigSpec / Configured can see the key
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> OVERRIDES_VALUE;

    private static final Map<String, ResourceLocation> RESOLVED = new LinkedHashMap<>();
    private static final List<String> CONFIG_ERRORS = new ArrayList<>();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("family_models")
               .comment("Controls which gun's magazine model is used to represent each magazine family.")
               .comment("")
               .comment("Format:  \"family_id = modid:gun_id\"")
               .comment("Example: \"9x19mm_17 = tacz:m9\"")
               .comment("")
               .comment("The gun must be compatible with that family.")
               .comment("If no override is set for a family, the first alphabetically-sorted gun is used.")
               .comment("Family IDs are printed to the log on startup (search 'Discovered magazine family').")
               .comment("Changes take effect after F3+T.");
        // Permissive validator — we validate in load() and report errors as red chat messages
        // instead of letting Forge crash on a malformed config file.
        OVERRIDES_VALUE = builder.defineListAllowEmpty("overrides", Collections.emptyList(), e -> e instanceof String);
        builder.pop();
        SPEC = builder.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "taczmagazines-families.toml");
    }

    // Reads user overrides from disk and resolves the representative gun for each family.
    // Must be called after MagazineFamilySystem.discoverMagazineFamilies() and
    // GunOverrideConfig.apply() (so isolated families are already in the family maps).
    public static void load() {
        CONFIG_ERRORS.clear();
        Map<String, String> stored = readFromDisk();
        buildResolved(stored);
    }

    public static ResourceLocation getOverride(String familyId) {
        return RESOLVED.get(familyId);
    }

    // Returns errors found during the last load() — not cleared until next load().
    public static List<String> getErrors() {
        return Collections.unmodifiableList(CONFIG_ERRORS);
    }

    // ── Disk reading ──────────────────────────────────────────────────────────

    private static final Pattern OVERRIDES_KEY =
            Pattern.compile("^\\s*overrides\\s*=\\s*\\[", Pattern.MULTILINE);

    private static Map<String, String> readFromDisk() {
        if (!Files.exists(CONFIG_FILE)) return new LinkedHashMap<>();
        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            Matcher m = OVERRIDES_KEY.matcher(content);
            if (!m.find()) return new LinkedHashMap<>();
            int arrayStart = m.end() - 1;
            int arrayEnd   = content.indexOf(']', arrayStart);
            if (arrayEnd < 0) return new LinkedHashMap<>();
            return parseArrayContent(content.substring(arrayStart + 1, arrayEnd));
        } catch (IOException e) {
            TaCZMagazines.LOGGER.warn("[FamilyConfig] Could not read config file: {}", e.getMessage());
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
            if (eq < 0) {
                if (!item.isEmpty()) {
                    addError("families.toml [family_models]: \"" + item
                           + "\" — missing '='.  Expected format:  family_id = modid:gun_id  "
                           + "e.g.  9x19mm_17 = tacz:m9");
                }
                continue;
            }
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

            if (!MagazineFamilySystem.getAllFamilies().contains(familyId)) {
                addError("families.toml [family_models]: family \"" + familyId
                       + "\" does not exist — check the log for valid family IDs "
                       + "(search 'Discovered magazine family')");
                continue;
            }

            ResourceLocation gunId;
            try { gunId = new ResourceLocation(rawGun); }
            catch (Exception e) {
                addError("families.toml [family_models]: \"" + rawGun
                       + "\" is not a valid gun ID for family \"" + familyId
                       + "\".  Expected format:  modid:gun_name  e.g.  tacz:m9");
                continue;
            }

            if (!MagazineFamilySystem.getCompatibleGuns(familyId).contains(gunId)) {
                addError("families.toml [family_models]: gun \"" + gunId
                       + "\" is not compatible with family \"" + familyId
                       + "\" — it must be in the family's gun list (check the log)");
                continue;
            }

            ResourceLocation def = MagazineFamilySystem.getDefaultRepresentativeGun(familyId);
            if (!gunId.equals(def)) {
                RESOLVED.put(familyId, gunId);
                TaCZMagazines.LOGGER.info("[FamilyConfig] '{}' → '{}'", familyId, gunId);
            }
        }
    }

    private static void addError(String msg) {
        CONFIG_ERRORS.add(msg);
        TaCZMagazines.LOGGER.warn("[FamilyConfig] Config error: {}", msg);
    }
}
