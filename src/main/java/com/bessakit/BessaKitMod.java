package com.bessakit;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
import net.minecraft.text.Text;

import java.util.LinkedList;
import java.util.Queue;
import java.util.regex.Pattern;

public class BessaKitMod implements ClientModInitializer {

    private enum State {
        IDLE,
        WALK_TO_CHEST,
        OPEN_CHEST,
        TAKE_ITEM,
        CLOSE_CHEST,
        REQUEST_TP,
        WAIT_FOR_TP,
        WALK_TO_PLAYER,
        DROP_ITEM,
        KILL_SELF,
        WAIT_RESPAWN,
        COOLDOWN
    }

    private static final Pattern TRIGGER = Pattern.compile(
            Pattern.quote(BessaKitConfig.TRIGGER_PHRASE), Pattern.CASE_INSENSITIVE);

    private final Queue<String> requestQueue = new LinkedList<>();
    private final SimpleNavigator navigator = new SimpleNavigator();

    private State state = State.IDLE;
    private String currentTarget = null;
    private BlockPos chestPos = null;
    private ItemStack itemToDrop = ItemStack.EMPTY;
    private int waitTimer = 0;
    private Vec3d lastPos = null;

    @Override
    public void onInitializeClient() {
        debug("Mod loaded, watching for " + BessaKitConfig.TRIGGER_PHRASE);

        // Listen for the trigger phrase in chat
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String text = message.getString();
            if (text.startsWith("[BessaKit]")) return true; // never process our own debug output

            debug("Saw chat line: " + text);
            if (TRIGGER.matcher(text).find()) {
                debug("Trigger matched!");
                String username = extractUsername(text);
                if (username != null) {
                    debug("Resolved sender as: " + username + " (queued)");
                    requestQueue.add(username);
                } else {
                    debug("Could NOT resolve a username from that message - no online player name found inside it.");
                }
            }
            return true; // don't block the message from showing
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    /** Prints a debug line straight to the chat HUD, bypassing the chat-received event pipeline entirely. */
    private static void debug(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal("[BessaKit] " + msg));
        }
        System.out.println("[BessaKit] " + msg);
    }

    /**
     * Naive username extraction: assumes standard "<Name> message" chat format.
     * Checks each online player's display name against the raw chat text.
     * Adjust this if your server uses a different chat format (prefixes, ranks, etc).
     */
    private String extractUsername(String rawMessage) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        for (PlayerEntity p : client.world.getPlayers()) {
            String name = p.getName().getString();
            if (rawMessage.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        switch (state) {
            case IDLE -> {
                if (!requestQueue.isEmpty()) {
                    currentTarget = requestQueue.poll();
                    debug("Starting delivery for " + currentTarget + ", searching for chest named '" + BessaKitConfig.CHEST_NAME + "'...");
                    chestPos = ChestFinder.findKitsChest();
                    if (chestPos == null) {
                        debug("No chest named '" + BessaKitConfig.CHEST_NAME + "' found within " + BessaKitConfig.CHEST_SEARCH_RADIUS + " blocks. Aborting this request.");
                        currentTarget = null;
                        break;
                    }
                    debug("Found chest at " + chestPos + ", walking there.");
                    navigator.reset();
                    state = State.WALK_TO_CHEST;
                }
            }

            case WALK_TO_CHEST -> {
                Vec3d target = new Vec3d(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5);
                if (navigator.tick(target)) {
                    debug("Arrived at chest, opening it.");
                    state = State.OPEN_CHEST;
                }
            }

            case OPEN_CHEST -> {
                if (openChest(client, player)) {
                    waitTimer = 5; // give the server a couple ticks to sync the screen
                    state = State.TAKE_ITEM;
                } else {
                    debug("Could not open the chest (moved, obstructed, or not a chest anymore). Aborting this request.");
                    resetToCooldown();
                }
            }

            case TAKE_ITEM -> {
                if (waitTimer > 0) {
                    waitTimer--;
                    break;
                }
                if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
                    debug("Chest screen never opened server-side. Aborting this request.");
                    resetToCooldown();
                    break;
                }
                ItemStack slotStack = handler.getSlot(BessaKitConfig.KIT_SLOT_INDEX).getStack();
                if (slotStack.isEmpty()) {
                    debug("Kit slot " + BessaKitConfig.KIT_SLOT_INDEX + " is empty. Aborting this request.");
                    resetToCooldown();
                    break;
                }
                itemToDrop = slotStack.copy();
                debug("Took item from chest: " + itemToDrop.getItem());
                // shift-click: moves the stack straight into the player's inventory
                client.interactionManager.clickSlot(
                        handler.syncId, BessaKitConfig.KIT_SLOT_INDEX, 0,
                        SlotActionType.QUICK_MOVE, player);
                state = State.CLOSE_CHEST;
            }

            case CLOSE_CHEST -> {
                player.closeHandledScreen();
                debug("Requesting tpa to " + currentTarget);
                state = State.REQUEST_TP;
            }

            case REQUEST_TP -> {
                sendCommand("tpa " + currentTarget);
                sendCommand("w " + currentTarget + " Your Bessakit is ready! Accept the tp to get it.");
                lastPos = player.getEntityPos();
                waitTimer = BessaKitConfig.TP_TIMEOUT_TICKS;
                debug("Waiting for " + currentTarget + " to accept tpa (timeout " + (BessaKitConfig.TP_TIMEOUT_TICKS / 20) + "s)...");
                state = State.WAIT_FOR_TP;
            }

            case WAIT_FOR_TP -> {
                Vec3d pos = player.getEntityPos();
                if (lastPos != null && pos.distanceTo(lastPos) > BessaKitConfig.TELEPORT_JUMP_THRESHOLD) {
                    debug("Detected teleport! Walking to " + currentTarget + " now.");
                    navigator.reset();
                    state = State.WALK_TO_PLAYER;
                    break;
                }
                lastPos = pos;
                waitTimer--;
                if (waitTimer <= 0) {
                    debug(currentTarget + " never accepted the tpa. Giving up on this request.");
                    itemToDrop = ItemStack.EMPTY;
                    resetToCooldown();
                }
            }

            case WALK_TO_PLAYER -> {
                Vec3d targetPos = resolvePlayerPos(client, currentTarget);
                if (targetPos == null) {
                    debug("Lost track of " + currentTarget + " (offline or out of range). Aborting this request.");
                    resetToCooldown();
                    break;
                }
                if (navigator.tick(targetPos)) {
                    debug("Reached " + currentTarget + ", dropping item.");
                    state = State.DROP_ITEM;
                }
            }

            case DROP_ITEM -> {
                dropItem(player, itemToDrop);
                debug("Dropped item, killing self to reset.");
                state = State.KILL_SELF;
            }

            case KILL_SELF -> {
                sendCommand("kill");
                waitTimer = 20 * 5;
                state = State.WAIT_RESPAWN;
            }

            case WAIT_RESPAWN -> {
                if (!player.isDead() && player.getHealth() > 0) {
                    debug("Respawned. Ready for next request.");
                    resetToCooldown();
                } else {
                    waitTimer--;
                    if (waitTimer <= 0) resetToCooldown(); // fallback if death state is weird
                }
            }

            case COOLDOWN -> {
                waitTimer--;
                if (waitTimer <= 0) {
                    currentTarget = null;
                    chestPos = null;
                    itemToDrop = ItemStack.EMPTY;
                    state = State.IDLE;
                }
            }
        }
    }

    private void resetToCooldown() {
        waitTimer = BessaKitConfig.COOLDOWN_TICKS;
        state = State.COOLDOWN;
    }

    private boolean openChest(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null || client.interactionManager == null) return false;
        if (!(client.world.getBlockState(chestPos).getBlock() instanceof net.minecraft.block.ChestBlock)) {
            return false;
        }
        Vec3d hitVec = new Vec3d(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, chestPos, false);
        client.interactionManager.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hitResult);
        return true;
    }

    private void dropItem(ClientPlayerEntity player, ItemStack stack) {
        if (stack.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();

        // find a matching stack in the player's inventory
        int slot = -1;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == stack.getItem()) {
                slot = i;
                break;
            }
        }
        if (slot == -1) return; // don't have it anymore for some reason

        if (slot < 9) {
            // already in the hotbar
            player.getInventory().setSelectedSlot(slot);
        } else if (player.currentScreenHandler != null) {
            // swap it into hotbar slot 0
            client.interactionManager.clickSlot(
                    player.currentScreenHandler.syncId, slot, 0, SlotActionType.SWAP, player);
            player.getInventory().setSelectedSlot(0);
        }
        player.dropSelectedItem(true); // true = drop whole stack, like ctrl+Q
    }

    private void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    private Vec3d resolvePlayerPos(MinecraftClient client, String name) {
        if (client.world == null) return null;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(name)) {
                return p.getEntityPos();
            }
        }
        return null;
    }
}
