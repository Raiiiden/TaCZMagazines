package com.raiiiden.taczmagazines.magazine;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.item.AmmoBoxMagazineStorage;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.item.MagazineReloadSource;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// Guns whose empty reload is a bulk insert (clip/speed loader) but tactical reload is round-by-round.
// Only the bulk half is a magazine swap; the other still feeds from loose ammo.
public final class SplitReloadGuns {

    // ── How the two halves are recognised ──
    // Bulk = "<name>_feed" + companion timing; loop = "loop_feed"/"roundN_feed"; both kinds = split-reload.

    private static final Pattern ROUND_FEED = Pattern.compile("round\\d+_feed");
    private static final Pattern LOOP_FEED = Pattern.compile("loop(_\\d+)?_feed(_\\d+)?");
    private static final Pattern EMPTY_LOOP_FEED = Pattern.compile("loop_empty(_\\d+)?_feed(_\\d+)?");
    private static final String FEED_SUFFIX = "_feed";

    private static final Set<ResourceLocation> SPLIT_RELOAD = new HashSet<>();
    // gunId -> true to force split-reload handling, false to force it off. From config.
    private static final Map<ResourceLocation, Boolean> OVERRIDES = new HashMap<>();

    // Per-reload flag: this reload is the bulk insert, so MixinAbstractGunItem's swap applies.
    public static final String BULK_RELOAD_TAG = "TaCZMag_BulkReload";
    // Inventory slot of this reload's loading device, so every call hits the same clip.
    public static final String BULK_SLOT_TAG = "TaCZMag_BulkSlot";
    // Left by older versions, which took the whole device into the gun. Cleared, never written.
    private static final String LEGACY_BULK_POOL_TAG = "TaCZMag_BulkPool";
    // Selector slot written by SelectMagazinePacket; -1/absent for a tap reload.
    public static final String SELECTED_SLOT_TAG = "TaCZMag_SelectedSlot";
    // Set when unchamberForTopUp moved the chambered round down, so it can be restored.
    private static final String UNCHAMBERED_TAG = "TaCZMag_Unchambered";
    // Rounds dumped from the cylinder, held on the gun until the reload ends (see dumpForSpeedLoader).
    private static final String DUMPED_TAG = "TaCZMag_Dumped";
    // Rounds drawn out of the loading device so far this reload.
    private static final String DRAWN_TAG = "TaCZMag_Drawn";

    private SplitReloadGuns() {
    }

    // ── Classification ────────────────────────────────────────────────────────

    public static boolean loadsRoundByRound(Map<String, Object> params) {
        if (params == null) return false;
        for (String key : params.keySet()) {
            String k = key.toLowerCase(Locale.ROOT);
            if (LOOP_FEED.matcher(k).matches() || ROUND_FEED.matcher(k).matches()) return true;
        }
        return false;
    }

    public static boolean loadsInBulk(Map<String, Object> params) {
        return bulkLoadParam(params) != null;
    }

    // script_param base name of the bulk insert, or null; also hints the device name.
    public static String bulkLoadParam(Map<String, Object> params) {
        if (params == null) return null;
        Set<String> keys = new LinkedHashSet<>();
        for (String key : params.keySet()) keys.add(key.toLowerCase(Locale.ROOT));

        for (String k : keys) {
            if (!k.endsWith(FEED_SUFFIX)) continue;
            String base = k.substring(0, k.length() - FEED_SUFFIX.length());
            if (base.isEmpty()) continue;
            // Loop feeds are round-by-round; intro/ending feeds just chamber a round.
            if (base.startsWith("loop") || base.startsWith("intro") || base.startsWith("ending")) continue;
            if (ROUND_FEED.matcher(k).matches()) continue;
            if (keys.contains(base) || keys.contains(base + "_cooldown") || keys.contains(base + "_time")) {
                return base;
            }
        }
        return null;
    }

    // Cosmetic device name guess: revolver = speed loader, "clip_load" = stripper clip, else "Clip".
    public static String deviceNoun(CommonGunIndex index, Map<String, Object> params) {
        if (usesSpeedLoader(index, params)) return "Speed Loader";
        String base = bulkLoadParam(params);
        if (base != null && base.contains("clip")) return "Stripper Clip";
        return "Clip";
    }

