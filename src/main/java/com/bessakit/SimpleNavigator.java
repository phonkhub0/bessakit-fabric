package com.bessakit;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;

/**
 * Very simple "walk toward the target" steering.
 *
 * This is NOT real pathfinding: it just faces the target, walks forward,
 * and jumps if it hits a wall. It works fine for a flat, open kit room,
 * but will get stuck on complex terrain, gaps, or multi-level builds.
 *
 * If your setup needs proper pathfinding, look at integrating with
 * Baritone's IBaritone / PathingBehavior API instead of this class.
 */
public class SimpleNavigator {

    private int stuckTicks = 0;
    private int totalTicks = 0;

    public void reset() {
        stuckTicks = 0;
        totalTicks = 0;
    }

    /**
     * Steers the player one tick closer to target.
     * @return true once close enough to target, or if we give up (timeout).
     */
    public boolean tick(Vec3d target) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return true;

        totalTicks++;
        if (totalTicks > BessaKitConfig.MAX_WALK_TICKS) {
            stopMoving(player);
            return true; // give up so we don't walk forever
        }

        Vec3d playerPos = player.getPos();
        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist <= BessaKitConfig.ARRIVE_DISTANCE) {
            stopMoving(player);
            return true;
        }

        // face the target
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        player.setYaw((float) targetYaw);
        player.setHeadYaw((float) targetYaw);

        // walk forward
        player.input.movementForward = 1.0f;
        player.input.movementSideways = 0.0f;
        player.input.jumping = false;

        // naive stuck detection: not much horizontal or vertical velocity
        boolean barelyMoving = player.getVelocity().horizontalLength() < 0.02;
        if (player.horizontalCollision || barelyMoving) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        // if stuck for a bit, try jumping to hop over a 1-block obstacle
        if (stuckTicks > 5) {
            player.input.jumping = true;
        }
        if (stuckTicks > 40) {
            // completely stuck for 2s, give up on this leg
            stopMoving(player);
            return true;
        }

        return false;
    }

    private void stopMoving(ClientPlayerEntity player) {
        player.input.movementForward = 0.0f;
        player.input.movementSideways = 0.0f;
        player.input.jumping = false;
    }
}
