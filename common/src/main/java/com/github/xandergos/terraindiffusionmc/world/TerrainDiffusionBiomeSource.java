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
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Map.entry;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    private static final ResourceKey<Biome> FOREST_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "forest_sparse"));
    private static final ResourceKey<Biome> TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "taiga_sparse"));
    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "snowy_taiga_sparse"));

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));


    private HolderGetter<Biome> biomeLookup;
    private Map<Short, Holder<Biome>> biomeIdMap = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

/**
     * Tries to register a Terralith biome by its string ID.
     * If the biome is not present in the registry (Terralith datapack not loaded),
     * silently falls back to the provided vanilla BiomeKey instead.
    */

    private void putOptional(java.util.HashMap<Short, RegistryEntry<Biome>> map,
                          short id, String terralithId, RegistryKey<Biome> fallback) {
        RegistryKey<Biome> key = RegistryKey.of(RegistryKeys.BIOME,
                Identifier.of(terralithId));
        java.util.Optional<RegistryEntry.Reference<Biome>> entry = biomeLookup.getOptional(key);
        if (entry.isPresent()) {
            map.put(id, entry.get());
        } else {
            map.put(id, biomeLookup.getOrThrow(fallback));
        }
    }

    private void requireBiomeIdMap() {
        if (biomeIdMap != null) return;

        // Helper: try a Terralith biome key, fall back to a vanilla one if not registered
        java.util.HashMap<Short, RegistryEntry<Biome>> map = new java.util.HashMap<>();

            // ── Vanilla biomes (always present) ──────────────────────────────────
            map.put((short)  1, biomeLookup.getOrThrow(BiomeKeys.PLAINS));
            map.put((short)  3, biomeLookup.getOrThrow(BiomeKeys.SNOWY_PLAINS));
            map.put((short)  5, biomeLookup.getOrThrow(BiomeKeys.DESERT));
            map.put((short)  6, biomeLookup.getOrThrow(BiomeKeys.SWAMP));
            map.put((short)  8, biomeLookup.getOrThrow(BiomeKeys.FOREST));
            map.put((short) 15, biomeLookup.getOrThrow(BiomeKeys.TAIGA));
            map.put((short) 16, biomeLookup.getOrThrow(BiomeKeys.SNOWY_TAIGA));
            map.put((short) 17, biomeLookup.getOrThrow(BiomeKeys.SAVANNA));
            map.put((short) 19, biomeLookup.getOrThrow(BiomeKeys.WINDSWEPT_HILLS));
            map.put((short) 23, biomeLookup.getOrThrow(BiomeKeys.JUNGLE));
            map.put((short) 26, biomeLookup.getOrThrow(BiomeKeys.BADLANDS));
            map.put((short) 29, biomeLookup.getOrThrow(BiomeKeys.MEADOW));
            map.put((short) 31, biomeLookup.getOrThrow(BiomeKeys.GROVE));
            map.put((short) 32, biomeLookup.getOrThrow(BiomeKeys.SNOWY_SLOPES));
            map.put((short) 33, biomeLookup.getOrThrow(BiomeKeys.FROZEN_PEAKS));
            map.put((short) 35, biomeLookup.getOrThrow(BiomeKeys.STONY_PEAKS));
            map.put((short) 41, biomeLookup.getOrThrow(BiomeKeys.WARM_OCEAN));
            map.put((short) 44, biomeLookup.getOrThrow(BiomeKeys.OCEAN));
            map.put((short) 46, biomeLookup.getOrThrow(BiomeKeys.COLD_OCEAN));
            map.put((short) 48, biomeLookup.getOrThrow(BiomeKeys.FROZEN_OCEAN));

            // ── Custom TD sparse biomes ───────────────────────────────────────────
            map.put((short) 108, biomeLookup.getOrThrow(FOREST_SPARSE));
            map.put((short) 115, biomeLookup.getOrThrow(TAIGA_SPARSE));
            map.put((short) 116, biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE));

            // ── New vanilla biomes ────────────────────────────────────────────────
            map.put((short) 50, biomeLookup.getOrThrow(BiomeKeys.BIRCH_FOREST));
            map.put((short) 51, biomeLookup.getOrThrow(BiomeKeys.DARK_FOREST));
            map.put((short) 52, biomeLookup.getOrThrow(BiomeKeys.FLOWER_FOREST));
            map.put((short) 53, biomeLookup.getOrThrow(BiomeKeys.CHERRY_GROVE));
            map.put((short) 54, biomeLookup.getOrThrow(BiomeKeys.MANGROVE_SWAMP));
            map.put((short) 55, biomeLookup.getOrThrow(BiomeKeys.BAMBOO_JUNGLE));
            map.put((short) 56, biomeLookup.getOrThrow(BiomeKeys.SPARSE_JUNGLE));
            map.put((short) 57, biomeLookup.getOrThrow(BiomeKeys.WINDSWEPT_FOREST));
            map.put((short) 58, biomeLookup.getOrThrow(BiomeKeys.WINDSWEPT_SAVANNA));
            map.put((short) 59, biomeLookup.getOrThrow(BiomeKeys.SNOWY_BEACH));
            map.put((short) 60, biomeLookup.getOrThrow(BiomeKeys.BEACH));
            map.put((short) 61, biomeLookup.getOrThrow(BiomeKeys.STONY_SHORE));
            map.put((short) 62, biomeLookup.getOrThrow(BiomeKeys.OLD_GROWTH_PINE_TAIGA));
            map.put((short) 63, biomeLookup.getOrThrow(BiomeKeys.OLD_GROWTH_SPRUCE_TAIGA));
            map.put((short) 64, biomeLookup.getOrThrow(BiomeKeys.OLD_GROWTH_BIRCH_FOREST));
            map.put((short) 65, biomeLookup.getOrThrow(BiomeKeys.SAVANNA_PLATEAU));

            // ── Terralith biomes (safe optional lookup — falls back to vanilla) ───
            // If Terralith datapack is not loaded these IDs simply resolve to their
            // fallback, so the same jar works with or without Terralith.
            putOptional(map, (short) 200, "terralith:yellowstone",          BiomeKeys.BADLANDS);
            putOptional(map, (short) 201, "terralith:siberian_taiga",       BiomeKeys.TAIGA);
            putOptional(map, (short) 202, "terralith:moonlight_grove",      BiomeKeys.TAIGA);
            putOptional(map, (short) 203, "terralith:lavender_forest",      BiomeKeys.FOREST);
            putOptional(map, (short) 204, "terralith:sakura_grove",         BiomeKeys.CHERRY_GROVE);
            putOptional(map, (short) 205, "terralith:blooming_valley",      BiomeKeys.FLOWER_FOREST);
            putOptional(map, (short) 206, "terralith:alpine_grove",         BiomeKeys.GROVE);
            putOptional(map, (short) 207, "terralith:ice_marsh",            BiomeKeys.SNOWY_TAIGA);
            putOptional(map, (short) 208, "terralith:snowy_maple_forest",   BiomeKeys.SNOWY_TAIGA);
            putOptional(map, (short) 209, "terralith:arid_highlands",       BiomeKeys.SAVANNA);
            putOptional(map, (short) 210, "terralith:savanna_badlands",     BiomeKeys.BADLANDS);
            putOptional(map, (short) 211, "terralith:hot_shrubland",        BiomeKeys.SAVANNA);
            putOptional(map, (short) 212, "terralith:desert_canyon",        BiomeKeys.DESERT);
            putOptional(map, (short) 213, "terralith:lush_desert",          BiomeKeys.FOREST);
            putOptional(map, (short) 214, "terralith:ancient_sands",        BiomeKeys.DESERT);
            putOptional(map, (short) 215, "terralith:highlands_plains",     BiomeKeys.PLAINS);
            putOptional(map, (short) 216, "terralith:brushland",            BiomeKeys.SAVANNA);
            putOptional(map, (short) 217, "terralith:rocky_mountains",      BiomeKeys.STONY_PEAKS);
            putOptional(map, (short) 218, "terralith:emerald_peaks",        BiomeKeys.FOREST);
            putOptional(map, (short) 219, "terralith:scarlet_mountains",    BiomeKeys.FOREST);
            putOptional(map, (short) 220, "terralith:amethyst_rainforest",  BiomeKeys.JUNGLE);
            putOptional(map, (short) 221, "terralith:underground_jungle",   BiomeKeys.JUNGLE);
            putOptional(map, (short) 222, "terralith:orchid_swamp",         BiomeKeys.SWAMP);
            putOptional(map, (short) 223, "terralith:warm_ocean",           BiomeKeys.WARM_OCEAN);
            putOptional(map, (short) 224, "terralith:lush_stacks",          BiomeKeys.WARM_OCEAN);

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

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = blockX >> tileShift;
        int tileZ = blockZ >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            Holder<Biome> entry = biomeIdMap.get(data.biomeIds[localZ][localX]);
            if (entry != null) return entry;
        }

        return defaultEntry;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval, Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return null;
    }
}
