package com.raiiiden.taczmagazines.magazine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Revolvers whose every reload seats a speed loader. TaCZ declares them as plain magazine reloads,
// so they're told apart from hand-fed revolvers by a loader bone moving in the reload animation.
public final class SpeedLoaderRevolvers {

    // "loader", "speed_loader", "speedloader", "loader_and_round", ...; not "reloader".
    private static final Pattern LOADER_BONE = Pattern.compile("(^|_)(speed_?)?loaders?(_|\\d|$)");
    private static final String[] RELOAD_ANIMATIONS = {"reload_empty", "reload_tactical"};

    private static final Set<ResourceLocation> DETECTED = new HashSet<>();

    private SpeedLoaderRevolvers() {
    }

    public static boolean isSpeedLoaderRevolver(ResourceLocation gunId) {
        return gunId != null && enabled() && DETECTED.contains(gunId);
    }

    public static boolean enabled() {
        return MechanicsConfig.SPEED_LOADER_REVOLVERS_USE_MAGAZINE.get();
    }

    public static Set<ResourceLocation> detected() {
        return Collections.unmodifiableSet(DETECTED);
    }

    // Rebuilt on every family discovery. Only revolver-fingerprint guns are looked at.
    // Reads the pack files directly, so server and client reach the same answer.
    static void scan(Iterable<Map.Entry<ResourceLocation, CommonGunIndex>> guns) {
        DETECTED.clear();
        Map<ResourceLocation, Boolean> overrides = MechanicsConfig.readSpeedLoaderRevolverOverrides();

        try (PackFiles packs = new PackFiles()) {
            for (Map.Entry<ResourceLocation, CommonGunIndex> entry : guns) {
                ResourceLocation gunId = entry.getKey();
                CommonGunIndex index = entry.getValue();
                Boolean forced = overrides.get(gunId);
                boolean speedLoader;
                if (forced != null) {
                    speedLoader = forced;
                } else {
                    if (!SplitReloadGuns.isRevolverAction(index)) continue;
                    speedLoader = animatesLoader(packs, index);
                }
                if (!speedLoader) continue;
                DETECTED.add(gunId);
                TaCZMagazines.LOGGER.info("[speed-loader] '{}' reloads with a speed loader{} — it will need one to reload",
                        gunId, forced != null ? " (config override)" : "");
            }
        } catch (Exception e) {
            // Never let a bad pack break discovery; those revolvers just stay loose-fed.
            TaCZMagazines.LOGGER.warn("[speed-loader] could not read gun packs: {}", e.toString());
        }
    }

    private static boolean animatesLoader(PackFiles packs, CommonGunIndex index) {
        if (index == null || index.getPojo() == null) return false;
        ResourceLocation displayId = index.getPojo().getDisplay();
        if (displayId == null) return false;

        JsonObject display = packs.readJson("assets/" + displayId.getNamespace()
                + "/display/guns/" + displayId.getPath() + ".json");
        if (display == null || !display.has("animation")) return false;
        ResourceLocation animId = ResourceLocation.tryParse(display.get("animation").getAsString());
        if (animId == null) return false;

        JsonObject file = packs.readJson("assets/" + animId.getNamespace()
                + "/animations/" + animId.getPath() + ".animation.json");
        if (file == null || !file.has("animations")) return false;
        JsonObject animations = file.getAsJsonObject("animations");
        for (String name : RELOAD_ANIMATIONS) {
            JsonElement anim = animations.get(name);
            if (anim == null || !anim.isJsonObject()) continue;
            JsonElement bones = anim.getAsJsonObject().get("bones");
            if (bones == null || !bones.isJsonObject()) continue;
            for (String bone : bones.getAsJsonObject().keySet()) {
                if (LOADER_BONE.matcher(bone.toLowerCase(Locale.ROOT)).find()) return true;
            }
        }
        return false;
    }

    // The gun packs in <game>/tacz (folders and zips), opened lazily and closed together.
    private static final class PackFiles implements Closeable {
        private List<Path> roots;
        private final List<FileSystem> zips = new ArrayList<>();

        JsonObject readJson(String relative) {
            for (Path root : roots()) {
                Path file = root.resolve(relative);
                if (!Files.isRegularFile(file)) continue;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    // Lenient: packs often carry // comments in their JSON.
                    JsonReader json = new JsonReader(reader);
                    json.setLenient(true);
                    JsonElement parsed = JsonParser.parseReader(json);
                    return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                } catch (Exception e) {
                    TaCZMagazines.LOGGER.debug("[speed-loader] unreadable {} in {}: {}", relative, root, e.toString());
                    return null;
                }
            }
            return null;
        }

        private List<Path> roots() {
            if (roots != null) return roots;
            roots = new ArrayList<>();
            Path dir = FMLPaths.GAMEDIR.get().resolve("tacz");
            if (!Files.isDirectory(dir)) return roots;
            List<Path> entries;
            try (Stream<Path> list = Files.list(dir)) {
                entries = list.sorted().toList();
            } catch (IOException e) {
                return roots;
            }
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    roots.add(entry);
                } else if (entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    try {
                        FileSystem fs = FileSystems.newFileSystem(entry, (ClassLoader) null);
                        zips.add(fs);
                        roots.add(fs.getPath("/"));
                    } catch (Exception e) {
                        TaCZMagazines.LOGGER.debug("[speed-loader] skipped pack {}: {}", entry, e.toString());
                    }
                }
            }
            return roots;
        }

        @Override
        public void close() {
            for (FileSystem fs : zips) {
                try {
                    fs.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
