package com.bessakit;

/**
 * Edit these constants to match your server / setup, then rebuild the mod.
 */
public final class BessaKitConfig {
    private BessaKitConfig() {}

    /** Chat phrases (case-insensitive) that trigger a delivery. Any one of these matching is enough. */
    public static final String[] TRIGGER_PHRASES = { "##bessakit", "$bessakit", "~bessakit" };

    /** The container title the chest must have (rename it via anvil). */
    public static final String CHEST_NAME = "kits";

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
