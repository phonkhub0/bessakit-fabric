package com.bessakit;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * "Walk toward the target" steering.
 *
 * IMPORTANT: this presses the REAL movement key bindings every tick. Writing to
 * player.input directly does not work - Minecraft rebuilds the input from the
 * key bindings at the start of every player tick and wipes anything we set.
 *
 * Not real pathfinding: faces the target, walks forward, jumps when it stops
 * making progress. Fine for a flat open kit room.
 */
public class SimpleNavigator {

    private int stuckTicks = 0;
    private int totalTicks = 0;
    private double lastX = 0, lastZ = 0;
    private boolean hasLast = false;

    public void reset() {
        stuckTicks = 0;
        totalTicks = 0;
        hasLast = false;
    }

    /** @return true once close enough to target, or if we give up (timeout / stuck). */
    public boolean tick(Vec3d target) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            release(client);
            return true;
        }

        totalTicks++;
        if (totalTicks > BessaKitConfig.MAX_WALK_TICKS) {
            release(client);
            return true;
        }

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist <= BessaKitConfig.ARRIVE_DISTANCE) {
            release(client);
            return true;
        }

        // face the target (yaw 0 = +Z, so atan2(-dx, dz))
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYaw(yaw);
        player.setPitch(0f);

        // progress-based stuck detection
        if (hasLast) {
            double moved = Math.sqrt(Math.pow(player.getX() - lastX, 2) + Math.pow(player.getZ() - lastZ, 2));
            if (moved < 0.03) stuckTicks++; else stuckTicks = 0;
        }
        lastX = player.getX();
        lastZ = player.getZ();
        hasLast = true;

        boolean shouldJump = stuckTicks > 6;

        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(shouldJump);

        if (stuckTicks > 60) { // ~3s with zero progress
            release(client);
            return true;
        }
        return false;
    }

    public void stop() {
        release(MinecraftClient.getInstance());
    }

    private void release(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }
}