    // Whether the device is a speed loader (cylinder dumped first) rather than a clip (presses on top).
    public static boolean usesSpeedLoader(CommonGunIndex index, Map<String, Object> params) {
        String base = bulkLoadParam(params);
        if (base != null && (base.contains("speed") || base.contains("loader"))) return true;
        return isRevolverAction(index);
    }

    // Open-bolt and semi-only: the same fingerprint MagazineFamilySystem uses to spot a revolver.
    public static boolean isRevolverAction(CommonGunIndex index) {
        if (index == null || index.getGunData() == null) return false;
        if (index.getGunData().getBolt() != Bolt.OPEN_BOLT) return false;
        List<FireMode> modes = index.getGunData().getFireModeSet();
        return modes == null || modes.stream().noneMatch(m -> m == FireMode.AUTO || m == FireMode.BURST);
    }

    // True when this gun's empty reload is a bulk insert and its tactical reload is not.
    public static boolean isSplitReloadData(CommonGunIndex index, ResourceLocation gunId) {
        Boolean forced = OVERRIDES.get(gunId);
        if (forced != null) return forced;
        if (index == null || index.getGunData() == null) return false;
        Map<String, Object> params = index.getGunData().getScriptParam();
        return loadsRoundByRound(params) && loadsInBulk(params) && !emptyReloadLoops(params);
    }

    // The empty reload has a round-by-round loop of its own, so it is not a bulk insert.
    private static boolean emptyReloadLoops(Map<String, Object> params) {
        if (params == null) return false;
        for (String key : params.keySet()) {
            if (EMPTY_LOOP_FEED.matcher(key.toLowerCase(Locale.ROOT)).matches()) return true;
        }
        return false;
    }

    // ── Registry, rebuilt on every datapack reload ─────────────────────────────

    // Split-reload guns skipped because the option is off, counted so the log explains it.
    private static int skippedWhileDisabled = 0;

    static void clear() {
        SPLIT_RELOAD.clear();
        OVERRIDES.clear();
        skippedWhileDisabled = 0;
        OVERRIDES.putAll(MechanicsConfig.readSplitReloadOverrides());
    }

    public static void countSkippedWhileDisabled() {
        skippedWhileDisabled++;
    }

    public static void reportDiscovery() {
        if (skippedWhileDisabled > 0) {
            TaCZMagazines.LOGGER.info(
                    "[split-reload] {} gun(s) load in bulk when empty but were left out of the magazine "
                            + "system: [split_reload] bulk_reload_requires_magazine is false in "
                            + "taczmagazines-mechanics.toml. No stripper clips or speed loaders are "
                            + "generated for them while it is off.", skippedWhileDisabled);
        } else if (SPLIT_RELOAD.isEmpty()) {
            TaCZMagazines.LOGGER.info("[split-reload] no gun in the loaded packs uses a stripper clip, "
                    + "en-bloc clip or speed loader that this mod could detect.");
        }
    }

    static void register(ResourceLocation gunId) {
        if (gunId == null) return;
        SPLIT_RELOAD.add(gunId);
        TaCZMagazines.LOGGER.info(
                "[split-reload] '{}' loads in bulk when empty and round by round otherwise — "
                        + "its bulk reload will use a magazine", gunId);
    }

    public static Set<ResourceLocation> registered() {
        return Collections.unmodifiableSet(SPLIT_RELOAD);
    }

    public static boolean isSplitReload(ResourceLocation gunId) {
        return gunId != null && SPLIT_RELOAD.contains(gunId);
    }

    public static boolean isSplitReload(ItemStack gun) {
        if (!(gun.getItem() instanceof IGun iGun)) return false;
        return isSplitReload(iGun.getGunId(gun));
    }

    // ── Reload branch prediction ──────────────────────────────────────────────

    // Scoped split-reload guns are assumed to load single rounds, as kar98's script does.
    public static boolean scopeBlocksBulkLoad(ItemStack gun) {
        if (!(gun.getItem() instanceof IGun iGun)) return false;
        ResourceLocation scope = iGun.getAttachmentId(gun, AttachmentType.SCOPE);
        return scope != null && !DefaultAssets.isEmptyAttachmentId(scope);
    }

