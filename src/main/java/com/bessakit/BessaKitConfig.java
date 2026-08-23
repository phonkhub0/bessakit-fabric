package com.bessakit;

/**
 * Edit these constants to match your server / setup, then rebuild the mod.
 */
public final class BessaKitConfig {
    private BessaKitConfig() {}

    /** Chat phrases (case-insensitive) that trigger a delivery. Any one of these matching is enough. */
    public static final String[] TRIGGER_PHRASES = { "##bessakit", "$bessakit", "~bessakit" };

    /** Recognized kit type keywords. A trigger phrase can optionally be followed by one of these. */
    public static final String[] KIT_TYPES = {
            "pvp", "illegal", "map", "pickaxe", "toothpick", "safemaps", "qbasty", "bessa", "cats"
    };

    /** Base label used for the default/no-type request. Typed requests become "kits <type>", e.g. "kits pvp". */
    public static final String CHEST_NAME = "kits";

    /** Builds the sign/chest label to search for, given an optional kit type (null = default). */
    public static String chestLabelFor(String kitType) {
        if (kitType == null || kitType.isBlank()) return CHEST_NAME;
        return CHEST_NAME + " " + kitType.toLowerCase();
    }

    /** Which slot in the chest to take from. 0 = first slot. */
    public static final int KIT_SLOT_INDEX = 0;

    /** How far (in blocks) to search for candidate chests. */
    public static final int CHEST_SEARCH_RADIUS = 32;

    /** How close counts as "arrived" at a waypoint. */
    public static final double ARRIVE_DISTANCE = 2.0;

    /** How many ticks to wait for a player to accept /tpa before giving up. (20 ticks = 1s) */
    public static final int TP_TIMEOUT_TICKS = 20 * 30;

    /** A position delta bigger than this in one tick is treated as "the tpa landed". */
    public static final double TELEPORT_JUMP_THRESHOLD = 8.0;

    /** Ticks to wait between finishing one delivery and starting the next. */
    public static final int COOLDOWN_TICKS = 20 * 3;

    /** Safety cap so the bot doesn't wander forever if it gets stuck. */
    public static final int MAX_WALK_TICKS = 20 * 25;
}
