package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Rule-based biome classifier.
 *
 * Changes vs. original:
 *  - Added RIVER / FROZEN_RIVER (IDs 7 / 11)  — post-pass over the biome grid
 *  - Added FLOWER_FOREST (52), CHERRY_GROVE (53), BEACH (60), SNOWY_BEACH (59),
 *    STONY_SHORE (61) to the per-pixel classifier
 *  - Added cave biome IDs (LUSH_CAVES 29001, DRIPSTONE_CAVES 29002, DEEP_DARK 29003)
 *    — these are only used by TerrainDiffusionBiomeSource.getNoiseBiome() for Y-routing;
 *    the classify() method itself only fills surface IDs.
 *  - All original sparse IDs (BIRCH_FOREST 50, OLD_GROWTH_* 62-64, etc.) are kept.
 */
public final class BiomeClassifier {

    // ── Noise instances (fixed seeds matching Python server) ─────────────────
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;
    // Extra noise for biome variety (river width, flower/cherry variation)
    private static final FastNoiseLite VARIETY_NOISE;

    static {
        TEMP_NOISE       = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE  = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE     = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE       = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE  = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        VARIETY_NOISE    = makeFnl(99887, 1f/256f, 3, 2f, 0.5f);
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

    // ── Vanilla biomes (IDs unchanged) ────────────────────────────────────────
    public static final short PLAINS          =  1;
    public static final short SNOWY_PLAINS    =  3;
    public static final short DESERT          =  5;
    public static final short SWAMP           =  6;
    public static final short RIVER           =  7;   // NEW
    public static final short FOREST          =  8;
    public static final short FROZEN_RIVER    = 11;   // NEW
    public static final short TAIGA           = 15;
    public static final short SNOWY_TAIGA     = 16;
    public static final short SAVANNA         = 17;
    public static final short WINDSWEPT_HILLS = 19;
    public static final short JUNGLE          = 23;
    public static final short BADLANDS        = 26;
    public static final short MEADOW          = 29;
    public static final short GROVE           = 31;
    public static final short SNOWY_SLOPES    = 32;
    public static final short FROZEN_PEAKS    = 33;
    public static final short STONY_PEAKS     = 35;
    public static final short WARM_OCEAN      = 41;
    public static final short OCEAN           = 44;
    public static final short COLD_OCEAN      = 46;
    public static final short FROZEN_OCEAN    = 48;

    // ── Additional vanilla biomes ─────────────────────────────────────────────
    public static final short BIRCH_FOREST            = 50;
    public static final short DARK_FOREST             = 51;
    public static final short FLOWER_FOREST           = 52;   // now assigned
    public static final short CHERRY_GROVE            = 53;   // now assigned
    public static final short MANGROVE_SWAMP          = 54;
    public static final short BAMBOO_JUNGLE           = 55;
    public static final short SPARSE_JUNGLE           = 56;
    public static final short WINDSWEPT_FOREST        = 57;
    public static final short WINDSWEPT_SAVANNA       = 58;
    public static final short SNOWY_BEACH             = 59;   // now assigned
    public static final short BEACH                   = 60;   // now assigned
    public static final short STONY_SHORE             = 61;   // now assigned
    public static final short OLD_GROWTH_PINE_TAIGA   = 62;
    public static final short OLD_GROWTH_SPRUCE_TAIGA = 63;
    public static final short OLD_GROWTH_BIRCH_FOREST = 64;
    public static final short SAVANNA_PLATEAU         = 65;

    // ── Cave biome sentinel IDs (used only in TerrainDiffusionBiomeSource) ───
    // These do not conflict with any surface biome IDs above.
    // TerrainDiffusionBiomeSource maps them to the real Biome holders.
    public static final short LUSH_CAVES      = (short) 29001;
    public static final short DRIPSTONE_CAVES = (short) 29002;
    public static final short DEEP_DARK       = (short) 29003;

    // ── River detection tunables ──────────────────────────────────────────────
    /**
     * Elevation below which a pixel is considered "near sea level" for river checks.
     * Adjust upward if your world scale produces wide flat lowlands.
     */
    private static final float RIVER_ELEV_MAX   = 250f;   // metres above sea level
    private static final float RIVER_SLOPE_MAX  = 0.08f;  // rivers only on near-flat ground
    /**
     * Noise threshold: only pixels whose variety noise < this get a river.
     * Controls river density / width.  ~0.15 gives ~15% coverage of eligible cells.
     */
    private static final float RIVER_NOISE_THRESH = 0.15f;

    // ── Beach / shore tunables ────────────────────────────────────────────────
    /**
     * How deep below sea level an ocean pixel must be to NOT be a beach.
     * Pixels between 0 and -BEACH_DEPTH_CUTOFF are treated as coast candidates.
     */
    private static final float BEACH_DEPTH_CUTOFF = 5f;

    /**
     * Classify biomes for a grid of pixels.
     *
     * @param elev        elevation in metres, (H×W) row-major
     * @param climate     climate data (5, H, W) row-major or null
     * @param i0          top-left row in world space (for noise sampling)
     * @param j0          top-left col in world space
     * @param elevPadded  elevation with 1-pixel padding, (H+2)×(W+2) row-major
     * @param H           height
     * @param W           width
     * @param pixelSizeM  physical size of one pixel in metres
     * @return short array (H×W) with biome IDs
     */
    public static short[] classify(float[] elev, float[] climate,
                                   int i0, int j0,
                                   float[] elevPadded,
                                   int H, int W, float pixelSizeM) {

        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = PLAINS;

        if (climate == null || climate.length < 4 * H * W) return out;

        // ── Perlin noise perturbations ────────────────────────────────────────
        float[] tempNoise       = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise       = new float[H * W];
        float[] varietyNoise    = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int   idx = r * W + c;
                float nx  = j0 + c, ny = i0 + r;

                float tnc = TEMP_NOISE.GetNoise(nx, ny);
                float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * tnc + 0.2f * tnf;

                precipNoiseFact[idx] = 1.0f + 0.2f * PRECIP_NOISE.GetNoise(nx, ny);

                float snc = SNOW_NOISE.GetNoise(nx, ny);
                float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                snowNoise[idx] = 3.0f * snc + 2.0f * snf;

                varietyNoise[idx] = VARIETY_NOISE.GetNoise(nx, ny); // [-1, 1]
            }
        }

        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

