package com.raiiiden.taczmagazines.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

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

    // Whether to replace TaCZ's reserve-ammo number with a RoN-style magazine silhouette row.
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_AMMO_HUD;

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
                .define("allow_extended_without_attachment", false);

        b.pop();
        b.push("hud");

        OVERRIDE_AMMO_HUD = b
                .comment("When true, replaces TaCZ's reserve-ammo counter with a row of magazine silhouettes",
                         "that each show how full they are (Ready Or Not-style magazine check UI).",
                         "Only applies to guns that use the magazine system.")
                .define("override_ammo_hud", true);

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

    // Effective load interval — always at least 1 tick so we don't spam packets.
    public static int effectiveLoadTicks() {
        return Math.max(1, LOAD_TICKS.get());
    }

    public static int effectiveUnloadTicks() {
        return Math.max(1, UNLOAD_TICKS.get());
    }
}
