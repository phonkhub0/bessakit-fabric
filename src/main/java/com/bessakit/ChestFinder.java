package com.bessakit;

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

    /**
     * Scans loaded block entities within CHEST_SEARCH_RADIUS for a chest
     * whose custom name (set via anvil) matches BessaKitConfig.CHEST_NAME.
     * Returns the nearest match, or null if none found.
     */
    public static BlockPos findKitsChest() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return null;

        BlockPos origin = player.getBlockPos();
        int radius = BessaKitConfig.CHEST_SEARCH_RADIUS;

        List<BlockPos> candidates = new ArrayList<>();
        for (BlockEntity be : world.getBlockEntities()) {
            if (!(be instanceof ChestBlockEntity chest)) continue;
            BlockPos pos = be.getPos();
            if (pos.getSquaredDistance(origin) > (double) radius * radius) continue;
            if (!chest.hasCustomName()) continue;
            String name = chest.getDisplayName().getString();
            if (name.equalsIgnoreCase(BessaKitConfig.CHEST_NAME)) {
                candidates.add(pos);
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
