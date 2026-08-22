package com.bessakit;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ChestFinder {
    private ChestFinder() {}

    /** Vertical search range is kept smaller than horizontal since kit rooms are usually flat. */
    private static final int VERTICAL_RADIUS = 8;

    /**
     * Scans nearby loaded chunks for a chest whose custom name (set via anvil)
     * matches BessaKitConfig.CHEST_NAME. Returns the nearest match, or null.
     */
    public static BlockPos findKitsChest() {
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
                    if (name.equalsIgnoreCase(BessaKitConfig.CHEST_NAME)) {
                        candidates.add(pos.toImmutable());
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Diagnostic scan: lists every chest found nearby, whether it has a custom
     * name, and what that name is (if any). Used to debug why findKitsChest()
     * isn't matching - e.g. the chest has no custom name, a different name,
     * or is simply out of range.
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
        int found = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = (origin.getX() + x) >> 4;
                int chunkZ = (origin.getZ() + z) >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;

                for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!(state.getBlock() instanceof ChestBlock)) continue;

                    found++;
                    BlockEntity be = world.getBlockEntity(pos);
                    if (!(be instanceof ChestBlockEntity chest)) {
                        results.add(pos + ": chest block but no ChestBlockEntity found");
                        continue;
                    }
                    if (!chest.hasCustomName()) {
                        results.add(pos + ": chest with NO custom name (distance " + Math.sqrt(pos.getSquaredDistance(origin)) + ")");
                    } else {
                        String name = chest.getDisplayName().getString();
                        results.add(pos + ": chest named '" + name + "' (distance " + Math.sqrt(pos.getSquaredDistance(origin)) + ")");
                    }
                }
            }
        }

        if (found == 0) {
            results.add("No chest blocks found at all within " + radius + " blocks horizontally / " + VERTICAL_RADIUS + " vertically of " + origin + ". Check you are close enough and the chunk is loaded.");
        }
        return results;
    }
}
