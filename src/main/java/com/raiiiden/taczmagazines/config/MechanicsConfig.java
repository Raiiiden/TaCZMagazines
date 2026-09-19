package com.raiiiden.taczmagazines.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MechanicsConfig {

    public static final ForgeConfigSpec SPEC;

    // Whether tick-based loading/unloading is active.
    public static final ForgeConfigSpec.BooleanValue TICK_BASED;
    //Ticks between each bullet being inserted
    public static final ForgeConfigSpec.IntValue LOAD_TICKS;
    // Ticks between each bullet being ejected. Same scale as LOAD_TICKS.
    public static final ForgeConfigSpec.IntValue UNLOAD_TICKS;
    // Whether extended magazines can be used without the extended-mag attachment installed.
    public static final ForgeConfigSpec.BooleanValue ALLOW_EXTENDED_WITHOUT_ATTACHMENT;
    // Whether split-reload guns (clips, speed loaders) take part in the magazine system.
    public static final ForgeConfigSpec.BooleanValue BULK_RELOAD_REQUIRES_MAGAZINE;
    // Manual classification for guns whose reload split the detector reads wrongly.
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPLIT_RELOAD_OVERRIDES;
    public static final ForgeConfigSpec.BooleanValue ALLOW_BULK_RELOAD_WHEN_LOADED;
    public static final ForgeConfigSpec.BooleanValue PARTIAL_BULK_RELOAD_REQUIRES_SELECTOR;
    // Whether seating a speed loader first empties the cylinder, as a real one has to.
    public static final ForgeConfigSpec.BooleanValue SPEED_LOADER_DUMPS_ROUNDS;
    // Whether those dumped rounds land on the ground rather than back in the inventory.
    public static final ForgeConfigSpec.BooleanValue DUMPED_ROUNDS_ON_GROUND;
    // Whether revolvers that always reload with a speed loader need one (see SpeedLoaderRevolvers).
    public static final ForgeConfigSpec.BooleanValue SPEED_LOADER_REVOLVERS_USE_MAGAZINE;
    // Manual classification for revolvers the speed-loader detector reads wrongly.
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPEED_LOADER_REVOLVER_OVERRIDES;

    // Whether to replace TaCZ's reserve-ammo number with a RoN-style magazine silhouette row.
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_AMMO_HUD;
    // Whether that row also replaces the HUD on split-reload guns (clips, speed loaders).
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_AMMO_HUD_FOR_SPLIT_RELOAD;
    // Seconds each half of the gun tooltip's ammo row is shown before swapping. 0 = never swap.
    public static final ForgeConfigSpec.IntValue TOOLTIP_CYCLE_SECONDS;

    // Whether holding a magazine in-hand enables tick-based load/unload sessions.
    public static final ForgeConfigSpec.BooleanValue IN_HAND_TICK_BASED;

    // Whether TaCZ ammo boxes may store magazine item stacks.
    public static final ForgeConfigSpec.BooleanValue MAGAZINES_IN_AMMO_BOXES;
    // Whether magazine loading may consume loose rounds from TaCZ ammo boxes.
    public static final ForgeConfigSpec.BooleanValue LOAD_MAGAZINES_FROM_AMMO_BOXES;
    // Whether bullets and magazines must use separate ammo boxes.
    public static final ForgeConfigSpec.BooleanValue SEPARATE_AMMO_BOX_CONTENTS;
    // Source ordering for both gun reloads and loose-round loading.
    public static final ForgeConfigSpec.BooleanValue PREFER_PLAYER_INVENTORY;
    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("loading");

        TICK_BASED = b
                .comment("Set to false to use drop-in loading/unloading.",
                         "  DROP-IN mode (false):",
                         "    Right-click ammo onto a magazine → fill instantly.",
                         "    Right-click an empty cursor onto a magazine → unload all ammo instantly.",
                         "  TICK-BASED mode (true, default):",
                         "    Left-click ammo onto magazine → auto-loads one bullet per interval, EFT-style spinner.",
                         "    Right-click empty cursor onto magazine → auto-ejects one bullet per interval.")
                .define("tick_based", true);

        LOAD_TICKS = b
                .comment("Ticks between each bullet being loaded (tick-based mode).",
                         "20 ticks = 1 second, 10 = 0.5 s, 0 = fastest (1 tick).",
                         "Range: 0 – 60")
                .defineInRange("load_ticks", 5, 0, 60);

        UNLOAD_TICKS = b
                .comment("Ticks between each bullet being unloaded (tick-based mode).",
                         "Same scale as load_ticks.")
                .defineInRange("unload_ticks", 5, 0, 60);

        b.pop();
        b.push("extended_magazines");

        ALLOW_EXTENDED_WITHOUT_ATTACHMENT = b
                .comment("When true, extended magazines can be loaded into a gun even if the",
                         "extended-mag attachment is not installed on that gun.",
                         "The gun will also visually display the extended mag bone and use the",
                         "correct extended capacity as if the attachment were present.",
                         "Only applies to guns that use the magazine system.")
                .define("allow_extended_without_attachment", true);

        b.pop();
        b.push("split_reload");

        BULK_RELOAD_REQUIRES_MAGAZINE = b
                .comment("Some guns do not use the same mechanism for both of their reloads. TaCZ calls the",
                         "two halves the EMPTY reload and the TACTICAL reload; for almost every gun both are",
                         "the same magazine change and only the timing differs. The exceptions load a whole",
                         "batch at once when empty -- a stripper clip, an en-bloc clip, a revolver speed",
                         "loader -- and push in single rounds the rest of the time. TaCZ's kar98 is the",
                         "shipped example: clip when the rifle is dry and no scope is fitted, one round at a",
                         "time otherwise.",
                         "When true, guns like that join the magazine system and their two reloads split:",
                         "  BULK reload    -> needs an actual magazine item, and swaps it in as one unit.",
                         "  ROUND-BY-ROUND -> feeds from loose ammo (and ammo boxes) exactly as TaCZ does.",
                         "With no magazine item to hand, an empty gun plays the round-by-round reload",
                         "instead of the bulk one, so the gun is never left unloadable.",
                         "When false these guns are left entirely to TaCZ and get no magazines at all.",
                         "Changing this needs a datapack reload (F3+T or /reload) to take effect.")
                .define("bulk_reload_requires_magazine", true);

        SPLIT_RELOAD_OVERRIDES = b
                .comment("Which reload a gun uses is decided by its pack's Lua, and no TaCZ API reports it.",
                         "It is worked out from the gun's script_param timings, which name a bulk insert as a",
                         "\"<name>_feed\" plus \"<name>\"/\"<name>_cooldown\" pair and round-by-round loading as",
                         "\"loop_feed\" or \"round1_feed\". A gun whose empty reload has a loop of its own",
                         "(\"loop_empty_feed\") loads round by round when empty as well, so it is not counted.",
                         "A pack that names its timings differently can be corrected here.",
                         "Format:",
                         "  \"modid:gun = bulk\"   -- treat its empty reload as a batch insert (clip/speed loader)",
                         "  \"modid:gun = rounds\" -- treat it as loading one round at a time, and leave it alone",
                         "Guns the detector found are listed in the log on reload (search '[split-reload]').",
                         "Changes take effect after F3+T.")
                .defineListAllowEmpty("gun_overrides", Collections.emptyList(), e -> e instanceof String);

        ALLOW_BULK_RELOAD_WHEN_LOADED = b
                .comment("TaCZ only ever runs these guns' bulk insert on a completely empty gun, because that",
                         "is the one case the pack's script checks for. When true, a stripper clip or speed",
                         "loader can also go into a gun that still has rounds in it, loading it in one",
                         "motion instead of one round at a time. A clip tops the gun up; a speed loader",
                         "empties the cylinder first (see speed_loader_dumps_rounds).",
                         "Anything the gun has no room for is handed back as loose rounds, so a part-used clip",
                         "never disappears into a nearly full gun.",
                         "Default true. When false these guns behave as TaCZ wrote them and only reach for a",
                         "clip when dry.")
                .define("allow_bulk_reload_when_loaded", true);

        PARTIAL_BULK_RELOAD_REQUIRES_SELECTOR = b
                .comment("Applies only to a gun that still has rounds in it -- an empty one always takes the",
                         "clip when there is one. When true, topping a gun up from a clip happens only if the",
                         "player picked that magazine from the selector (hold the reload key); a plain tap",
                         "reload still feeds single rounds.",
                         "Default true, which keeps the tap reload meaning what it has always meant and makes",
                         "spending a whole clip on a half-full gun a deliberate act.",
                         "With no loose rounds left to feed, a tap reload uses the clip anyway rather than",
                         "playing a reload that loads nothing.",
                         "Has no effect when allow_bulk_reload_when_loaded is false.")
                .define("partial_bulk_reload_requires_selector", true);

        SPEED_LOADER_DUMPS_ROUNDS = b
                .comment("A speed loader only goes into an empty cylinder: the revolver is opened, everything",
                         "in it is dumped out, and then the loader's rounds go in. When true, a speed-loader",
                         "reload does exactly that to a revolver that still has rounds in it, so afterwards",
                         "the gun holds what the speed loader held -- even if that is fewer than before.",
                         "Stripper clips are not affected: a clip always presses its rounds in on top of",
                         "whatever the gun already holds.",
                         "The dumped rounds are live ammo and are never lost (see dumped_rounds_on_ground).",
                         "A reload cut short before the loader goes in leaves the gun as it was.",
                         "When false, a speed loader tops the cylinder up the way a clip does.")
                .define("speed_loader_dumps_rounds", true);

        DUMPED_ROUNDS_ON_GROUND = b
                .comment("Where the rounds a speed-loader reload dumps out of the cylinder go.",
                         "  false (default) -> back into the inventory; dropped only if it is full.",
                         "  true            -> on the ground at the shooter's feet, to be picked back up.",
                         "Has no effect when speed_loader_dumps_rounds is false.")
                .define("dumped_rounds_on_ground", false);

        b.pop();
        b.push("speed_loader_revolvers");

        SPEED_LOADER_REVOLVERS_USE_MAGAZINE = b
                .comment("Revolvers are normally left out of the magazine system, since most are loaded one",
                         "round at a time. Some always reload with a speed loader instead (TaCZ's taurus500",
                         "and rhino357, for example); they are recognised by a speed-loader bone moving in",
                         "their reload animation.",
                         "When true, those revolvers join the magazine system: every reload, empty or not,",
                         "needs a speed loader, and the one coming out goes back to the inventory with",
                         "whatever rounds were left in the cylinder.",
                         "Revolvers loaded by hand, and clip/speed-loader guns covered by [split_reload],",
                         "are not affected.",
                         "When false every revolver is left to TaCZ, as before.",
                         "Changing this needs a datapack reload (F3+T or /reload) to take effect.")
                .define("require_speed_loader", true);

        SPEED_LOADER_REVOLVER_OVERRIDES = b
                .comment("Corrects the speed-loader detector for a single gun.",
                         "Format:",
                         "  \"modid:gun = speedloader\" -- always reloads with a speed loader",
                         "  \"modid:gun = none\"        -- loaded by hand; leave it to TaCZ",
                         "Detected revolvers are listed in the log on reload (search '[speed-loader]').",
                         "Changes take effect after F3+T.")
                .defineListAllowEmpty("gun_overrides", Collections.emptyList(), e -> e instanceof String);

        b.pop();
        b.push("hud");

        OVERRIDE_AMMO_HUD = b
                .comment("When true, replaces TaCZ's reserve-ammo counter with a row of magazine silhouettes",
                         "that each show how full they are (Ready Or Not-style magazine check UI).",
                         "Only applies to guns that use the magazine system.")
                .define("override_ammo_hud", true);

        OVERRIDE_AMMO_HUD_FOR_SPLIT_RELOAD = b
                .comment("Whether the silhouette row also takes over the HUD on split-reload guns --",
                         "the ones fed by a stripper clip, en-bloc clip or revolver speed loader (see",
                         "[split_reload]). Those guns are loaded from loose rounds as often as from a",
                         "magazine, so a row of magazines describes the reserve poorly and TaCZ's plain",
                         "number says more. Default false: they keep TaCZ's counter.",
                         "The counter itself still counts magazines correctly either way, including",
                         "stacked ones, and on these guns it counts loose rounds too.")
                .define("override_ammo_hud_for_split_reload", false);

        b.pop();
        b.push("tooltip");

        TOOLTIP_CYCLE_SECONDS = b
                .comment("A gun's tooltip normally shows the loaded magazine on its ammo row. Set this to",
                         "alternate that row between the magazine and TaCZ's own loose-ammo entry, so both",
                         "the magazine and the round it holds stay visible.",
                         "Value is how many seconds each side is shown. 0 keeps the magazine up permanently.",
                         "Range: 0 - 30")
                .defineInRange("ammo_cycle_seconds", 2, 0, 30);

        b.pop();
        b.push("in_hand_loading");

        IN_HAND_TICK_BASED = b
                .comment("When true, holding a magazine in your main hand enables tick-based loading/unloading.",
                         "  Left-click  → start/stop adding bullets one per load_ticks interval.",
                         "  Right-click → start/stop removing bullets one per unload_ticks interval.",
                         "  Scrolling to another slot or dropping the magazine cancels the session.",
                         "  Progress appears as a ring on the hotbar slot icon, not the cursor.",
                         "  Uses the same load_ticks / unload_ticks intervals as inventory tick-based mode.",
                         "  Block breaking and arm-swing are suppressed whenever a magazine is held.")
                .define("in_hand_tick_based", true);

        b.pop();
        b.push("ammo_boxes");

        MAGAZINES_IN_AMMO_BOXES = b
                .comment("Allow magazine items to be placed inside TaCZ ammo boxes.",
                         "Right-click a magazine onto a box to store it; right-click the box",
                         "onto an empty inventory slot to take a stored magazine back out.")
                .define("store_magazines", true);

        LOAD_MAGAZINES_FROM_AMMO_BOXES = b
                .comment("Allow magazines to take compatible loose rounds from TaCZ ammo boxes.",
                         "Creative ammo boxes are treated as infinite sources.")
                .define("supply_bullets_to_magazines", true);

        SEPARATE_AMMO_BOX_CONTENTS = b
                .comment("When true, bullets and magazines cannot share the same ammo box.",
                         "A box containing bullets rejects magazines, and a box containing",
                         "magazines rejects bullets. Set false to share the box's stack slots.")
                .define("separate_magazines_and_ammo", true);

        PREFER_PLAYER_INVENTORY = b
                .comment("When true, loose ammo and loose magazines in the player inventory",
                         "are used before ammo-box contents. Set false to drain boxes first.")
                .define("prefer_player_inventory", true);

        b.pop();
        SPEC = b.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "taczmagazines-mechanics.toml");
    }

    // Parses [split_reload] gun_overrides into gunId -> isSplitReload; bad lines are logged and skipped.
    public static Map<ResourceLocation, Boolean> readSplitReloadOverrides() {
        Map<ResourceLocation, Boolean> result = new LinkedHashMap<>();
        for (String raw : SPLIT_RELOAD_OVERRIDES.get()) {
            String entry = raw == null ? "" : raw.strip();
            if (entry.isEmpty()) continue;

            int eq = entry.indexOf('=');
            if (eq < 0) {
                warnOverride(entry, "missing '=' — expected  modid:gun = bulk  or  modid:gun = rounds");
                continue;
            }
            String key = entry.substring(0, eq).strip();
            String value = entry.substring(eq + 1).strip().toLowerCase(java.util.Locale.ROOT);

            Boolean split;
            if (value.equals("bulk") || value.equals("clip") || value.equals("true")) {
                split = Boolean.TRUE;
            } else if (value.equals("rounds") || value.equals("round") || value.equals("false")) {
                split = Boolean.FALSE;
            } else {
                warnOverride(entry, "unknown value \"" + value + "\" — expected 'bulk' or 'rounds'");
                continue;
            }

            try {
                result.put(new ResourceLocation(key), split);
            } catch (Exception ex) {
                warnOverride(entry, "\"" + key + "\" is not a valid gun ID (expected modid:gun_name)");
            }
        }
        return result;
    }

    // Parses [speed_loader_revolvers] gun_overrides into gunId -> usesSpeedLoader; bad lines are logged and skipped.
    public static Map<ResourceLocation, Boolean> readSpeedLoaderRevolverOverrides() {
        Map<ResourceLocation, Boolean> result = new LinkedHashMap<>();
        for (String raw : SPEED_LOADER_REVOLVER_OVERRIDES.get()) {
            String entry = raw == null ? "" : raw.strip();
            if (entry.isEmpty()) continue;

            int eq = entry.indexOf('=');
            if (eq < 0) {
                warnSpeedLoaderOverride(entry, "missing '=' — expected  modid:gun = speedloader  or  modid:gun = none");
                continue;
            }
            String key = entry.substring(0, eq).strip();
            String value = entry.substring(eq + 1).strip().toLowerCase(java.util.Locale.ROOT);

            Boolean speedLoader;
            if (value.equals("speedloader") || value.equals("speed_loader") || value.equals("true")) {
                speedLoader = Boolean.TRUE;
            } else if (value.equals("none") || value.equals("rounds") || value.equals("false")) {
                speedLoader = Boolean.FALSE;
            } else {
                warnSpeedLoaderOverride(entry, "unknown value \"" + value + "\" — expected 'speedloader' or 'none'");
                continue;
            }

            try {
                result.put(new ResourceLocation(key), speedLoader);
            } catch (Exception ex) {
                warnSpeedLoaderOverride(entry, "\"" + key + "\" is not a valid gun ID (expected modid:gun_name)");
            }
        }
        return result;
    }

    private static void warnSpeedLoaderOverride(String entry, String reason) {
        com.raiiiden.taczmagazines.TaCZMagazines.LOGGER.warn(
                "[speed-loader] mechanics.toml [speed_loader_revolvers] gun_overrides: \"{}\" — {}", entry, reason);
    }

    private static void warnOverride(String entry, String reason) {
        com.raiiiden.taczmagazines.TaCZMagazines.LOGGER.warn(
                "[split-reload] mechanics.toml [split_reload] gun_overrides: \"{}\" — {}", entry, reason);
    }

    // Effective load interval — always at least 1 tick so we don't spam packets.
    public static int effectiveLoadTicks() {
        return Math.max(1, LOAD_TICKS.get());
    }

    public static int effectiveUnloadTicks() {
        return Math.max(1, UNLOAD_TICKS.get());
    }
}
