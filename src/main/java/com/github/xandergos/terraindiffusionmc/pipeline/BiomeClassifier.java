package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Rule-based biome classifier port of _classify_biome in minecraft_api.py.
 *
 * <p>Uses fixed-seed FastNoiseLite instances for climate and elevation noise perturbations.
 * Biome IDs match the Python server's _BIOME_ID mapping.
 */
public final class BiomeClassifier {

    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;

    static {
        TEMP_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }


    // ── Existing vanilla biomes (IDs unchanged) ─────────────────────────────
    static final short PLAINS = 1, SNOWY_PLAINS = 3, DESERT = 5, SWAMP = 6;
    static final short FOREST = 8, TAIGA = 15, SNOWY_TAIGA = 16, SAVANNA = 17;
    static final short WINDSWEPT_HILLS = 19, JUNGLE = 23, BADLANDS = 26, MEADOW = 29;
    static final short GROVE = 31, SNOWY_SLOPES = 32, FROZEN_PEAKS = 33, STONY_PEAKS = 35;
    static final short WARM_OCEAN = 41, OCEAN = 44, COLD_OCEAN = 46, FROZEN_OCEAN = 48;
    static final short FOREST_SPARSE = 108, TAIGA_SPARSE = 115, SNOWY_TAIGA_SPARSE = 116;

    // ── New vanilla biomes ───────────────────────────────────────────────────
    static final short BIRCH_FOREST = 50, DARK_FOREST = 51, FLOWER_FOREST = 52;
    static final short CHERRY_GROVE = 53, MANGROVE_SWAMP = 54, BAMBOO_JUNGLE = 55;
    static final short SPARSE_JUNGLE = 56, WINDSWEPT_FOREST = 57, WINDSWEPT_SAVANNA = 58;
    static final short SNOWY_BEACH = 59, BEACH = 60, STONY_SHORE = 61;
    static final short OLD_GROWTH_PINE_TAIGA = 62, OLD_GROWTH_SPRUCE_TAIGA = 63;
    static final short OLD_GROWTH_BIRCH_FOREST = 64, SAVANNA_PLATEAU = 65;

    // ── Terralith biomes (optional — only used if datapack is loaded) ────────
    static final short T_YELLOWSTONE = 200, T_SIBERIAN_TAIGA = 201, T_MOONLIGHT_GROVE = 202;
    static final short T_LAVENDER_FOREST = 203, T_SAKURA_GROVE = 204, T_BLOOMING_VALLEY = 205;
    static final short T_ALPINE_GROVE = 206, T_ICE_MARSH = 207, T_SNOWY_MAPLE_FOREST = 208;
    static final short T_ARID_HIGHLANDS = 209, T_SAVANNA_BADLANDS = 210, T_HOT_SHRUBLAND = 211;
    static final short T_DESERT_CANYON = 212, T_LUSH_DESERT = 213, T_ANCIENT_SANDS = 214;
    static final short T_HIGHLANDS_PLAINS = 215, T_BRUSHLAND = 216, T_ROCKY_MOUNTAINS = 217;
    static final short T_EMERALD_PEAKS = 218, T_SCARLET_MOUNTAINS = 219;
    static final short T_AMETHYST_RAINFOREST = 220, T_UNDERGROUND_JUNGLE = 221;
    static final short T_ORCHID_SWAMP = 222, T_WARM_OCEAN_T = 223, T_LUSH_STACKS = 224;

