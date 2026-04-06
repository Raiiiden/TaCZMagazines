package com.raiiiden.taczmagazines.crafting;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.item.MagazineRegistrar;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.crafting.GunSmithTableIngredient;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.crafting.result.GunSmithTableResult;
import com.tacz.guns.resource.pojo.data.block.BlockData;
import com.tacz.guns.resource.pojo.data.block.TabConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.lang.reflect.Field;
import java.util.*;

// Injects a "Magazines" tab and corresponding recipes into TaCZ's gun_smith_table at runtime, after magazine families are discovered each datapack reload.

public class GunsmithIntegration {

    static final ResourceLocation BLOCK_ID = new ResourceLocation("tacz", "ammo_workbench");
    static final ResourceLocation TAB_ID   = new ResourceLocation("taczmagazines", "magazines");

    // -------------------------------------------------------------------------

    public static void setup(MinecraftServer server) {
        if (server == null) return;
        injectTab();
        injectRecipes(server.getRecipeManager(), buildGunTypeMap(server.getRecipeManager()));
    }

    // -------------------------------------------------------------------------
    // Tab injection
    // -------------------------------------------------------------------------

    // Injects our tab into the gun_smith_table's BlockData.
    private static void injectTab() {
        TimelessAPI.getCommonBlockIndex(BLOCK_ID).ifPresent(blockIndex -> {
            BlockData data = blockIndex.getData();
            if (data == null) {
                TaCZMagazines.LOGGER.warn("[GunsmithIntegration] BlockData for {} is null — skipping tab injection", BLOCK_ID);
                return;
            }

            try {
                Field tabsField = BlockData.class.getDeclaredField("tabs");
                tabsField.setAccessible(true);

                @SuppressWarnings("unchecked")
                List<TabConfig> current = (List<TabConfig>) tabsField.get(data);
                List<TabConfig> mutable = new ArrayList<>(current != null ? current : Collections.emptyList());

                // Remove stale entry from previous reload so we don't accumulate duplicates
                mutable.removeIf(t -> TAB_ID.equals(t.id()));

                ItemStack icon = buildTabIcon();
                mutable.add(new TabConfig(TAB_ID, "taczmagazines.tab.magazines", icon));

                tabsField.set(data, mutable);
                TaCZMagazines.LOGGER.info("[GunsmithIntegration] Injected '{}' tab into {}", TAB_ID, BLOCK_ID);

            } catch (NoSuchFieldException | IllegalAccessException e) {
                TaCZMagazines.LOGGER.error("[GunsmithIntegration] Could not inject tab: {}", e.getMessage());
            }
        });
    }

    // Builds the icon stack for the Magazines tab — an empty magazine of the first available family. */
    private static ItemStack buildTabIcon() {
        for (String fid : MagazineFamilySystem.getAllFamilies()) {
            if (!MagazineFamilySystem.isExtendedFamily(fid)) {
                return MagazineItem.createMagazineByFamily(MagazineRegistrar.MAGAZINE.get(), fid, 0);
            }
        }
        return new ItemStack(MagazineRegistrar.MAGAZINE.get());
    }

    // -------------------------------------------------------------------------
    // Gun-type lookup
    // -----------------------------------------        --------------------------------

    // Scans existing GunSmithTableRecipes in the RecipeManager and maps each gun's ResourceLocation → the tab it belongs to (tacz:pistol, tacz:rifle, etc.).
    private static Map<ResourceLocation, ResourceLocation> buildGunTypeMap(RecipeManager rm) {
        Map<ResourceLocation, ResourceLocation> map = new HashMap<>();
        for (Recipe<?> recipe : rm.getRecipes()) {
            if (!(recipe instanceof GunSmithTableRecipe gsr)) continue;
            ItemStack output = gsr.getOutput();
            if (output != null && !output.isEmpty() && output.getItem() instanceof IGun iGun) {
                ResourceLocation gunId = iGun.getGunId(output);
                if (gunId != null) {
                    map.put(gunId, gsr.getTab());
                }
            }
        }
        TaCZMagazines.LOGGER.debug("[GunsmithIntegration] Built gun-type map with {} entries", map.size());
        return map;
    }

