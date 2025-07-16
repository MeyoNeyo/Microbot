package net.runelite.client.plugins.microbot.apexfighter.combat;

import lombok.SneakyThrows;
import net.runelite.api.Actor;
import net.runelite.api.coords.WorldPoint;
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

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.runelite.client.plugins.microbot.apexfighter.worldhop.WorldHopManager;

public class AttackNpcScript extends Script {
    private long lastMonsterFoundTime = System.currentTimeMillis();

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

                // Process world hop state first
                WorldHopManager.processWorldHop();
                if(config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) < config.attackRadius() &&
                        !config.centerLocation().equals(new WorldPoint(0, 0, 0)) &&  ApexFighterPlugin.getState() != State.BANKING) {
                    if(ShortestPathPlugin.getPathfinder() != null)
                        Rs2Walker.setTarget(null);
                    ApexFighterPlugin.setState(State.IDLE);
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
                    // Count players in area (excluding self)
                    net.runelite.api.Player localPlayer = Microbot.getClient().getLocalPlayer();
                    long playersInArea = Microbot.getClient().getTopLevelWorldView().players().stream()
                        .filter(p -> p != localPlayer)  // Exclude the local player
                        .filter(p -> p.getWorldLocation().distanceTo(config.centerLocation()) <= config.attackRadius())
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
                    
                    // World hop logic with pause/resume
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

                if(config.state().equals(State.BANKING) || config.state().equals(State.WALKING))
                    return;

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

                if (!attackableNpcs.isEmpty()) {
                    Rs2NpcModel npc = attackableNpcs.stream().findFirst().orElse(null);

                    if (!Rs2Camera.isTileOnScreen(npc.getLocalLocation()))
                        Rs2Camera.turnTo(npc);

                    Rs2Npc.interact(npc, "attack");
                    Microbot.status = "Attacking " + npc.getName();
                    ApexFighterPlugin.setCooldown(config.playStyle().getRandomTickInterval());

                } else {
                    Microbot.log("Standing still: no attackable NPCs and not hopping.");
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
            Rs2Inventory.useItemOnNpc(net.runelite.api.ItemID.ICE_COOLER, npc);
            Rs2Player.waitForAnimation();
        } else if (npc.getName().equalsIgnoreCase("rockslug") && npc.getHealthRatio() < 5) {
            Rs2Inventory.useItemOnNpc(net.runelite.api.ItemID.BAG_OF_SALT, npc);
            Rs2Player.waitForAnimation();
        } else if (npc.getName().equalsIgnoreCase("gargoyle") && npc.getHealthRatio() < 3) {
            Rs2Inventory.useItemOnNpc(net.runelite.api.ItemID.ROCK_HAMMER, npc);
            Rs2Player.waitForAnimation();
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}