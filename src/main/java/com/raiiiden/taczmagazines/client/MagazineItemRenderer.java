package com.raiiiden.taczmagazines.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class MagazineItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static MagazineItemRenderer INSTANCE;

    private record MagazineRenderData(
            BedrockGunModel model,
            BedrockPart magazineNode,
            ResourceLocation texture
    ) {}

    private final Map<ResourceLocation, MagazineRenderData> renderCache = new HashMap<>();
    private final Set<ResourceLocation> permanentFailures = new HashSet<>();

    private static Field magazineNodeField;
    private static boolean reflectionInitialized = false;

    // Target visual sizes (block units) — all magazines are normalized to their
    // largest dimension equaling this value so every mag appears the same size.
    private static final float TARGET_GUI    = 0.8f;
    private static final float TARGET_HAND = 0.55f;
    private static final float TARGET_FIRST_PERSON = 0.55f;
    private static final float TARGET_THIRD_PERSON = 0.35f;
    private static final float TARGET_GROUND = 0.35f;
    private static final float TARGET_FIXED  = 0.10f;

    public MagazineItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    public static MagazineItemRenderer getInstance() {
        if (INSTANCE == null) INSTANCE = new MagazineItemRenderer();
        return INSTANCE;
    }

    public static void invalidateCache() {
        if (INSTANCE != null) {
            INSTANCE.renderCache.clear();
            INSTANCE.permanentFailures.clear();
        }
    }

    // =========================================================================
    // BEWLR entry point
    // =========================================================================

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext,
                             PoseStack poseStack, MultiBufferSource bufferSource,
                             int packedLight, int packedOverlay) {
        if (!(stack.getItem() instanceof MagazineItem)) return;

        String familyId = MagazineItem.getMagazineFamilyId(stack);
        if (familyId == null) return;

        ResourceLocation repGunId = MagazineFamilySystem.getRepresentativeGun(familyId);
        if (repGunId == null) return;

        MagazineRenderData data = getRenderData(repGunId);
        if (data == null) return;

        BedrockPart mag = data.magazineNode();

        // 1. SAVE & RESET STATE
        float sX = mag.x, sY = mag.y, sZ = mag.z;
        float rX = mag.xRot, rY = mag.yRot, rZ = mag.zRot;
        mag.x = 0; mag.y = 0; mag.z = 0;
        mag.xRot = 0; mag.yRot = 0; mag.zRot = 0;

        // 2. FILTER VISIBILITY
        MagazineItem magItem = (MagazineItem) stack.getItem();
        boolean hasBullets = magItem.getAmmoCount(stack) > 0;

        List<BedrockPart> subtree = new ArrayList<>();
        collectSubtree(mag, subtree);
        boolean[] savedVisible = new boolean[subtree.size()];
        for (int i = 0; i < subtree.size(); i++) {
            BedrockPart part = subtree.get(i);
            savedVisible[i] = part.visible;
            String name = part.name != null ? part.name.toLowerCase(Locale.ROOT) : "";
            if (name.contains("extend") || name.contains("drum") || name.contains("max")) {
                part.visible = false;
            } else if (isBulletBone(name)) {
                // Override TaCZ animation state — use the magazine's own ammo count,
                // not whatever the gun animation left the bone at (0 during reload).
                part.visible = hasBullets;
            }
        }

        // 3. CALC BOUNDS
        float[] bounds = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        accumulateBounds(mag, new PoseStack(), bounds);

        if (bounds[0] != Float.MAX_VALUE) {
            float centerX = (bounds[0] + bounds[3]) / 2f;
            float centerY = (bounds[1] + bounds[4]) / 2f;
            float centerZ = (bounds[2] + bounds[5]) / 2f;
            float maxDim = Math.max(bounds[3]-bounds[0], Math.max(bounds[4]-bounds[1], bounds[5]-bounds[2]));

            poseStack.pushPose();
            applyDisplayTransform(displayContext, new float[]{centerX, centerY, centerZ}, maxDim, poseStack);
            mag.render(poseStack, displayContext, bufferSource.getBuffer(RenderType.entityCutoutNoCull(data.texture())), packedLight, packedOverlay);
            poseStack.popPose();
        }

        // 4. RESTORE
        mag.x = sX; mag.y = sY; mag.z = sZ;
        mag.xRot = rX; mag.yRot = rY; mag.zRot = rZ;
        for (int i = 0; i < subtree.size(); i++) {
            subtree.get(i).visible = savedVisible[i];
        }
    }

    // =========================================================================
    // Display transforms
    // =========================================================================

    private static void applyDisplayTransform(ItemDisplayContext ctx, float[] center, float maxDim, PoseStack ps) {
        float md = (maxDim > 0.001f) ? maxDim : 1f;

        switch (ctx) {
            case THIRD_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND -> {
                // Use the smaller third-person target
                float s = TARGET_THIRD_PERSON / md;
                ps.translate(0.5, 0.5, 0.5);
                ps.scale(-1f, -1f, 1f);
                ps.scale(s, s, s);
                ps.translate(-center[0], -center[1], -center[2]);
            }
            case FIRST_PERSON_RIGHT_HAND, FIRST_PERSON_LEFT_HAND -> {
                // Keep the larger first-person target
                float s = TARGET_FIRST_PERSON / md;
                ps.translate(0.5, 0.5, 0.5);
                ps.scale(-1f, -1f, 1f);
                ps.scale(s, s, s);
                ps.translate(-center[0], -center[1], -center[2]);
            }
            case GUI -> {
                float s = TARGET_GUI / md;
                ps.translate(0.5, 0.5, 0.5);
                ps.mulPose(Axis.ZP.rotationDegrees(15f));
                ps.mulPose(Axis.XP.rotationDegrees(180f));
                ps.mulPose(Axis.YP.rotationDegrees(-45f));
                ps.mulPose(Axis.XP.rotationDegrees(30f));
                ps.scale(s, s, s);
                ps.translate(-center[0], -center[1], -center[2]); // already correct
            }
            case GROUND -> {
                float s = TARGET_GROUND / md;
                ps.translate(0.5, 0.5, 0.5);
                ps.scale(-1f, -1f, 1f);
                ps.scale(s, s, s);
                ps.translate(-center[0], -center[1], -center[2]);
            }
            case FIXED -> {
                float s = TARGET_FIXED / md;
                ps.translate(0.5, 0.5, 0.5);
                ps.scale(-1f, -1f, 1f);
                ps.scale(s, s, s);
                ps.translate(-center[0], -center[1], -center[2]);
            }
            default -> {
                float s = TARGET_HAND / md;
                ps.translate(0.5, 0.5, 0.5);
                ps.scale(-1f, -1f, 1f);
                ps.scale(s, s, s);
                ps.translate(-center[0], -center[1], -center[2]);
            }
        }
    }

    // =========================================================================
    // Geometry — bounding box + max dimension, starting from identity.
    // The magazine bone's pivot is already zeroed by the caller so this is
    // purely a function of the cube geometry in local space.
    //
    // Returns float[4]: { centerX, centerY, centerZ, maxDimension }
    // =========================================================================

    private static float[] computeGeometry(BedrockPart magazine) {
        PoseStack temp = new PoseStack();

        float[] bounds = {
                Float.MAX_VALUE,  Float.MAX_VALUE,  Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        };

        accumulateBounds(magazine, temp, bounds);

        // Fallback to origin if no cube geometry was found
        if (bounds[0] > bounds[3]) {
            return new float[]{ 0f, 0f, 0f, 1f };
        }

        float maxDim = Math.max(Math.max(bounds[3] - bounds[0],
                        bounds[4] - bounds[1]),
                bounds[5] - bounds[2]);
        return new float[]{
                (bounds[0] + bounds[3]) / 2f,
                (bounds[1] + bounds[4]) / 2f,
                (bounds[2] + bounds[5]) / 2f,
                maxDim
        };
    }

    // Recursively expands {@code bounds} with every BedrockCubeBox in {@code bone} and all its descendants.
    private static void accumulateBounds(BedrockPart bone, PoseStack ps, float[] bounds) {
        if (!bone.visible) return;

        ps.pushPose();

        // Mirror the exact transformation logic from BedrockPart.java
        ps.translate(bone.offsetX, bone.offsetY, bone.offsetZ);
        ps.translate(bone.x / 16.0F, bone.y / 16.0F, bone.z / 16.0F);

        if (bone.zRot != 0.0F) ps.mulPose(Axis.ZP.rotation(bone.zRot));
        if (bone.yRot != 0.0F) ps.mulPose(Axis.YP.rotation(bone.yRot));
        if (bone.xRot != 0.0F) ps.mulPose(Axis.XP.rotation(bone.xRot));

        ps.mulPose(bone.additionalQuaternion);
        ps.scale(bone.xScale, bone.yScale, bone.zScale);

        Matrix4f matrix = ps.last().pose();

        for (BedrockCube cube : bone.cubes) {
            // We use a helper because BedrockCube is an interface without exposed fields
            float[] cBounds = getCubeBounds(cube);
            if (cBounds == null) continue;

            float lx = cBounds[0] / 16f; float hx = cBounds[1] / 16f;
            float ly = cBounds[2] / 16f; float hy = cBounds[3] / 16f;
            float lz = cBounds[4] / 16f; float hz = cBounds[5] / 16f;

            float[][] corners = {
                    {lx, ly, lz}, {hx, ly, lz}, {lx, hy, lz}, {hx, hy, lz},
                    {lx, ly, hz}, {hx, ly, hz}, {lx, hy, hz}, {hx, hy, hz}
            };

            for (float[] c : corners) {
                float wx = matrix.m00() * c[0] + matrix.m10() * c[1] + matrix.m20() * c[2] + matrix.m30();
                float wy = matrix.m01() * c[0] + matrix.m11() * c[1] + matrix.m21() * c[2] + matrix.m31();
                float wz = matrix.m02() * c[0] + matrix.m12() * c[1] + matrix.m22() * c[2] + matrix.m32();

                if (wx < bounds[0]) bounds[0] = wx; if (wy < bounds[1]) bounds[1] = wy; if (wz < bounds[2]) bounds[2] = wz;
                if (wx > bounds[3]) bounds[3] = wx; if (wy > bounds[4]) bounds[4] = wy; if (wz > bounds[5]) bounds[5] = wz;
            }
        }

        for (BedrockPart child : bone.children) {
            accumulateBounds(child, ps, bounds);
        }

        ps.popPose();
    }

    // Collects the bone and all its descendants into {@code out}.
    private void collectSubtree(BedrockPart part, List<BedrockPart> list) {
        list.add(part);
        for (BedrockPart child : part.children) {
            collectSubtree(child, list);
        }
    }

    // =========================================================================
    // Render-data cache
    // =========================================================================

    private MagazineRenderData getRenderData(ResourceLocation gunId) {
        if (permanentFailures.contains(gunId)) return null;

        MagazineRenderData cached = renderCache.get(gunId);
        if (cached != null) return cached;

        MagazineRenderData data = buildRenderData(gunId);
        if (data != null) {
            renderCache.put(gunId, data);
            TaCZMagazines.LOGGER.info("[MagazineItemRenderer] Cached magazine bone '{}' for gun '{}'",
                    data.magazineNode().name, gunId);
        }
        return data;
    }

    private MagazineRenderData buildRenderData(ResourceLocation gunId) {
        Optional<ClientGunIndex> indexOpt = TimelessAPI.getClientGunIndex(gunId);
        if (!indexOpt.isPresent()) return null;

        GunDisplayInstance display = indexOpt.get().getDefaultDisplay();
        if (display == null)  { permanentFailures.add(gunId); return null; }

        BedrockGunModel model = display.getGunModel();
        if (model == null)    { permanentFailures.add(gunId); return null; }

        BedrockPart magazineNode = findMagazineNode(model);
        if (magazineNode == null) {
            TaCZMagazines.LOGGER.warn("[MagazineItemRenderer] No magazine bone found for gun '{}', skipping", gunId);
            permanentFailures.add(gunId);
            return null;
        }

        ResourceLocation texture = display.getModelTexture();
        if (texture == null)  { permanentFailures.add(gunId); return null; }

        return new MagazineRenderData(model, magazineNode, texture);
    }

    // =========================================================================
    // Magazine bone discovery
    // =========================================================================

    private static BedrockPart findMagazineNode(BedrockGunModel model) {
        initReflection();

        // 1. Try the official TaCZ field first
        if (magazineNodeField != null) {
            try {
                BedrockPart node = (BedrockPart) magazineNodeField.get(model);
                if (node != null) return node;
            } catch (Exception ignored) {}
        }

        // 2. Priority Search: Look for the ACTUAL geometry bones first
        // This prevents picking up "mag_and_lefthand" or "additional_mag"
        String[] priorityNames = {"mag_and_bullet", "magzine", "mag", "mag_standard", "mag_default", "magazine_body", "gun_mag"};
        for (String name : priorityNames) {
            BedrockPart found = findBoneEquals(model.getRootNode(), name);
            if (found != null) return found;
        }

        // 3. Fallback: existing fuzzy search
        BedrockPart found = findBoneContaining(model.getRootNode(), "magazine");
        if (found == null) found = findBoneContaining(model.getRootNode(), "mag");

        return found;
    }

    // Helper for exact matches
    private static BedrockPart findBoneEquals(BedrockPart part, String name) {
        if (part == null) return null;
        if (part.name != null && part.name.equalsIgnoreCase(name)) return part;
        for (BedrockPart child : part.children) {
            BedrockPart found = findBoneEquals(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static BedrockPart findBoneContaining(BedrockPart part, String keyword) {
        if (part == null) return null;
        if (part.name != null && part.name.toLowerCase(Locale.ROOT).contains(keyword)) return part;
        for (BedrockPart child : part.children) {
            BedrockPart found = findBoneContaining(child, keyword);
            if (found != null) return found;
        }
        return null;
    }

    // =========================================================================
    // One-time reflection init
    // =========================================================================

    private static synchronized void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        try {
            magazineNodeField = BedrockGunModel.class.getDeclaredField("magazineNode");
            magazineNodeField.setAccessible(true);
            TaCZMagazines.LOGGER.info("[MagazineItemRenderer] Reflected BedrockGunModel.magazineNode");
        } catch (Exception e) {
            TaCZMagazines.LOGGER.warn("[MagazineItemRenderer] Could not reflect magazineNode: {}", e.getMessage());
        }
    }

    // Sets what the bullet bones are in each gun model
    private static boolean isBulletBone(String lowerName) {
        if (lowerName.contains("mag_and_bullet")) return false;
        return lowerName.contains("bullet")
                || lowerName.contains("ammo")
                || lowerName.contains("bullet_in_mag")
                || lowerName.contains("round")
                || lowerName.contains("cartridge")
                || lowerName.contains("shell");
    }

    private static float[] getCubeBounds(BedrockCube cube) {
        try {
            // TaCZ implementations usually have these fields.
            // We try the most common names used in Bedrock model loaders.
            java.lang.reflect.Field fMinX = cube.getClass().getDeclaredField("minX");
            java.lang.reflect.Field fMaxX = cube.getClass().getDeclaredField("maxX");
            java.lang.reflect.Field fMinY = cube.getClass().getDeclaredField("minY");
            java.lang.reflect.Field fMaxY = cube.getClass().getDeclaredField("maxY");
            java.lang.reflect.Field fMinZ = cube.getClass().getDeclaredField("minZ");
            java.lang.reflect.Field fMaxZ = cube.getClass().getDeclaredField("maxZ");

            fMinX.setAccessible(true); fMaxX.setAccessible(true);
            fMinY.setAccessible(true); fMaxY.setAccessible(true);
            fMinZ.setAccessible(true); fMaxZ.setAccessible(true);

            return new float[] {
                    fMinX.getFloat(cube), fMaxX.getFloat(cube),
                    fMinY.getFloat(cube), fMaxY.getFloat(cube),
                    fMinZ.getFloat(cube), fMaxZ.getFloat(cube)
            };
        } catch (Exception e) {
            // If names are different (e.g. x1, x2), handle fallback here
            return null;
        }
    }
}