package com.bessakit;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ChestFinder {
    private ChestFinder() {}

    /** Vertical search range is kept smaller than horizontal since kit rooms are usually flat. */
    private static final int VERTICAL_RADIUS = 8;

    /**
     * Finds the chest matching the given label two ways:
     *  1. A chest with an anvil-set custom name matching the label exactly
     *     (only possible if the chest was renamed via anvil BEFORE being
     *     placed - chests can't be renamed after placement).
     *  2. A sign anywhere near the chest whose text contains the label -
     *     much easier to set up: just place a sign on or next to the chest
     *     and write the label on it, e.g. "kits pvp".
     * Returns the nearest match from either method, or null if neither finds anything.
     *
     * @param label the chest/sign label to search for, e.g. "kits" or "kits pvp"
     */
    public static BlockPos findKitsChest(String label) {
        BlockPos viaName = findKitsChestViaCustomName(label);
        if (viaName != null) return viaName;
        return findKitsChestViaSign(label);
    }

    private static BlockPos findKitsChestViaCustomName(String label) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return null;

        BlockPos origin = player.getBlockPos();
        int radius = BessaKitConfig.CHEST_SEARCH_RADIUS;

        List<BlockPos> candidates = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = (origin.getX() + x) >> 4;
                int chunkZ = (origin.getZ() + z) >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;

                for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!(state.getBlock() instanceof ChestBlock)) continue;

                    BlockEntity be = world.getBlockEntity(pos);
                    if (!(be instanceof ChestBlockEntity chest)) continue;
                    if (!chest.hasCustomName()) continue;

                    String name = chest.getDisplayName().getString();
                    if (name.equalsIgnoreCase(label)) {
                        candidates.add(pos.toImmutable());
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static BlockPos findKitsChestViaSign(String label) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return null;

        BlockPos origin = player.getBlockPos();
        int radius = BessaKitConfig.CHEST_SEARCH_RADIUS;

        List<BlockPos> candidates = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = (origin.getX() + x) >> 4;
                int chunkZ = (origin.getZ() + z) >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;

                for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockEntity be = world.getBlockEntity(pos);
                    if (!(be instanceof SignBlockEntity sign)) continue;

                    if (!signMatches(sign, label)) continue;

                    // check all 6 neighbors for a chest
                    for (Direction dir : Direction.values()) {
                        BlockPos neighbor = pos.offset(dir);
                        BlockState state = world.getBlockState(neighbor);
                        if (state.getBlock() instanceof ChestBlock) {
                            candidates.add(neighbor.toImmutable());
                        }
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean signMatches(SignBlockEntity sign, String label) {
        return textMatches(sign.getFrontText().getMessages(false), label)
                || textMatches(sign.getBackText().getMessages(false), label);
    }

    /**
     * Joins all non-blank sign lines into one string and checks it against the
     * label. This lets a label like "kits pvp" match a sign with "kits" on one
     * line and "pvp" on the next, not just a single line containing both words.
     */
    private static boolean textMatches(Text[] lines, String label) {
        StringBuilder combined = new StringBuilder();
        for (Text line : lines) {
            String s = line.getString().trim();
            if (!s.isEmpty()) {
                if (combined.length() > 0) combined.append(" ");
                combined.append(s);
            }
        }
        String joined = combined.toString().toLowerCase();
        return joined.equals(label.toLowerCase()) || joined.contains(label.toLowerCase());
    }

    /**
     * Diagnostic scan: lists every chest AND every sign found nearby, so we
     * can see exactly what the bot sees when findKitsChest() comes up empty.
     */
    public static List<String> debugScanChests() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        List<String> results = new ArrayList<>();
        if (world == null || player == null) {
            results.add("world or player is null");
            return results;
        }

        BlockPos origin = player.getBlockPos();
        int radius = BessaKitConfig.CHEST_SEARCH_RADIUS;
        int chestsFound = 0;
        int signsFound = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = (origin.getX() + x) >> 4;
                int chunkZ = (origin.getZ() + z) >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;

                for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    double dist = Math.sqrt(pos.getSquaredDistance(origin));

                    if (state.getBlock() instanceof ChestBlock) {
                        chestsFound++;
                        BlockEntity be = world.getBlockEntity(pos);
                        if (be instanceof ChestBlockEntity chest && chest.hasCustomName()) {
                            results.add(pos + ": chest named '" + chest.getDisplayName().getString() + "' (distance " + dist + ")");
                        } else {
                            results.add(pos + ": chest with NO custom name (distance " + dist + ")");
                        }
                    }

                    BlockEntity be = world.getBlockEntity(pos);
                    if (be instanceof SignBlockEntity sign) {
                        signsFound++;
                        StringBuilder text = new StringBuilder();
                        for (Text line : sign.getFrontText().getMessages(false)) {
                            if (!line.getString().isBlank()) text.append(line.getString()).append("|");
                        }
                        results.add(pos + ": sign with text [" + text + "] (distance " + dist + ")");
                    }
                }
            }
        }

        if (chestsFound == 0 && signsFound == 0) {
            results.add("No chest blocks OR signs found at all within " + radius + " blocks horizontally / " + VERTICAL_RADIUS + " vertically of " + origin + ".");
        }
        return results;
    }
}
