package net.runelite.client.plugins.microbot.mining;

import net.runelite.api.GameObject;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.mining.enums.Rocks;
import net.runelite.client.plugins.microbot.mining.service.RockTracker;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

enum State {
    MINING,
    RESETTING,
}

public class AutoMiningScript extends Script {
        private void applyFastMiningAntibanSetup() {
        Rs2Antiban.resetAntibanSettings();
        Rs2AntibanSettings.antibanEnabled = true;
        Rs2AntibanSettings.usePlayStyle = true;
        Rs2AntibanSettings.randomIntervals = false;
        Rs2AntibanSettings.simulateFatigue = false;
        Rs2AntibanSettings.simulateAttentionSpan = false;
        Rs2AntibanSettings.behavioralVariability = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.takeMicroBreaks = false;
        Rs2AntibanSettings.microBreakChance = 0.01;
        Rs2AntibanSettings.actionCooldownChance = 0.1;

        Rs2Antiban.setActivity(Activity.GENERAL_MINING);
        Rs2Antiban.setActivityIntensity(ActivityIntensity.EXTREME);
    }

    public static final String version = "1.4.4";
    private static final int GEM_MINE_UNDERGROUND = 11410;
    private static final int BASALT_MINE = 11425;
    
    // World hopping protection
    private static volatile long lastWorldHopTime = 0;
    private static final long MIN_WORLD_HOP_DELAY_MS = 5000; // 5 seconds minimum between hops
    private static volatile boolean isCurrentlyHopping = false;
    private static volatile int consecutiveHops = 0;
    private static final int MAX_CONSECUTIVE_HOPS = 10; // Max 10 consecutive hops before forced delay
    
    State state = State.MINING;

