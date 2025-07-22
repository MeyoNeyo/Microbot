package net.runelite.client.plugins.microbot.apexfighter.combat;

import lombok.SneakyThrows;
import net.runelite.api.Actor;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterPlugin;
import net.runelite.client.plugins.microbot.apexfighter.enums.State;
import net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin;
import net.runelite.client.plugins.microbot.util.ActorModel;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldArea;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcManager;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.apexfighter.worldhop.WorldHopManager;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class AttackNpcScript extends Script {
    // Arrow/bolt usage is automatically tracked by ConsumableUsageMonitor
    private long lastMonsterFoundTime = System.currentTimeMillis();
    private int consecutiveAttackFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    public static Actor currentNpc = null;
    public static AtomicReference<List<Rs2NpcModel>> filteredAttackableNpcs = new AtomicReference<>(new ArrayList<>());
    public static Rs2WorldArea attackableArea = null;
    private boolean messageShown = false;

    public static void skipNpc() {
        currentNpc = null;
    }

    @SneakyThrows
    public void run(ApexFighterConfig config) {
        try {
            Rs2NpcManager.loadJson();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                // Let Microbot.pauseAllScripts handle pausing during world hops
                // Don't check plugin state here as it can get stuck
                if (!Microbot.isLoggedIn() || !super.run() || !config.toggleCombat())
                    return;
                    
                // Skip execution if scripts are paused (e.g., during world hopping)
                if (Microbot.pauseAllScripts.get()) {
                    return;
                }

                // Process world hop state first
                WorldHopManager.processWorldHop();
                
                // Set state to IDLE when in center area but not in combat/banking
                if(config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) < config.attackRadius() &&
                        !config.centerLocation().equals(new WorldPoint(0, 0, 0)) && 
                        ApexFighterPlugin.getState() != State.BANKING && 
                        ApexFighterPlugin.getState() != State.COMBAT &&
                        !Rs2Combat.inCombat()) {
                    if(ShortestPathPlugin.getPathfinder() != null)
                        Rs2Walker.setTarget(null);
                    // Only set to IDLE if we're not already in a valid state
                    if (ApexFighterPlugin.getState() == State.WALKING) {
                        ApexFighterPlugin.setState(State.IDLE);
                    }
                }

                attackableArea = new Rs2WorldArea(config.centerLocation().toWorldArea());
                attackableArea = attackableArea.offset(config.attackRadius());
                List<String> npcsToAttack = Arrays.stream(config.attackableNpcs().split(","))
                        .map(x -> x.trim().toLowerCase())
                        .collect(Collectors.toList());

                filteredAttackableNpcs.set(
                        Rs2Npc.getAttackableNpcs(config.attackReachableNpcs())
                                .filter(npc -> npc.getWorldLocation().distanceTo(config.centerLocation()) <= config.attackRadius())
                                .filter(npc -> {
                                    String name = npc.getName();
                                    if (name == null || name.isEmpty()) return false;
                                    return !npcsToAttack.isEmpty() && npcsToAttack.stream().anyMatch(name::equalsIgnoreCase);
                                })
                                .sorted(Comparator.comparingInt((Rs2NpcModel npc) -> Objects.equals(npc.getInteracting(), Microbot.getClient().getLocalPlayer()) ? 0 : 1)
                                        .thenComparingInt(npc -> Rs2Player.getRs2WorldPoint().distanceToPath(npc.getWorldLocation())))
                                .collect(Collectors.toList())
                );
                final List<Rs2NpcModel> attackableNpcs = new ArrayList<>();

                for (var attackableNpc: filteredAttackableNpcs.get()) {
                    if (attackableNpc == null || attackableNpc.getName() == null) continue;
                    for (var npcToAttack: npcsToAttack) {
                        if (npcToAttack.equalsIgnoreCase(attackableNpc.getName())) {
                            attackableNpcs.add(attackableNpc);
                        }
                    }
                }


                filteredAttackableNpcs.set(attackableNpcs);

                // World hop logic - check after monsters are filtered
                boolean inTargetArea = config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) <= config.attackRadius();
                if (inTargetArea) {
                    // Count players in area (excluding self) - use same center logic as getPlayerCountInArea
                    WorldPoint center = config.toggleCenterTile() ? config.centerLocation() : Rs2Player.getWorldLocation();
                    Player localPlayer = Microbot.getClient().getLocalPlayer();
                    long playersInArea = Microbot.getClient().getTopLevelWorldView().players().stream()
                        .filter(p -> p != localPlayer)  // Exclude the local player
                        .filter(p -> p.getWorldLocation().distanceTo(center) <= config.attackRadius())
                        .count();
                    
                    int maxPlayers = config.maxPlayersBeforeHop();
                    int maxSecondsWithoutMonsters = config.maxSecondsWithoutMonstersBeforeHop();
                    
                    // Update monster timer based on filtered monster list
                    if (!attackableNpcs.isEmpty()) {
                        lastMonsterFoundTime = System.currentTimeMillis();
                    }
                    
                    int secondsWithoutMonsters = (int)((System.currentTimeMillis() - lastMonsterFoundTime) / 1000);
                    
                    // Log current conditions for debugging
                    if (maxPlayers > 0 || maxSecondsWithoutMonsters > 0) {
                        // Only log every 5 seconds to avoid spam
                        if (System.currentTimeMillis() % 5000 < 600) {
                            Microbot.log("[ApexFighter] Area Status - Players: " + playersInArea + "/" + maxPlayers + 
                                       ", Monsters: " + attackableNpcs.size() + ", No monsters for: " + secondsWithoutMonsters + "/" + maxSecondsWithoutMonsters + " seconds");
                        }
                    }
                    
                // PRIORITY CHECK: Banking has priority over world hopping
                if (net.runelite.client.plugins.microbot.apexfighter.bank.BankerScript.isBankingNeeded(config)) {
                    if (maxPlayers > 0 && playersInArea >= maxPlayers) {
                        Microbot.log("[ApexFighter] ⚠️ BANKING NEEDED - Skipping world hop due to too many players. Banking takes priority!");
                    }
                    if (maxSecondsWithoutMonsters > 0 && secondsWithoutMonsters >= maxSecondsWithoutMonsters) {
                        Microbot.log("[ApexFighter] ⚠️ BANKING NEEDED - Skipping world hop due to no monsters. Banking takes priority!");
                    }
                    // Don't hop worlds, but ALLOW combat to continue unless food is completely depleted
                    // Check for critical banking needs (food depletion) - use simpler food check
                    boolean criticalBanking = false;
                    if (config.useFood()) {
                        int foodCount = net.runelite.client.plugins.microbot.util.misc.Rs2Food.getIds().stream()
                            .mapToInt(Rs2Inventory::count).sum();
                        if (foodCount == 0) {
                            criticalBanking = true;
                        }
                    }
                    if (criticalBanking) {
                        Microbot.log("[AttackNpc] CRITICAL - No food left, stopping combat for emergency banking");
                        return; // Stop combat if no food
                    }
                    // Otherwise continue combat even if banking is needed (non-critical)
                }                    // World hop logic with pause/resume (only if banking is NOT needed)
                    if ((maxPlayers > 0 && playersInArea >= maxPlayers) ||
                        (maxSecondsWithoutMonsters > 0 && secondsWithoutMonsters >= maxSecondsWithoutMonsters)) {
                        // Only hop if not already paused
                        if (!Microbot.pauseAllScripts.get()) {
                            String hopReason = playersInArea >= maxPlayers ? 
                                "TOO MANY PLAYERS: Found " + playersInArea + " players in area (max allowed: " + maxPlayers + ")" : 
                                "NO MONSTERS: No monsters found for " + secondsWithoutMonsters + " seconds (max allowed: " + maxSecondsWithoutMonsters + ")";
                            Microbot.log("[ApexFighter] ⚠️ WORLD HOP TRIGGERED - " + hopReason);
                            net.runelite.client.plugins.microbot.apexfighter.worldhop.WorldHopManager.safeHopWorlds(hopReason);
                        } else {
                            Microbot.log("[ApexFighter] World hop conditions met but scripts already paused - waiting for current hop to complete");
                        }
                        return;
                    }
                } else {
                    // Reset timer if not in area
                    lastMonsterFoundTime = System.currentTimeMillis();
                }

                if(ApexFighterPlugin.getState().equals(State.BANKING) || ApexFighterPlugin.getState().equals(State.WALKING))
                    return;
                    
                // PRIORITY CHECK: If banking is needed, don't attack - let banking system handle it
                if (net.runelite.client.plugins.microbot.apexfighter.bank.BankerScript.isBankingNeeded(config)) {
                    Microbot.log("[AttackNpc] Banking needed - skipping attack to prioritize banking");
                    return;
                }

                if (config.toggleCenterTile() && config.centerLocation().getX() == 0
                        && config.centerLocation().getY() == 0) {
                    String coords = config.manualCenterTileCoords();
                    if (coords != null && !coords.isEmpty()) {
                        String[] parts = coords.split(",");
                        if (parts.length == 3) {
                            try {
                                int x = Integer.parseInt(parts[0].trim());
                                int y = Integer.parseInt(parts[1].trim());
                                int z = Integer.parseInt(parts[2].trim());
                                ApexFighterPlugin.setCenter(new net.runelite.api.coords.WorldPoint(x, y, z));
                                messageShown = false;
                                // After setting, continue as normal
                            } catch (NumberFormatException ignored) {
                                if (!messageShown) {
                                    Microbot.showMessage("Please set a valid center location");
                                    messageShown = true;
                                }
                                return;
                            }
                        } else {
                            if (!messageShown) {
                                Microbot.showMessage("Please set a center location");
                                messageShown = true;
                            }
                            return;
                        }
                    } else {
                        if (!messageShown) {
                            Microbot.showMessage("Please set a center location");
                            messageShown = true;
                        }
                        return;
                    }
                }
                messageShown = false;

                if (ApexFighterPlugin.getCooldown() > 0 || Rs2Combat.inCombat()) {
                    ApexFighterPlugin.setState(State.COMBAT);
                    handleItemOnNpcToKill();
                    return;
                }

                // Reset state to IDLE if no longer in combat and no cooldown
                if (ApexFighterPlugin.getState() == State.COMBAT && ApexFighterPlugin.getCooldown() <= 0 && !Rs2Combat.inCombat()) {
                    Microbot.log("[AttackNpc] Combat finished, resetting to IDLE state");
                    ApexFighterPlugin.setState(State.IDLE);
                }
                
                // IMPORTANT: Check if player is walking to safespot - don't interrupt with combat
                if (Rs2Player.isMoving() && !Rs2Combat.inCombat()) {
                    // Allow safespot movement to complete without combat interference
                    return;
                }

                if (!attackableNpcs.isEmpty()) {
                    Microbot.log("[AttackNpc] Found " + attackableNpcs.size() + " attackable NPCs, current state: " + ApexFighterPlugin.getState());
                    ApexFighterPlugin.setState(State.COMBAT); // Set state to combat when about to attack
                    Microbot.log("[AttackNpc] State set to COMBAT, proceeding with attack logic");
                    // Check if we've had too many consecutive failures
                    if (consecutiveAttackFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Microbot.log("[AttackNpc] Too many consecutive attack failures (" + consecutiveAttackFailures + "), waiting before retrying");
                        consecutiveAttackFailures = 0; // Reset counter
                        try {
                            Thread.sleep(2000); // Wait 2 seconds before retrying
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    
                    Rs2NpcModel npc = attackableNpcs.stream().findFirst().orElse(null);
                    
                    if (npc == null) {
                        Microbot.log("[AttackNpc] No valid NPC found from filtered list");
                        return;
                    }

                    // Check if camera needs adjustment and wait for it to complete
                    if (!Rs2Camera.isTileOnScreen(npc.getLocalLocation())) {
                        Microbot.log("[AttackNpc] Turning camera to " + npc.getName());
                        
                        try {
                            // Use a smaller angle to prevent camera getting stuck
                            int angle = Rs2Camera.getCharacterAngle(npc);
                            Rs2Camera.setAngle(angle, 20); // Use smaller max angle for better control
                            
                            // Wait for camera movement to complete with timeout
                            long cameraStartTime = System.currentTimeMillis();
                            boolean cameraSuccess = false;
                            
                            while ((System.currentTimeMillis() - cameraStartTime) < 3000) { // 3 second timeout
                                if (Rs2Camera.isTileOnScreen(npc.getLocalLocation())) {
                                    cameraSuccess = true;
                                    break;
                                }
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            
                            if (!cameraSuccess) {
                                Microbot.log("[AttackNpc] Camera movement timed out, trying alternative approach");
                                // Alternative: Try center tile on screen method
                                Rs2Camera.centerTileOnScreen(npc.getLocalLocation(), 15.0);
                                
                                // One more check with shorter timeout
                                cameraStartTime = System.currentTimeMillis();
                                while ((System.currentTimeMillis() - cameraStartTime) < 1500) {
                                    if (Rs2Camera.isTileOnScreen(npc.getLocalLocation())) {
                                        cameraSuccess = true;
                                        break;
                                    }
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                }
                            }
                            
                            // Final check - if camera still failed, skip this NPC
                            if (!cameraSuccess || !Rs2Camera.isTileOnScreen(npc.getLocalLocation())) {
                                Microbot.log("[AttackNpc] Camera failed to turn to NPC after multiple attempts, skipping attack this cycle");
                                consecutiveAttackFailures++;
                                return;
                            }
                            
                            Microbot.log("[AttackNpc] Camera successfully positioned for " + npc.getName());
                            
                        } catch (Exception e) {
                            Microbot.log("[AttackNpc] Error during camera movement: " + e.getMessage());
                            consecutiveAttackFailures++;
                            return;
                        }
                    }

                    // Attempt to attack the NPC
                    Microbot.log("[AttackNpc] Attempting to attack " + npc.getName());
                    boolean attackSuccess = Rs2Npc.interact(npc, "attack");
                    
                    if (attackSuccess) {
                        Microbot.status = "Attacking " + npc.getName();
                        ApexFighterPlugin.setCooldown(config.playStyle().getRandomTickInterval());
                        Microbot.log("[AttackNpc] Successfully initiated attack on " + npc.getName());
                        consecutiveAttackFailures = 0; // Reset failure counter on success
                    } else {
                        consecutiveAttackFailures++;
                        Microbot.log("[AttackNpc] Failed to attack " + npc.getName() + ", will retry next cycle (failure count: " + consecutiveAttackFailures + ")");
                        // Don't set cooldown if attack failed, allow immediate retry
                    }

                } else {
                    // Only log every 5 seconds to avoid spam
                    if (System.currentTimeMillis() % 5000 < 600) {
                        Microbot.log("[AttackNpc] No attackable NPCs found - Total filtered: " + filteredAttackableNpcs.get().size() + 
                                   ", In area: " + inTargetArea + ", State: " + ApexFighterPlugin.getState());
                    }
                    consecutiveAttackFailures = 0; // Reset counter when no NPCs found
                    // If we're in combat state but no monsters found, reset to IDLE
                    if (ApexFighterPlugin.getState() == State.COMBAT && !Rs2Combat.inCombat()) {
                        ApexFighterPlugin.setState(State.IDLE);
                    }
                }
            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
    }


    /**
     * item on npcs that need to kill like rockslug
     */
    private void handleItemOnNpcToKill() {
        Rs2NpcModel npc = Rs2Npc.getNpcsForPlayer(ActorModel::isDead).findFirst().orElse(null);
        List<String> lizardVariants = new ArrayList<>(Arrays.asList("Lizard", "Desert Lizard", "Small Lizard"));
        if (npc == null) return;
        if (lizardVariants.contains(npc.getName()) && npc.getHealthRatio() < 5) {
            Rs2Inventory.useItemOnNpc(ItemID.SLAYER_ICY_WATER, npc);
            Rs2Player.waitForAnimation();
        } else if (npc.getName().equalsIgnoreCase("rockslug") && npc.getHealthRatio() < 5) {
            Rs2Inventory.useItemOnNpc(ItemID.SLAYER_BAG_OF_SALT, npc);
            Rs2Player.waitForAnimation();
        } else if (npc.getName().equalsIgnoreCase("gargoyle") && npc.getHealthRatio() < 3) {
            Rs2Inventory.useItemOnNpc(ItemID.SLAYER_ROCK_HAMMER, npc);
            Rs2Player.waitForAnimation();
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}