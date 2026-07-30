package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.item.MagazineItem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gui.GunSmithTableScreen;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.resource.pojo.data.block.TabConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mixin(value = GunSmithTableScreen.class, remap = false)
public abstract class MixinGunSmithTableScreen {
    private static final ResourceLocation AMMO_WORKBENCH =
            new ResourceLocation("tacz", "ammo_workbench");
    private static final ResourceLocation MAGAZINE_TAB =
            new ResourceLocation("taczmagazines", "magazines");

    @Shadow @Final
    private LinkedHashMap<ResourceLocation, TabConfig> recipeKeys;

    @Shadow @Final
    private Map<ResourceLocation, List<ResourceLocation>> recipes;

    @Shadow
    private ResourceLocation selectedType;

    @Shadow
    private List<ResourceLocation> selectedRecipeList;

    // Extends TaCZ's held-item filter to recognize compatible magazine recipes.
    @Inject(method = "isSuitableForMainHand", at = @At("HEAD"), cancellable = true)
    private void filterMagazineForHeldGun(GunSmithTableRecipe recipe,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!isHeldGunAmmoWorkbench()) return;

        ItemStack output = recipe.getOutput();
        if (!(output.getItem() instanceof MagazineItem magazine)) return;

        ItemStack heldGun = Minecraft.getInstance().player.getMainHandItem();
        cir.setReturnValue(magazine.isAmmoBoxOfGun(heldGun, output));
    }

    // While holding a gun, shows only its compatible magazines and their tab.
    @Inject(method = "classifyRecipes", at = @At("TAIL"))
    private void showOnlyCompatibleMagazines(CallbackInfo ci) {
        if (!isHeldGunAmmoWorkbench()) return;

        List<ResourceLocation> compatible =
                new ArrayList<>(recipes.getOrDefault(MAGAZINE_TAB, List.of()));
        TabConfig magazineTab = recipeKeys.get(MAGAZINE_TAB);
        if (magazineTab == null) {
            magazineTab = findMagazineTab();
        }

        recipes.clear();
        recipeKeys.clear();
        recipes.put(MAGAZINE_TAB, compatible);
        if (magazineTab != null) {
            recipeKeys.put(MAGAZINE_TAB, magazineTab);
        }

        selectedType = MAGAZINE_TAB;
        selectedRecipeList = compatible;
    }

    private boolean isHeldGunAmmoWorkbench() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || !(minecraft.player.getMainHandItem().getItem() instanceof IGun)) return false;

        GunSmithTableScreen screen = (GunSmithTableScreen) (Object) this;
        return AMMO_WORKBENCH.equals(screen.getMenu().getBlockId());
    }

    private static TabConfig findMagazineTab() {
        return TimelessAPI.getCommonBlockIndex(AMMO_WORKBENCH)
                .map(index -> index.getData())
                .filter(data -> data != null && data.getTabs() != null)
                .flatMap(data -> data.getTabs().stream()
                        .filter(tab -> MAGAZINE_TAB.equals(tab.id()))
                        .findFirst())
                .orElse(null);
    }
}
