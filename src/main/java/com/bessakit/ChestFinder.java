package com.bessakit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class ChestFinder {
    private ChestFinder() {}

    private static final int VERTICAL_RADIUS = 8;

    /**
     * Finds the nearest chest matching the label, either by anvil-set custom name
     * or by a sign next to it whose text equals the label (e.g. "kits" or "kits pvp").
     */
    public static BlockPos findKitsChest(String label) {
        BlockPos viaName = findViaCustomName(label);
        if (viaName != null) return viaName;
        return findViaSign(label);
    }

    private static BlockPos findViaCustomName(String label) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return null;

        BlockPos origin = player.getBlockPos();
        int radius = BessaKitConfig.CHEST_SEARCH_RADIUS;
        List<BlockPos> candidates = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!world.isChunkLoaded((origin.getX() + x) >> 4, (origin.getZ() + z) >> 4)) continue;
                for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!(state.getBlock() instanceof AbstractChestBlock)) continue;
                    BlockEntity be = world.getBlockEntity(pos);
                    if (!(be instanceof ChestBlockEntity chest)) continue;
                    if (!chest.hasCustomName()) continue;
                    Text custom = chest.getCustomName();
                    if (custom != null && normalize(custom.getString()).equals(normalize(label))) {
                        candidates.add(pos.toImmutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static BlockPos findViaSign(String label) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return null;

        BlockPos origin = player.getBlockPos();
        int radius = BessaKitConfig.CHEST_SEARCH_RADIUS;
        List<BlockPos> candidates = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!world.isChunkLoaded((origin.getX() + x) >> 4, (origin.getZ() + z) >> 4)) continue;
                for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockEntity be = world.getBlockEntity(pos);
                    if (!(be instanceof SignBlockEntity sign)) continue;
                    if (!signMatches(sign, label)) continue;

                    for (Direction dir : Direction.values()) {
                        BlockPos neighbor = pos.offset(dir);
                        if (world.getBlockState(neighbor).getBlock() instanceof AbstractChestBlock) {
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
     * EXACT match after normalising (lowercase, symbols stripped, spaces collapsed), so a
     * "kits" sign no longer matches a "kits pvp" sign. Lines are joined, so "kits" on one
     * line and "pvp" on the next equals the label "kits pvp".
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
        return normalize(combined.toString()).equals(normalize(label));
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Diagnostic scan: every chest and sign near the bot, shown when nothing matches. */
    public static List<String> debugScan() {
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
        int found = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!world.isChunkLoaded((origin.getX() + x) >> 4, (origin.getZ() + z) >> 4)) continue;
                for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be instanceof ChestBlockEntity chest) {
                        found++;
                        Text custom = chest.hasCustomName() ? chest.getCustomName() : null;
                        results.add(pos.toShortString() + ": chest " + (custom == null ? "(no custom name)" : "named '" + custom.getString() + "'"));
                    } else if (be instanceof SignBlockEntity sign) {
                        found++;
                        StringBuilder text = new StringBuilder();
                        for (Text line : sign.getFrontText().getMessages(false)) {
                            if (!line.getString().isBlank()) text.append(line.getString()).append("|");
                        }
                        results.add(pos.toShortString() + ": sign [" + text + "]");
                    }
                }
            }
        }
        if (found == 0) {
            results.add("No chests or signs loaded within " + radius + " blocks of " + origin.toShortString() + ".");
        }
        return results;
    }
}
