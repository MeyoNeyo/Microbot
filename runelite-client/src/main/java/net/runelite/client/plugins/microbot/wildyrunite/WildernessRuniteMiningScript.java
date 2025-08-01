package net.runelite.client.plugins.microbot.wildyrunite;

import lombok.Getter;
import net.runelite.api.GameObject;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.kit.KitType;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.mining.enums.Rocks;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.player.Rs2PlayerModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;


import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static net.runelite.api.ObjectID.POOL_OF_REFRESHMENT;

public class WildernessRuniteMiningScript extends Script {

    @Getter
    private int totalMined = 0;
    @Getter
    private int orePrice = 0;

    private static final WorldPoint POOL_OF_REFRESHMENT_TILE = new WorldPoint(3129, 3635, 0);
    private final WorldPoint FEROX_ENCLAVE_BANK = new WorldPoint(3130, 3631, 0);
    private static final WorldPoint LUMBRIDGE_BANK_TILE = new WorldPoint(3209, 3220, 2);
    private final WorldPoint RUNITE_ORE_TILE = new WorldPoint(3059, 3884, 0);
    private volatile boolean isBanking = false;
    private long scriptStartTime;
    private volatile boolean fleeingFromPlayer = false;
    private final AtomicLong fleeingStartTime = new AtomicLong(0);
    private static final long FLEEING_TIMEOUT_MS = 60000; // 60 seconds timeout
    private volatile WorldPoint lastKnownLocation = null;
    private volatile int lastKnownHitpoints = -1;
    private volatile boolean wasInWilderness = false;
    private volatile long lastLocationUpdateTime = 0;

    private final AtomicBoolean scriptRunning = new AtomicBoolean(false);
    @Getter
    private final Set<String> recentAttackers = new HashSet<>();

    private ScheduledFuture<?> worldHopFuture;
    private ScheduledFuture<?> combatFuture;
    private ScheduledFuture<?> zoneFuture;

    private void updateStatus(String message) {
        String timestamp = java.time.LocalTime.now().withNano(0).toString();
        Microbot.status = "[" + timestamp + "] " + message;
        Microbot.log("[Status] " + timestamp + " → " + message);
    }

    /**
     * Thread-safe method to start fleeing from a player
     */
    private void startFleeingFromPlayer(String attackerName) {
        if (!fleeingFromPlayer) {
            fleeingFromPlayer = true;
            fleeingStartTime.set(System.currentTimeMillis());
            lastKnownLocation = Rs2Player.getWorldLocation();
            recentAttackers.add(attackerName);
            updateStatus("⚠️ Started fleeing from player: " + attackerName + " at " + lastKnownLocation);
        }
    }

    /**
     * Thread-safe method to stop fleeing from a player
     */
    private void stopFleeingFromPlayer(String reason) {
        if (fleeingFromPlayer) {
            fleeingFromPlayer = false;
            fleeingStartTime.set(0);
            lastKnownLocation = null;
            updateStatus("✅ Stopped fleeing: " + reason);
        }
    }

    /**
     * Enhanced death detection using multiple indicators for robustness
     */
    private boolean isPlayerDead() {
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        int currentHp = Microbot.getClient().getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS);
        boolean currentlyInWilderness = isInWilderness(currentLocation);
        
        // Method 1: Death animation detection (most reliable)
        if (Rs2Player.isAnimating()) {
            int animationId = Microbot.getClient().getLocalPlayer().getAnimation();
            // Common death animation IDs in OSRS
            if (animationId == 836 || animationId == 2304 || animationId == 1378) {
                updateStatus("🔍 Death detected: Death animation (ID: " + animationId + ")");
                return true;
            }
        }
        
        // Method 2: HP-based detection with location context
        if (currentHp <= 0 && lastKnownHitpoints > 0) {
            updateStatus("🔍 Death detected: HP dropped to 0");
            return true;
        }
        
        // Method 3: Wilderness → Safe zone teleportation with HP context
        if (wasInWilderness && !currentlyInWilderness && currentHp < lastKnownHitpoints) {
            // Common respawn locations
            if (isAtRespawnLocation(currentLocation)) {
                updateStatus("🔍 Death detected: Teleported from wilderness to respawn location");
                return true;
            }
        }
        
