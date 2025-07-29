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
                        Microbot.status = "Too many players nearby. Hopping...";
                        Rs2Random.waitEx(3200, 800); // Delay to avoid UI locking

                        int world = Login.getRandomWorld(Rs2Player.isMember());
                        boolean hopped = Microbot.hopToWorld(world);
                        if (hopped) {
                            Microbot.status = "Hopped to world: " + world;
                            return; // Exit current cycle after hop
                        }
                    }
                }


                switch (state) {
                    case MINING:
                        if (Rs2Inventory.isFull()) {
                            state = State.RESETTING;
                            return;
                        }

                        GameObject rock = Rs2GameObject.getGameObject(config.ORE().getName(), true, initialPlayerLocation, config.distanceToStray());

                        if (rock != null) {
                            if (Rs2GameObject.interact(rock)) {
                                Rs2Player.waitForXpDrop(Skill.MINING, true);
                                
                                // Track the rock as depleted for smart world hopping
                                if (config.hopWorldsIfNoOre() && config.keepTrackOfRocks()) {
                                    RockTracker.trackDepletedRock(rock.getWorldLocation(), config.ORE(), config.inMiningGuild());
                                    String guildText = config.inMiningGuild() ? " (Mining Guild)" : "";
                                    Microbot.log("🗿 Tracked depleted " + config.ORE().getName() + " at " + rock.getWorldLocation() + guildText);
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
                Microbot.log("🔍 Current world appears fully explored with no available rocks - hopping");
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
        
        Microbot.status = "🌍 " + hopReason + " → World " + targetWorld;
        Microbot.log("🌍 WORLD HOP: " + hopReason + " | New World: " + targetWorld + trackingStats);
        
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
    }

    @Override
    public void shutdown(){
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
