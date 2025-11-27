package com.raiiiden.taczmagazines.item;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
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

    private static final List<ResourceLocation> ALL_GUN_IDS = new ArrayList<>();

    public static final RegistryObject<Item> MAGAZINE = ITEMS.register("magazine",
            () -> new MagazineItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<CreativeModeTab> MAGAZINE_TAB = CREATIVE_MODE_TABS.register("magazines",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("TaCZ Magazines"))
                    .icon(() -> {
                        if (!ALL_GUN_IDS.isEmpty()) {
                            return MagazineItem.createMagazine(MAGAZINE.get(), ALL_GUN_IDS.get(0), 0);
                        }
                        return new ItemStack(MAGAZINE.get());
                    })
                    .displayItems((params, output) -> {
                        Map<String, List<ResourceLocation>> gunsByType = new TreeMap<>();
                        for (ResourceLocation gunId : ALL_GUN_IDS) {
                            CommonGunIndex index = CommonAssetsManager.getInstance().getGunIndex(gunId);
                            String type = "Gun";
                            if (index != null && index.getType() != null) {
                                type = capitalize(index.getType());
                            } else {
                                TaCZMagazines.LOGGER.warn("Missing gun index or type for: {}", gunId);
                            }
                            gunsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(gunId);
                        }

                        // Sort and add to creative tab
                        gunsByType.forEach((type, ids) -> {
                            ids.sort(Comparator.comparing(ResourceLocation::getPath));
                            for (ResourceLocation gunId : ids) {
                                CommonGunIndex index = CommonAssetsManager.getInstance().getGunIndex(gunId);
                                if (index != null && index.getGunData() != null) {
                                    int capacity = index.getGunData().getAmmoAmount();
                                    ResourceLocation ammoId = index.getGunData().getAmmoId();

                                    // Add empty magazine
                                    output.accept(MagazineItem.createMagazine(MAGAZINE.get(), gunId, 0));

                                    // Add full magazine
                                    if (ammoId != null && capacity > 0) {
                                        output.accept(MagazineItem.createMagazine(MAGAZINE.get(), gunId, capacity, ammoId));
                                    }
                                }
                            }
                        });
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
        CommonAssetsManager assetsManager = CommonAssetsManager.getInstance();
        if (assetsManager == null) {
            TaCZMagazines.LOGGER.warn("[{}] CommonAssetsManager instance is null!", TaCZMagazines.MODID);
            return;
        }

        ALL_GUN_IDS.clear();
        for (Map.Entry<ResourceLocation, CommonGunIndex> entry : assetsManager.getAllGuns()) {
            ResourceLocation id = entry.getKey();
            CommonGunIndex index = entry.getValue();

            // **FIX: Filter out guns that don't use detachable magazines**
            if (index != null && index.getGunData() != null) {
                FeedType feedType = index.getGunData().getReloadData().getType();
                boolean isInfinite = index.getGunData().getReloadData().isInfinite();
                int ammoAmount = index.getGunData().getAmmoAmount();

                // Check reload times - tube-fed guns have very short reload times per shell
                float emptyReloadTime = index.getGunData().getReloadData().getFeed().getEmptyTime();
                float tacticalReloadTime = index.getGunData().getReloadData().getFeed().getTacticalTime();

                // Tube-fed shotguns typically have reload times under 1 second per shell
                // Magazine-fed guns have reload times of 2-3+ seconds for the entire mag
                boolean hasTickBasedReload = emptyReloadTime < 1.0f || tacticalReloadTime < 1.0f;

                // Only add guns that:
                // 1. Use MAGAZINE feed type
                // 2. Are not infinite ammo
                // 3. Have more than 2 ammo capacity (filters out double barrels)
                // 4. Don't have tick-based reloads (filters out M870 and similar)
                if (feedType.equals(FeedType.MAGAZINE) && !isInfinite && ammoAmount > 2 && !hasTickBasedReload) {
                    ALL_GUN_IDS.add(id);
                    TaCZMagazines.LOGGER.debug("Added magazine for: {} (emptyTime: {}s, tacticalTime: {}s, ammo: {})",
                            id, emptyReloadTime, tacticalReloadTime, ammoAmount);
                } else if (feedType.equals(FeedType.MAGAZINE) && hasTickBasedReload) {
                    TaCZMagazines.LOGGER.debug("Excluded tick-based reload gun: {} (emptyTime: {}s, tacticalTime: {}s)",
                            id, emptyReloadTime, tacticalReloadTime);
                }
            }
        }

        TaCZMagazines.LOGGER.info("[{}] Total magazine-compatible guns found: {}", TaCZMagazines.MODID, ALL_GUN_IDS.size());
    }

    public static List<ResourceLocation> getAllGunIds() {
        return ALL_GUN_IDS;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return "Gun";
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}