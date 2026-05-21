package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
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

public class TerrainDiffusionBiomeSource extends BiomeSource {

        // Custom TD biome keys removed; sparse IDs map to vanilla biomes.

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));

    private final HolderGetter<Biome> biomeLookup;
    private Map<Short, Holder<Biome>> biomeIdMap = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    /**
     * Tries to register a Terralith biome by its namespaced string ID.
     * If the biome is not present in the registry (Terralith datapack not loaded),
     * silently falls back to the provided vanilla ResourceKey instead.
     * Uses 1.21.1 API: Holder, HolderGetter, ResourceKey, ResourceLocation.
     */
    // private void putOptional(HashMap<Short, Holder<Biome>> map,
    //                      short id, String terralithId, ResourceKey<Biome> fallback) {
    //     ResourceKey<Biome> key = ResourceKey.create(
    //             Registries.BIOME, ResourceLocation.parse(terralithId));
    //     // Use getOptional from the lookup — but only if the key is already registered.
    //     // We check existence first by streaming known elements to avoid touching the frozen registry.
    //     Optional<Holder.Reference<Biome>> entry = biomeLookup.listElements()
    //             .filter(h -> h.key().equals(key))
    //             .findFirst();
    //     if (entry.isPresent()) {
    //         map.put(id, entry.get());
    //     } else {
    //         map.put(id, biomeLookup.getOrThrow(fallback));
    //     }
    // }

    private void requireBiomeIdMap() {
        if (biomeIdMap != null) return;

        HashMap<Short, Holder<Biome>> map = new HashMap<>();

        // ── Vanilla biomes (always present) ──────────────────────────────────
        map.put((short)  1, biomeLookup.getOrThrow(Biomes.PLAINS));
        map.put((short)  3, biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS));
        map.put((short)  5, biomeLookup.getOrThrow(Biomes.DESERT));
        map.put((short)  6, biomeLookup.getOrThrow(Biomes.SWAMP));
        map.put((short)  8, biomeLookup.getOrThrow(Biomes.FOREST));
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

        // ── Sparse IDs mapped to vanilla biomes ─────────────────────────────
        map.put((short) 108, biomeLookup.getOrThrow(Biomes.FOREST));
        map.put((short) 115, biomeLookup.getOrThrow(Biomes.TAIGA));
        map.put((short) 116, biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA));

        // ── New vanilla biomes ────────────────────────────────────────────────
        map.put((short) 50, biomeLookup.getOrThrow(Biomes.BIRCH_FOREST));
        map.put((short) 51, biomeLookup.getOrThrow(Biomes.DARK_FOREST));
        map.put((short) 52, biomeLookup.getOrThrow(Biomes.FLOWER_FOREST));
        map.put((short) 53, biomeLookup.getOrThrow(Biomes.CHERRY_GROVE));
        map.put((short) 54, biomeLookup.getOrThrow(Biomes.MANGROVE_SWAMP));
        map.put((short) 55, biomeLookup.getOrThrow(Biomes.BAMBOO_JUNGLE));
        map.put((short) 56, biomeLookup.getOrThrow(Biomes.SPARSE_JUNGLE));
        map.put((short) 57, biomeLookup.getOrThrow(Biomes.WINDSWEPT_FOREST));
        map.put((short) 58, biomeLookup.getOrThrow(Biomes.WINDSWEPT_SAVANNA));
        map.put((short) 59, biomeLookup.getOrThrow(Biomes.SNOWY_BEACH));
        map.put((short) 60, biomeLookup.getOrThrow(Biomes.BEACH));
        map.put((short) 61, biomeLookup.getOrThrow(Biomes.STONY_SHORE));
        map.put((short) 62, biomeLookup.getOrThrow(Biomes.OLD_GROWTH_PINE_TAIGA));
        map.put((short) 63, biomeLookup.getOrThrow(Biomes.OLD_GROWTH_SPRUCE_TAIGA));
        map.put((short) 64, biomeLookup.getOrThrow(Biomes.OLD_GROWTH_BIRCH_FOREST));
        map.put((short) 65, biomeLookup.getOrThrow(Biomes.SAVANNA_PLATEAU));

        // ── Terralith biomes (safe optional lookup — falls back to vanilla) ───
        // If Terralith datapack is not loaded, these resolve to their fallback.
        // The same jar works with OR without Terralith — no crashes, no hard dep.
        // putOptional(map, (short) 200, "terralith:yellowstone",         Biomes.BADLANDS);
        // putOptional(map, (short) 201, "terralith:siberian_taiga",      Biomes.TAIGA);
        // putOptional(map, (short) 202, "terralith:moonlight_grove",     Biomes.TAIGA);
        // putOptional(map, (short) 203, "terralith:lavender_forest",     Biomes.FOREST);
        // putOptional(map, (short) 204, "terralith:sakura_grove",        Biomes.CHERRY_GROVE);
        // putOptional(map, (short) 205, "terralith:blooming_valley",     Biomes.FLOWER_FOREST);
        // putOptional(map, (short) 206, "terralith:alpine_grove",        Biomes.GROVE);
        // putOptional(map, (short) 207, "terralith:ice_marsh",           Biomes.SNOWY_TAIGA);
        // putOptional(map, (short) 208, "terralith:snowy_maple_forest",  Biomes.SNOWY_TAIGA);
        // putOptional(map, (short) 209, "terralith:arid_highlands",      Biomes.SAVANNA);
        // putOptional(map, (short) 210, "terralith:savanna_badlands",    Biomes.BADLANDS);
        // putOptional(map, (short) 211, "terralith:hot_shrubland",       Biomes.SAVANNA);
        // putOptional(map, (short) 212, "terralith:desert_canyon",       Biomes.DESERT);
        // putOptional(map, (short) 213, "terralith:lush_desert",         Biomes.FOREST);
        // putOptional(map, (short) 214, "terralith:ancient_sands",       Biomes.DESERT);
        // putOptional(map, (short) 215, "terralith:highlands_plains",    Biomes.PLAINS);
        // putOptional(map, (short) 216, "terralith:brushland",           Biomes.SAVANNA);
        // putOptional(map, (short) 217, "terralith:rocky_mountains",     Biomes.STONY_PEAKS);
        // putOptional(map, (short) 218, "terralith:emerald_peaks",       Biomes.FOREST);
        // putOptional(map, (short) 219, "terralith:scarlet_mountains",   Biomes.FOREST);
        // putOptional(map, (short) 220, "terralith:amethyst_rainforest", Biomes.JUNGLE);
        // putOptional(map, (short) 221, "terralith:underground_jungle",  Biomes.JUNGLE);
        // putOptional(map, (short) 222, "terralith:orchid_swamp",        Biomes.SWAMP);
        // putOptional(map, (short) 223, "terralith:warm_ocean",          Biomes.WARM_OCEAN);
        // putOptional(map, (short) 224, "terralith:lush_stacks",         Biomes.WARM_OCEAN);

        biomeIdMap = map;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        return biomeIdMap.values().stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();
        Holder<Biome> defaultEntry = biomeIdMap.get((short) 1);

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
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
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            Holder<Biome> entry = biomeIdMap.get(data.biomeIds[localZ][localX]);
            if (entry != null) return entry;
        }

        return defaultEntry;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius,
            int horizontalBlockCheckInterval, int verticalBlockCheckInterval,
            Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius,
            int blockCheckInterval, Predicate<Holder<Biome>> predicate,
            RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return null;
    }
}