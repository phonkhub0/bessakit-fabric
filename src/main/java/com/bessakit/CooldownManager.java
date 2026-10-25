package com.bessakit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks per-player kit request cooldowns and a whitelist of players who
 * bypass cooldowns entirely.
 *
 * Rule: a non-whitelisted player can request up to MAX_ORDERS kits within
 * any rolling WINDOW_MS window. Once they hit that limit, further requests
 * are rejected until enough time has passed for their oldest request in
 * the window to fall outside it.
 */
public final class CooldownManager {
    private CooldownManager() {}

    private static final long WINDOW_MS = 30 * 60 * 1000L; // 30 minutes
    private static final int MAX_ORDERS = 2;

    private static final Map<String, Deque<Long>> history = new HashMap<>();
    private static final Set<String> whitelist = new HashSet<>();

    static {
        // Default whitelisted player - always has unlimited kits.
        whitelist.add("bessagoncc");
    }

    public static boolean isWhitelisted(String username) {
        return whitelist.contains(username.toLowerCase());
    }

    public static void whitelist(String username) {
        whitelist.add(username.toLowerCase());
    }

    public static void blacklist(String username) {
        whitelist.remove(username.toLowerCase());
    }

    /** True if this player is allowed to make another kit request right now. */
    public static boolean canOrder(String username) {
        if (isWhitelisted(username)) return true;
        purgeOld(username);
        Deque<Long> times = history.get(username.toLowerCase());
        return times == null || times.size() < MAX_ORDERS;
    }

    /** Call this once a request from this player is actually accepted/queued. */
    public static void recordOrder(String username) {
        history.computeIfAbsent(username.toLowerCase(), k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    /** How many more milliseconds until this player can order again (0 if they can order now). */
    public static long remainingCooldownMs(String username) {
        if (isWhitelisted(username)) return 0;
        purgeOld(username);
        Deque<Long> times = history.get(username.toLowerCase());
        if (times == null || times.size() < MAX_ORDERS) return 0;
        long oldest = times.peekFirst();
        return Math.max(0, WINDOW_MS - (System.currentTimeMillis() - oldest));
    }

    private static void purgeOld(String username) {
        Deque<Long> times = history.get(username.toLowerCase());
        if (times == null) return;
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.pollFirst();
        }
    }
}