    public boolean run(AutoMiningConfig config) {
        initialPlayerLocation = null;
        Rs2Antiban.resetAntibanSettings();
        applyFastMiningAntibanSetup();
        
        //Rs2Antiban.antibanSetupTemplates.applyMiningSetup();
        //Rs2AntibanSettings.actionCooldownChance = 0.1;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) return;
                if (Rs2AntibanSettings.actionCooldownActive) return;
                if (initialPlayerLocation == null) {
                    initialPlayerLocation = Rs2Player.getWorldLocation();
                }

                if (!config.ORE().hasRequiredLevel()) {
                    Microbot.log("You do not have the required mining level to mine this ore.");
                    return;
                }

                if (Rs2Equipment.isWearing("Dragon pickaxe"))
                    Rs2Combat.setSpecState(true, 1000);

                if (Rs2Player.isMoving() || Rs2Player.isAnimating()) return;

                //code to change worlds if there are too many players in the distance to stray tiles
                int maxPlayers = config.maxPlayersInArea();
                if (maxPlayers > 0) {
                    WorldPoint localLocation = Rs2Player.getWorldLocation();

                    long nearbyPlayers = Microbot.getClient().getPlayers().stream()
                            .filter(p -> p != null && p != Microbot.getClient().getLocalPlayer())
                            .filter(p -> {
                                if (config.distanceToStray() == 0) {
                                    // Only count players standing on the same exact tile
                                    return p.getWorldLocation().equals(localLocation);
                                }
                                // Count players within distanceToStray
                                return p.getWorldLocation().distanceTo(localLocation) <= config.distanceToStray();
                            })
                            //filter if players are using mining animation
                            .filter(p -> p.getAnimation() != -1)
                            .count();

                    if (nearbyPlayers >= maxPlayers) {
                        // Check if we can hop (rate limiting)
                        long currentTime = System.currentTimeMillis();
                        if (isCurrentlyHopping) {
                            Microbot.status = "World hop already in progress. Waiting...";
                            return;
                        }
                        
                        // Check for consecutive hop limit
                        if (consecutiveHops >= MAX_CONSECUTIVE_HOPS) {
                            Microbot.status = "Too many consecutive hops. Forced 30s break...";
                            Rs2Random.waitEx(30000, 5000); // 30 second forced break
                            consecutiveHops = 0;
                            return;
                        }
                        
                        if (currentTime - lastWorldHopTime < MIN_WORLD_HOP_DELAY_MS) {
                            Microbot.status = "Too many players nearby but hop on cooldown. Waiting...";
                            return;
                        }
                        
                        isCurrentlyHopping = true;
                        lastWorldHopTime = currentTime;
                        consecutiveHops++;
                        Microbot.status = "Too many players nearby. Hopping...";
                        Rs2Random.waitEx(3200, 800); // Delay to avoid UI locking

                        int world = Login.getRandomWorld(Rs2Player.isMember());
                        boolean hopped = Microbot.hopToWorld(world);
                        if (hopped) {
                            Microbot.status = "Hopped to world: " + world;
                            Rs2Random.waitEx(2000, 500); // Wait for hop to complete
                        }
                        isCurrentlyHopping = false;
                        return; // Exit current cycle after hop
                    }
                }


                switch (state) {
                    case MINING:
                        if (Rs2Inventory.isFull()) {
                            state = State.RESETTING;
                            return;
                        }

                        // Check if we should hop to a world with soon-respawning rocks (5 seconds or less)
                        if (config.hopWorldsIfNoOre() && config.keepTrackOfRocks()) {
                            OptionalInt worldWithSoonRespawningRock = RockTracker.getWorldWithSoonRespawningRock(
                                config.ORE(), Rs2Player.getWorldLocation(), config.distanceToStray()
                            );
                            
                            if (worldWithSoonRespawningRock.isPresent()) {
                                int targetWorld = worldWithSoonRespawningRock.getAsInt();
                                int currentWorld = Microbot.getClient().getWorld();
                                
                                // Only hop if we're not already on that world and not currently hopping
                                if (targetWorld != currentWorld && !isCurrentlyHopping) {
                                    long currentTime = System.currentTimeMillis();
                                    
                                    // Allow smart hops to bypass cooldown if rock is respawning very soon
                                    if (currentTime - lastWorldHopTime >= MIN_WORLD_HOP_DELAY_MS / 2) { // Half cooldown for smart hops
                                        isCurrentlyHopping = true;
                                        lastWorldHopTime = currentTime;
                                        
                                        Microbot.status = "[SMART HOP] Hopping to world " + targetWorld + " for soon-respawning " + config.ORE().getName();
                                        Microbot.log("[SMART HOP] Found " + config.ORE().getName() + " respawning within 5s on world " + targetWorld);
                                        
                                        boolean hopped = Microbot.hopToWorld(targetWorld);
                                        if (hopped) {
                                            Rs2Random.waitEx(2000, 500); // Wait for world hop to complete
                                            Microbot.status = "Hopped to world " + targetWorld + " - Waiting for respawn...";
                                        }
                                        isCurrentlyHopping = false;
                                        return; // Exit current cycle after hop
                                    }
                                }
                            }
                        }

                        // Check if player is currently mining but the rock is depleted
                        if (Rs2Player.isAnimating()) {
                            // Player is mining, check if any nearby rocks are depleted versions of our target ore
                            GameObject depletedRock = Rs2GameObject.getGameObject(config.ORE().getName() + " (depleted)", true, initialPlayerLocation, config.distanceToStray());
                            if (depletedRock == null) {
                                // Try alternative depleted rock names
                                depletedRock = Rs2GameObject.getGameObject("Depleted " + config.ORE().getName().toLowerCase(), true, initialPlayerLocation, config.distanceToStray());
                            }
                            if (depletedRock == null) {
                                // Try generic depleted rock pattern
                                depletedRock = Rs2GameObject.getGameObject("rocks", true, initialPlayerLocation, config.distanceToStray());
                                if (depletedRock != null) {
                                    // Check if this rock name contains our ore type
                                    String rockName = Rs2GameObject.getCompositionName(depletedRock).orElse("");
                                    if (!rockName.toLowerCase().contains(config.ORE().getName().toLowerCase().split(" ")[0])) {
                                        depletedRock = null; // Not our rock type
                                    }
                                }
                            }
                            
                            if (depletedRock != null) {
                                Microbot.log("[DEPLETED ROCK] Detected depleted rock while mining - clicking to stop animation");
                                Microbot.status = "Stopping mining animation on depleted rock...";
                                
                                // Click the depleted rock to stop the mining animation
                                Rs2GameObject.interact(depletedRock);
                                Rs2Random.waitEx(600, 200); // Brief wait for animation to stop
                                
                                // Track the depleted rock if tracking is enabled
                                if (config.hopWorldsIfNoOre() && config.keepTrackOfRocks()) {
                                    RockTracker.trackDepletedRock(depletedRock.getWorldLocation(), config.ORE(), config.inMiningGuild());
                                    String guildText = config.inMiningGuild() ? " (Mining Guild)" : "";
                                    Microbot.log("[ROCK TRACKER] Tracked depleted " + config.ORE().getName() + " at " + depletedRock.getWorldLocation() + guildText);
                                }
                                
                                return; // Exit this cycle to look for new rocks
                            }
                        }

                        GameObject rock = Rs2GameObject.getGameObject(config.ORE().getName(), true, initialPlayerLocation, config.distanceToStray());

                        if (rock != null) {
                            if (Rs2GameObject.interact(rock)) {
                                Rs2Player.waitForXpDrop(Skill.MINING, true);
                                
                                // Reset consecutive hops counter when successfully mining
                                consecutiveHops = 0;
                                
                                // Track the rock as depleted for smart world hopping
                                if (config.hopWorldsIfNoOre() && config.keepTrackOfRocks()) {
                                    RockTracker.trackDepletedRock(rock.getWorldLocation(), config.ORE(), config.inMiningGuild());
                                    String guildText = config.inMiningGuild() ? " (Mining Guild)" : "";
                                    Microbot.log("[ROCK TRACKER] Tracked depleted " + config.ORE().getName() + " at " + rock.getWorldLocation() + guildText);
                                }
                                
                                Rs2Antiban.actionCooldown();
                                Rs2Antiban.takeMicroBreakByChance();
                            }
                        } else if (config.hopWorldsIfNoOre()) {
                            // Enhanced world hopping with rock tracking
                            handleNoRockWorldHopping(config);
                        }
                        break;
                    case RESETTING:
                        List<String> itemNames = Arrays.stream(config.itemsToBank().split(",")).map(String::toLowerCase).collect(Collectors.toList());

                        if (config.useBank()) {
                            if (config.ORE() == Rocks.GEM && Rs2Player.getWorldLocation().getRegionID() == GEM_MINE_UNDERGROUND) {
                                if (Rs2DepositBox.openDepositBox()) {
                                    if (Rs2Inventory.contains("Open gem bag")) {
                                        Rs2Inventory.interact("Open gem bag", "Empty");
                                        Rs2DepositBox.depositAllExcept("Open gem bag");
                                    } else {
                                        Rs2DepositBox.depositAll();
                                    }
                                    Rs2DepositBox.closeDepositBox();
                                }
                            }
                            else if (Rocks.BASALT == config.ORE() && BASALT_MINE == Rs2Player.getWorldLocation().getRegionID()) {
                                if (Rs2Walker.walkTo(2872,3935,0)){
                                    Rs2Inventory.useItemOnNpc(ItemID.BASALT, NpcID.MY2ARM_SNOWFLAKE);
                                    Rs2Walker.walkTo(2841,10339,0);
                                }
                            } else {
                                if (!Rs2Bank.bankItemsAndWalkBackToOriginalPosition(itemNames, initialPlayerLocation, 0, config.distanceToStray()))
                                    return;
                            }

                        } else {
                            Rs2Inventory.dropAllExcept(false, config.interactOrder(), Arrays.stream(config.itemsToKeep().split(",")).map(String::trim).toArray(String[]::new));
                        }

                        state = State.MINING;
                        break;
                }
            } catch (Exception ex) {
                Microbot.log(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * Handle world hopping when no rocks are found, using intelligent rock tracking or random hopping
     */
    private void handleNoRockWorldHopping(AutoMiningConfig config) {
        // Check if we can hop (rate limiting)
        long currentTime = System.currentTimeMillis();
        if (isCurrentlyHopping) {
            Microbot.status = "World hop already in progress. Waiting...";
            return;
        }
        
        // Check for consecutive hop limit
        if (consecutiveHops >= MAX_CONSECUTIVE_HOPS) {
            Microbot.status = "Too many consecutive hops. Forced 30s break...";
            Rs2Random.waitEx(30000, 5000); // 30 second forced break
            consecutiveHops = 0;
            return;
        }
        
        if (currentTime - lastWorldHopTime < MIN_WORLD_HOP_DELAY_MS) {
            Microbot.status = "World hop on cooldown. Waiting " + 
                    ((MIN_WORLD_HOP_DELAY_MS - (currentTime - lastWorldHopTime)) / 1000) + "s...";
            return;
        }
        
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        
        int targetWorld;
        String hopReason;
        String trackingStats = "";
        
        if (config.keepTrackOfRocks()) {
            // Use intelligent rock tracking
            
            // Get detailed rock tracking information
            boolean hasAvailableRocks = RockTracker.hasAvailableRocksInArea(playerLoc, config.ORE(), config.distanceToStray());
            int soonRespawningCount = RockTracker.getSoonRespawningRocksCount(playerLoc, config.ORE(), config.distanceToStray(), 5000); // within 5 seconds
            boolean worldFullyExplored = RockTracker.isCurrentWorldFullyExplored(playerLoc, config.ORE(), config.distanceToStray());
            
            // Only wait if we have rocks that will respawn very soon (within 5 seconds) and world isn't fully explored
            if (hasAvailableRocks && soonRespawningCount > 0 && !worldFullyExplored) {
                Microbot.status = String.format("No rocks found but %d respawning within 5s. Waiting...", soonRespawningCount);
                Rs2Random.waitEx(2000, 500); // Shorter wait time
                return;
            }
            
            // If current world seems fully explored or has no available rocks, hop more aggressively
            if (worldFullyExplored) {
                Microbot.log("[SEARCH] Current world appears fully explored with no available rocks - hopping");
            }
            
            // Try to find the best world based on rock tracking data
            OptionalInt bestWorld = RockTracker.getBestWorldForRockType(config.ORE(), playerLoc, config.distanceToStray());
            
            if (bestWorld.isPresent()) {
                targetWorld = bestWorld.getAsInt();
                hopReason = "Smart hop to world with tracked available " + config.ORE().getName();
            } else {
                targetWorld = Login.getRandomWorld(Rs2Player.isMember());
                hopReason = "Exploring new world for " + config.ORE().getName() + " (no tracked data)";
            }
            
            // Get rock tracking stats for status
            trackingStats = " | " + RockTracker.getRockTrackingStats(config.ORE(), playerLoc, config.distanceToStray());
        } else {
            // Use random world hopping (tracking disabled)
            targetWorld = Login.getRandomWorld(Rs2Player.isMember());
            hopReason = "Random world hop for " + config.ORE().getName() + " (tracking disabled)";
        }
        
        // Set hopping state and timestamp
        isCurrentlyHopping = true;
        lastWorldHopTime = currentTime;
        consecutiveHops++;
        
        Microbot.status = "[WORLD HOP] " + hopReason + " -> World " + targetWorld;
        Microbot.log("[WORLD HOP] " + hopReason + " | New World: " + targetWorld + trackingStats + " | Hops: " + consecutiveHops);
        
        boolean hopped = Microbot.hopToWorld(targetWorld);
        if (hopped) {
            Rs2Random.waitEx(2000, 500); // Wait for world hop to complete
            Microbot.status = "Hopped to world " + targetWorld + " - Resuming mining...";
            
            // Clear current world tracking after hopping to get fresh data
            if (config.keepTrackOfRocks()) {
                // Give the new world a chance, clear old tracking data for this world after some time
                // This will be handled by the periodic cleanup
            }
        }
        
        // Reset hopping state
        isCurrentlyHopping = false;
    }

    @Override
    public void shutdown(){
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
