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
import net.runelite.api.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WildernessRuniteMiningScript extends Script {

    @Getter
    private int totalMined = 0;
    @Getter
    private int orePrice = 0;

    private static final WorldPoint POOL_OF_REFRESHMENT_TILE = new WorldPoint(3129, 3635, 0);
    private final WorldPoint FEROX_ENCLAVE_BANK = new WorldPoint(3130, 3631, 0);
    private static final WorldPoint LUMBRIDGE_BANK_TILE = new WorldPoint(3209, 3220, 2);
    private final WorldPoint RUNITE_ORE_TILE = new WorldPoint(3059, 3884, 0);
    
    // State management
    private boolean isBanking = false;
    private long scriptStartTime;
    private boolean fleeingFromPlayer = false;
    private boolean cameraConfigured = false;
    private boolean equipmentPrepared = false;
      // Performance optimization - cache frequently accessed values
    private WorldPoint lastKnownLocation = null;
    private long lastLocationUpdate = 0;
    private long lastStuckCheck = 0;
    private static final long LOCATION_CACHE_DURATION = 500; // Cache location for 500ms

    private final AtomicBoolean scriptRunning = new AtomicBoolean(false);
    @Getter
    private final Set<String> recentAttackers = new HashSet<>();

    private Thread worldHopThread;
    private Thread combatThread;
    private Thread zoneThread;    private void updateStatus(String message) {
        String timestamp = java.time.LocalTime.now().withNano(0).toString();
        Microbot.status = "[" + timestamp + "] " + message;
        // Reduce logging frequency for performance
        if (System.currentTimeMillis() % 5000 == 0) { // Log every 5 seconds
            Microbot.log("[Status] " + timestamp + " → " + message);
        }
    }

    // Optimized location getter with caching
    private WorldPoint getCachedLocation() {
        long currentTime = System.currentTimeMillis();
        if (lastKnownLocation == null || (currentTime - lastLocationUpdate) > LOCATION_CACHE_DURATION) {
            lastKnownLocation = Rs2Player.getWorldLocation();
            lastLocationUpdate = currentTime;
        }
        return lastKnownLocation;
    }

    private void stopAllThreads() {
        if (worldHopThread != null && !worldHopThread.isInterrupted()) {
            worldHopThread.interrupt();
        }
        if (combatThread != null && !combatThread.isInterrupted()) {
            combatThread.interrupt();
        }
        if (zoneThread != null && !zoneThread.isInterrupted()) {
            zoneThread.interrupt();
        }
    }    private boolean preparePickaxeAndAxe() {
        // Skip if already prepared and equipment is still valid
        if (equipmentPrepared && hasValidEquipment()) {
            return true;
        }
        
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

            // Check for axe only if we don't have one
            if (!hasAnyAxe()) {
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
            }

            Rs2Bank.closeBank();
            updateStatus("Bank closed after gear prep.");
        }

        equipmentPrepared = hasPick && hasAnyAxe();
        return hasPick;
    }

    private boolean hasValidEquipment() {
        return (Rs2Inventory.hasItem("Rune pickaxe") || 
                Microbot.getClient().getLocalPlayer().getPlayerComposition().getEquipmentId(KitType.WEAPON) == ItemID.RUNE_PICKAXE)
                && hasAnyAxe();
    }

    private boolean hasAnyAxe() {
        return Rs2Inventory.contains(item -> item.getName().toLowerCase().contains(" axe"));
    }    private void startWorldHopThread() {
        // Don't recreate thread if it's already running
        if (worldHopThread != null && worldHopThread.isAlive() && !worldHopThread.isInterrupted()) {
            return;
        }
        
        worldHopThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && scriptRunning.get()) {
                WorldPoint currentLoc = getCachedLocation();
                
                // Instant world hop if above Ferox Enclave and players detected
                if (currentLoc.getY() > 3643) {
                    boolean playersNearby = Rs2Player.getPlayers(p -> 
                        p != null && 
                        !p.equals(Microbot.getClient().getLocalPlayer()) &&
                        p.getWorldLocation().distanceTo(currentLoc) < 15
                    ).findAny().isPresent();
                    
                    if (playersNearby) {
                        updateStatus("⚠️ Players detected above Ferox → Instant world hop!");
                        int world = Login.getRandomWorld(Rs2Player.isMember());
                        Microbot.hopToWorld(world);
                        updateStatus("Hopped to world: " + world);
                        sleep(4000); // Wait after hopping
                        continue;
                    }
                }
                
                sleep(1000); // Check every second for instant detection
            }        });
        worldHopThread.start();
    }

    private void monitorZone() {
        zoneThread = new Thread(() -> {
            boolean previouslyInFerox = true;
            WorldPoint lastCheckedLocation = null;
            
            while (!Thread.currentThread().isInterrupted() && scriptRunning.get()) {
                WorldPoint currentLocation = getCachedLocation();
                
                // Only recalculate distance if location changed significantly
                if (lastCheckedLocation == null || currentLocation.distanceTo(lastCheckedLocation) > 5) {
                    boolean inFerox = currentLocation.distanceTo(FEROX_ENCLAVE_BANK) < 30;
                    
                    if (inFerox && !equipmentPrepared) {
                        updateStatus("Detected in Ferox. Preparing tools.");
                        preparePickaxeAndAxe();
                    }
                    
                    // Start world hopping immediately when leaving Ferox OR when above Ferox enclave
                    if ((!inFerox && previouslyInFerox) || currentLocation.getY() > 3643) {
                        updateStatus("Outside Ferox safety zone - starting world hop monitoring.");
                        startWorldHopThread();
                    }
                    
                    previouslyInFerox = inFerox;
                    lastCheckedLocation = currentLocation;
                }
                
                sleep(2000); // Check more frequently for better responsiveness
            }
        });
        zoneThread.start();
    }    private void monitorCombatAndHealth() {
        combatThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && scriptRunning.get()) {
                WorldPoint location = getCachedLocation();
                
                // Only monitor combat when actually in dangerous areas (wilderness)
                if (location.getY() > 3520) { // Approximate wilderness boundary
                    if (Rs2Combat.inCombat()) {
                        Player local = Microbot.getClient().getLocalPlayer();

                        Rs2Player.getPlayers(p -> p != null && !p.getPlayer().equals(local) && p.getPlayer().getInteracting() == local)
                                .findFirst()
                                .ifPresent(attacker -> {
                                    if (!fleeingFromPlayer) {
                                        fleeingFromPlayer = true;
                                        recentAttackers.add(attacker.getName());
                                        updateStatus("⚠️ Under attack by player: " + attacker.getName());

                                        if (Rs2Player.getHealthPercentage() < 50 &&
                                                !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_ITEM)) {
                                            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_ITEM);
                                            updateStatus("🛡️ Low HP → Protect Item activated.");
                                        }

                                        stopWalking();
                                        updateStatus("🏃 Fleeing to Ferox Enclave...");
                                        walkToFerox();
                                    }
                                });
                    } else if (fleeingFromPlayer) {
                        // Check if we should stop fleeing (no longer in combat and in safe area)
                        boolean inSafeArea = location.distanceTo(FEROX_ENCLAVE_BANK) < 10 || location.getY() < 3520;
                        if (inSafeArea) {
                            updateStatus("✅ No longer in combat and in safe area. Stopping flee mode.");
                            fleeingFromPlayer = false;
                        }
                    }
                    sleep(500); // Check more frequently in wilderness
                } else {
                    // Reset fleeing if we're in a safe area
                    if (fleeingFromPlayer) {
                        updateStatus("✅ Reached safe area. Stopping flee mode.");
                        fleeingFromPlayer = false;
                    }
                    sleep(3000); // Check less frequently when safe
                }
            }
        });
        combatThread.start();
    }


    public int calculateGpPerHour() {
        long elapsedMillis = System.currentTimeMillis() - scriptStartTime;
        double hours = elapsedMillis / (1000.0 * 60 * 60);
        if (hours == 0) return 0;
        return (int) ((totalMined * orePrice) / hours);
    }    private void setTopDownCameraView() {
        if (Microbot.getClient() == null || cameraConfigured) return;
        Microbot.getClient().setCameraPitchTarget(383);
        Microbot.getClient().setCameraYawTarget(0);
        Microbot.getClient().setCameraShakeDisabled(true);
        cameraConfigured = true;
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
    }    public boolean run(WildernessRuniteMiningConfig config) {
        scriptRunning.set(true);
        scriptStartTime = System.currentTimeMillis();

        updateStatus("Waiting for login...");
        while (!Microbot.isLoggedIn() && scriptRunning.get()) {
            sleep(500);
        }
        
        if (!scriptRunning.get()) return false;
        
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
            return false;
        }
        
        monitorCombatAndHealth();
        monitorZone();
        
        // Start world hop monitoring immediately if above Ferox
        WorldPoint startLocation = Rs2Player.getWorldLocation();
        if (startLocation.getY() > 3643) {
            updateStatus("Starting above Ferox - enabling immediate world hop monitoring.");
            startWorldHopThread();
        }

        // Use standard Microbot scheduling pattern for proper shutdown handling
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) {
                    updateStatus("Not logged in. Waiting...");
                    return;
                }
                
                if (!scriptRunning.get()) return;

                executeMainLoop(config);
                
            } catch (Exception ex) {
                Microbot.log("Error in WildernessRuniteMiningScript: " + ex.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        
        return true;
    }
    
    private void executeMainLoop(WildernessRuniteMiningConfig config) {
        WorldPoint currentLocation = getCachedLocation();
        
        // Periodically check for stuck states (every 30 seconds)
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastStuckCheck > 30000) {
            checkForStuckStates();
            lastStuckCheck = currentTime;
        }
        
        // Handle Lumbridge death recovery
        if (currentLocation.distanceTo(new WorldPoint(3222, 3218, 0)) < 20) {
            updateStatus("Detected in Lumbridge. Starting recovery...");
            // Reset fleeing state since we died and spawned in Lumbridge
            if (fleeingFromPlayer) {
                updateStatus("💀 Death detected - resetting fleeing state.");
                fleeingFromPlayer = false;
                recentAttackers.clear(); // Clear attackers list since we died
            }
            handleLumbridgeDeathRecovery();
            return;
        }
        
        // Handle fleeing state
        if (fleeingFromPlayer) {
            updateStatus("⚠️ Fleeing from player → Pausing all actions.");
            
            // Reset fleeing if we're safe (not in combat and in safe area)
            boolean inSafeArea = currentLocation.distanceTo(FEROX_ENCLAVE_BANK) < 10 || 
                               currentLocation.getY() < 3520; // Below wilderness
            boolean notInCombat = !Rs2Combat.inCombat();
            
            if (inSafeArea && notInCombat) {
                updateStatus("✅ Safe area reached and not in combat. Resuming script.");
                fleeingFromPlayer = false;
                sleep(2000); // Brief pause before resuming
            } else if (currentLocation.distanceTo(FEROX_ENCLAVE_BANK) < 5) {
                updateStatus("✅ Arrived at Ferox bank. Resuming script.");
                fleeingFromPlayer = false;
                sleep(1000);
            } else {
                // Still fleeing - continue to safety
                if (currentLocation.getY() > 3520) { // Still in wilderness
                    walkToFerox();
                }
                sleep(1000);
                return;
            }
        }

        // Main banking logic - check inventory space before other operations
        boolean needsBanking = hasEnoughOre(config);
        if (needsBanking || isBanking) {
            // 🔁 Always walk if not banking or too far from bank
            if (!isBanking || currentLocation.distanceTo(FEROX_ENCLAVE_BANK) >= 5) {
                isBanking = true;
                updateStatus("Inventory full. Banking runite ore...");
                walkToFerox();
            }            if (currentLocation.distanceTo(FEROX_ENCLAVE_BANK) < 5) {
                updateStatus("Arrived at Ferox. Banking...");
                bankOres();
                
                // Use pool for restoration
                updateStatus("Banking complete. Using pool for restoration...");
                drinkPoolIfAtFerox();
                
                // Wait a moment to ensure pool interaction is complete
                sleep(1000);

                if (config.stopAfterOneRun()) {
                    updateStatus("Stopping script after one full run.");
                    shutdown();
                    return;
                }

                updateStatus("Restoration complete. Walking back to ore...");
                isBanking = false;
                walkToOre();
            }

            return;
        }

        // Check if we need to walk to ore location
        if (currentLocation.distanceTo(RUNITE_ORE_TILE) > 3) {
            updateStatus("Walking to Runite ore tile...");
            walkToOre();
            return;
        }

        // Mining logic - optimized to reduce redundant checks
        if (!Rs2Player.isAnimating() && Rs2Inventory.getEmptySlots() > 0) {
            GameObject rock = Rs2GameObject.getGameObject(
                    Rocks.RUNITE.getName(), true, currentLocation, 10);

            if (rock != null) {
                int oreBefore = Rs2Inventory.count("Runite ore");
                updateStatus("Rock found. Attempting to mine...");

                if (Rs2GameObject.interact(rock, "Mine")) {
                    boolean startedMining = sleepUntil(Rs2Player::isAnimating, 2000);

                    if (!startedMining) {
                        updateStatus("Mining did not start → Hopping world.");
                        hopToRandomWorld();
                        return;
                    }

                    // Wait until mining finishes
                    sleepUntil(() -> !Rs2Player.isAnimating(), 8000);
                    sleep(300); // short delay for inventory to update

                    int oreAfter = Rs2Inventory.count("Runite ore");
                    if (oreAfter > oreBefore) {
                        totalMined += (oreAfter - oreBefore);
                        updateStatus("Successfully mined ore. Total: " + totalMined);
                    }

                    sleep(500);
                }
            } else {
                updateStatus("No rock found → Hopping world.");
                hopToRandomWorld();
            }
        }
    }private void stopWalking() {
        Microbot.log("Stopping any active pathing...");
        Rs2Walker.setTarget(null); // This clears any current web-walking and cancels pathfinder
        // Force stop any background pathfinding threads
        try {
            Thread.sleep(100); // Small delay to ensure cleanup
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void hopToRandomWorld() {
        int world = Login.getRandomWorld(Rs2Player.isMember());
        Microbot.hopToWorld(world);
        updateStatus("Hopped to world: " + world);
        sleep(2000);
    }    @Override
    public void shutdown() {
        updateStatus("🛑 Shutting down script - Resetting all state...");
        
        // Stop the main script loop
        scriptRunning.set(false);
        
        // Stop all background threads
        stopAllThreads();
        
        // Reset all script state variables to initial values
        totalMined = 0;  // Reset ore count
        orePrice = 0;
        isBanking = false;
        fleeingFromPlayer = false;
        cameraConfigured = false;
        equipmentPrepared = false;
        
        // Clear cached location data
        lastKnownLocation = null;
        lastLocationUpdate = 0;
        lastStuckCheck = 0;
        
        // Clear collections
        recentAttackers.clear();
        
        // Reset antiban settings
        Rs2Antiban.resetAntibanSettings();
        
        // Stop any active walking
        stopWalking();
          // Reset script start time for fresh start
        scriptStartTime = 0;
        
        // Clear the status display
        Microbot.status = "Script stopped - Ready for fresh start";
        
        updateStatus("✅ Script shutdown complete - All state reset.");
        
        super.shutdown();
    }
    // Manual reset function for stuck states
    public void resetScriptState() {
        updateStatus("🔄 Manual reset triggered - clearing all stuck states.");
        fleeingFromPlayer = false;
        isBanking = false;
        equipmentPrepared = false;
        lastKnownLocation = null;
        lastLocationUpdate = 0;
        recentAttackers.clear();
        
        // Stop any current pathing
        stopWalking();
        
        updateStatus("✅ Script state reset complete. Ready to resume normal operation.");
    }
    
    // Auto-reset function that can be called periodically to prevent stuck states
    private void checkForStuckStates() {
        WorldPoint currentLoc = getCachedLocation();
        
        // Auto-reset fleeing if we've been fleeing for too long and are clearly safe
        if (fleeingFromPlayer && currentLoc.getY() < 3520) { // Below wilderness
            updateStatus("🔄 Auto-reset: Been fleeing but now in safe area (below wilderness).");
            fleeingFromPlayer = false;
            recentAttackers.clear();
        }
        
        // Auto-reset if we're at Lumbridge (died) but still think we're fleeing
        if (fleeingFromPlayer && currentLoc.distanceTo(new WorldPoint(3222, 3218, 0)) < 50) {
            updateStatus("🔄 Auto-reset: At Lumbridge area but still in fleeing mode.");
            fleeingFromPlayer = false;
            recentAttackers.clear();
        }
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
    }    private void walkToOre() {
        updateStatus("Walking to ore location...");
        Rs2Walker.walkTo(RUNITE_ORE_TILE);
        sleepUntil(() -> getCachedLocation().distanceTo(RUNITE_ORE_TILE) < 5, 15000);
    }

    private void walkToFerox() {
        updateStatus("Walking to Ferox Enclave...");
        Rs2Walker.walkTo(FEROX_ENCLAVE_BANK);
        sleepUntil(() -> getCachedLocation().distanceTo(FEROX_ENCLAVE_BANK) < 5, 15000);
    }private void bankOres() {
        if (Rs2Bank.openBank()) {
            updateStatus("Bank opened. Depositing ores and gems...");
            sleepUntil(Rs2Bank::isOpen, 5000);

            int oreInInventory = Rs2Inventory.count("Runite ore");
            if (oreInInventory > 0) {
                totalMined += oreInInventory;
                updateStatus("Depositing " + oreInInventory + " ore(s). Total mined: " + totalMined);
                Rs2Bank.depositAll("Runite ore");
                sleep(300);
            }

            // Optimized gem depositing - check once and deposit all found gems
            String[] uncutGems = {
                    "Uncut sapphire", "Uncut emerald", "Uncut ruby", "Uncut diamond",
                    "Uncut dragonstone", "Uncut onyx", "Uncut opal", "Uncut jade", "Uncut red topaz"
            };

            boolean hasGems = false;
            for (String gem : uncutGems) {
                if (Rs2Inventory.hasItem(gem)) {
                    if (!hasGems) {
                        hasGems = true;
                        updateStatus("Depositing gems...");
                    }
                    Rs2Bank.depositAll(gem);
                    sleep(200); // Reduced delay
                }
            }

            Rs2Bank.closeBank();
            updateStatus("Banking complete.");
        }    }    private void drinkPoolIfAtFerox() {
        WorldPoint currentLocation = getCachedLocation();
        if (currentLocation.distanceTo(POOL_OF_REFRESHMENT_TILE) < 20) {            updateStatus("Moving to Pool of Refreshment...");
            
            // Walk to the pool
            Rs2Walker.walkTo(POOL_OF_REFRESHMENT_TILE);
            sleepUntil(() -> getCachedLocation().distanceTo(POOL_OF_REFRESHMENT_TILE) <= 2, 8000);
            
            // Give a moment for the area to load
            sleep(1000);
              // Debug: List all objects in the area to see what we can detect
            updateStatus("🔍 Debugging: Looking for objects in area...");
            java.util.List<GameObject> nearbyObjects = Rs2GameObject.getGameObjects(15);
            updateStatus("Found " + nearbyObjects.size() + " objects nearby");
            for (GameObject obj : nearbyObjects) {
                var objComp = Rs2GameObject.convertToObjectComposition(obj);
                if (obj.getId() == 39651 || (objComp != null && objComp.getName().toLowerCase().contains("pool"))) {
                    String objName = objComp != null ? objComp.getName() : "Unknown";
                    updateStatus("DEBUG: Found object - ID: " + obj.getId() + ", Name: " + objName);
                }
            }// Find the pool object - use non-deprecated methods
            GameObject pool = Rs2GameObject.getGameObject("Pool of Refreshment");
            if (pool == null) {
                // Try alternative methods to find the pool
                TileObject tilePool = Rs2GameObject.getTileObject(39651); // Pool of Refreshment ID
                if (tilePool instanceof GameObject) {
                    pool = (GameObject) tilePool;
                } else if (tilePool == null) {
                    updateStatus("Pool of Refreshment not found, trying by name in area...");
                    tilePool = Rs2GameObject.getTileObject("Pool of Refreshment", 20);
                    if (tilePool instanceof GameObject) {
                        pool = (GameObject) tilePool;
                    }
                }
            }
            
            if (pool != null) {
                updateStatus("Found Pool of Refreshment, checking health/stamina...");
                
                // Only interact if we actually need restoration
                if (!isHealthFull() || !isStaminaFull()) {
                    updateStatus("Need restoration - Health: " + Rs2Player.getHealthPercentage() + "%, Run: " + Rs2Player.getRunEnergy() + "%");
                    
                    int maxAttempts = 5;
                    int attempts = 0;
                    
                    while (attempts < maxAttempts && (!isHealthFull() || !isStaminaFull())) {
                        updateStatus("Drinking from pool... Attempt " + (attempts + 1) + "/" + maxAttempts);
                        
                        // Try to interact with the pool
                        if (Rs2GameObject.interact(pool, "Drink")) {
                            updateStatus("Clicked pool, waiting for effect...");
                            
                            // Wait for the interaction to start
                            sleepUntil(() -> Rs2Player.isAnimating(), 3000);
                            
                            // Wait for animation to finish
                            sleepUntil(() -> !Rs2Player.isAnimating(), 5000);
                            
                            // Give time for stats to update
                            sleep(2000);
                            
                            updateStatus("After drinking - Health: " + Rs2Player.getHealthPercentage() + "%, Run: " + Rs2Player.getRunEnergy() + "%");
                            
                            // Check if we're fully restored
                            if (isHealthFull() && isStaminaFull()) {
                                updateStatus("✅ Fully restored at pool!");
                                break;
                            }
                        } else {
                            updateStatus("Failed to interact with pool, trying again...");
                            sleep(1000);
                        }
                        
                        attempts++;
                        
                        if (attempts < maxAttempts) {
                            sleep(500); // Brief pause between attempts
                        }
                    }
                    
                    if (attempts >= maxAttempts && (!isHealthFull() || !isStaminaFull())) {
                        updateStatus("⚠️ Pool restoration incomplete after " + maxAttempts + " attempts.");
                    }
                } else {
                    updateStatus("Already at full health and stamina, skipping pool.");
                }
            } else {
                updateStatus("❌ Could not find Pool of Refreshment!");
            }
        }
    }
    
    private boolean isHealthFull() {
        return Rs2Player.getHealthPercentage() >= 100;
    }
    
    private boolean isStaminaFull() {
        return Rs2Player.getRunEnergy() >= 100;
    }private void handleLumbridgeDeathRecovery() {
        updateStatus("Died → Recovering from Lumbridge");
        
        // Reset all combat/fleeing states since we died
        if (fleeingFromPlayer) {
            updateStatus("💀 Death recovery - clearing fleeing state and recent attackers.");
            fleeingFromPlayer = false;
            recentAttackers.clear();
        }

        boolean hasPickaxe = Rs2Inventory.contains(item -> item.getName().toLowerCase().contains("pickaxe"));
        boolean hasAxe = hasAnyAxe();

        if (hasPickaxe && hasAxe) {
            updateStatus("Tools found → Walking to wilderness...");
            Rs2Inventory.interact("Rune pickaxe", "Wield");
            walkToOre();
            return;
        }

        WorldPoint currentLocation = getCachedLocation();
        if (currentLocation.distanceTo(LUMBRIDGE_BANK_TILE) > 5) {
            Rs2Walker.walkTo(LUMBRIDGE_BANK_TILE);
            sleepUntil(() -> getCachedLocation().distanceTo(LUMBRIDGE_BANK_TILE) < 5, 10000);
        }

        // ✅ Bank runite ore instead of dropping it
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

            // Withdraw axe first if needed
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

            // Withdraw pickaxe if needed
            if (!hasPickaxe && Rs2Bank.hasItem("Rune pickaxe")) {
                Rs2Bank.withdrawX("Rune pickaxe", 1);
                Rs2Inventory.interact("Rune pickaxe", "Wield");
                updateStatus("Withdrew and wielded Rune pickaxe");
                sleep(600);
            }

            Rs2Bank.closeBank();
        }

        // Reset equipment prepared state
        equipmentPrepared = false;
        updateStatus("Recovered → Walking back to wilderness...");
        walkToOre();
    }

}
