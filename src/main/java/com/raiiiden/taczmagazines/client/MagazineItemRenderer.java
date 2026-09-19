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
import com.tacz.guns.client.model.bedrock.BedrockPolygon;
import com.tacz.guns.client.model.bedrock.BedrockVertex;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class MagazineItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static MagazineItemRenderer INSTANCE;

    // rounds: the node's round bones in fill order (see getRoundBones); empty if it has no row to count down.
    private record MagazineRenderData(
            BedrockGunModel model,
            BedrockPart magazineNode,
            ResourceLocation texture,
            List<BedrockPart> rounds
    ) {}

    // Normal mag cache — unchanged from original
    private final Map<ResourceLocation, MagazineRenderData> renderCache = new HashMap<>();
    private final Set<ResourceLocation> permanentFailures = new HashSet<>();

    // Extended mag cache — keyed by (gunId, extLevel), completely separate
    private record ExtCacheKey(ResourceLocation gunId, int extLevel) {}
    private final Map<ExtCacheKey, MagazineRenderData> extRenderCache = new HashMap<>();
    private final Set<ExtCacheKey> extPermanentFailures = new HashSet<>();

    private static Field magazineNodeField;
    private static boolean reflectionInitialized = false;

    // Target visual sizes (block units) — all magazines are normalized to their
    // largest dimension equaling this value so every mag appears the same size.
    private static final float TARGET_GUI    = 0.8f;
    private static final float TARGET_HAND = 0.55f;
    private static final float TARGET_FIRST_PERSON = 0.55f;
    private static final float TARGET_THIRD_PERSON = 0.35f;
    private static final float TARGET_GROUND = 0.35f;
    private static final float TARGET_FIXED  = 0.50f;

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
            INSTANCE.extRenderCache.clear();
            INSTANCE.extPermanentFailures.clear();
            INSTANCE.roundBoneCache.clear();
            INSTANCE.chamberBoneCache.clear();
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

        boolean isExtended = MagazineFamilySystem.isExtendedFamily(familyId);
        int extLevel = isExtended ? MagazineFamilySystem.getExtLevelForFamily(familyId) : 0;

        ResourceLocation repGunId = MagazineFamilySystem.getRepresentativeGun(familyId);
        if (repGunId == null) return;

        MagazineRenderData data = isExtended
                ? getExtRenderData(repGunId, extLevel)
                : getRenderData(repGunId);
        if (data == null) return;

        BedrockPart mag = data.magazineNode();

        // 1. SAVE & RESET STATE
        float sX = mag.x, sY = mag.y, sZ = mag.z;
        float rX = mag.xRot, rY = mag.yRot, rZ = mag.zRot;
        mag.x = 0; mag.y = 0; mag.z = 0;
        mag.xRot = 0; mag.yRot = 0; mag.zRot = 0;

        // 2. FILTER VISIBILITY
        MagazineItem magItem = (MagazineItem) stack.getItem();
        int ammoCount = magItem.getAmmoCount(stack);
        boolean hasBullets = ammoCount > 0;

        List<BedrockPart> subtree = new ArrayList<>();
        collectSubtree(mag, subtree);
        boolean[] savedVisible = new boolean[subtree.size()];

        // Draw only this item's mag-body variant; skipped if the model lacks it so the body isn't fully hidden.
        boolean pickVariant = false;
        for (BedrockPart part : subtree) {
            if (part == mag) continue;
            String name = part.name != null ? part.name.toLowerCase(Locale.ROOT) : "";
            if (isSelectedVariant(name, extLevel)) { pickVariant = true; break; }
        }

        for (int i = 0; i < subtree.size(); i++) {
            BedrockPart part = subtree.get(i);
            savedVisible[i] = part.visible;
            if (part == mag) continue; // never hide the root — it IS the magazine shape
            String name = part.name != null ? part.name.toLowerCase(Locale.ROOT) : "";
            if (pickVariant && isMagVariantBone(name)) {
                part.visible = isSelectedVariant(name, extLevel);
            } else if (!isExtended && (name.contains("extend") || name.contains("drum") || name.contains("max"))) {
                part.visible = false;
            } else if (isBulletBone(name)) {
                // Measured full, so the item keeps one size however many rounds it holds.
                part.visible = true;
            }
        }

        // 3. CALC BOUNDS
        float[] bounds = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        accumulateBounds(mag, new PoseStack(), bounds);

        // Rounds from the magazine's own count, not the gun animation's pose; a modelled row shows one bone per round.
        for (BedrockPart part : subtree) {
            if (part != mag && isBulletBone(part.name) && !hasBullets) part.visible = false;
        }
        List<BedrockPart> rounds = data.rounds();
        for (int i = Math.max(0, ammoCount); i < rounds.size(); i++) {
            rounds.get(i).visible = false;
        }

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
        accumulateBounds(bone, ps, bounds, true);
    }

    // requireVisible=false measures geometry even while the animation hides it (e.g. idle clip rounds).
    private static void accumulateBounds(BedrockPart bone, PoseStack ps, float[] bounds, boolean requireVisible) {
        if (requireVisible && !bone.visible) return;

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
            accumulateBounds(child, ps, bounds, requireVisible);
        }

        ps.popPose();
    }

    private void collectSubtree(BedrockPart part, List<BedrockPart> list) {
        list.add(part);
        for (BedrockPart child : part.children) {
            collectSubtree(child, list);
        }
    }

    // =========================================================================
    // Render-data cache — normal mags (unchanged from original)
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

        BedrockPart magazineNode = findMagazineNode(model, 0);
        if (magazineNode == null) {
            TaCZMagazines.LOGGER.warn("[MagazineItemRenderer] No magazine bone found for gun '{}', skipping", gunId);
            permanentFailures.add(gunId);
            return null;
        }

        ResourceLocation texture = display.getModelTexture();
        if (texture == null)  { permanentFailures.add(gunId); return null; }

        return ownedRenderData(model, magazineNode, texture);
    }

    // =========================================================================
    // Render-data cache — extended mags (separate, no impact on normal path)
    // =========================================================================

    private MagazineRenderData getExtRenderData(ResourceLocation gunId, int extLevel) {
        ExtCacheKey key = new ExtCacheKey(gunId, extLevel);
        if (extPermanentFailures.contains(key)) return null;

        MagazineRenderData cached = extRenderCache.get(key);
        if (cached != null) return cached;

        MagazineRenderData data = buildExtRenderData(gunId, extLevel);
        if (data != null) {
            extRenderCache.put(key, data);
            TaCZMagazines.LOGGER.info("[MagazineItemRenderer] Cached extended bone '{}' for gun '{}' level {}",
                    data.magazineNode().name, gunId, extLevel);
        } else {
            extPermanentFailures.add(key);
        }
        return data;
    }

    private MagazineRenderData buildExtRenderData(ResourceLocation gunId, int extLevel) {
        Optional<ClientGunIndex> indexOpt = TimelessAPI.getClientGunIndex(gunId);
        if (!indexOpt.isPresent()) return null;

        GunDisplayInstance display = indexOpt.get().getDefaultDisplay();
        if (display == null) return null;

        BedrockGunModel model = display.getGunModel();
        if (model == null) return null;

        ResourceLocation texture = display.getModelTexture();
        if (texture == null) return null;

        BedrockPart extNode = findMagazineNode(model, extLevel);
        if (extNode == null) {
            TaCZMagazines.LOGGER.warn("[MagazineItemRenderer] No extended mag bone (level {}) found for gun '{}', skipping", extLevel, gunId);
            return null;
        }

        return ownedRenderData(model, extNode, texture);
    }

    // Copies the node and finds its round row on the original, whose rest rotations the copy lacks.
    private static MagazineRenderData ownedRenderData(BedrockGunModel model, BedrockPart node, ResourceLocation texture) {
        Map<BedrockPart, BedrockPart> copies = new IdentityHashMap<>();
        BedrockPart ownedNode = deepCopy(node, copies);
        List<BedrockPart> rounds = new ArrayList<>();
        for (BedrockPart round : roundRow(node)) rounds.add(copies.get(round));
        return new MagazineRenderData(model, ownedNode, texture, List.copyOf(rounds));
    }

    // Deep Copy Method
    private static BedrockPart deepCopy(BedrockPart src, Map<BedrockPart, BedrockPart> copies) {
        BedrockPart copy = new BedrockPart(src.name);

        copy.x = src.x;
        copy.y = src.y;
        copy.z = src.z;
        copy.xRot = src.xRot;
        copy.yRot = src.yRot;
        copy.zRot = src.zRot;
        copy.offsetX = src.offsetX;
        copy.offsetY = src.offsetY;
        copy.offsetZ = src.offsetZ;
        copy.xScale = src.xScale;
        copy.yScale = src.yScale;
        copy.zScale = src.zScale;
        copy.visible = true; // start fully visible; renderByItem filter controls per-bone visibility
        copy.additionalQuaternion = new org.joml.Quaternionf(src.additionalQuaternion);

        // Cubes are geometry-only, they have no animation state — safe to share
        copy.cubes.addAll(src.cubes);

        // Recursively deep-copy children
        for (BedrockPart child : src.children) {
            copy.children.add(deepCopy(child, copies));
        }
        copies.put(src, copy);

        return copy;
    }

    // === Magazine bone discovery ===
    // Rounds are usually a sibling of the mag body, so we climb to the smallest ammo group holding them.

    private static final String[] PRIORITY_NAMES = {
            "mag_and_bullet", "magzine", "mag_standard", "mag_default", "magazine_body", "gun_mag",
            "loader_and_round", "speed_loader", "clip", "ammo", "loader", "mag"
    };
    private static final String[] FUZZY_KEYWORDS = {"magazine", "mag"};

    public static BedrockPart findMagazineNode(BedrockGunModel model, int extLevel) {
        initReflection();
        BedrockPart root = model.getRootNode();
        if (root == null) return null;

        List<BedrockPart> path = null;

        // For extended mags, the exact extended bone (mag_extended_1/2/3) names the variant.
        if (extLevel > 0) {
            path = findPath(root, "mag_extended_" + extLevel, false);
            if (path == null) path = findPath(root, "extended_" + extLevel, true);
        }

        // TaCZ's own field, which is an exact lookup of a bone literally named "magazine".
        if (path == null) {
            BedrockPart taczNode = taczMagazineNode(model);
            if (taczNode != null && !isNeverMagazineBone(taczNode.name)) {
                path = findPath(root, taczNode.name, false);
                if (path == null) path = new ArrayList<>(List.of(taczNode));
            }
        }

        // Priority search, specific names first; each match must have non-bullet geometry.
        if (path == null) {
            for (String name : PRIORITY_NAMES) {
                List<BedrockPart> found = findPath(root, name, false);
                if (found == null) continue;
                if (!hasNonBulletGeometry(found.get(found.size() - 1))) continue;
                path = found;
                break;
            }
        }

        // Fuzzy fallback — still require actual geometry.
        if (path == null) {
            for (String keyword : FUZZY_KEYWORDS) {
                List<BedrockPart> found = findPath(root, keyword, true);
                if (found != null && hasNonBulletGeometry(found.get(found.size() - 1))) {
                    path = found;
                    break;
                }
            }
        }

        // Last resort: accept any match even without confirmed geometry.
        if (path == null) {
            for (String keyword : FUZZY_KEYWORDS) {
                List<BedrockPart> found = findPath(root, keyword, true);
                if (found != null) {
                    path = found;
                    break;
                }
            }
        }

        if (path == null) return null;
        return elevateToBullets(path);
    }

    // Climbs to the smallest enclosing ammo group holding the rounds, never pulling in a hand.
    private static BedrockPart elevateToBullets(List<BedrockPart> path) {
        BedrockPart node = path.get(path.size() - 1);
        if (subtreeHasBulletBone(node)) return node;

        for (int i = path.size() - 2; i >= 0; i--) {
            BedrockPart ancestor = path.get(i);
            if (!isAmmoGroupBone(ancestor.name) || isNeverMagazineBone(ancestor.name)) break;
            if (subtreeHasHandBone(ancestor)) break;
            if (subtreeHasBulletBone(ancestor)) return ancestor;
        }
        return node;
    }

    // Root-to-bone path for the first match, or null; skips bones that can never be a magazine.
    private static List<BedrockPart> findPath(BedrockPart part, String name, boolean fuzzy) {
        if (part == null) return null;
        String lower = part.name != null ? part.name.toLowerCase(Locale.ROOT) : "";
        boolean matches = fuzzy ? lower.contains(name) : lower.equals(name.toLowerCase(Locale.ROOT));
        if (matches && !isNeverMagazineBone(part.name)) {
            List<BedrockPart> path = new ArrayList<>();
            path.add(part);
            return path;
        }
        for (BedrockPart child : part.children) {
            List<BedrockPart> found = findPath(child, name, fuzzy);
            if (found != null) {
                found.add(0, part);
                return found;
            }
        }
        return null;
    }

    private static BedrockPart taczMagazineNode(BedrockGunModel model) {
        if (magazineNodeField == null) return null;
        try {
            return (BedrockPart) magazineNodeField.get(model);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean subtreeHasBulletBone(BedrockPart bone) {
        for (BedrockPart child : bone.children) {
            if (isBulletBone(child.name)) return true;
            if (subtreeHasBulletBone(child)) return true;
        }
        return false;
    }

    private static boolean subtreeHasHandBone(BedrockPart bone) {
        if (isHandBone(bone.name)) return true;
        for (BedrockPart child : bone.children) {
            if (subtreeHasHandBone(child)) return true;
        }
        return false;
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


    // === Individual rounds on a stripper clip or speed loader ===
    // Each round is its own bone, so a part-full device can be drawn part-full.

    // Keyed on the model instance, since skinned guns render a different model.
    private final Map<BedrockGunModel, List<BedrockPart>> roundBoneCache = new IdentityHashMap<>();

    // Device round bones ordered along their layout axis so hiding from the end empties one side; empty if none.
    public List<BedrockPart> getRoundBones(ResourceLocation gunId, BedrockGunModel model) {
        if (model == null) return List.of();
        List<BedrockPart> cached = roundBoneCache.get(model);
        if (cached != null) return cached;

        List<BedrockPart> rounds = buildRoundBones(model);
        roundBoneCache.put(model, rounds);
        if (!rounds.isEmpty()) {
            TaCZMagazines.LOGGER.info("[MagazineItemRenderer] Gun '{}' models {} rounds on its loading device",
                    gunId, rounds.size());
        }
        return rounds;
    }

    private List<BedrockPart> buildRoundBones(BedrockGunModel model) {
        BedrockPart node = findMagazineNode(model, 0);
        if (node == null) return List.of();
        return roundRow(node);
    }

    // Round bones under `node`, top/first-fed first; empty unless there are at least two.
    private static List<BedrockPart> roundRow(BedrockPart node) {
        List<BedrockPart> rounds = new ArrayList<>();
        collectLargestRoundGroup(node, rounds);
        // One bone is a single modelled round, not a row we can count down.
        if (rounds.size() < 2) return List.of();
        sortAlongLongestAxis(rounds);
        return rounds;
    }

    // Largest run of sibling round bones under the device — the row, not the parts of one round.
    private static void collectLargestRoundGroup(BedrockPart bone, List<BedrockPart> best) {
        List<BedrockPart> siblings = roundSiblings(bone);
        if (siblings.size() > best.size() && isNumberedSeries(siblings)) {
            best.clear();
            best.addAll(siblings);
        }
        for (BedrockPart child : bone.children) collectLargestRoundGroup(child, best);
    }

    // Children of `bone` that are individual rounds; if `bone` is the round group, its numbered children count too.
    private static List<BedrockPart> roundSiblings(BedrockPart bone) {
        List<BedrockPart> named = new ArrayList<>();
        for (BedrockPart child : bone.children) {
            if (isBulletBone(child.name) && hasAnyGeometry(child)) named.add(child);
        }
        if (isNumberedSeries(named) || !isBulletBone(bone.name)) return named;

        // One series, one stem: group13..group21 is a row; head3 + bullet_shell3 is one round.
        List<BedrockPart> numbered = new ArrayList<>();
        String stem = null;
        for (BedrockPart child : bone.children) {
            String name = child.name;
            if (name == null || name.isEmpty() || !Character.isDigit(name.charAt(name.length() - 1))) continue;
            if (!hasAnyGeometry(child)) continue;
            String childStem = numberStem(name);
            if (stem == null) stem = childStem;
            else if (!stem.equals(childStem)) return named;
            numbered.add(child);
        }
        return numbered.size() >= 2 ? numbered : named;
    }

    private static String numberStem(String name) {
        int end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) end--;
        return name.substring(0, end).toLowerCase(Locale.ROOT);
    }

    // A row of rounds is numbered; an unnumbered head/shell pair is one round in two pieces.
    private static boolean isNumberedSeries(List<BedrockPart> bones) {
        int numbered = 0;
        for (BedrockPart bone : bones) {
            String name = bone.name;
            if (name != null && !name.isEmpty() && Character.isDigit(name.charAt(name.length() - 1))) {
                numbered++;
            }
        }
        return numbered >= 2;
    }

    private static boolean hasAnyGeometry(BedrockPart bone) {
        if (!bone.cubes.isEmpty()) return true;
        for (BedrockPart child : bone.children) if (hasAnyGeometry(child)) return true;
        return false;
    }

    // Orders rounds by the pivot axis they vary on; pivots are never animated, so the order is pose-independent.
    private static void sortAlongLongestAxis(List<BedrockPart> rounds) {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (BedrockPart round : rounds) {
            float[] pivot = {round.x, round.y, round.z};
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], pivot[axis]);
                max[axis] = Math.max(max[axis], pivot[axis]);
            }
        }

        int longest = 0;
        for (int axis = 1; axis < 3; axis++) {
            if (max[axis] - min[axis] > max[longest] - min[longest]) longest = axis;
        }
        // Every round sharing one pivot means they are laid out by rotation instead — a ring.
        if (max[longest] - min[longest] < 1.0e-4f) {
            sortAroundRing(rounds);
            return;
        }

        final int axis = longest;
        rounds.sort((a, b) -> {
            int byPivot = Float.compare(pivotOnAxis(b, axis), pivotOnAxis(a, axis));
            if (byPivot != 0) return byPivot;
            return String.valueOf(a.name).compareTo(String.valueOf(b.name));
        });
    }

    private static float pivotOnAxis(BedrockPart bone, int axis) {
        return axis == 0 ? bone.x : axis == 1 ? bone.y : bone.z;
    }

    // Orders ring rounds by their rest rotation angle so hiding empties the ring from one side.
    private static void sortAroundRing(List<BedrockPart> rounds) {
        int axis = ringAxis(rounds);
        if (axis < 0) return; // No layout to read at all: keep the pack's own order.
        rounds.sort(java.util.Comparator.comparingDouble(r -> normalizedAngle(initRot(r, axis))));
    }

    // The axis the rounds are turned about, or -1 if they are not turned differently at all.
    private static int ringAxis(List<BedrockPart> rounds) {
        int best = -1;
        double bestSpread = 1.0e-3;
        for (int axis = 0; axis < 3; axis++) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (BedrockPart round : rounds) {
                double angle = normalizedAngle(initRot(round, axis));
                min = Math.min(min, angle);
                max = Math.max(max, angle);
            }
            if (max - min > bestSpread) {
                bestSpread = max - min;
                best = axis;
            }
        }
        return best;
    }

    private static boolean sharesOnePivot(List<BedrockPart> rounds) {
        BedrockPart first = rounds.get(0);
        for (BedrockPart round : rounds) {
            if (Math.abs(round.x - first.x) > 1.0e-4f
                    || Math.abs(round.y - first.y) > 1.0e-4f
                    || Math.abs(round.z - first.z) > 1.0e-4f) return false;
        }
        return true;
    }

    private static float initRot(BedrockPart bone, int axis) {
        return axis == 0 ? bone.getInitRotX() : axis == 1 ? bone.getInitRotY() : bone.getInitRotZ();
    }

    private static double normalizedAngle(float radians) {
        double turn = Math.PI * 2;
        double angle = radians % turn;
        return angle < 0 ? angle + turn : angle;
    }

    private static double angularDistance(float a, float b) {
        double d = Math.abs(normalizedAngle(a) - normalizedAngle(b));
        return Math.min(d, Math.PI * 2 - d);
    }

    // === Where a speed loader's rounds end up ===
    // Ties each loader round to the cylinder chamber it drops into, matched by angle (rings only).

    private final Map<BedrockGunModel, List<BedrockPart>> chamberBoneCache = new IdentityHashMap<>();

    // Gun rounds the device rounds become, in getRoundBones order; empty if the gun has no such ring.
    public List<BedrockPart> getChamberBones(ResourceLocation gunId, BedrockGunModel model) {
        if (model == null) return List.of();
        List<BedrockPart> cached = chamberBoneCache.get(model);
        if (cached != null) return cached;

        List<BedrockPart> chambers = buildChamberBones(model, getRoundBones(gunId, model));
        chamberBoneCache.put(model, chambers);
        if (!chambers.isEmpty()) {
            TaCZMagazines.LOGGER.info("[MagazineItemRenderer] Gun '{}' loads its loading device into {} matching chambers",
                    gunId, chambers.size());
        }
        return chambers;
    }

    private static List<BedrockPart> buildChamberBones(BedrockGunModel model, List<BedrockPart> rounds) {
        if (rounds.size() < 2 || !sharesOnePivot(rounds) || ringAxis(rounds) < 0) return List.of();
        BedrockPart root = model.getRootNode();
        if (root == null) return List.of();

        Set<BedrockPart> device = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        device.addAll(rounds);
        List<BedrockPart> chambers = new ArrayList<>();
        findChamberRing(root, rounds, device, chambers);
        return chambers;
    }

    private static boolean findChamberRing(BedrockPart bone, List<BedrockPart> rounds,
                                           Set<BedrockPart> device, List<BedrockPart> out) {
        List<BedrockPart> candidates = roundSiblings(bone);
        candidates.removeIf(device::contains);
        if (candidates.size() == rounds.size()) {
            List<BedrockPart> paired = pairByRotation(rounds, candidates);
            if (paired != null) {
                out.addAll(paired);
                return true;
            }
        }
        for (BedrockPart child : bone.children) {
            if (findChamberRing(child, rounds, device, out)) return true;
        }
        return false;
    }

    // Candidates reordered to match each device round's angle, or null if any has no partner.
    private static List<BedrockPart> pairByRotation(List<BedrockPart> rounds, List<BedrockPart> candidates) {
        List<BedrockPart> left = new ArrayList<>(candidates);
        List<BedrockPart> paired = new ArrayList<>(rounds.size());
        for (BedrockPart round : rounds) {
            BedrockPart partner = null;
            for (BedrockPart candidate : left) {
                if (sameInitRotation(round, candidate)) {
                    partner = candidate;
                    break;
                }
            }
            if (partner == null) return null;
            left.remove(partner);
            paired.add(partner);
        }
        return paired;
    }

    private static boolean sameInitRotation(BedrockPart a, BedrockPart b) {
        for (int axis = 0; axis < 3; axis++) {
            if (angularDistance(initRot(a, axis), initRot(b, axis)) > 1.0e-3) return false;
        }
        return true;
    }

    // Max axis misalignment (~25 degrees, either facing) before rings are paired by direction.
    private static final float RINGS_LINED_UP = 0.9f;

    // Chambers reordered to match device rounds' current directions; null while the rings aren't aligned.
    // Rest angles alone aren't enough since packs rotate loader and cylinder differently.
    public static List<BedrockPart> pairChambersByPose(List<BedrockPart> rounds, List<BedrockPart> chambers) {
        if (rounds.size() < 2 || rounds.size() != chambers.size()) return null;
        int axis = ringAxis(rounds);
        if (axis < 0) return null;
        org.joml.Vector3f along = unitAxis(axis);
        org.joml.Vector3f across = unitAxis((axis + 1) % 3);

        org.joml.Vector3f deviceAxis = poseRotation(rounds.get(0)).transform(new org.joml.Vector3f(along));
        org.joml.Vector3f chamberAxis = poseRotation(chambers.get(0)).transform(new org.joml.Vector3f(along));
        if (Math.abs(deviceAxis.dot(chamberAxis)) < RINGS_LINED_UP) return null;

        List<BedrockPart> left = new ArrayList<>(chambers);
        List<org.joml.Vector3f> leftDirections = new ArrayList<>(chambers.size());
        for (BedrockPart chamber : chambers) {
            leftDirections.add(poseRotation(chamber).transform(new org.joml.Vector3f(across)));
        }
        List<BedrockPart> paired = new ArrayList<>(rounds.size());
        for (BedrockPart round : rounds) {
            org.joml.Vector3f direction = poseRotation(round).transform(new org.joml.Vector3f(across));
            int best = 0;
            for (int i = 1; i < left.size(); i++) {
                if (direction.dot(leftDirections.get(i)) > direction.dot(leftDirections.get(best))) best = i;
            }
            paired.add(left.remove(best));
            leftDirections.remove(best);
        }
        return paired;
    }

    // Bone's posed model-space rotation, built like BedrockPart.translateAndRotate.
    private static org.joml.Quaternionf poseRotation(BedrockPart bone) {
        List<BedrockPart> chain = new ArrayList<>();
        for (BedrockPart part = bone; part != null; part = part.getParent()) chain.add(part);
        org.joml.Quaternionf rotation = new org.joml.Quaternionf();
        for (int i = chain.size() - 1; i >= 0; i--) {
            BedrockPart part = chain.get(i);
            rotation.rotateZ(part.zRot).rotateY(part.yRot).rotateX(part.xRot);
            if (part.additionalQuaternion != null) rotation.mul(part.additionalQuaternion);
        }
        return rotation;
    }

    private static org.joml.Vector3f unitAxis(int axis) {
        return new org.joml.Vector3f(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
    }

    // === Bone-name classification ===
    // Names match by whole word: "ground" isn't a round, "mag_release" isn't a magazine.

    private static final Set<String> BULLET_TOKENS = Set.of(
            "bullet", "bullets", "ammo", "ammos", "round", "rounds", "roundgroup",
            "cartridge", "cartridges", "shell", "shells");
    private static final Set<String> MAG_TOKENS = Set.of(
            "mag", "mags", "magazine", "magazines", "magzine", "magzines",
            "clip", "clips", "loader", "loaders");
    private static final Set<String> FEED_TOKENS = Set.of("belt", "chain", "chains");
    private static final Set<String> HAND_TOKENS = Set.of(
            "hand", "hands", "lefthand", "righthand", "lh", "rh", "arm", "arms");
    // "bullet_in_mag" names the rounds; "mag_and_bullet" names the group that holds them.
    private static final Set<String> JOINER_TOKENS = Set.of("in", "of", "inside", "on");

    private static List<String> tokens(String name) {
        if (name == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String token : name.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    private static boolean containsAny(Collection<String> tokens, Set<String> vocabulary) {
        for (String token : tokens) if (vocabulary.contains(token)) return true;
        return false;
    }

    // The bones that visualise loaded rounds, as opposed to the group that contains them.
    public static boolean isBulletBone(String name) {
        List<String> tokens = tokens(name);
        if (!containsAny(tokens, BULLET_TOKENS)) return false;
        if (containsAny(tokens, HAND_TOKENS)) return false;
        // "<a>_and_<b>" and "magazine_bullet" are container groups, not rounds.
        if (tokens.contains("and")) return false;
        return !containsAny(tokens, MAG_TOKENS) || containsAny(tokens, JOINER_TOKENS);
    }

    // True if the bone name marks a dedicated ammo/magazine group rather than a generic parent.
    private static boolean isAmmoGroupBone(String name) {
        List<String> tokens = tokens(name);
        return containsAny(tokens, BULLET_TOKENS)
                || containsAny(tokens, MAG_TOKENS)
                || containsAny(tokens, FEED_TOKENS);
    }

    private static boolean isHandBone(String name) {
        return containsAny(tokens(name), HAND_TOKENS);
    }

    // Bones matching "mag" that never render as one (camera locators, release buttons).
    private static boolean isNeverMagazineBone(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("refit")
                || lower.contains("release")
                || lower.endsWith("_view")
                || lower.equals("view") || lower.equals("views")
                || lower.equals("ground")
                || lower.equals("hide");
    }

    // Interchangeable mag-body variants; only this item's is drawn.
    private static final String EXTENDED_PREFIX = "mag_extended_";

    private static boolean isMagVariantBone(String lowerName) {
        return extendedVariantLevel(lowerName) > 0
                || lowerName.endsWith("mag_standard")
                || lowerName.endsWith("mag_default");
    }

    // The level in a "…mag_extended_<n>" bone name, or 0 if the name isn't one.
    private static int extendedVariantLevel(String lowerName) {
        int at = lowerName.lastIndexOf(EXTENDED_PREFIX);
        if (at < 0) return 0;
        String digits = lowerName.substring(at + EXTENDED_PREFIX.length());
        if (digits.isEmpty()) return 0;
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isSelectedVariant(String lowerName, int extLevel) {
        if (extLevel > 0) return extendedVariantLevel(lowerName) == extLevel;
        return lowerName.endsWith("mag_standard") || lowerName.endsWith("mag_default");
    }

    private static boolean hasNonBulletGeometry(BedrockPart bone) {
        if (!bone.cubes.isEmpty()) return true;
        for (BedrockPart child : bone.children) {
            if (isBulletBone(child.name)) continue;
            if (hasNonBulletGeometry(child)) return true;
        }
        return false;
    }

    // Cube extent in pixels, from the vertices actually drawn: minX/maxX leave out "inflate",
    // which packs use to shrink cubes (rhino357's rounds are drawn ~9x smaller than their box).
    private static final Map<Class<?>, Field[]> CUBE_FIELDS = new HashMap<>();

    private static float[] getCubeBounds(BedrockCube cube) {
        Field[] fields = CUBE_FIELDS.computeIfAbsent(cube.getClass(), MagazineItemRenderer::cubeFields);
        try {
            if (fields[0] != null && fields[0].get(cube) instanceof BedrockPolygon[] polygons && polygons.length > 0) {
                float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
                for (BedrockPolygon polygon : polygons) {
                    for (BedrockVertex vertex : polygon.vertices) {
                        b[0] = Math.min(b[0], vertex.pos.x()); b[1] = Math.max(b[1], vertex.pos.x());
                        b[2] = Math.min(b[2], vertex.pos.y()); b[3] = Math.max(b[3], vertex.pos.y());
                        b[4] = Math.min(b[4], vertex.pos.z()); b[5] = Math.max(b[5], vertex.pos.z());
                    }
                }
                if (b[0] <= b[1]) return b;
            }
            if (fields[1] == null) return null;
            return new float[] {
                    fields[1].getFloat(cube), fields[2].getFloat(cube),
                    fields[3].getFloat(cube), fields[4].getFloat(cube),
                    fields[5].getFloat(cube), fields[6].getFloat(cube)
            };
        } catch (Exception e) {
            return null;
        }
    }

    // { polygons, minX, maxX, minY, maxY, minZ, maxZ }; entries are null where the class lacks them.
    private static Field[] cubeFields(Class<?> type) {
        String[] names = {"polygons", "minX", "maxX", "minY", "maxY", "minZ", "maxZ"};
        Field[] fields = new Field[names.length];
        for (int i = 0; i < names.length; i++) {
            try {
                fields[i] = type.getDeclaredField(names[i]);
                fields[i].setAccessible(true);
            } catch (Exception ignored) {
                fields[i] = null;
            }
        }
        return fields;
    }
}