    // Returns the number of iron ingots required based on the gun's tab type
    private static int ingredientCount(ResourceLocation tab) {
        if (tab == null) return 5;
        return switch (tab.getPath()) {
            case "pistol" -> 4;
            case "smg"    -> 5;
            case "rifle"  -> 6;
            case "sniper" -> 7;
            case "mg"     -> 8;
            default       -> 5;
        };
    }

    // -------------------------------------------------------------------------
    // Recipe injection
    // -------------------------------------------------------------------------

    private static void injectRecipes(RecipeManager rm, Map<ResourceLocation, ResourceLocation> gunTypeMap) {
        // Collect all existing recipes, removing any previous injections (reload safety)
        List<Recipe<?>> allRecipes = new ArrayList<>(rm.getRecipes());
        allRecipes.removeIf(r ->
                "taczmagazines".equals(r.getId().getNamespace())
                        && r.getId().getPath().startsWith("magazine/"));

        List<String> orderedFamilies = MagazineFamilySystem.getFamiliesInCreativeTabOrder();
        orderedFamilies.forEach(f -> TaCZMagazines.LOGGER.info("[GunsmithIntegration] Order: {}", f));

        int added = 0;
        for (String familyId : orderedFamilies) {
            boolean isExtended = MagazineFamilySystem.isExtendedFamily(familyId);

            int count;
            if (isExtended) {
                int extLevel = MagazineFamilySystem.getExtLevelForFamily(familyId);
                int baseCost = resolveIngredientCount(familyId, gunTypeMap);
                count = baseCost + extLevel * 2;
            } else {
                count = resolveIngredientCount(familyId, gunTypeMap);
            }

            ItemStack resultStack = MagazineItem.createMagazineByFamily(
                    MagazineRegistrar.MAGAZINE.get(), familyId, 0);

            GunSmithTableResult result = new GunSmithTableResult(resultStack, TAB_ID);
            List<GunSmithTableIngredient> inputs = List.of(
                    new GunSmithTableIngredient(Ingredient.of(Items.IRON_INGOT), count));

            // Zero-padded index prefix ensures lexicographic order == intended display order
            int sortIndex = orderedFamilies.indexOf(familyId);
            ResourceLocation recipeId = new ResourceLocation("taczmagazines",
                    String.format("magazine/%04d_%s", sortIndex, familyId));
            GunSmithTableRecipe recipe = new GunSmithTableRecipe(recipeId, result, inputs);
            recipe.init();

            allRecipes.add(recipe);
            added++;
        }

        rm.replaceRecipes(allRecipes);
        allRecipes.stream()
                .filter(r -> r.getId().getNamespace().equals("taczmagazines"))
                .forEach(r -> TaCZMagazines.LOGGER.info("[GunsmithIntegration] Registered recipe ID: {}", r.getId()));
        TaCZMagazines.LOGGER.info("[GunsmithIntegration] Injected {} magazine recipes into RecipeManager", added);
    }

    // Determines the ingredient count for a family:
    private static int resolveIngredientCount(String familyId,
                                               Map<ResourceLocation, ResourceLocation> gunTypeMap) {
        ResourceLocation repGun = MagazineFamilySystem.getRepresentativeGun(familyId);
        if (repGun != null) {
            ResourceLocation tab = gunTypeMap.get(repGun);
            if (tab != null) return ingredientCount(tab);
        }

        // Fallback: scan all compatible guns and use the first match found
        for (ResourceLocation gunId : MagazineFamilySystem.getCompatibleGuns(familyId)) {
            ResourceLocation tab = gunTypeMap.get(gunId);
            if (tab != null) return ingredientCount(tab);
        }

        return 5; // safe default
    }
}
