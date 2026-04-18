package com.raiiiden.taczmagazines.item;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.*;

public class MagazineRegistrar {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
            net.minecraftforge.registries.ForgeRegistries.ITEMS,
            TaCZMagazines.MODID
    );

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB,
            TaCZMagazines.MODID
    );

    private static final List<String> ALL_MAGAZINE_FAMILIES = new ArrayList<>();

    public static final RegistryObject<Item> MAGAZINE = ITEMS.register("magazine",
            () -> new MagazineItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> TAB_ICON = ITEMS.register("tab_icon",
            () -> new Item(new Item.Properties())
    );

    public static final RegistryObject<CreativeModeTab> MAGAZINE_TAB = CREATIVE_MODE_TABS.register("magazines",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("TaCZ Magazines"))
                    .icon(() -> new ItemStack(TAB_ICON.get()))
                    .displayItems((params, output) -> {
                        Map<String, Set<Integer>> baseFamiliesByAmmo = MagazineFamilySystem.getAllMagazineFamiliesWithCapacities();

                        List<String> sortedAmmoTypes = new ArrayList<>(baseFamiliesByAmmo.keySet());
                        sortedAmmoTypes.sort(String::compareToIgnoreCase);

                        for (String ammoType : sortedAmmoTypes) {
                            List<Integer> sortedCapacities = new ArrayList<>(baseFamiliesByAmmo.get(ammoType));
                            Collections.sort(sortedCapacities);

                            for (int capacity : sortedCapacities) {
                                String familyId = ammoType + "_" + capacity;
                                ResourceLocation ammoId = MagazineFamilySystem.getAmmoTypeForFamily(familyId);

                                // Full first, then empty
                                if (ammoId != null) {
                                    output.accept(MagazineItem.createMagazineByFamily(MAGAZINE.get(), familyId, capacity, ammoId));
                                }
                                output.accept(MagazineItem.createMagazineByFamily(MAGAZINE.get(), familyId, 0));

                                // Extended mags (empty only) that belong to guns in this base family
                                for (String extFamilyId : MagazineFamilySystem.getExtendedFamiliesForBaseFamily(familyId)) {
                                    output.accept(MagazineItem.createMagazineByFamily(MAGAZINE.get(), extFamilyId, 0));
                                }
                            }
                        }

                        // Isolated (solo) magazine families — each gun listed in [isolated_guns]
                        // gets its own group here, separate from the shared families above.
                        for (String soloFamilyId : MagazineFamilySystem.getIsolatedBaseFamilies()) {
                            ResourceLocation ammoId = MagazineFamilySystem.getAmmoTypeForFamily(soloFamilyId);
                            int capacity = MagazineFamilySystem.getCapacityForFamily(soloFamilyId);
                            if (ammoId != null) {
                                output.accept(MagazineItem.createMagazineByFamily(MAGAZINE.get(), soloFamilyId, capacity, ammoId));
                            }
                            output.accept(MagazineItem.createMagazineByFamily(MAGAZINE.get(), soloFamilyId, 0));
                            for (String extFamilyId : MagazineFamilySystem.getExtendedFamiliesForBaseFamily(soloFamilyId)) {
                                output.accept(MagazineItem.createMagazineByFamily(MAGAZINE.get(), extFamilyId, 0));
                            }
                        }
                    })
                    .build()
    );

    public static void register() {
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        CREATIVE_MODE_TABS.register(FMLJavaModLoadingContext.get().getModEventBus());
        MinecraftForge.EVENT_BUS.register(MagazineRegistrar.class);
        TaCZMagazines.LOGGER.info("[{}] Magazine registry initialized", TaCZMagazines.MODID);
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        ALL_MAGAZINE_FAMILIES.clear();
        ALL_MAGAZINE_FAMILIES.addAll(MagazineFamilySystem.getAllFamilies());

        TaCZMagazines.LOGGER.info("[{}] Registered {} magazine families for creative tab",
                TaCZMagazines.MODID, ALL_MAGAZINE_FAMILIES.size());
    }

    public static List<String> getAllMagazineFamilies() {
        return ALL_MAGAZINE_FAMILIES;
    }
}