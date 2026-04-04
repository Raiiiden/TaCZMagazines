package com.raiiiden.taczmagazines.client;

import com.raiiiden.taczmagazines.TaCZMagazines;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Replaces the baked magazine item model with a thin wrapper whose only job is to
 * return isCustomRenderer() = true so Minecraft routes all magazine rendering through
 * MagazineItemRenderer (our BEWLR that draws the gun's magazine bone).
 *
 * The magazine.json model file uses "parent": "builtin/entity" (same as TaCZ guns) so
 * the PoseStack arriving at renderByItem() is in the same coordinate state that TaCZ's
 * own gun BEWLR expects.
 */
@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class MagazineModelProvider {

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        ModelResourceLocation magazineModelLocation = new ModelResourceLocation(
                new ResourceLocation(TaCZMagazines.MODID, "magazine"),
                "inventory"
        );

        BakedModel baseModel = event.getModels().get(magazineModelLocation);
        if (baseModel == null) {
            TaCZMagazines.LOGGER.error(
                    "[MagazineModelProvider] Could not find baked model for taczmagazines:magazine. " +
                    "Make sure assets/taczmagazines/models/item/magazine.json exists.");
            return;
        }

        // Wrap the base model so isCustomRenderer() always returns true.
        // All actual rendering is delegated to MagazineItemRenderer.
        event.getModels().put(magazineModelLocation, new CustomRendererWrapper(baseModel));
        TaCZMagazines.LOGGER.info("[MagazineModelProvider] Magazine model replaced with BEWLR wrapper.");
    }

    // -------------------------------------------------------------------------

    private static final class CustomRendererWrapper implements BakedModel {

        private final BakedModel base;

        CustomRendererWrapper(BakedModel base) {
            this.base = base;
        }

        @Override public boolean isCustomRenderer() { return true; }

        // Delegate everything else to the base (builtin/entity) model
        @Override public List<BakedQuad> getQuads(BlockState state, Direction face, RandomSource rand) { return base.getQuads(state, face, rand); }
        @Override public boolean useAmbientOcclusion() { return base.useAmbientOcclusion(); }
        @Override public boolean isGui3d() { return base.isGui3d(); }
        @Override public boolean usesBlockLight() { return base.usesBlockLight(); }
        @Override public TextureAtlasSprite getParticleIcon() { return base.getParticleIcon(); }
        @Override public ItemOverrides getOverrides() { return base.getOverrides(); }
        @Override public ItemTransforms getTransforms() { return base.getTransforms(); }
    }
}
