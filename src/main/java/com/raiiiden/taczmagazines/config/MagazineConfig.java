package com.raiiiden.taczmagazines.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.HashMap;
import java.util.Map;

public class MagazineConfig {

    public static final ForgeConfigSpec COMMON_SPEC;

    // Capacity range to model mapping
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_2_3;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_4_6;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_7_9;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_10;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_11_14;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_15;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_16_17;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_18_20;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_21_24;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_25;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_26_27;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_28_29;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_30_31;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_32_35;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_36_38;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_39_49;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_49_50;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_51_60;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_61_70;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_71_75;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_76_90;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_100_PLUS;

    private static final Map<CapacityRange, ForgeConfigSpec.ConfigValue<String>> CAPACITY_MODEL_MAP = new HashMap<>();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Magazine Model Configuration")
                .comment("Specify which model/texture to use for each capacity range")
                .comment("Format: 'magazine_model_name' (will use taczmagazines:item/magazine_model_name)")
                .push("models");

        MODEL_2_3 = builder.define("capacity_2_3", "magazine_small");
        MODEL_4_6 = builder.define("capacity_4_6", "magazine_compact");
        MODEL_7_9 = builder.define("capacity_7_9", "magazine_standard");
        MODEL_10 = builder.define("capacity_10", "magazine_10");
        MODEL_11_14 = builder.define("capacity_11_14", "magazine_extended");
        MODEL_15 = builder.define("capacity_15", "magazine_stanag_15");
        MODEL_16_17 = builder.define("capacity_16_17", "magazine_glock_17");
        MODEL_18_20 = builder.define("capacity_18_20", "magazine_20");
        MODEL_21_24 = builder.define("capacity_21_24", "magazine_24");
        MODEL_25 = builder.define("capacity_25", "magazine_stanag_25");
        MODEL_26_27 = builder.define("capacity_26_27", "magazine_27");
        MODEL_28_29 = builder.define("capacity_28_29", "magazine_29");
        MODEL_30_31 = builder.define("capacity_30_31", "magazine_stanag_30");
        MODEL_32_35 = builder.define("capacity_32_35", "magazine_35");
        MODEL_36_38 = builder.define("capacity_36_38", "magazine_38");
        MODEL_39_49 = builder.define("capacity_39_49", "magazine_40");
        MODEL_49_50 = builder.define("capacity_49_50", "magazine_50");
        MODEL_51_60 = builder.define("capacity_51_60", "magazine_60");
        MODEL_61_70 = builder.define("capacity_61_70", "magazine_drum_70");
        MODEL_71_75 = builder.define("capacity_71_75", "magazine_drum_75");
        MODEL_76_90 = builder.define("capacity_76_90", "magazine_drum_90");
        MODEL_100_PLUS = builder.define("capacity_100_plus", "magazine_belt_100");

        builder.pop();

        COMMON_SPEC = builder.build();

        // Build the map
        CAPACITY_MODEL_MAP.put(new CapacityRange(2, 3), MODEL_2_3);
        CAPACITY_MODEL_MAP.put(new CapacityRange(4, 6), MODEL_4_6);
        CAPACITY_MODEL_MAP.put(new CapacityRange(7, 9), MODEL_7_9);
        CAPACITY_MODEL_MAP.put(new CapacityRange(10, 10), MODEL_10);
        CAPACITY_MODEL_MAP.put(new CapacityRange(11, 14), MODEL_11_14);
        CAPACITY_MODEL_MAP.put(new CapacityRange(15, 15), MODEL_15);
        CAPACITY_MODEL_MAP.put(new CapacityRange(16, 17), MODEL_16_17);
        CAPACITY_MODEL_MAP.put(new CapacityRange(18, 20), MODEL_18_20);
        CAPACITY_MODEL_MAP.put(new CapacityRange(21, 24), MODEL_21_24);
        CAPACITY_MODEL_MAP.put(new CapacityRange(25, 25), MODEL_25);
        CAPACITY_MODEL_MAP.put(new CapacityRange(26, 27), MODEL_26_27);
        CAPACITY_MODEL_MAP.put(new CapacityRange(28, 29), MODEL_28_29);
        CAPACITY_MODEL_MAP.put(new CapacityRange(30, 31), MODEL_30_31);
        CAPACITY_MODEL_MAP.put(new CapacityRange(32, 35), MODEL_32_35);
        CAPACITY_MODEL_MAP.put(new CapacityRange(36, 38), MODEL_36_38);
        CAPACITY_MODEL_MAP.put(new CapacityRange(39, 49), MODEL_39_49);
        CAPACITY_MODEL_MAP.put(new CapacityRange(49, 50), MODEL_49_50);
        CAPACITY_MODEL_MAP.put(new CapacityRange(51, 60), MODEL_51_60);
        CAPACITY_MODEL_MAP.put(new CapacityRange(61, 70), MODEL_61_70);
        CAPACITY_MODEL_MAP.put(new CapacityRange(71, 75), MODEL_71_75);
        CAPACITY_MODEL_MAP.put(new CapacityRange(76, 90), MODEL_76_90);
        CAPACITY_MODEL_MAP.put(new CapacityRange(100, Integer.MAX_VALUE), MODEL_100_PLUS);
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    // Gets the model name for a given capacity
    public static String getModelForCapacity(int capacity) {
        for (Map.Entry<CapacityRange, ForgeConfigSpec.ConfigValue<String>> entry : CAPACITY_MODEL_MAP.entrySet()) {
            if (entry.getKey().contains(capacity)) {
                return entry.getValue().get();
            }
        }
        return "magazine_stanag_30"; // Default fallback
    }

    private static class CapacityRange {
        private final int min;
        private final int max;

        public CapacityRange(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public boolean contains(int capacity) {
            return capacity >= min && capacity <= max;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CapacityRange)) return false;
            CapacityRange that = (CapacityRange) o;
            return min == that.min && max == that.max;
        }

        @Override
        public int hashCode() {
            return 31 * min + max;
        }
    }
}