        // Method 4: Enhanced location-based detection (improved original method)
        // Only trigger if we were recently in wilderness and have context
        if (wasInWilderness && isAtRespawnLocation(currentLocation)) {
            // Additional validation: check if we lost items or HP
            boolean lostItems = Rs2Inventory.isEmpty() && lastKnownLocation != null;
            boolean significantHpLoss = lastKnownHitpoints > 50 && currentHp < 30;
            
            if (lostItems || significantHpLoss) {
                updateStatus("🔍 Death detected: At respawn location with item/HP loss indicators");
                return true;
            }
        }
        
        // Method 5: Sudden location change with full HP restoration
        if (lastKnownLocation != null && wasInWilderness) {
            double distance = currentLocation.distanceTo(lastKnownLocation);
            long timeSinceUpdate = System.currentTimeMillis() - lastLocationUpdateTime;
            
            // Sudden teleportation (>100 tiles in <5 seconds) + full HP = likely death
            if (distance > 100 && timeSinceUpdate < 5000 && currentHp >= 99) {
                if (isAtRespawnLocation(currentLocation)) {
                    updateStatus("🔍 Death detected: Sudden teleportation to respawn with full HP");
                    return true;
                }
            }
        }
        
        // Update tracking variables for next check
        updatePlayerState(currentLocation, currentHp, currentlyInWilderness);
        
