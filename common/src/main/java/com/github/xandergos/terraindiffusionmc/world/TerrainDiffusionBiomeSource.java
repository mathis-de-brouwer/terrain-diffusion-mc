package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.BiomeClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * BiomeSource for Terrain Diffusion worlds.
 *
 * Changes vs. original:
 *  1. All new surface biomes (RIVER, FROZEN_RIVER, FLOWER_FOREST, CHERRY_GROVE,
 *     BEACH, SNOWY_BEACH, STONY_SHORE) are registered.
 *  2. getNoiseBiome() now routes underground Y levels to cave biomes using
 *     a converted surface height (heightmap meters -> Minecraft block Y):
 *     - at or above surface: surface biome from biomeIds
 *     - below surface: LUSH_CAVES or DRIPSTONE_CAVES based on surface climate
 *     - DEEP_DARK when sufficiently deep and below sea level
 *  3. Cobblemon compatibility: all biomes that TD can generate are listed in
 *     collectPossibleBiomes() so Cobblemon builds correct spawn pools at load.
 *     The companion datapack (see cobblemon_tags/ folder) supplies the
 *     #cobblemon:is_* tag JSON files so every biome gets the right tags.
 */
public class TerrainDiffusionBiomeSource extends BiomeSource {

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group(
                            RegistryOps.retrieveGetter(Registries.BIOME)
                    ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));

    private final HolderGetter<Biome> biomeLookup;
    private Map<Short, Holder<Biome>> biomeIdMap = null;

    // ── Cave depth thresholds (blocks below converted surface height) ─────────
    /**
     * Below DEEP_DARK_DEPTH blocks underground -> Deep Dark.
     * In a standard 1.21.1 world this corresponds roughly to y < -20 for flat terrain,
     * but shifts upward under mountains, which is exactly what we want.
     */
    private static final int DEEP_DARK_DEPTH    = 160;  // blocks below surface

    // ── Cave biome climate thresholds ─────────────────────────────────────────
    /**
     * Lush Caves prefer humid climates.  If the surface aridity proxy
     * (precip / PET) is above this value the cave is lush.
     * Cobblemon: Lush Caves get #cobblemon:is_lush and #cobblemon:is_cave tags.
     */
    private static final float LUSH_ARIDITY_THRESHOLD      = 0.55f;
    /**
     * Dripstone Caves prefer dry climates.  If aridity is below this the cave
     * is dripstone.  Between the two thresholds the biome alternates via a
     * small noise offset so there's no hard line.
     */
    private static final float DRIPSTONE_ARIDITY_THRESHOLD = 0.35f;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Biome ID → Holder map
    // ─────────────────────────────────────────────────────────────────────────
    private void requireBiomeIdMap() {
        if (biomeIdMap != null) return;
        HashMap<Short, Holder<Biome>> map = new HashMap<>();

        // ── Core vanilla surface biomes ───────────────────────────────────────
        map.put((short)  1, biomeLookup.getOrThrow(Biomes.PLAINS));
        map.put((short)  3, biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS));
        map.put((short)  5, biomeLookup.getOrThrow(Biomes.DESERT));
        map.put((short)  6, biomeLookup.getOrThrow(Biomes.SWAMP));
        map.put((short)  7, biomeLookup.getOrThrow(Biomes.RIVER));            // NEW
        map.put((short)  8, biomeLookup.getOrThrow(Biomes.FOREST));
        map.put((short) 11, biomeLookup.getOrThrow(Biomes.FROZEN_RIVER));     // NEW
        map.put((short) 15, biomeLookup.getOrThrow(Biomes.TAIGA));
        map.put((short) 16, biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA));
        map.put((short) 17, biomeLookup.getOrThrow(Biomes.SAVANNA));
        map.put((short) 19, biomeLookup.getOrThrow(Biomes.WINDSWEPT_HILLS));
        map.put((short) 23, biomeLookup.getOrThrow(Biomes.JUNGLE));
        map.put((short) 26, biomeLookup.getOrThrow(Biomes.BADLANDS));
        map.put((short) 29, biomeLookup.getOrThrow(Biomes.MEADOW));
        map.put((short) 31, biomeLookup.getOrThrow(Biomes.GROVE));
        map.put((short) 32, biomeLookup.getOrThrow(Biomes.SNOWY_SLOPES));
        map.put((short) 33, biomeLookup.getOrThrow(Biomes.FROZEN_PEAKS));
        map.put((short) 35, biomeLookup.getOrThrow(Biomes.STONY_PEAKS));
        map.put((short) 41, biomeLookup.getOrThrow(Biomes.WARM_OCEAN));
        map.put((short) 44, biomeLookup.getOrThrow(Biomes.OCEAN));
        map.put((short) 46, biomeLookup.getOrThrow(Biomes.COLD_OCEAN));
        map.put((short) 48, biomeLookup.getOrThrow(Biomes.FROZEN_OCEAN));

        // ── Extended vanilla biomes ───────────────────────────────────────────
        map.put((short) 50, biomeLookup.getOrThrow(Biomes.BIRCH_FOREST));
        map.put((short) 51, biomeLookup.getOrThrow(Biomes.DARK_FOREST));
        map.put((short) 52, biomeLookup.getOrThrow(Biomes.FLOWER_FOREST));    // NEW in classifier
        map.put((short) 53, biomeLookup.getOrThrow(Biomes.CHERRY_GROVE));     // NEW in classifier
        map.put((short) 54, biomeLookup.getOrThrow(Biomes.MANGROVE_SWAMP));
        map.put((short) 55, biomeLookup.getOrThrow(Biomes.BAMBOO_JUNGLE));
        map.put((short) 56, biomeLookup.getOrThrow(Biomes.SPARSE_JUNGLE));
        map.put((short) 57, biomeLookup.getOrThrow(Biomes.WINDSWEPT_FOREST));
        map.put((short) 58, biomeLookup.getOrThrow(Biomes.WINDSWEPT_SAVANNA));
        map.put((short) 59, biomeLookup.getOrThrow(Biomes.SNOWY_BEACH));      // NEW in classifier
        map.put((short) 60, biomeLookup.getOrThrow(Biomes.BEACH));            // NEW in classifier
        map.put((short) 61, biomeLookup.getOrThrow(Biomes.STONY_SHORE));      // NEW in classifier
        map.put((short) 62, biomeLookup.getOrThrow(Biomes.OLD_GROWTH_PINE_TAIGA));
        map.put((short) 63, biomeLookup.getOrThrow(Biomes.OLD_GROWTH_SPRUCE_TAIGA));
        map.put((short) 64, biomeLookup.getOrThrow(Biomes.OLD_GROWTH_BIRCH_FOREST));
        map.put((short) 65, biomeLookup.getOrThrow(Biomes.SAVANNA_PLATEAU));

        // ── Cave biomes (sentinel IDs from BiomeClassifier) ───────────────────
        // These are never stored in the biomeIds grid; they are returned on-the-fly
        // in getNoiseBiome() based on the Y coordinate.
        map.put(BiomeClassifier.LUSH_CAVES,      biomeLookup.getOrThrow(Biomes.LUSH_CAVES));
        map.put(BiomeClassifier.DRIPSTONE_CAVES, biomeLookup.getOrThrow(Biomes.DRIPSTONE_CAVES));
        map.put(BiomeClassifier.DEEP_DARK,       biomeLookup.getOrThrow(Biomes.DEEP_DARK));

        biomeIdMap = map;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // collectPossibleBiomes — Cobblemon reads this at world load
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        return biomeIdMap.values().stream().distinct();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getNoiseBiome — called for every quart (4×4 block column, every Y level)
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Returns the biome for the given quart-coordinates.
     *
     * Surface biome:  fetched from the heightmap tile (as before).
     * Underground:    determined by how far below the heightmap surface we are.
     *
    * Depth model (blocks below converted surface height):
    *   blockY >= surfaceY        -> surface biome
    *   blockY < surfaceY         -> LUSH_CAVES or DRIPSTONE_CAVES
    *   depth >= DEEP_DARK_DEPTH  -> DEEP_DARK (only below sea level)
     *
     * This means a cave at y=100 inside a mountain that is 3000 m tall is still
     * considered "shallow" relative to the surface, while the same absolute y
     * under a sea-level plain is properly deep.
     */
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();
        Holder<Biome> defaultBiome = biomeIdMap.get((short) 1); // PLAINS fallback

        // x, y, z are quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockY = QuartPos.toBlock(y);
        int blockZ = QuartPos.toBlock(z);

        int tileSize  = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = blockX >> tileShift;
        int tileZ = blockZ >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX   = blockStartX + tileSize;
        int blockEndZ   = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);

        if (data == null || data.biomeIds == null) return defaultBiome;

        int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));

        short surfaceBiomeId = data.biomeIds[localZ][localX];

        // Underground routing
        // We only assign cave biomes when we are strictly below the surface.
        // surfaceY is the heightmap value converted to Minecraft block Y.
        //
        // Rules:
        //   blockY >= surfaceY -> surface biome
        //   blockY < surfaceY  -> underground; assign Lush or Dripstone Caves
        //   depthBelow >= DEEP_DARK_DEPTH AND blockY < 0 -> Deep Dark
        //     (Deep Dark is only below sea level; tall mountains can be 160+ blocks deep
        //      while still at positive Y, so we need the extra blockY < 0 guard)
        //
        // When heightmap is unavailable (tile not loaded), we return the surface biome
        // as a safe fallback rather than guessing with absolute-Y thresholds.
        // This prevents cave biomes appearing above ground on unloaded tiles.

        short[] heightmapRow = (data.heightmap != null && localZ < data.heightmap.length)
                ? data.heightmap[localZ] : null;

        if (heightmapRow != null && localX < heightmapRow.length) {
            int surfaceY   = HeightConverter.convertToMinecraftHeight(heightmapRow[localX]);
            int depthBelow = surfaceY - blockY; // positive = underground

            if (depthBelow > 0) {
                // Deep Dark: must be both deep below the surface AND below sea level (y < 0)
                if (depthBelow >= DEEP_DARK_DEPTH && blockY < 0) {
                    return biomeIdMap.get(BiomeClassifier.DEEP_DARK);
                }

                // Lush / Dripstone: underground at any Y is fine.
                // A cave opening in a mountainside at y=200 is still correctly
                // underground relative to the surface above it.
                short caveBiomeId = chooseMidCaveBiome(surfaceBiomeId, depthBelow);
                Holder<Biome> caveHolder = biomeIdMap.get(caveBiomeId);
                if (caveHolder != null) return caveHolder;
            }
        }
        // Heightmap not loaded or cave conditions not met: return surface biome.

        // Surface biome
        Holder<Biome> surfaceHolder = biomeIdMap.get(surfaceBiomeId);
        return surfaceHolder != null ? surfaceHolder : defaultBiome;
    }
    /**
     * Choose between LUSH_CAVES and DRIPSTONE_CAVES based on the surface biome.
     *
     * Lush biomes (high humidity) → LUSH_CAVES
     * Dry/arid biomes → DRIPSTONE_CAVES
     * Everything else alternates with a simple depth-based stripe
     *
     * This matches vanilla logic: lush caves prefer humid surface biomes,
     * dripstone caves prefer dry ones.
     */
    private static short chooseMidCaveBiome(short surfaceBiomeId, int depthBelow) {
        // Biomes that indicate high surface humidity → lush caves below
        switch (surfaceBiomeId) {
            case BiomeClassifier.JUNGLE:
            case BiomeClassifier.BAMBOO_JUNGLE:
            case BiomeClassifier.SPARSE_JUNGLE:
            case BiomeClassifier.DARK_FOREST:
            case BiomeClassifier.MANGROVE_SWAMP:
            case BiomeClassifier.SWAMP:
            case BiomeClassifier.OLD_GROWTH_SPRUCE_TAIGA:
            case BiomeClassifier.OLD_GROWTH_BIRCH_FOREST:
            case BiomeClassifier.OLD_GROWTH_PINE_TAIGA:
            case BiomeClassifier.WINDSWEPT_FOREST:
            case BiomeClassifier.FOREST:
            case BiomeClassifier.BIRCH_FOREST:
            case BiomeClassifier.FLOWER_FOREST:
            case BiomeClassifier.CHERRY_GROVE:
                return BiomeClassifier.LUSH_CAVES;

            // Dry biomes → dripstone
            case BiomeClassifier.DESERT:
            case BiomeClassifier.BADLANDS:
            case BiomeClassifier.SAVANNA:
            case BiomeClassifier.SAVANNA_PLATEAU:
            case BiomeClassifier.WINDSWEPT_SAVANNA:
            case BiomeClassifier.STONY_PEAKS:
            case BiomeClassifier.STONY_SHORE:
                return BiomeClassifier.DRIPSTONE_CAVES;

            // Neutral / snowy — alternate based on depth parity for natural variation
            default:
                return (depthBelow % 60 < 30)
                        ? BiomeClassifier.LUSH_CAVES
                        : BiomeClassifier.DRIPSTONE_CAVES;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // findBiome overrides (returning null is fine — vanilla handles the fallback)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
            BlockPos origin, int radius,
            int horizontalBlockCheckInterval, int verticalBlockCheckInterval,
            Predicate<Holder<Biome>> predicate,
            Climate.Sampler noiseSampler, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(
            int x, int y, int z, int radius,
            int blockCheckInterval, Predicate<Holder<Biome>> predicate,
            RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return null;
    }
}