    // Any magazine reachable in the inventory or ammo boxes, excluding the spent one in the gun.
    public static boolean hasUsableMagazine(LivingEntity shooter, ItemStack gun) {
        if (shooter == null) return false;
        return shooter.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null)
                .map(handler -> MagazineReloadSource.hasUsableMagazine(handler, gun))
                .orElse(false);
    }

    // Loose rounds or real TaCZ ammo boxes; this mod's magazines are excluded.
    public static boolean hasLooseAmmo(LivingEntity shooter, ItemStack gun) {
        if (shooter == null) return false;
        return shooter.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null).map(handler -> {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty() || stack.getItem() instanceof MagazineItem) continue;
                if (stack.getItem() instanceof IAmmo ammo && ammo.isAmmoOfGun(gun, stack)) return true;
                if (stack.getItem() instanceof IAmmoBox box && box.isAmmoBoxOfGun(gun, stack)
                        && box.getAmmoCount(stack) > 0) return true;
            }
            return false;
        }).orElse(false);
    }

    // Rounds as TaCZ counts them for empty vs tactical: magazine plus chambered round.
    public static int roundsInGun(ItemStack gun) {
        if (!(gun.getItem() instanceof IGun iGun)) return 0;
        int rounds = iGun.getCurrentAmmoCount(gun);
        if (iGun.hasBulletInBarrel(gun) && !isOpenBolt(iGun.getGunId(gun))) rounds++;
        return rounds;
    }

    private static boolean isOpenBolt(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getBolt() == Bolt.OPEN_BOLT)
                .orElse(false);
    }

    // Moves a chambered round into the magazine so a bulk insert that overwrites the chamber loses nothing.
    public static void unchamberForTopUp(ItemStack gun) {
        if (!(gun.getItem() instanceof IGun iGun)) return;
        if (!iGun.hasBulletInBarrel(gun) || isOpenBolt(iGun.getGunId(gun))) return;
        iGun.setBulletInBarrel(gun, false);
        iGun.setCurrentAmmoCount(gun, iGun.getCurrentAmmoCount(gun) + 1);
        gun.getOrCreateTag().putBoolean(UNCHAMBERED_TAG, true);
    }

    // Undoes unchamberForTopUp if the reload ended before any round left the clip.
    private static void rechamberIfUnused(ItemStack gun) {
        if (!gun.hasTag() || !gun.getTag().getBoolean(UNCHAMBERED_TAG)) return;
        if (boundSlot(gun) >= 0) return;
        if (!(gun.getItem() instanceof IGun iGun)) return;
        if (iGun.hasBulletInBarrel(gun)) return;
        int rounds = iGun.getCurrentAmmoCount(gun);
        if (rounds <= 0) return;
        iGun.setCurrentAmmoCount(gun, rounds - 1);
        iGun.setBulletInBarrel(gun, true);
    }

    // ── Speed loaders ──
    // A speed loader only fills an empty cylinder, so the cylinder is dumped first.

    public static boolean dumpsOnBulkLoad(ItemStack gun) {
        if (!MechanicsConfig.SPEED_LOADER_DUMPS_ROUNDS.get()) return false;
        if (!(gun.getItem() instanceof IGun iGun)) return false;
        ResourceLocation gunId = iGun.getGunId(gun);
        if (!isSplitReload(gunId)) return false;
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> usesSpeedLoader(index, index.getGunData().getScriptParam()))
                .orElse(false);
    }

    // Dumps the cylinder before the script counts needed ammo; rounds are held on the gun until settleDump.
    // Skipped when the reload consumes nothing, to avoid minting ammo.
    public static void dumpForSpeedLoader(ItemStack gun, LivingEntity shooter) {
        if (shooter == null || !dumpsOnBulkLoad(gun)) return;
        if (!(gun.getItem() instanceof AbstractGunItem gunItem)) return;
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        if (operator == null || !operator.needCheckAmmo() || gunItem.useDummyAmmo(gun)) return;

        int rounds = gunItem.getCurrentAmmoCount(gun);
        if (rounds <= 0) return;
        gunItem.setCurrentAmmoCount(gun, 0);
        gun.getOrCreateTag().putInt(DUMPED_TAG, rounds);
        TaCZMagazines.LOGGER.debug("Split reload: dumped {} round(s) out of the cylinder for a speed loader", rounds);
    }

    // After the reload: dumped rounds go to the player if the loader went in, else back into the cylinder.
    private static void settleDump(ItemStack gun, LivingEntity shooter) {
        CompoundTag tag = gun.getTag();
        if (tag == null || !tag.contains(DUMPED_TAG)) return;
        int dumped = tag.getInt(DUMPED_TAG);
        boolean loaderWentIn = tag.getInt(DRAWN_TAG) > 0;
        tag.remove(DUMPED_TAG);
        if (dumped <= 0 || !(gun.getItem() instanceof IGun iGun)) return;

        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(iGun.getGunId(gun))
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
        boolean canHandBack = shooter != null && ammoId != null && !DefaultAssets.EMPTY_AMMO_ID.equals(ammoId);
        if (!loaderWentIn || !canHandBack) {
            iGun.setCurrentAmmoCount(gun, iGun.getCurrentAmmoCount(gun) + dumped);
            return;
        }
        handBack(shooter, ammoId, dumped);
    }

    // Gives loose rounds to the shooter, or drops them if full or configured to.
    private static void handBack(LivingEntity shooter, ResourceLocation ammoId, int count) {
        boolean onGround = MechanicsConfig.DUMPED_ROUNDS_ON_GROUND.get();
        int perStack = Math.max(1, AmmoItemBuilder.create().setId(ammoId).setCount(1).build().getMaxStackSize());
        int left = count;
        while (left > 0) {
            int size = Math.min(perStack, left);
            left -= size;
            ItemStack rounds = AmmoItemBuilder.create().setId(ammoId).setCount(size).build();
            if (rounds.isEmpty()) return;
            if (!onGround && shooter instanceof Player player && player.getInventory().add(rounds)) continue;
            shooter.spawnAtLocation(rounds);
        }
    }

    // The slot the player picked out of the selector for this reload, or -1 for a tap reload.
    public static int selectedSlot(ItemStack gun) {
        return gun.hasTag() && gun.getTag().contains(SELECTED_SLOT_TAG)
                ? gun.getTag().getInt(SELECTED_SLOT_TAG)
                : -1;
    }

    // Whether the player picked a device from the selector; an aimed pick bypasses the part-loaded rules.
    public static boolean deviceWasChosen(LivingEntity shooter, ItemStack gun) {
        if (shooter == null) return false;
        int slot = selectedSlot(gun);
        if (slot < 0) return false;
        return shooter.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null).map(handler -> {
            if (slot >= handler.getSlots()) return false;
            ItemStack picked = handler.getStackInSlot(slot);
            if (hasRounds(picked, gun)) return true;
            // The selector lists ammo boxes by the device inside them.
            return hasRounds(AmmoBoxMagazineStorage.peekBestCompatible(picked, gun), gun);
        }).orElse(false);
    }

    private static boolean hasRounds(ItemStack stack, ItemStack gun) {
        return isUsableDevice(stack, gun) && ((MagazineItem) stack.getItem()).getAmmoCount(stack) > 0;
    }

    // ── Per-reload markers ────────────────────────────────────────────────────

    public static boolean isBulkReload(ItemStack gun) {
        return gun.hasTag() && gun.getTag().getBoolean(BULK_RELOAD_TAG);
    }

    public static void markBulkReload(ItemStack gun) {
        gun.getOrCreateTag().putBoolean(BULK_RELOAD_TAG, true);
    }

    // ── Drawing rounds out of a loading device ──
    // Devices are drained in place, bound to one slot for the whole reload.

    // Draws rounds from the device at `slot` without removing it; returns how many were available.
    public static int drawFromLoadingDevice(IItemHandler inventory, ItemStack gun, int wanted,
                                            int selectedSlot) {
        if (wanted <= 0 || inventory == null) return 0;

        int slot = boundSlot(gun);
        if (slot < 0) {
            slot = resolveSlot(inventory, gun, selectedSlot);
            if (slot < 0) return 0;
            bindSlot(gun, slot);
        }
        if (slot >= inventory.getSlots()) return 0;

        ItemStack device = inventory.getStackInSlot(slot);
        // The player can move things around mid-reload; only ever draw from what is still there.
        if (!isUsableDevice(device, gun)) return 0;

        MagazineItem item = (MagazineItem) device.getItem();
        int have = item.getAmmoCount(device);
        int give = Math.min(wanted, have);
        if (give <= 0) return 0;

        item.setAmmoCount(device, have - give);
        gun.getOrCreateTag().putInt(DRAWN_TAG, gun.getOrCreateTag().getInt(DRAWN_TAG) + give);
        TaCZMagazines.LOGGER.debug("Split reload: drew {} round(s) from the device in slot {}, {} left in it",
                give, slot, have - give);
        return give;
    }

    private static boolean isUsableDevice(ItemStack stack, ItemStack gun) {
        return stack.getItem() instanceof MagazineItem magItem && magItem.isAmmoBoxOfGun(gun, stack);
    }

    private static int resolveSlot(IItemHandler inventory, ItemStack gun, int selectedSlot) {
        if (selectedSlot >= 0) {
            if (selectedSlot >= inventory.getSlots()) return -1;
            if (isUsableDevice(inventory.getStackInSlot(selectedSlot), gun)) return selectedSlot;
            // Ammo box picked: lift one device out so its remainder stays visible.
            return takeFromBox(inventory, gun, selectedSlot);
        }

        // Tap reload: take the fullest device to hand.
        int best = -1;
        int bestAmmo = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!isUsableDevice(stack, gun)) continue;
            int ammo = ((MagazineItem) stack.getItem()).getAmmoCount(stack);
            if (ammo > bestAmmo) {
                bestAmmo = ammo;
                best = i;
            }
        }
        if (best >= 0) return best;

        for (int i = 0; i < inventory.getSlots(); i++) {
            if (AmmoBoxMagazineStorage.peekBestCompatible(inventory.getStackInSlot(i), gun).isEmpty()) continue;
            int taken = takeFromBox(inventory, gun, i);
            if (taken >= 0) return taken;
        }
        return -1;
    }

    // Moves one device from an ammo box into a free slot and returns it; -1 if impossible.
    private static int takeFromBox(IItemHandler inventory, ItemStack gun, int boxSlot) {
        if (!(inventory instanceof IItemHandlerModifiable modifiable)) return -1;
        int free = -1;
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (inventory.getStackInSlot(i).isEmpty()) {
                free = i;
                break;
            }
        }
        if (free < 0) return -1;

        ItemStack box = inventory.getStackInSlot(boxSlot);
        ItemStack device = AmmoBoxMagazineStorage.extractBestCompatible(box, gun);
        if (!isUsableDevice(device, gun)) return -1;

        modifiable.setStackInSlot(boxSlot, box);
        modifiable.setStackInSlot(free, device);
        return free;
    }

    private static int boundSlot(ItemStack gun) {
        return gun.hasTag() && gun.getTag().contains(BULK_SLOT_TAG)
                ? gun.getTag().getInt(BULK_SLOT_TAG)
                : -1;
    }

    private static void bindSlot(ItemStack gun, int slot) {
        gun.getOrCreateTag().putInt(BULK_SLOT_TAG, slot);
    }

    public static void clearReloadMarkers(ItemStack gun, LivingEntity shooter) {
        if (!gun.hasTag()) return;
        // The dump first: rounds it puts back are where rechamberIfUnused takes its round from.
        settleDump(gun, shooter);
        rechamberIfUnused(gun);
        CompoundTag tag = gun.getTag();
        tag.remove(UNCHAMBERED_TAG);
        tag.remove(DRAWN_TAG);
        tag.remove(BULK_RELOAD_TAG);
        tag.remove(BULK_SLOT_TAG);
        tag.remove(LEGACY_BULK_POOL_TAG);
    }

    // ── Config ────────────────────────────────────────────────────────────────

    public static boolean enabled() {
        return MechanicsConfig.BULK_RELOAD_REQUIRES_MAGAZINE.get();
    }

    // True when this gun is split-reload and the mod is allowed to manage its bulk reload.
    public static boolean managed(ItemStack gun) {
        return enabled() && isSplitReload(gun);
    }
}