    /**
     * Classify biomes for a grid of pixels.
     *
     * @param elev       elevation in meters, (H, W) row-major
     * @param climate    climate data (5, H, W) row-major or null
     * @param i0         top-left row in world space (for noise sampling)
     * @param j0         top-left col in world space
     * @param elevPadded elevation with 1-pixel padding, (H+2, W+2) row-major
     * @param H          height
     * @param W          width
     * @param pixelSizeM physical size of one pixel in meters
     * @return short array (H, W) with biome IDs
     */
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM) {
        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = PLAINS;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        // Generate Perlin noise perturbations
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nx = j0 + c, ny = i0 + r;
                float tnc = TEMP_NOISE.GetNoise(nx, ny);
                float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * tnc + 0.2f * tnf;

                float pn = PRECIP_NOISE.GetNoise(nx, ny);
                precipNoiseFact[idx] = 1.0f + 0.2f * pn;

                float snc = SNOW_NOISE.GetNoise(nx, ny);
                float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                snowNoise[idx] = 3.0f * snc + 2.0f * snf;
            }
        }

        // Compute slope from padded elevation using Sobel (divide by pixelSizeM for ratio)
        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

        // Process per-pixel
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elevVal   = elev[idx];
                float altM      = Math.max(0f, elevVal);
                float slope     = slopeRatio[idx];

                // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
                float temp     = climate[idx] + tempNoise[idx];
                float tSeason  = climate[H * W + idx];
                float precip   = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                float pCV      = climate[3 * H * W + idx];

                // Derived climate variables
                float tStd     = tSeason / 100f;
                float tEff     = Math.max(0f, temp + 0.5f * tStd);
                float pet      = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
                float aridity  = precip / Math.max(1f, pet);
                float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture = aridity * seasonPenalty;

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if (x <= -1f) growingSeason = 365f;
                    else if (x >= 1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                // Slope-dependent bare threshold
                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage classification
                boolean treesNone = effTreeMoisture < 0.2f;
                boolean tooArid   = treeMoisture < 0.05f;
                boolean tooCold   = growingSeason < 60f;
                boolean barren    = tooArid || tooCold;
                boolean treesSparse    = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest    = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense     = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Slope overrides
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) { treesSparse = true; }
                    treesForest = treesForest && false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Snow classification
                float snowTemp = temp + snowNoise[idx];
                boolean isSteep = slope > 0.78f;
                boolean hasSnow = snowTemp < 0f && precip > 150f && !isSteep;

                // Elevation/temp bands
                boolean isOcean   = elevVal < 0f;
                boolean mountains = altM > 2500f;
                boolean lowland   = altM < 200f;
                boolean frozen    = temp < -5f;
                boolean cold      = temp >= -5f && temp < 5f;
                boolean cool      = temp >= 5f  && temp < 12f;
                boolean temperate = temp >= 12f && temp < 20f;
                boolean warm      = temp >= 20f && temp < 26f;
                boolean hot       = temp >= 26f;

                short biome = PLAINS;

                // ── OCEAN ──────────────────────────────────────────────────────────────
                if (isOcean) {
                    if (frozen)           biome = FROZEN_OCEAN;
                    else if (cold)        biome = COLD_OCEAN;
                    else if (warm || hot) biome = WARM_OCEAN;
                    else                  biome = OCEAN;

                // ── MOUNTAINS (altM > 2500m) ────────────────────────────────────────────
                } else if (mountains) {
                    if (slopeBare) {
                        biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;

                    } else if (hasSnow) {
                        if (treesNone)                       biome = SNOWY_SLOPES;
                        else if (treesSparse)                biome = T_ALPINE_GROVE;      // Terralith → fallback GROVE
                        else if (treesForest)                biome = SNOWY_TAIGA_SPARSE;
                        else                                 biome = SNOWY_TAIGA;

                    } else if (treesNone) {
                        if (hot || warm)                     biome = T_ROCKY_MOUNTAINS;   // → STONY_PEAKS
                        else if (barren && (frozen || cold)) biome = WINDSWEPT_HILLS;
                        else if (cool || temperate)          biome = MEADOW;
                        else                                 biome = SNOWY_SLOPES;

                    } else if (treesSparse) {
                        if (frozen || cold)                  biome = OLD_GROWTH_PINE_TAIGA;
                        else if (cool)                       biome = T_ALPINE_GROVE;      // → GROVE
                        else if (temperate)                  biome = WINDSWEPT_FOREST;
                        else                                 biome = WINDSWEPT_SAVANNA;

                    } else if (treesForest) {
                        if (frozen || cold)                  biome = SNOWY_TAIGA;
                        else if (cool)                       biome = OLD_GROWTH_SPRUCE_TAIGA;
                        else if (temperate)                  biome = T_EMERALD_PEAKS;     // → FOREST
                        else                                 biome = WINDSWEPT_FOREST;

                    } else { // dense / rainforest at altitude
                        if (frozen || cold)                  biome = SNOWY_TAIGA;
                        else if (cool)                       biome = TAIGA;
                        else if (temperate)                  biome = T_SCARLET_MOUNTAINS; // → FOREST
                        else                                 biome = JUNGLE;
                    }

                // ── LOWLAND / MIDLAND ──────────────────────────────────────────────────
                } else {

                    // Snowy
                    if (hasSnow && treesNone) {
                        biome = SNOWY_PLAINS;
                    } else if (hasSnow) {
                        if (treesSparse)                     biome = T_SNOWY_MAPLE_FOREST; // → SNOWY_TAIGA
                        else if (treesForest)                biome = SNOWY_TAIGA_SPARSE;
                        else                                 biome = SNOWY_TAIGA;

                    // No trees
                    } else if (treesNone) {
                        if (hot && aridity < 0.15f)          biome = T_ANCIENT_SANDS;     // → BADLANDS/DESERT
                        else if (hot && aridity < 0.35f)     biome = T_DESERT_CANYON;     // → DESERT
                        else if (hot)                        biome = DESERT;
                        else if (warm && aridity < 0.2f)     biome = T_SAVANNA_BADLANDS;  // → BADLANDS
                        else if (warm && aridity < 0.45f)    biome = T_ARID_HIGHLANDS;    // → SAVANNA
                        else if (warm && aridity < 0.65f)    biome = T_BRUSHLAND;         // → SAVANNA/PLAINS
                        else if (warm)                       biome = T_HIGHLANDS_PLAINS;  // → PLAINS
                        else if (temperate && precip > 600f) biome = MEADOW;
                        else if (temperate)                  biome = PLAINS;
                        else if (cool)                       biome = PLAINS;
                        else                                 biome = SNOWY_PLAINS;

                    // Sparse trees
                    } else if (treesSparse) {
                        if (hot && precip > 1800f)           biome = SPARSE_JUNGLE;
                        else if (hot)                        biome = T_HOT_SHRUBLAND;     // → SAVANNA
                        else if (warm && aridity < 0.35f)    biome = SAVANNA_PLATEAU;
                        else if (warm && aridity < 0.6f)     biome = SAVANNA;
                        else if (warm)                       biome = FOREST_SPARSE;
                        else if (temperate && precip > 800f) biome = BIRCH_FOREST;
                        else if (temperate)                  biome = FOREST_SPARSE;
                        else if (cool)                       biome = TAIGA_SPARSE;
                        else                                 biome = SNOWY_TAIGA_SPARSE;

                    // Forest
                    } else if (treesForest) {
                        if (hot && precip > 2000f)           biome = JUNGLE;
                        else if (hot)                        biome = JUNGLE;
                        else if (warm && lowland && precip > 1200f) biome = T_ORCHID_SWAMP; // → SWAMP
                        else if (warm && precip > 900f)      biome = T_LUSH_DESERT;       // → FOREST (wet warm)
                        else if (warm)                       biome = FOREST_SPARSE;
                        else if (temperate && precip > 1100f) biome = DARK_FOREST;
                        else if (temperate && precip > 600f) biome = FOREST;
                        else if (temperate)                  biome = BIRCH_FOREST;
                        else if (cool && precip > 700f)      biome = OLD_GROWTH_BIRCH_FOREST;
                        else if (cool)                       biome = TAIGA;
                        else                                 biome = SNOWY_TAIGA;

                    // Dense
                    } else if (treesDense) {
                        if (hot && precip > 2500f)           biome = T_AMETHYST_RAINFOREST; // → JUNGLE (magical)
                        else if (hot)                        biome = JUNGLE;
                        else if (warm && lowland)            biome = MANGROVE_SWAMP;
                        else if (warm && precip > 1000f)     biome = BAMBOO_JUNGLE;
                        else if (warm)                       biome = FOREST;
                        else if (temperate && precip > 1200f) biome = DARK_FOREST;
                        else if (temperate && precip > 700f) biome = T_LAVENDER_FOREST;   // → FOREST (floral/magical)
                        else if (temperate)                  biome = FOREST;
                        else if (cool && precip > 600f)      biome = T_MOONLIGHT_GROVE;   // → TAIGA (magical)
                        else if (cool)                       biome = TAIGA;
                        else                                 biome = SNOWY_TAIGA;

                    // Rainforest
                    } else {
                        if (hot || (warm && temp >= 18f && tStd < 5f)) biome = JUNGLE;
                        else if (warm && lowland)            biome = MANGROVE_SWAMP;
                        else if (warm && precip > 1500f)     biome = T_AMETHYST_RAINFOREST;
                        else if (temperate && precip > 1400f) biome = DARK_FOREST;
                        else if (temperate && precip > 800f) biome = T_BLOOMING_VALLEY;   // → FOREST (floral)
                        else if (lowland)                    biome = SWAMP;
                        else if (cool || cold)              biome = T_SIBERIAN_TAIGA;     // → TAIGA
                        else                                 biome = FOREST;
                    }
                }

                // Bare slope override for lowland/non-mountain cliffs
                if (slopeBare && !isOcean && !mountains) {
                    biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;
                }

                out[idx] = biome;
            }
        }
        return out;
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) padded array → (H, W) output
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1,0,1, -2,0,2, -1,0,1};
        float[] sy = {-1,-2,-1, 0,0,0, 1,2,1};
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int kr = 0; kr < 3; kr++)
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + (c + kc)];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                dx /= 8f; dy /= 8f;
                slope[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy) / pixelSizeM;
            }
        }
        return slope;
    }
}
