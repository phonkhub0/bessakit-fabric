package com.bessakit;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.AbstractChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedList;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BessaKitMod implements ClientModInitializer {

    private enum State {
        IDLE, WALK_TO_CHEST, OPEN_CHEST, TAKE_ITEM, CLOSE_CHEST,
        REQUEST_TP, WAIT_FOR_TP, KILL_SELF, WAIT_RESPAWN, COOLDOWN
    }

    private record Request(String username, String kitType) {}

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
        return Pattern.compile("(?:" + phrases + ")(?:\\s+(" + types + "))?", Pattern.CASE_INSENSITIVE);
    }

    /**
     * Pulls the sender name out of a chat line. Handles:
     *   "name » msg", "[ELITE] name » msg", "[A] [B] name » msg", "<name> msg", "name: msg"
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile(
            "^(?:\\[[^\\]]*\\]\\s*|[^A-Za-z0-9_<\\[]+)*<?([A-Za-z0-9_]{2,16})(?:>|\\s*[\u00BB:])");

    private final Queue<Request> requestQueue = new LinkedList<>();
    private final SimpleNavigator navigator = new SimpleNavigator();

    private State state = State.IDLE;
    private Request currentRequest = null;
    private BlockPos chestPos = null;
    private int waitTimer = 0;
    private Vec3d lastPos = null;
    private boolean sawDeath = false;
    private int announceTimer = 0;
    private String lastHandledText = "";
    private long lastHandledAt = 0;

    @Override
    public void onInitializeClient() {
        System.out.println("[BessaKit] Mod loaded.");

        // Visible proof in chat that the mod is actually running.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> announceTimer = 60);

        // Chat can arrive as a system/"game" message OR as a real player-chat packet,
        // depending on the server. Listen to both.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            handleChat(message.getString());
            return true;
        });
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            handleChat(message.getString());
            return true;
        });

        // Local-only commands: /whitekit <player>, /blackkit <player>
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String[] parts = command.trim().split("\\s+", 2);
            String cmd = parts[0].toLowerCase();
            if (cmd.equals("whitekit")) {
                if (parts.length < 2 || parts[1].isBlank()) debug("Usage: /whitekit <player>");
                else {
                    CooldownManager.whitelist(parts[1].trim());
                    debug("Whitelisted " + parts[1].trim() + " - unlimited kits.");
                }
                return false;
            }
            if (cmd.equals("blackkit")) {
                if (parts.length < 2 || parts[1].isBlank()) debug("Usage: /blackkit <player>");
                else {
                    CooldownManager.blacklist(parts[1].trim());
                    debug("Removed " + parts[1].trim() + " from the whitelist.");
                }
                return false;
            }
            return true;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void handleChat(String text) {
        if (text.startsWith("[BessaKit]")) return;

        long now = System.currentTimeMillis();
        if (text.equals(lastHandledText) && now - lastHandledAt < 1000) return; // same line via two paths
        lastHandledText = text;
        lastHandledAt = now;

        Matcher matcher = TRIGGER.matcher(text);
        if (!matcher.find()) return;

        String kitType = matcher.group(1);
        debug("Trigger seen: \"" + text + "\"");
        String username = extractUsername(text);
        if (username == null) {
            debug("Could not work out who sent that. Chat format not recognised.");
            return;
        }
        if (!CooldownManager.canOrder(username)) {
            long remainingMin = CooldownManager.remainingCooldownMs(username) / 60000 + 1;
            debug(username + " is on cooldown (" + remainingMin + " min left).");
            sendCommand("w " + username + " You're on cooldown! Try again in about " + remainingMin + " minute(s).");
            return;
        }
        CooldownManager.recordOrder(username);
        requestQueue.add(new Request(username, kitType));
        debug("Queued " + username + " (kit: " + BessaKitConfig.chestLabelFor(kitType) + "). Queue size: " + requestQueue.size());
    }

    private static void debug(String msg) {
        System.out.println("[BessaKit] " + msg);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal("[BessaKit] " + msg));
        }
    }

    private String extractUsername(String raw) {
        Matcher matcher = USERNAME_PATTERN.matcher(raw.trim());
        if (matcher.find()) return matcher.group(1);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        for (PlayerEntity p : client.world.getPlayers()) {
            String name = p.getName().getString();
            if (raw.contains(name)) return name;
        }
        return null;
    }

    private static Vec3d posOf(ClientPlayerEntity p) {
        return new Vec3d(p.getX(), p.getY(), p.getZ());
    }

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            navigator.stop();
            return;
        }

        if (announceTimer > 0 && --announceTimer == 0) {
            debug("Loaded and watching for " + String.join(", ", BessaKitConfig.TRIGGER_PHRASES)
                    + " [kit types: " + String.join(", ", BessaKitConfig.KIT_TYPES) + "]");
        }

        switch (state) {
            case IDLE -> {
                if (requestQueue.isEmpty()) return;
                // never start a delivery while dead
                if (player.isDead() || player.getHealth() <= 0) return;

                currentRequest = requestQueue.poll();
                String label = BessaKitConfig.chestLabelFor(currentRequest.kitType());
                debug("Starting delivery for " + currentRequest.username() + ", looking for '" + label + "'...");
                chestPos = ChestFinder.findKitsChest(label);
                if (chestPos == null) {
                    debug("No chest/sign '" + label + "' within " + BessaKitConfig.CHEST_SEARCH_RADIUS + " blocks. Nearby chests/signs:");
                    for (String line : ChestFinder.debugScan()) debug(line);
                    currentRequest = null;
                    return;
                }
                debug("Found chest at " + chestPos.toShortString() + ", walking there.");
                navigator.reset();
                state = State.WALK_TO_CHEST;
            }

            case WALK_TO_CHEST -> {
                Vec3d target = new Vec3d(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5);
                if (navigator.tick(target)) {
                    debug("Reached chest, opening it.");
                    state = State.OPEN_CHEST;
                }
            }

            case OPEN_CHEST -> {
                Vec3d eye = new Vec3d(player.getX(), player.getEyeY(), player.getZ());
                double dist = eye.distanceTo(Vec3d.ofCenter(chestPos));
                if (dist > 6.0) {
                    debug("Still " + String.format("%.1f", dist) + " blocks from the chest (stuck on the way). Aborting.");
                    resetToCooldown();
                    return;
                }
                if (openChest(client, player)) {
                    waitTimer = 0;
                    state = State.TAKE_ITEM;
                } else {
                    debug("Could not open the chest. Aborting.");
                    resetToCooldown();
                }
            }

            case TAKE_ITEM -> {
                waitTimer++;
                if (player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
                    ItemStack stack = handler.getSlot(BessaKitConfig.KIT_SLOT_INDEX).getStack();
                    if (!stack.isEmpty()) {
                        debug("Taking " + stack.getItem() + " from the chest.");
                        client.interactionManager.clickSlot(
                                handler.syncId, BessaKitConfig.KIT_SLOT_INDEX, 0,
                                SlotActionType.QUICK_MOVE, player);
                        waitTimer = 4; // let the click packet go out before closing
                        state = State.CLOSE_CHEST;
                        return;
                    }
                }
                if (waitTimer > 40) { // ~2s for the server to open + sync the chest
                    debug("Chest never opened, or kit slot " + BessaKitConfig.KIT_SLOT_INDEX + " is empty. Aborting.");
                    resetToCooldown();
                }
            }

            case CLOSE_CHEST -> {
                if (--waitTimer > 0) return;
                player.closeHandledScreen();
                state = State.REQUEST_TP;
            }

            case REQUEST_TP -> {
                String user = currentRequest.username();
                debug("Sending /tpa to " + user);
                sendCommand("tpa " + user);
                sendCommand("w " + user + " Your Bessakit is ready! Accept the tp to get it.");
                lastPos = posOf(player);
                waitTimer = BessaKitConfig.TP_TIMEOUT_TICKS;
                state = State.WAIT_FOR_TP;
            }

            case WAIT_FOR_TP -> {
                Vec3d pos = posOf(player);
                if (lastPos != null && pos.distanceTo(lastPos) > BessaKitConfig.TELEPORT_JUMP_THRESHOLD) {
                    debug("Teleported! Dying to drop the kit.");
                    state = State.KILL_SELF;
                    return;
                }
                lastPos = pos;
                if (--waitTimer <= 0) {
                    debug(currentRequest.username() + " never accepted the tpa. Giving up.");
                    resetToCooldown();
                }
            }

            case KILL_SELF -> {
                sendCommand(BessaKitConfig.KILL_COMMAND);
                sawDeath = false;
                waitTimer = 20 * 10;
                state = State.WAIT_RESPAWN;
            }

            case WAIT_RESPAWN -> {
                boolean dead = player.isDead() || player.getHealth() <= 0;
                if (dead) {
                    sawDeath = true;
                    if (waitTimer % 10 == 0) player.requestRespawn(); // click "Respawn" for us
                } else if (sawDeath) {
                    debug("Respawned. Ready for the next request.");
                    resetToCooldown();
                    return;
                }
                if (--waitTimer <= 0) {
                    if (!sawDeath) debug("Never died - /" + BessaKitConfig.KILL_COMMAND + " may be blocked on this server.");
                    resetToCooldown();
                }
            }

            case COOLDOWN -> {
                if (--waitTimer <= 0) {
                    currentRequest = null;
                    chestPos = null;
                    state = State.IDLE;
                }
            }
        }
    }

    private void resetToCooldown() {
        navigator.stop();
        waitTimer = BessaKitConfig.COOLDOWN_TICKS;
        state = State.COOLDOWN;
    }

    private boolean openChest(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null || client.interactionManager == null) return false;
        if (!(client.world.getBlockState(chestPos).getBlock() instanceof AbstractChestBlock)) return false;
        Vec3d hitVec = Vec3d.ofCenter(chestPos);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, chestPos, false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        return true;
    }

    private void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }
}