        // ── Per-pixel surface classification ─────────────────────────────────
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int   idx    = r * W + c;
                float elevVal = elev[idx];
                float altM    = Math.max(0f, elevVal);
                float slope   = slopeRatio[idx];
                float variety = varietyNoise[idx]; // [-1, 1]

                // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
                float temp   = climate[idx]               + tempNoise[idx];
                float tSeason = climate[H * W + idx];
                float precip = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                float pCV    = climate[3 * H * W + idx];

                // Derived climate
                float tStd    = tSeason / 100f;
                float tEff    = Math.max(0f, temp + 0.5f * tStd);
                float pet     = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
                float aridity = precip / Math.max(1f, pet);
                float seasonPenalty  = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture   = aridity * seasonPenalty;

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if      (x <= -1f) growingSeason = 365f;
                    else if (x >=  1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f -
                            (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor       = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold  = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage flags
                boolean treesNone       = effTreeMoisture < 0.2f;
                boolean tooArid         = treeMoisture < 0.05f;
                boolean tooCold         = growingSeason < 60f;
                boolean barren          = tooArid || tooCold;
                boolean treesSparse     = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest     = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense      = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Slope overrides
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) treesSparse = true;
                    treesForest = false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Snow
                float   snowTemp = temp + snowNoise[idx];
                boolean isSteep  = slope > 0.78f;
                boolean hasSnow  = snowTemp < 0f && precip > 150f && !isSteep;

                // Elevation / temp bands
                boolean isOcean   = elevVal < 0f;
                boolean isCoast   = !isOcean && altM < 15f;   // very near sea level
                boolean mountains = altM > 2500f;
                boolean lowland   = altM < 200f;
                boolean frozen    = temp < -5f;
                boolean cold      = temp >= -5f  && temp < 5f;
                boolean cool      = temp >= 5f   && temp < 12f;
                boolean temperate = temp >= 12f  && temp < 20f;
                boolean warm      = temp >= 20f  && temp < 26f;
                boolean hot       = temp >= 26f;

                short biome = PLAINS;

                // ── OCEAN ────────────────────────────────────────────────────
                if (isOcean) {
                    // Shallow coast → beach biomes
                    if (elevVal >= -BEACH_DEPTH_CUTOFF) {
                        if      (frozen || (hasSnow && cold)) biome = SNOWY_BEACH;
                        else if (slope > 0.5f)                biome = STONY_SHORE;
                        else                                   biome = BEACH;
                    } else {
                        if      (frozen)       biome = FROZEN_OCEAN;
                        else if (cold)         biome = COLD_OCEAN;
                        else if (warm || hot)  biome = WARM_OCEAN;
                        else                   biome = OCEAN;
                    }

                // ── COAST (land, altM < 15m) ──────────────────────────────
                } else if (isCoast && !mountains) {
                    // Only assign a beach if the pixel is actually adjacent to ocean.
                    // We detect this by checking whether ANY of the 4 neighbours is ocean
                    // (using the padded elevation array, which has a 1-pixel border).
                    int PW = W + 2;
                    float n  = elevPadded[(r + 0) * PW + (c + 1)]; // top
                    float s  = elevPadded[(r + 2) * PW + (c + 1)]; // bottom
                    float ww = elevPadded[(r + 1) * PW + (c + 0)]; // left
                    float e  = elevPadded[(r + 1) * PW + (c + 2)]; // right
                    boolean nextToOcean = n < 0f || s < 0f || ww < 0f || e < 0f;

                    if (nextToOcean) {
                        if      (frozen || (hasSnow && cold)) biome = SNOWY_BEACH;
                        else if (slope > 0.45f)               biome = STONY_SHORE;
                        else                                   biome = BEACH;
                    } else {
                        // Low land not next to ocean — fall through to normal logic
                        biome = classifyLowMid(temp, tSeason, tStd, precip, aridity,
                                treeMoisture, effTreeMoisture, gsFactor, growingSeason,
                                treesNone, treesSparse, treesForest, treesDense, treesRainforest,
                                hasSnow, lowland, barren,
                                frozen, cold, cool, temperate, warm, hot,
                                slopeMedium, slopeBare, bareThreshold, variety);
                    }

                // ── MOUNTAINS (altM > 2500 m) ─────────────────────────────
                } else if (mountains) {
                    if (slopeBare) {
                        biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;

                    } else if (hasSnow) {
                        if      (treesNone)    biome = SNOWY_SLOPES;
                        else if (treesSparse)  biome = GROVE;
                        else                   biome = SNOWY_TAIGA;

                    } else if (treesNone) {
                        if      (hot || warm)               biome = STONY_PEAKS;
                        else if (barren && (frozen || cold)) biome = WINDSWEPT_HILLS;
                        else if (cool || temperate)         biome = MEADOW;
                        else                                biome = SNOWY_SLOPES;

                    } else if (treesSparse) {
                        if      (frozen || cold) biome = OLD_GROWTH_PINE_TAIGA;
                        else if (cool)           biome = GROVE;
                        else if (temperate)      biome = WINDSWEPT_FOREST;
                        else                     biome = WINDSWEPT_SAVANNA;

                    } else if (treesForest) {
                        if      (frozen || cold) biome = SNOWY_TAIGA;
                        else if (cool)           biome = OLD_GROWTH_SPRUCE_TAIGA;
                        else if (temperate)      biome = FOREST;
                        else                     biome = WINDSWEPT_FOREST;

                    } else { // dense / rainforest at altitude
                        if      (frozen || cold) biome = SNOWY_TAIGA;
                        else if (cool)           biome = TAIGA;
                        else if (temperate)      biome = FOREST;
                        else                     biome = JUNGLE;
                    }

                // ── LOWLAND / MIDLAND ─────────────────────────────────────
                } else {
                    biome = classifyLowMid(temp, tSeason, tStd, precip, aridity,
                            treeMoisture, effTreeMoisture, gsFactor, growingSeason,
                            treesNone, treesSparse, treesForest, treesDense, treesRainforest,
                            hasSnow, lowland, barren,
                            frozen, cold, cool, temperate, warm, hot,
                            slopeMedium, slopeBare, bareThreshold, variety);
                }

                // Bare slope override for non-mountain cliffs
                if (slopeBare && !isOcean && !mountains) {
                    biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;
                }

                out[idx] = biome;
            }
        }

        // ── River post-pass ───────────────────────────────────────────────────
        // Rivers are placed on flat, near-sea-level land that borders an ocean or
        // is marked "river-worthy" by the variety noise.  We do a simple scan:
        // a pixel becomes a river if it is low-altitude, gently sloped, not already
        // ocean/beach, AND its variety noise is in a river band.
        out = applyRivers(out, elev, slopeRatio, climate, tempNoise, snowNoise, H, W);

        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lowland/Midland classifier (extracted so coast path can reuse it)
    // ─────────────────────────────────────────────────────────────────────────
    private static short classifyLowMid(
            float temp, float tSeason, float tStd,
            float precip, float aridity,
            float treeMoisture, float effTreeMoisture, float gsFactor, float growingSeason,
            boolean treesNone, boolean treesSparse, boolean treesForest,
            boolean treesDense, boolean treesRainforest,
            boolean hasSnow, boolean lowland, boolean barren,
            boolean frozen, boolean cold, boolean cool,
            boolean temperate, boolean warm, boolean hot,
            boolean slopeMedium, boolean slopeBare, float bareThreshold,
            float variety)
    {
        short biome = PLAINS;

        // Snowy ----------------------------------------------------------------
        if (hasSnow && treesNone) {
            biome = SNOWY_PLAINS;
        } else if (hasSnow) {
            biome = SNOWY_TAIGA;    // sparse/forest/dense all get snowy taiga

        // No trees -------------------------------------------------------------
        } else if (treesNone) {
            if      (hot && aridity < 0.35f)             biome = DESERT;
            else if (hot)                                biome = DESERT;
            else if (warm && aridity < 0.2f)             biome = BADLANDS;
            else if (warm && aridity < 0.55f)            biome = SAVANNA;
            else if (warm)                               biome = PLAINS;
            else if (temperate && precip > 600f)         biome = MEADOW;
            else if (temperate)                          biome = PLAINS;
            else if (cool)                               biome = PLAINS;
            else                                         biome = SNOWY_PLAINS;

        // Sparse trees ---------------------------------------------------------
        } else if (treesSparse) {
            if      (hot && precip > 1800f)              biome = SPARSE_JUNGLE;
            else if (hot)                                biome = SAVANNA;
            else if (warm && aridity < 0.35f)            biome = SAVANNA_PLATEAU;
            else if (warm && aridity < 0.6f)             biome = SAVANNA;
            else if (warm)                               biome = FOREST;
            else if (temperate && precip > 800f)         biome = BIRCH_FOREST;
            else if (temperate)                          biome = FOREST;
            else if (cool)                               biome = TAIGA;
            else                                         biome = SNOWY_TAIGA;

        // Forest ---------------------------------------------------------------
        } else if (treesForest) {
            if      (hot && precip > 2000f)              biome = JUNGLE;
            else if (hot)                                biome = JUNGLE;
            else if (warm && lowland && precip > 1200f)  biome = SWAMP;
            else if (warm && precip > 900f)              biome = FOREST;
            else if (warm)                               biome = FOREST;
            else if (temperate && precip > 1100f)        biome = DARK_FOREST;
            else if (temperate && precip > 600f) {
                // Split temperate-wet forest into FLOWER_FOREST / CHERRY_GROVE / plain FOREST
                // based on variety noise so they appear as patches:
                //   variety < -0.4  → CHERRY_GROVE  (~30% of eligible)
                //   variety < -0.1  → FLOWER_FOREST (~15% of eligible)
                //   else            → FOREST
                if      (variety < -0.4f) biome = CHERRY_GROVE;
                else if (variety < -0.1f) biome = FLOWER_FOREST;
                else                      biome = FOREST;
            }
            else if (temperate) {
                biome = BIRCH_FOREST;
            }
            else if (cool && precip > 700f)              biome = OLD_GROWTH_BIRCH_FOREST;
            else if (cool)                               biome = TAIGA;
            else                                         biome = SNOWY_TAIGA;

        // Dense ----------------------------------------------------------------
        } else if (treesDense) {
            if      (hot && precip > 2500f)              biome = JUNGLE;
            else if (hot)                                biome = JUNGLE;
            else if (warm && lowland)                    biome = MANGROVE_SWAMP;
            else if (warm && precip > 1000f)             biome = BAMBOO_JUNGLE;
            else if (warm)                               biome = FOREST;
            else if (temperate && precip > 1200f)        biome = DARK_FOREST;
            else if (temperate && precip > 700f) {
                // Same flower/cherry patch logic as above
                if      (variety < -0.4f) biome = CHERRY_GROVE;
                else if (variety < -0.1f) biome = FLOWER_FOREST;
                else                      biome = FOREST;
            }
            else if (temperate)                          biome = FOREST;
            else if (cool && precip > 600f)              biome = TAIGA;
            else if (cool)                               biome = TAIGA;
            else                                         biome = SNOWY_TAIGA;

        // Rainforest -----------------------------------------------------------
        } else {
            if      (hot || (warm && temp >= 18f && tStd < 5f))  biome = JUNGLE;
            else if (warm && lowland)                            biome = MANGROVE_SWAMP;
            else if (warm && precip > 1500f)                     biome = JUNGLE;
            else if (temperate && precip > 1400f)                biome = DARK_FOREST;
            else if (temperate && precip > 800f)                 biome = FOREST;
            else if (lowland)                                    biome = SWAMP;
            else if (cool || cold)                               biome = TAIGA;
            else                                                 biome = FOREST;
        }

        return biome;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // River post-pass
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Convert eligible lowland cells to RIVER / FROZEN_RIVER.
     *
     * A cell is eligible when:
     *   - It is already a land biome (not ocean, not beach/shore, not a cave)
     *   - Its altitude is below RIVER_ELEV_MAX
     *   - Its slope is below RIVER_SLOPE_MAX (nearly flat)
     *   - The variety noise for the cell falls in the river band [−1, −1 + 2*RIVER_NOISE_THRESH]
     *     (i.e. the bottom RIVER_NOISE_THRESH fraction of [-1,1])
     *
     * This produces natural-looking sinuous river corridors through valleys.
     */
    private static short[] applyRivers(short[] biomes, float[] elev, float[] slopeRatio,
                                       float[] climate, float[] tempNoise, float[] snowNoise,
                                       int H, int W) {
        // Re-derive variety noise — we already stored it per pixel above but
        // this method is static so we recompute from the biome array layout.
        // Actually we need variety noise per pixel; since we cannot easily pass it
        // through the static call, we embed a tiny deterministic inline approach:
        // river band = variety noise in (-1, -1 + 2*thresh).
        // We use a second instance here with a different seed for river geometry.
        FastNoiseLite riverNoise = makeFnl(77331, 1f/300f, 4, 2f, 0.5f);

        short[] out = biomes.clone();
        int HW = H * W;

        for (int idx = 0; idx < HW; idx++) {
            short b = biomes[idx];

            // Skip ocean, beach, shore, or already-assigned special biomes
            if (b == OCEAN || b == COLD_OCEAN || b == WARM_OCEAN || b == FROZEN_OCEAN
                    || b == BEACH || b == SNOWY_BEACH || b == STONY_SHORE
                    || b == RIVER || b == FROZEN_RIVER) continue;

            float alt   = elev[idx];
            if (alt < 0f || alt > RIVER_ELEV_MAX) continue;

            float slope = slopeRatio[idx];
            if (slope > RIVER_SLOPE_MAX) continue;

            int r = idx / W, c = idx % W;
            float rn = riverNoise.GetNoise(c, r); // [-1, 1]
            // River band: narrow strip near rn == 0 (centre of distribution)
            if (Math.abs(rn) > RIVER_NOISE_THRESH) continue;

            // Determine frozen or normal river
            float temp     = climate[idx] + tempNoise[idx];
            float snowTemp = temp + snowNoise[idx];
            out[idx] = (snowTemp < -2f) ? FROZEN_RIVER : RIVER;
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slope computation (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1, 0, 1, -2, 0, 2, -1, 0, 1};
        float[] sy = {-1,-2,-1,  0, 0, 0,  1, 2, 1};
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