        return false;
    }
    
    /**
     * Check if the player is at any known respawn location
     */
    private boolean isAtRespawnLocation(WorldPoint location) {
        // Lumbridge spawn area (default F2P respawn)
        if (location.distanceTo(new WorldPoint(3222, 3218, 0)) < 25) {
            return true;
        }
        
        // Falador respawn (if player has completed certain quests)
        if (location.distanceTo(new WorldPoint(2966, 3382, 0)) < 15) {
            return true;
        }
        
        // Camelot respawn (if player has completed certain quests)
        if (location.distanceTo(new WorldPoint(2757, 3477, 0)) < 15) {
            return true;
        }
        
        // Edge-ville respawn (if player has high wilderness level)
        if (location.distanceTo(new WorldPoint(3093, 3493, 0)) < 15) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if location is in wilderness
     */
    private boolean isInWilderness(WorldPoint location) {
        return location.getY() > 3520; // Wilderness starts at Y > 3520
    }
    
    /**
     * Update player state tracking for death detection
     */
    private void updatePlayerState(WorldPoint location, int hp, boolean inWilderness) {
        lastKnownLocation = location;
        lastKnownHitpoints = hp;
        wasInWilderness = inWilderness;
        lastLocationUpdateTime = System.currentTimeMillis();
    }

    /**
     * Check if fleeing has timed out
     */
    private boolean hasFleeingTimedOut() {
        long startTime = fleeingStartTime.get();
        return startTime > 0 && (System.currentTimeMillis() - startTime) > FLEEING_TIMEOUT_MS;
    }

    private void stopAllFutures() {
        if (worldHopFuture != null && !worldHopFuture.isDone()) {
            worldHopFuture.cancel(true);
        }
        if (combatFuture != null && !combatFuture.isDone()) {
            combatFuture.cancel(true);
        }
        if (zoneFuture != null && !zoneFuture.isDone()) {
            zoneFuture.cancel(true);
        }
    }

    private boolean preparePickaxeAndAxe() {
        updateStatus("Checking equipment for pickaxe and axe...");

        boolean hasPick = Rs2Inventory.hasItem("Rune pickaxe")
                || Rs2Inventory.contains("Rune pickaxe")
                || Microbot.getClient().getLocalPlayer().getPlayerComposition().getEquipmentId(KitType.WEAPON) == ItemID.RUNE_PICKAXE;

        if (!hasPick && Rs2Bank.openBank()) {
            updateStatus("Bank opened. Attempting to withdraw Rune pickaxe...");
            if (Rs2Bank.hasItem("Rune pickaxe")) {
                Rs2Bank.withdrawX("Rune pickaxe", 1);
                sleep(600);
                Rs2Inventory.interact("Rune pickaxe", "Wield");
                hasPick = true;
                updateStatus("Rune pickaxe withdrawn and wielded.");
            } else {
                updateStatus("No rune pickaxe found! Stopping script.");
                Rs2Bank.closeBank();
                shutdown();
                return false;
            }

            updateStatus("Looking for best available axe...");
            String[] f2pAxes = {
                    "Rune axe", "Adamant axe", "Mithril axe",
                    "Steel axe", "Black axe", "Iron axe", "Bronze axe"
            };

            for (String axe : f2pAxes) {
                if (Rs2Bank.hasItem(axe)) {
                    Rs2Bank.withdrawX(axe, 1);
                    sleep(600);
                    updateStatus(axe + " withdrawn.");
                    break;
                }
            }

            Rs2Bank.closeBank();
            updateStatus("Bank closed after gear prep.");
        }

        return hasPick;
    }

    private void startWorldHopMonitoring() {
        worldHopFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!scriptRunning.get()) return;
                
                String hopReason = shouldHopWorldWithReason();
                if (hopReason != null) {
                    int world = Login.getRandomWorld(Rs2Player.isMember());
                    Microbot.hopToWorld(world);
                    updateStatus("🌍 WORLD HOP: " + hopReason + " → World " + world);
                    Microbot.log("🌍 WORLD HOP REASON: " + hopReason + " | New World: " + world);
                }
            } catch (Exception e) {
                updateStatus("Error in world hop monitoring: " + e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    private String shouldHopWorldWithReason() {
        WorldPoint loc = Rs2Player.getWorldLocation();
        
        // Only hop if we're above Ferox Enclave (in wilderness)
        if (loc.getY() <= 3850) {
            return null; // Don't hop if we're at Ferox or below
        }
        
        // Check for other players with PvP level filtering
        List<Rs2PlayerModel> allPlayerModels = Rs2Player.getPlayers(p -> p != null && !p.equals(Rs2Player.getLocalPlayer()))
                .collect(Collectors.toList());
        
        if (allPlayerModels.isEmpty()) {
            return null; // No players detected
        }
        
        // Filter for attackable players (who can actually attack you in PvP)
        List<Rs2PlayerModel> attackablePlayerModels = allPlayerModels.stream()
                .filter(p -> Rs2Pvp.isAttackable(p))
                .collect(Collectors.toList());
        
        // If there are players but none can attack you, stay and mine
        if (attackablePlayerModels.isEmpty()) {
            updateStatus("👥 " + allPlayerModels.size() + " player(s) detected but none can attack (combat level safe) → Continue mining");
            return null; // Safe to stay
        }
        
        // Build detailed reason with player info
        StringBuilder reason = new StringBuilder();
        reason.append("⚔️ DANGEROUS PLAYER(S) detected: ");
        reason.append(attackablePlayerModels.size()).append(" attackable");
        
        if (allPlayerModels.size() > attackablePlayerModels.size()) {
            reason.append(" (").append(allPlayerModels.size() - attackablePlayerModels.size()).append(" safe)");
        }
        
        // Add combat level details for first few attackable players
        if (attackablePlayerModels.size() <= 3) {
            reason.append(" [");
            for (int i = 0; i < attackablePlayerModels.size(); i++) {
                if (i > 0) reason.append(", ");
                Rs2PlayerModel p = attackablePlayerModels.get(i);
                reason.append("CB").append(p.getCombatLevel());
                String playerName = p.getName();
                if (playerName != null && !playerName.isEmpty()) {
                    reason.append(":").append(playerName);
                }
            }
            reason.append("]");
        }
        
        return reason.toString();
    }

    private void monitorZone() {
        zoneFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!scriptRunning.get()) return;
                
                boolean inFerox = Rs2Player.getWorldLocation().distanceTo(FEROX_ENCLAVE_BANK) < 30;
                if (inFerox) {
                    updateStatus("Detected in Ferox. Preparing tools.");
                    preparePickaxeAndAxe();
                }
                // Note: Removed world hop logic as it should be managed separately
            } catch (Exception e) {
                updateStatus("Error in zone monitoring: " + e.getMessage());
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    private void monitorCombatAndHealth() {
        combatFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!scriptRunning.get()) return;

                // **CRITICAL FIX**: Check for death and timeout first
                if (isPlayerDead()) {
                    stopFleeingFromPlayer("Player died - detected respawn");
                    return;
                }

                if (hasFleeingTimedOut()) {
                    stopFleeingFromPlayer("Fleeing timeout exceeded (" + (FLEEING_TIMEOUT_MS / 1000) + "s)");
                    return;
                }

                // Update last known location for death detection
                if (!fleeingFromPlayer) {
                    lastKnownLocation = Rs2Player.getWorldLocation();
                }
                
                if (Rs2Combat.inCombat()) {
                    Rs2PlayerModel local = Rs2Player.getLocalPlayer();

                    Rs2Player.getPlayers(p -> p != null && !p.equals(local) && p.getInteracting() == local)
                            .findFirst()
                            .ifPresent(attacker -> {
                                startFleeingFromPlayer(attacker.getName());

                                if (Rs2Player.getHealthPercentage() < 50 &&
                                        !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_ITEM)) {
                                    Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_ITEM);
                                    updateStatus("🛡️ Low HP → Protect Item activated.");
                                }

                                stopWalking();
                                updateStatus("🏃 Fleeing to Ferox Enclave...");
                                walkToFerox();
                            });
                }
            } catch (Exception e) {
                updateStatus("Error in combat monitoring: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }


    public int calculateGpPerHour() {
        long elapsedMillis = System.currentTimeMillis() - scriptStartTime;
        double hours = elapsedMillis / (1000.0 * 60 * 60);
        if (hours == 0) return 0;
        return (int) ((totalMined * orePrice) / hours);
    }

    private void setTopDownCameraView() {
        if (Microbot.getClient() == null) return;
        Microbot.getClient().setCameraPitchTarget(383);
        Microbot.getClient().setCameraYawTarget(0);
        Microbot.getClient().setCameraShakeDisabled(true);
        updateStatus("Camera set to top-down view.");
    }

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
        updateStatus("Applied antiban mining configuration (EXTREME speed).");
    }

    public void run(WildernessRuniteMiningConfig config) {
        if (scriptRunning.get() || Microbot.getClient() == null) return;
        scriptRunning.set(true);
        scriptStartTime = System.currentTimeMillis();

        updateStatus("Waiting for login...");
        while (!Microbot.isLoggedIn() && scriptRunning.get()) {
            sleep(500);
        }
        updateStatus("Logged in. Starting script...");

        applyFastMiningAntibanSetup();
        Microbot.enableAutoRunOn = true;
        setTopDownCameraView();

        try {
            orePrice = Microbot.getItemManager().search("Runite ore").get(0).getPrice();
            updateStatus("Runite ore price fetched: " + orePrice + " gp");
        } catch (Exception e) {
            orePrice = 11500;
            updateStatus("Failed to fetch ore price, using default: 11500 gp");
        }

        if (!preparePickaxeAndAxe()) {
            shutdown();
            return;
        }

        monitorCombatAndHealth();
        monitorZone();
        startWorldHopMonitoring();
        
        // Log world hopping configuration
        updateStatus("🌍 World Hopping enabled for: 1) Player detection in wilderness 2) No rocks found 3) Mining failed to start");
        Microbot.log("🌍 WORLD HOP TRIGGERS: Player detection, No rocks, Mining animation failure");

        // Main script logic using ScheduledExecutorService
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!scriptRunning.get()) return;
                
                if (!Microbot.isLoggedIn()) {
                    updateStatus("Not logged in. Waiting...");
                    return;
                }

                // **ENHANCED DEATH DETECTION**: Check death and timeout states first
                if (isPlayerDead()) {
                    updateStatus("Detected death. Starting recovery...");
                    stopFleeingFromPlayer("Player died - detected respawn");
                    handleLumbridgeDeathRecovery();
                    return;
                }

                if (fleeingFromPlayer) {
                    updateStatus("⚠️ Fleeing from player → Pausing all actions.");
                    // When arriving safely at Ferox, reset the state
                    if (Rs2Player.getWorldLocation().distanceTo(FEROX_ENCLAVE_BANK) < 5) {
                        stopFleeingFromPlayer("Arrived safely at Ferox Enclave");
                    } else {
                        return; // Continue fleeing
                    }
                }

                // Main banking logic
                if (hasEnoughOre(config) || isBanking) {
                    // 🔁 Always walk if not banking or too far from bank
                    if (!isBanking || Rs2Player.getWorldLocation().distanceTo(FEROX_ENCLAVE_BANK) >= 5) {
                        isBanking = true;
                        updateStatus("Inventory full. Banking runite ore...");
                        walkToFerox();
                    }

                    if (Rs2Player.getWorldLocation().distanceTo(FEROX_ENCLAVE_BANK) < 5) {
                        updateStatus("Arrived at Ferox. Banking...");
                        bankOres();
                        drinkPoolIfAtFerox();

                        if (config.stopAfterOneRun()) {
                            updateStatus("Stopping script after one full run.");
                            shutdown();
                            return;
                        }

                        updateStatus("Banking done. Walking back to ore...");
                        isBanking = false;
                        walkToOre();
                    }

                    return;
                }

                if (Rs2Player.getWorldLocation().distanceTo(RUNITE_ORE_TILE) > 3) {
                    updateStatus("Walking to Runite ore tile...");
                    walkToOre();
                    return;
                }

                GameObject rock = Rs2GameObject.findReachableObject(
                        Rocks.RUNITE.getName(), true, 10, Rs2Player.getWorldLocation());

                if (rock != null && !Rs2Player.isAnimating()) {
                    int oreBefore = Rs2Inventory.count("Runite ore");
                    updateStatus("✅ Rock found. Attempting to mine...");

                    if (Rs2GameObject.interact(rock, "Mine")) {
                        boolean startedMining = sleepUntil(Rs2Player::isAnimating, 2000);

                        if (!startedMining) {
                            int world = Login.getRandomWorld(Rs2Player.isMember());
                            String hopReason = "Mining animation failed to start (rock possibly depleted or lag)";
                            updateStatus("🌍 WORLD HOP: " + hopReason + " → World " + world);
                            Microbot.log("🌍 WORLD HOP REASON: " + hopReason + " | New World: " + world);
                            Microbot.hopToWorld(world);
                            sleep(2000);
                            return;
                        }

                        // Wait until mining finishes
                        sleepUntil(() -> !Rs2Player.isAnimating(), 8000);
                        sleep(300); // short delay for inventory to update

                        int oreAfter = Rs2Inventory.count("Runite ore");

                        if (oreAfter > oreBefore) {
                            updateStatus("⛏️ Successfully mined ore! (No world hop needed)");
                        } else {
                            updateStatus("⛏️ Mining completed but no ore gained");
                        }

                        sleep(500);
                    }
                } else if (rock == null) {
                    int world = Login.getRandomWorld(Rs2Player.isMember());
                    String hopReason = "No runite rocks found at location (all rocks depleted or taken)";
                    updateStatus("🌍 WORLD HOP: " + hopReason + " → World " + world);
                    Microbot.log("🌍 WORLD HOP REASON: " + hopReason + " | New World: " + world);
                    Microbot.hopToWorld(world);
                    sleep(2000);
                }
            } catch (Exception e) {
                updateStatus("Error in main loop: " + e.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
    }


    private void stopWalking() {
        Microbot.log("Stopping any active pathing...");
        Rs2Walker.setTarget(null); // This clears any current web-walking
    }

    @Override
    public void shutdown() {
        updateStatus("Shutting down script.");
        scriptRunning.set(false);
        stopAllFutures();
        Rs2Antiban.resetAntibanSettings();
        stopWalking();
        super.shutdown();
    }

    private boolean hasEnoughOre(WildernessRuniteMiningConfig config) {
        return Rs2Inventory.count("Runite ore") >= getOreLimit(config);
    }

    private int getOreLimit(WildernessRuniteMiningConfig config) {
        int userSetLimit = config.oreLimit();
        int occupied = 0;
        if (Rs2Inventory.hasItem("Rune pickaxe")) occupied++;
        if (Rs2Inventory.contains(item -> item.getName().toLowerCase().contains(" axe"))) occupied++;
        int maxOreSpace = 28 - occupied;
        int defaultLimit = Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.PRAYER) >= 25 ? 3 : 2;
        return userSetLimit > 0 ? Math.min(userSetLimit, maxOreSpace) : defaultLimit;
    }

    private void walkToOre() {
        updateStatus("Walking to ore location...");
        Rs2Walker.walkTo(RUNITE_ORE_TILE);
        sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(RUNITE_ORE_TILE) < 5, 15000);
    }

    private void walkToFerox() {
        updateStatus("Walking to Ferox Enclave...");
        Rs2Walker.walkTo(FEROX_ENCLAVE_BANK);
        sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(FEROX_ENCLAVE_BANK) < 5, 15000);
    }

    private void bankOres() {
        if (Rs2Bank.openBank()) {
            updateStatus("Bank opened. Depositing ores and gems...");
            sleepUntil(Rs2Bank::isOpen, 5000);

            int oreInInventory = Rs2Inventory.count("Runite ore");
            if (oreInInventory > 0) {
                totalMined += oreInInventory;
                updateStatus("Depositing " + oreInInventory + " ore(s). Total mined: " + totalMined);
                Rs2Bank.depositAll("Runite ore");
            }

            String[] uncutGems = {
                    "Uncut sapphire", "Uncut emerald", "Uncut ruby", "Uncut diamond",
                    "Uncut dragonstone", "Uncut onyx", "Uncut opal", "Uncut jade", "Uncut red topaz"
            };

            for (String gem : uncutGems) {
                if (Rs2Inventory.hasItem(gem)) {
                    Rs2Bank.depositAll(gem);
                    sleep(300);
                }
            }

            Rs2Bank.closeBank();
            updateStatus("Banking complete.");
        }
    }

    private void drinkPoolIfAtFerox() {
        if (Rs2Player.getWorldLocation().distanceTo(POOL_OF_REFRESHMENT_TILE) < 20) {
            updateStatus("Drinking from Pool of Refreshment...");
            Rs2Walker.walkTo(POOL_OF_REFRESHMENT_TILE);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(POOL_OF_REFRESHMENT_TILE) < 3, 5000);
            TileObject pool = Rs2GameObject.findObjectById(POOL_OF_REFRESHMENT);
            if (pool != null && Rs2GameObject.interact(pool, "Drink")) {
                sleepUntil(() -> Rs2Player.getRunEnergy() >= 100, 5000);
                sleep(500);
                updateStatus("Recovered run energy at pool.");
            }
        }
    }

    private void handleLumbridgeDeathRecovery() {
        updateStatus("Died → Recovering from Lumbridge");
        
        // **CRITICAL**: Ensure fleeing state is reset
        stopFleeingFromPlayer("Death recovery initiated");

        boolean hasPickaxe = Rs2Inventory.contains(item -> item.getName().toLowerCase().contains("pickaxe"));
        boolean hasAxe = Rs2Inventory.contains(item -> item.getName().toLowerCase().contains(" axe"));

        if (hasPickaxe && hasAxe) {
            updateStatus("Tools found → Walking to wilderness...");
            Rs2Inventory.interact("Rune pickaxe", "Wield");
            walkToOre();
            return;
        }

        if (Rs2Player.getWorldLocation().distanceTo(LUMBRIDGE_BANK_TILE) > 5) {
            Rs2Walker.walkTo(LUMBRIDGE_BANK_TILE);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(LUMBRIDGE_BANK_TILE) < 5, 10000);
        }

        // ✅ NEW: Bank runite ore instead of dropping it
        if (Rs2Inventory.hasItem("Runite ore")) {
            updateStatus("Banking ores before withdrawing tools...");
            if (Rs2Bank.openBank()) {
                sleepUntil(Rs2Bank::isOpen, 5000);

                int oreCount = Rs2Inventory.count("Runite ore"); // count before deposit
                Rs2Bank.depositAll("Runite ore");
                totalMined += oreCount; // ✅ add to totalMined
                updateStatus("Deposited " + oreCount + " ores from death recovery. Total mined: " + totalMined);

                sleep(600);
            }
        }


        if (Rs2Bank.openBank()) {
            sleepUntil(Rs2Bank::isOpen, 5000);

            if (!hasAxe) {
                String[] f2pAxes = {
                        "Rune axe", "Adamant axe", "Mithril axe",
                        "Steel axe", "Black axe", "Iron axe", "Bronze axe"
                };
                for (String axe : f2pAxes) {
                    if (Rs2Bank.hasItem(axe)) {
                        Rs2Bank.withdrawX(axe, 1);
                        updateStatus("Withdrew axe: " + axe);
                        sleep(600);
                        break;
                    }
                }
            }

            if (!hasPickaxe && Rs2Bank.hasItem("Rune pickaxe")) {
                Rs2Bank.withdrawX("Rune pickaxe", 1);
                Rs2Inventory.interact("Rune pickaxe", "Wield");
                updateStatus("Withdrew and wielded Rune pickaxe");
                sleep(600);
            }

            Rs2Bank.closeBank();
        }

        updateStatus("Recovered → Walking back to wilderness...");
        walkToOre();
    }

}
