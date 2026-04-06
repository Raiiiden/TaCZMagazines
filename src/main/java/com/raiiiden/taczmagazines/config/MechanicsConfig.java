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

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("loading");

        TICK_BASED = b
                .comment("Set to false to use drop-in loading/unloading.",
                         "  DROP-IN mode (true):",
                         "    Right-click ammo onto a magazine → fill instantly.",
                         "    Right-click an empty cursor onto a magazine → unload all ammo instantly.",
                         "  TICK-BASED mode (true, default):",
                         "    Right-click ammo onto magazine → auto-loads one bullet per interval, EFT-style spinner.",
                         "    Left-click  ammo onto magazine → insert exactly one bullet.",
                         "    Right-click empty cursor onto magazine → auto-ejects one bullet per interval.")
                .define("tick_based", true);

        LOAD_TICKS = b
                .comment("Ticks between each bullet being loaded (tick-based mode).",
                         "20 ticks = 1 second, 10 = 0.5 s, 0 = fastest (1 tick).",
                         "Range: 0 – 60")
                .defineInRange("load_ticks", 10, 0, 60);

        UNLOAD_TICKS = b
                .comment("Ticks between each bullet being unloaded (tick-based mode).",
                         "Same scale as load_ticks.")
                .defineInRange("unload_ticks", 10, 0, 60);

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
