package com.raiiiden.taczmagazines.crafting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.raiiiden.taczmagazines.TaCZMagazines;
import com.tacz.guns.crafting.GunSmithTableIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MagazineRecipeOverrides extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final MagazineRecipeOverrides INSTANCE = new MagazineRecipeOverrides();
    private static volatile Map<String, RecipeOverride> overrides = Map.of();

    private MagazineRecipeOverrides() {
        super(GSON, "taczmagazines/magazine_recipes");
    }

    public static RecipeOverride get(String familyId) {
        return overrides.get(familyId);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        Map<String, RecipeOverride> loaded = new LinkedHashMap<>();
        resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), loaded));
        overrides = Map.copyOf(loaded);
        TaCZMagazines.LOGGER.info("[MagazineRecipeOverrides] Loaded {} recipe overrides", loaded.size());
    }

    private static void parse(ResourceLocation source, JsonElement element,
                              Map<String, RecipeOverride> loaded) {
        try {
            JsonObject root = element.getAsJsonObject();
            if (!root.has("family") || !root.get("family").isJsonPrimitive()) {
                throw new JsonParseException("Missing family");
            }

            String familyId = root.get("family").getAsString().trim();
            if (familyId.isEmpty()) {
                throw new JsonParseException("Family cannot be empty");
            }

            boolean enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
            if (!enabled) {
                loaded.put(familyId, new RecipeOverride(false, List.of()));
                return;
            }

            if (!root.has("ingredients") || !root.get("ingredients").isJsonArray()) {
                throw new JsonParseException("Missing ingredients");
            }

            JsonArray ingredientElements = root.getAsJsonArray("ingredients");
            if (ingredientElements.isEmpty()) {
                throw new JsonParseException("Ingredients cannot be empty");
            }

            List<GunSmithTableIngredient> ingredients = new ArrayList<>();
            for (JsonElement ingredientElement : ingredientElements) {
                JsonObject ingredientObject = ingredientElement.getAsJsonObject();
                int count = ingredientObject.has("count") ? ingredientObject.get("count").getAsInt() : 1;
                if (count < 1) {
                    throw new JsonParseException("Ingredient count must be at least 1");
                }

                JsonObject ingredientData = ingredientObject.deepCopy();
                ingredientData.remove("count");
                ingredients.add(new GunSmithTableIngredient(Ingredient.fromJson(ingredientData), count));
            }

            if (loaded.put(familyId, new RecipeOverride(true, List.copyOf(ingredients))) != null) {
                TaCZMagazines.LOGGER.warn("[MagazineRecipeOverrides] {} replaces another override for family {}",
                        source, familyId);
            }
        } catch (RuntimeException exception) {
            TaCZMagazines.LOGGER.error("[MagazineRecipeOverrides] Invalid override {}: {}",
                    source, exception.getMessage());
        }
    }

    public record RecipeOverride(boolean enabled, List<GunSmithTableIngredient> ingredients) {
    }
}
