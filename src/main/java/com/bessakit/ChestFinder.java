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
}
