package com.swordfish.lemuroid.app.shared.settings

/**
 * Aspect ratio modes mirroring RetroArch's aspect_ratio enum (video_defines.h).
 *
 * [ratio] is null for CORE_PROVIDED (LibretroDroid handles internally), FULL
 * (entire GLSurfaceView), SQUARE_PIXEL (computed from core geometry at runtime),
 * and CUSTOM (user-supplied numerator/denominator). All fixed-ratio modes carry
 * the exact float value.
 */
enum class AspectRatio(val key: String, val ratio: Float?) {
    /** Let the core/LibretroDroid decide – mirrors RetroArch ASPECT_RATIO_CORE. */
    CORE_PROVIDED("core_provided", null),

    /** Fill the entire GLSurfaceView (stretch) – mirrors RetroArch ASPECT_RATIO_FULL. */
    FULL("full", null),

    /**
     * Square pixel ratio – computed from the core's base_width / base_height using GCD,
     * mirrors RetroArch ASPECT_RATIO_SQUARE.  [ratio] is null; the viewport helper
     * derives the actual float at runtime from GLRetroView.getVideoWidth/Height().
     */
    SQUARE_PIXEL("square_pixel", null),

    // ── Fixed ratios matching RetroArch aspectratio_lut order ────────────────
    RATIO_4_3("4_3",       4f  / 3f),
    RATIO_16_9("16_9",     16f / 9f),
    RATIO_16_10("16_10",   16f / 10f),
    RATIO_16_15("16_15",   16f / 15f),
    RATIO_21_9("21_9",     21f / 9f),
    RATIO_1_1("1_1",       1f),
    RATIO_2_1("2_1",       2f),
    RATIO_3_2("3_2",       3f  / 2f),
    RATIO_3_4("3_4",       3f  / 4f),
    RATIO_4_1("4_1",       4f),
    /** Portrait ratio – mirrors RetroArch ASPECT_RATIO_4_4 (value 9:16). */
    RATIO_9_16("9_16",     9f  / 16f),
    RATIO_5_4("5_4",       5f  / 4f),
    RATIO_6_5("6_5",       6f  / 5f),
    RATIO_7_9("7_9",       7f  / 9f),
    RATIO_8_3("8_3",       8f  / 3f),
    RATIO_8_7("8_7",       8f  / 7f),
    RATIO_19_12("19_12",   19f / 12f),
    RATIO_19_14("19_14",   19f / 14f),
    RATIO_30_17("30_17",   30f / 17f),
    RATIO_32_9("32_9",     32f / 9f),

    /** User-defined ratio stored as separate numerator/denominator preferences. */
    CUSTOM("custom", null),
    ;

    companion object {
        fun parse(key: String): AspectRatio =
            entries.firstOrNull { it.key == key } ?: CORE_PROVIDED
    }
}
