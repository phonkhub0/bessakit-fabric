package com.bessakit;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
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
import java.util.regex.Matcher;

public class BessaKitMod implements ClientModInitializer {

    private enum State {
        IDLE,
        WALK_TO_CHEST,
        OPEN_CHEST,
        TAKE_ITEM,
        CLOSE_CHEST,
        REQUEST_TP,
        WAIT_FOR_TP,
        KILL_SELF,
        WAIT_RESPAWN,
        COOLDOWN
    }

    /** A single queued delivery: who asked, and which kit type (null = default "kits"). */
    private record Request(String username, String kitType) {}

    /**
     * Matches any trigger phrase, optionally followed by whitespace and one of
     * the known kit type keywords. Example matches:
     *   "##bessakit"            -> type = null (default)
     *   "~bessakit pvp"         -> type = "pvp"
     *   "$bessakit Cats"        -> type = "cats"
     */
    private static final Pattern TRIGGER = buildTriggerPattern();

    private static Pattern buildTriggerPattern() {
        StringBuilder phrases = new StringBuilder();
        for (int i = 0; i < BessaKitConfig.TRIGGER_PHRASES.length; i++) {
            if (i > 0) phrases.append("|");
            phrases.append(Pattern.quote(BessaKitConfig.TRIGGER_PHRASES[i]));
        }
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < BessaKitConfig.KIT_TYPES.length; i++) {
            if (i > 0) types.append("|");
            types.append(Pattern.quote(BessaKitConfig.KIT_TYPES[i]));
        }
        String regex = "(?:" + phrases + ")(?:\\s+(" + types + "))?";
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private final Queue<Request> requestQueue = new LinkedList<>();
    private final SimpleNavigator navigator = new SimpleNavigator();

    private State state = State.IDLE;
    private Request currentRequest = null;
    private String currentChestLabel = null;
    private BlockPos chestPos = null;
    private ItemStack itemToDrop = ItemStack.EMPTY;
    private int waitTimer = 0;
    private Vec3d lastPos = null;

    @Override
    public void onInitializeClient() {
        debug("Mod loaded, watching for: " + String.join(", ", BessaKitConfig.TRIGGER_PHRASES)
                + " (optionally followed by a kit type: " + String.join(", ", BessaKitConfig.KIT_TYPES) + ")");
        debug("Cooldown: 2 kits per 30 min per player (except whitelisted). Use /whitekit <player> and /blackkit <player> to manage the whitelist.");

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String text = message.getString();
            if (text.startsWith("[BessaKit]")) return true; // never process our own debug output

            debug("Saw chat line: " + text);
            Matcher matcher = TRIGGER.matcher(text);
            if (matcher.find()) {
                String kitType = matcher.group(1);
                debug("Trigger matched! kitType=" + (kitType == null ? "(default)" : kitType));
                String username = extractUsername(text);
                if (username == null) {
                    debug("Could NOT resolve a username from that message - no online player name found inside it.");
                } else if (!CooldownManager.canOrder(username)) {
                    long remainingMin = CooldownManager.remainingCooldownMs(username) / 60000 + 1;
                    debug(username + " is on cooldown - " + remainingMin + " more minute(s) before they can order again.");
                    sendCommand("w " + username + " You're on cooldown! Try again in about " + remainingMin + " minute(s).");
                } else {
                    debug("Resolved sender as: " + username + " (queued, kit=" + BessaKitConfig.chestLabelFor(kitType) + ")");
                    CooldownManager.recordOrder(username);
                    requestQueue.add(new Request(username, kitType));
                }
            }
            return true; // don't block the message from showing
        });

        // Local-only commands: /whitekit <player> and /blackkit <player>.
        // Intercepted here and never actually sent to the server.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String trimmed = command.trim();
            String[] parts = trimmed.split("\\s+", 2);
            String cmd = parts[0].toLowerCase();

            if (cmd.equals("whitekit")) {
                if (parts.length < 2 || parts[1].isBlank()) {
                    debug("Usage: /whitekit <player>");
                } else {
                    String target = parts[1].trim();
                    CooldownManager.whitelist(target);
                    debug("Whitelisted " + target + " - they now have unlimited kits, no cooldown.");
                }
                return false; // don't send to the server
            }

            if (cmd.equals("blackkit")) {
                if (parts.length < 2 || parts[1].isBlank()) {
                    debug("Usage: /blackkit <player>");
                } else {
                    String target = parts[1].trim();
                    CooldownManager.blacklist(target);
                    debug("Removed " + target + " from the kit whitelist - normal cooldown applies again.");
                }
                return false; // don't send to the server
            }

            return true; // any other command goes to the server as normal
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
     * Extracts the sender's username directly from the chat line's own format,
     * rather than relying on the world's list of loaded player entities (which
     * only contains players rendered nearby - a message from someone far away
     * would never match that way).
     *
     * Expected formats (based on this server's chat style):
     *   "bessagoncc » message"
     *   "[ELITE] bessagoncc » message"
     *   "[APEX] bessagoncc » message"
     *
     * Adjust USERNAME_PATTERN below if your server's chat format differs.
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile(
            "^(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{2,16})\\s*»");

    private String extractUsername(String rawMessage) {
        Matcher matcher = USERNAME_PATTERN.matcher(rawMessage.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Fallback: check against nearby loaded player entities, in case the
        // chat format doesn't match the pattern above for some messages.
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
                    currentRequest = requestQueue.poll();
                    currentChestLabel = BessaKitConfig.chestLabelFor(currentRequest.kitType());
                    debug("Starting delivery for " + currentRequest.username() + ", searching for chest/sign labeled '" + currentChestLabel + "'...");
                    chestPos = ChestFinder.findKitsChest(currentChestLabel);
                    if (chestPos == null) {
                        debug("No chest/sign labeled '" + currentChestLabel + "' found within " + BessaKitConfig.CHEST_SEARCH_RADIUS + " blocks. Diagnostic scan follows:");
                        for (String line : ChestFinder.debugScanChests()) {
                            debug(line);
                        }
                        currentRequest = null;
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
                debug("Requesting tpa to " + currentRequest.username());
                state = State.REQUEST_TP;
            }

            case REQUEST_TP -> {
                sendCommand("tpa " + currentRequest.username());
                sendCommand("w " + currentRequest.username() + " Your Bessakit is ready! Accept the tp to get it.");
                lastPos = player.getEntityPos();
                waitTimer = BessaKitConfig.TP_TIMEOUT_TICKS;
                debug("Waiting for " + currentRequest.username() + " to accept tpa (timeout " + (BessaKitConfig.TP_TIMEOUT_TICKS / 20) + "s)...");
                state = State.WAIT_FOR_TP;
            }

            case WAIT_FOR_TP -> {
                Vec3d pos = player.getEntityPos();
                if (lastPos != null && pos.distanceTo(lastPos) > BessaKitConfig.TELEPORT_JUMP_THRESHOLD) {
                    // Teleport landed - kill immediately so the kit drops here,
                    // right next to the player who just teleported to us.
                    debug("Detected teleport! Killing to drop the kit.");
                    state = State.KILL_SELF;
                    break;
                }
                lastPos = pos;
                waitTimer--;
                if (waitTimer <= 0) {
                    debug(currentRequest.username() + " never accepted the tpa. Giving up on this request.");
                    itemToDrop = ItemStack.EMPTY;
                    resetToCooldown();
                }
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
                    currentRequest = null;
                    currentChestLabel = null;
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

    private void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }
}
