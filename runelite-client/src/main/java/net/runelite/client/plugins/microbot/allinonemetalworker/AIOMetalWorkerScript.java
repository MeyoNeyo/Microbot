package net.runelite.client.plugins.microbot.allinonemetalworker;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.allinonemetalworker.enums.MetalType;
import net.runelite.client.plugins.microbot.allinonemetalworker.enums.ProcessPhase;
import net.runelite.client.plugins.microbot.allinonemetalworker.enums.SmithingProduct;
import net.runelite.client.plugins.microbot.allinonemetalworker.enums.StartingPhase;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.*;
import java.util.List;

/**
 * Main script for the AIO Metal Worker plugin.
 * Handles the complete automation cycle: mining → banking → smelting → smithing
 */
@Slf4j
public class AIOMetalWorkerScript extends Script {

    // Configuration and state management
    private AIOMetalWorkerConfig config;
    @Getter
    private static ProcessPhase currentPhase = ProcessPhase.MINING;
    @Getter
    private static ProgressTracker progress = new ProgressTracker();

    // Shutdown flag to immediately stop all operations
    private volatile boolean isShuttingDown = false;

    // Mining locations (adaptive - will use current location if ores are nearby)
    private static final WorldPoint LUMBRIDGE_MINING_AREA = new WorldPoint(3230, 3148, 0);
    private static final WorldPoint AL_KHARID_MINING_AREA = new WorldPoint(3302, 3315, 0);
    private static final int MINING_RADIUS = 15;

    // Current mining area (dynamic based on player location)
    private WorldPoint currentMiningArea = null;

    // Al Kharid furnace location
    private static final WorldPoint FURNACE_LOCATION = new WorldPoint(3275, 3185, 0);

    // Varrock anvil location
    private static final WorldPoint ANVIL_LOCATION = new WorldPoint(3189, 3425, 0);

    // Banking locations
    private static final WorldPoint LUMBRIDGE_BANK = new WorldPoint(3208, 3220, 2);
    private static final WorldPoint AL_KHARID_BANK = new WorldPoint(3269, 3167, 0);

    // State tracking
    private int failedActionCount = 0;

    public AIOMetalWorkerScript() {
        // Default constructor
    }

    /**
     * Public run method that accepts config parameter
     */
    public boolean run(AIOMetalWorkerConfig config) {
        this.config = config;
        return run();
    }

    @Override
    public boolean run() {
        try {
            // EXPERT FIX: Reset shutdown flag for proper restart
            isShuttingDown = false;

            // Initialize progress tracking
            if (progress.getStartTime() == null) {
                progress.setStartTime(Instant.now());
                Microbot.log("AIO Metal Worker started - Target: " + config.targetQuantity() + " "
                        + config.metalType().getDisplayName());

                // Set initial phase based on config (for debugging)
                switch (config.startingPhase()) {
                    case MINING:
                        currentPhase = ProcessPhase.MINING;
                        Microbot.log("Starting at MINING phase (normal operation)");
                        break;
                    case SMELTING:
                        currentPhase = ProcessPhase.SMELTING;
                        // For smelting debug: simulate having mined all required ores
                        // but no bars smelted yet, so we can test the smelting process
                        progress.setOresMined(config.targetQuantity());
                        progress.setBarsSmelted(0); // Start fresh for smelting
                        Microbot.log("Starting at SMELTING phase (debug mode) - simulated " + config.targetQuantity()
                                + " ores mined, 0 bars smelted (target bars: " + getRequiredBars() + ")");
                        break;
                    case SMITHING:
                        currentPhase = ProcessPhase.SMITHING;
                        // For smithing, we need all ores but only some bars to continue working
                        progress.setOresMined(config.targetQuantity());
                        // Set bars to a reasonable amount but not the full target
                        int smithingStartBars = Math.max(1, Math.min(getRequiredBars() - 1, 27)); // At most an
                                                                                                  // inventory, at least
                                                                                                  // 1
                        progress.setBarsSmelted(smithingStartBars);
                        Microbot.log("Starting at SMITHING phase (debug mode) - simulated " + config.targetQuantity()
                                + " ores mined and " + smithingStartBars + " bars smelted (target: " + getRequiredBars()
                                + ")");
                        break;
                    default:
                        currentPhase = ProcessPhase.MINING;
                        break;
                }
            }

            // Configure anti-ban if enabled
            if (config.enableAntiban()) {
                configureAntiban();
            }

            // Use proper Microbot scheduling to prevent client freezing
            mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
                try {
                    // Check shutdown flag first - highest priority
                    if (isShuttingDown) {
                        Rs2Walker.setTarget(null);
                        return;
                    }

                    if (!super.run() || !Microbot.isLoggedIn()) {
                        return;
                    }

                    // Check shutdown flag again after parent run
                    if (isShuttingDown) {
                        Rs2Walker.setTarget(null);
                        return;
                    }

                    // Check if script is in shutdown state
                    if (isShuttingDown) {
                        Rs2Walker.setTarget(null);
                        return;
                    }

                    // Check runtime limit
                    if (config.maxRunTimeMinutes() > 0) {
                        long runtime = (System.currentTimeMillis() - progress.getStartTime().toEpochMilli()) / 60000;
                        if (runtime >= config.maxRunTimeMinutes()) {
                            updateStatus("Runtime limit reached, stopping...");
                            shutdown();
                            return;
                        }
                    }

                    // Update progress status every few cycles (for overlay updates)
                    if (System.currentTimeMillis() % 5000 < 1200) { // Roughly every 5 seconds
                        updateProgressStatus();
                    }

                    executeCurrentPhase();

                } catch (Exception e) {
                    handleError("Error in main execution loop", e);
                }
            }, 0, 1200, java.util.concurrent.TimeUnit.MILLISECONDS);

            return true;

        } catch (Exception e) {
            handleError("Critical error in script execution", e);
            return false;
        }
    }

    /**
     * Executes the current phase of the automation process
     */
    private void executeCurrentPhase() {
        // Safety check - don't execute if script is shutting down
        if (isShuttingDown) {
            return;
        }

        updateStatus("Executing phase: " + currentPhase);

        switch (currentPhase) {
            case MINING:
                executeMiningPhase();
                break;
            case BANKING:
                executeBankingPhase();
                break;
            case SMELTING:
                executeSmeltingPhase();
                break;
            case SMITHING:
                executeSmithingPhase();
                break;
            case WALKING:
                executeWalkingPhase();
                break;
            case ERROR:
                handleErrorPhase();
                break;
            case COMPLETE:
                // This state should never be reached in continuous loop mode
                // If it is reached, restart mining
                updateStatus("WARNING: Unexpected COMPLETE state - restarting mining cycle");
                currentPhase = ProcessPhase.MINING;
                break;
        }
    }

    /**
     * Mining phase - mines ores with intelligent location detection
     */
    private void executeMiningPhase() {
        // Safety check - stop if shutting down
        if (isShuttingDown) {
            return;
        }

        updateStatus("Mining " + config.metalType().getDisplayName() + " ore");

        // First priority: Check if we've reached the target mining quantity
        int totalOresMined = progress.getOresMined();
        int targetQuantity = config.targetQuantity();

        if (totalOresMined >= targetQuantity) {
            updateStatus("Target mining quantity reached! Ores mined: " + totalOresMined + "/" + targetQuantity);
            
            // Check if we should start a new cycle or continue to next phase
            if (shouldStartNewMiningCycle()) {
                updateStatus("Cycle complete - resetting progress and starting new mining cycle");
                // Reset progress for new cycle but keep cumulative counters
                progress.setOresMined(0);
                progress.setBarsSmelted(0);
                progress.setItemsSmithed(0);
                // Continue mining in this phase with reset counters
                updateStatus("New cycle started - continuing mining");
            } else {
                // Move to next phase (banking to proceed to smelting/smithing)
                currentPhase = ProcessPhase.BANKING;
                return;
            }
        }

        // Check if inventory is full
        if (Rs2Inventory.isFull()) {
            updateStatus("Inventory full - going to bank to deposit ores");
            currentPhase = ProcessPhase.BANKING;
            return;
        }

        // Ensure we have a pickaxe
        if (!hasPickaxe()) {
            updateStatus("No pickaxe found - going to bank");
            currentPhase = ProcessPhase.BANKING;
            return;
        }

        // Set current mining area based on player location if not set
        if (currentMiningArea == null) {
            currentMiningArea = detectCurrentMiningArea();
        }

        // Check if player is near the current mining area
        if (!isNearMiningArea()) {
            updateStatus("Moving to mining area...");
            currentPhase = ProcessPhase.WALKING;
            return;
        }

        // Try to mine nearby ores based on current location
        if (!findAndMineNearbyOres()) {
            updateStatus("No " + config.metalType().getDisplayName() + " ores found nearby");
            // Search for alternative ore spots
            searchForAlternativeOres();
        }

        sleep(600, 1000); // Brief pause between mining attempts
    }

    /**
     * Detects the current mining area based on nearby ore availability
     */
    private WorldPoint detectCurrentMiningArea() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();

        // First check if we're near any known mining areas
        if (playerLocation.distanceTo(LUMBRIDGE_MINING_AREA) <= MINING_RADIUS) {
            updateStatus("Detected location: Lumbridge mining area");
            return LUMBRIDGE_MINING_AREA;
        }
        if (playerLocation.distanceTo(AL_KHARID_MINING_AREA) <= MINING_RADIUS) {
            updateStatus("Detected location: Al Kharid mining area");
            return AL_KHARID_MINING_AREA;
        }

        // If not near known areas, check if there are suitable ores nearby
        if (hasNearbyOres()) {
            updateStatus("Detected ores near current location - using as mining area");
            return playerLocation; // Use current location as mining area
        }

        // Default to closest known mining area if no ores detected
        updateStatus("No ores detected nearby - will use closest mining area");
        return getClosestMiningArea(playerLocation);
    }

    /**
     * Gets the closest known mining area to the player
     */
    private WorldPoint getClosestMiningArea(WorldPoint playerLocation) {
        WorldPoint[] knownAreas = {
                LUMBRIDGE_MINING_AREA,
                AL_KHARID_MINING_AREA,
                new WorldPoint(3183, 3376, 0), // Varrock East mine
                new WorldPoint(3146, 3149, 0), // Lumbridge Swamp East mine
                new WorldPoint(3289, 3365, 0) // Al Kharid mine north
        };

        WorldPoint closest = knownAreas[0];
        int shortestDistance = playerLocation.distanceTo(closest);

        for (WorldPoint area : knownAreas) {
            int distance = playerLocation.distanceTo(area);
            if (distance < shortestDistance) {
                shortestDistance = distance;
                closest = area;
            }
        }

        return closest;
    }

    /**
     * Checks if player is near the current mining area
     */
    private boolean isNearMiningArea() {
        if (currentMiningArea == null)
            return false;
        return Rs2Player.getWorldLocation().distanceTo(currentMiningArea) <= MINING_RADIUS;
    }

    /**
     * Checks if there are suitable ores nearby for the current metal type
     */
    private boolean hasNearbyOres() {
        String[] oreNames = config.metalType().getOreNames();

        for (String oreName : oreNames) {
            if (Rs2GameObject.getTileObject(oreName) != null) {
                updateStatus("Found " + oreName + " nearby");
                return true;
            }
        }

        // Also check for common ore rock names that might contain the ores
        String[] genericRockNames = { "Rock", "Rocks", "Mining rocks", "Tin rocks", "Copper rocks", "Iron rocks",
                "Coal rocks" };
        for (String rockName : genericRockNames) {
            if (Rs2GameObject.getTileObject(rockName) != null) {
                updateStatus("Found mining rocks nearby - will attempt to mine");
                return true;
            }
        }

        return false;
    }

    /**
     * Gets current ore inventory counts for the metal type
     */
    private Map<String, Integer> getCurrentOreInventory() {
        Map<String, Integer> oreCount = new HashMap<>();
        for (String oreName : config.metalType().getOreNames()) {
            oreCount.put(oreName, Rs2Inventory.count(oreName));
        }
        return oreCount;
    }

    /**
     * Determines if we need more of a specific ore type based on metal requirements
     */
    private boolean needsMoreOre(String oreName, Map<String, Integer> currentOres) {
        // For simple metals like iron, always mine if we don't have enough
        if (config.metalType().getOreNames().length == 1) {
            return currentOres.get(oreName) < 28; // Fill inventory
        }

        // For alloy metals, maintain proper ratios
        // Example: Bronze needs 1:1 copper:tin ratio
        // Steel needs 1:2 iron:coal ratio
        // This is a simplified version - more complex logic could be added

        int currentCount = currentOres.getOrDefault(oreName, 0);
        int totalOres = currentOres.values().stream().mapToInt(Integer::intValue).sum();

        // If we have very few ores, mine any available
        if (totalOres < 10) {
            return true;
        }

        // Otherwise, try to maintain ratios (simplified approach)
        return currentCount < totalOres / config.metalType().getOreNames().length + 5;
    }

    /**
     * Searches for alternative ore locations when primary spots are unavailable
     */
    private void searchForAlternativeOres() {
        updateStatus("Searching for available ore rocks...");

        // Check for other players in the area and consider world hopping
        if (config.hopWorlds() && shouldHopWorlds()) {
            hopToLessPopulatedWorld();
            return;
        }

        // Move to a different spot within the current mining area
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        int offsetX = (int) (Math.random() * 11) - 5; // -5 to +5
        int offsetY = (int) (Math.random() * 11) - 5; // -5 to +5
        WorldPoint newSpot = currentLocation.dx(offsetX).dy(offsetY);

        if (Rs2Walker.walkTo(newSpot)) {
            updateStatus("Moving to alternative mining spot...");
            sleep(1000, 2000);
        }
    }

    /**
     * Checks if we should hop worlds due to crowding
     */
    private boolean shouldHopWorlds() {
        if (!config.hopWorlds() || config.maxPlayersInArea() == 0) {
            return false;
        }

        // Count nearby players (simplified - would need actual player detection)
        // This is a placeholder for more sophisticated player detection
        return Math.random() < 0.1; // Randomly hop 10% of the time as example
    }

    /**
     * Hops to a less populated world
     */
    private void hopToLessPopulatedWorld() {
        updateStatus("Hopping to less populated world...");
        // Placeholder for world hopping logic
        // In practice, this would use Rs2WorldHopper or similar utility
        sleep(3000, 5000); // Simulate world hop delay
    }

    /**
     * Banking phase - deposits ores and withdraws necessary items
     */
    private void executeBankingPhase() {
        // Safety check - stop if shutting down
        if (isShuttingDown) {
            Rs2Walker.setTarget(null);
            return;
        }

        updateStatus("Banking items");

        try {
            // Simple approach: use Rs2Bank.walkToBankAndUseBank() directly
            updateStatus("Walking to nearest bank and opening...");

            if (!Rs2Bank.isOpen()) {
                boolean bankingSuccess = Rs2Bank.walkToBankAndUseBank();
                if (!bankingSuccess) {
                    updateStatus("Failed to reach and open bank - retrying...");
                    sleep(3000, 5000);
                    return;
                }
            }

            if (Rs2Bank.isOpen()) {
                updateStatus("Bank is open - handling banking operations");
                handleBankingOperations();
                sleep(1000, 1500); // Pause after banking operations
            } else {
                updateStatus("Bank not open after walking - retrying...");
                sleep(2000, 3000);
            }

        } catch (Exception e) {
            handleError("Banking phase failed: " + e.getMessage());
        }
    }

    /**
     * Smelting phase - smelts ores into bars at Al Kharid furnace
     */
    private void executeSmeltingPhase() {
        // Safety check - stop if shutting down
        if (isShuttingDown) {
            Rs2Walker.setTarget(null);
            return;
        }

        updateStatus("Smelting " + config.metalType().getDisplayName() + " bars");

        // Check if we have the required ores FIRST before doing anything
        if (!hasRequiredOres()) {
            updateStatus("No required ores in inventory - going to bank");
            if (config.enableDebugLogs()) {
                Microbot.log("Smelting phase: Missing ores in inventory");
                for (String oreName : config.metalType().getOreNames()) {
                    int count = Rs2Inventory.count(oreName);
                    Microbot.log("  " + oreName + ": " + count + " in inventory");
                }
            }
            currentPhase = ProcessPhase.BANKING;
            return;
        }

        // Check if we're at the furnace
        if (Rs2Player.getWorldLocation().distanceTo(FURNACE_LOCATION) > 3) {
            currentPhase = ProcessPhase.WALKING;
            return;
        }

        // Smelt the bars
        if (Rs2GameObject.interact("Furnace", "Smelt")) {
            updateStatus("Opening smelting interface...");

            // Wait for smelting interface with proper timeout
            if (Rs2Widget.sleepUntilHasWidgetText("What would you like to smelt?", 270, 5, false, 6000)) {
                updateStatus("Smelting interface opened");

                // Select the bar type and amount
                if (selectSmeltingOption()) {
                    // Wait for smelting to complete
                    waitForSmelting();

                    // After smelting is complete, we should go to banking to either
                    // get more ores or proceed to smithing
                    currentPhase = ProcessPhase.BANKING;
                    updateStatus("Smelting completed - proceeding to banking");
                } else {
                    updateStatus("Failed to select smelting option - will retry");
                }
            } else {
                updateStatus("Smelting interface did not appear - will retry");
            }
        } else {
            updateStatus("Failed to interact with furnace - will retry");
        }

        // Brief pause before next attempt
        sleep(1000, 2000);
    }

    /**
     * Smithing phase - smiths bars into items at Varrock anvil
     * MYTHICAL-LEVEL ENHANCEMENT: Complete smithing cycle with progressive item
     * selection and banking loop
     */
    private void executeSmithingPhase() {
        // Safety check - stop if shutting down
        if (isShuttingDown) {
            Rs2Walker.setTarget(null);
            return;
        }

        updateStatus("MYTHICAL: Smithing " + config.metalType().getDisplayName() + " items");

        // Check if we're at the anvil
        if (Rs2Player.getWorldLocation().distanceTo(ANVIL_LOCATION) > 3) {
            updateStatus("MYTHICAL: Not at anvil - walking to Varrock anvil");
            currentPhase = ProcessPhase.WALKING;
            return;
        }

        // MYTHICAL ENHANCEMENT: Check if we have bars to smith in inventory
        if (!hasBarsToSmith()) {
            updateStatus("MYTHICAL: No bars in inventory - going to bank to get bars");
            if (config.enableDebugLogs()) {
                Microbot.log("MYTHICAL: Smithing phase - missing bars in inventory");
                String barName = config.metalType().getBarName();
                Microbot.log("  Looking for: " + barName + " (count: " + Rs2Inventory.count(barName) + ")");
            }
            currentPhase = ProcessPhase.BANKING;
            return;
        }

        // MYTHICAL ENHANCEMENT: Ensure we have a hammer - go to bank if missing
        if (!hasHammer()) {
            updateStatus("MYTHICAL: No hammer found - going to bank to get hammer");
            if (config.enableDebugLogs()) {
                Microbot.log("MYTHICAL: Smithing phase - missing hammer");
            }
            currentPhase = ProcessPhase.BANKING;
            return;
        }

        // Check if inventory is full of finished items (need to bank)
        if (Rs2Inventory.isFull() && !hasBarsToSmith()) {
            updateStatus("MYTHICAL: Inventory full of finished items - going to bank to deposit");
            currentPhase = ProcessPhase.BANKING;
            return;
        }

        // MYTHICAL ENHANCEMENT: Smith items with dynamic item selection
        if (Rs2GameObject.interact("Anvil", "Smith")) {
            updateStatus("MYTHICAL: Opening smithing interface...");

            // Wait for smithing interface with timeout
            if (Rs2Widget.sleepUntilHasWidgetText("What would you like to make?", 270, 5, false, 6000)) {
                updateStatus("MYTHICAL: Smithing interface opened - selecting best item");

                // MYTHICAL ENHANCEMENT: Select the best available item based on current
                // smithing level
                if (selectBestSmithingOption()) {
                    updateStatus("MYTHICAL: Selected smithing option - waiting for completion");
                    // Wait for smithing to complete
                    waitForSmithing();

                    // After smithing, check if we should continue or go to bank
                    if (hasBarsToSmith()) {
                        updateStatus("MYTHICAL: More bars available - continuing smithing");
                        // Continue smithing cycle
                    } else {
                        updateStatus("MYTHICAL: No more bars - going to bank");
                        currentPhase = ProcessPhase.BANKING;
                    }
                } else {
                    updateStatus("MYTHICAL: Failed to select smithing option - will retry");
                }
            } else {
                updateStatus("MYTHICAL: Smithing interface did not appear - will retry");
            }
        } else {
            updateStatus("MYTHICAL: Failed to interact with anvil - will retry");
        }

        // Brief pause before next attempt
        sleep(1000, 2000);
    }

    /**
     * Walking phase - handles movement between locations
     */
    private void executeWalkingPhase() {
        // Check shutdown flag immediately
        if (isShuttingDown) {
            Rs2Walker.setTarget(null);
            try {
                net.runelite.client.plugins.microbot.util.walker.Rs2Walker.setTarget(null);
            } catch (Exception e) {
                // Ignore walker cleanup errors during shutdown
            }
            return;
        }

        WorldPoint destination = determineWalkingDestination();

        if (destination != null) {
            updateStatus("Walking to " + getLocationName(destination));

            // Check if already close to destination
            if (Rs2Player.getWorldLocation().distanceTo(destination) <= 3) {
                updatePhaseAfterWalking(destination);
                return;
            }

            // Use proper walking with retry logic
            boolean walkingStarted = false;
            int retryCount = 0;
            int maxRetries = 3;

            while (!walkingStarted && retryCount < maxRetries && !isShuttingDown) {
                if (Rs2Walker.walkTo(destination)) {
                    walkingStarted = true;
                    updateStatus("Walking started to " + getLocationName(destination));
                } else {
                    retryCount++;
                    updateStatus("Walk attempt " + retryCount + " failed, retrying...");
                    sleep(1000, 2000);
                }
            }

            if (walkingStarted) {
                // Wait for walking to start
                sleep(1000, 1500);

                // Wait for arrival with timeout
                int waitTime = 0;
                while (Rs2Player.isMoving() && waitTime < 30000) {
                    sleep(500, 800);
                    waitTime += 600;
                }

                // Update phase based on destination
                updatePhaseAfterWalking(destination);
            } else {
                handleError("Failed to start walking after " + maxRetries + " attempts");

                // Emergency fallback - if we're supposed to be banking, switch directly to
                // banking
                if (currentPhase == ProcessPhase.WALKING && Rs2Inventory.isFull()) {
                    updateStatus("Emergency fallback - switching directly to banking");
                    currentPhase = ProcessPhase.BANKING;
                    return;
                }

                sleep(3000, 5000);
            }
        } else {
            updateStatus("No walking destination needed - continuing with current phase");
            // If destination is null, continue with previous phase logic
            switch (currentPhase) {
                case WALKING:
                    // If we were walking but no destination, go back to mining
                    currentPhase = ProcessPhase.MINING;
                    break;
                default:
                    // For other phases, the logic will be handled in the next cycle
                    break;
            }
        }
    }

    /**
     * Error handling phase
     */
    private void handleErrorPhase() {
        updateStatus("Handling error state - attempt " + (failedActionCount + 1));

        failedActionCount++;
        if (failedActionCount >= config.maxFailedActions()) {
            Microbot.log("Too many failed actions (" + failedActionCount + "), stopping script");
            shutdown();
            return;
        }

        // Try to recover by closing interfaces and resetting state
        try {
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                sleep(1000, 1500);
            }

            // Close any open interfaces
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
            sleep(500, 800);

            // Reset to a safe state
            currentPhase = ProcessPhase.MINING;
            updateStatus("Recovered from error, resuming mining...");

        } catch (Exception e) {
            Microbot.log("Error during recovery: " + e.getMessage());
        }

        // Longer sleep on errors to prevent spam
        sleep(5000, 8000);
    }

    // Helper Methods

    /**
     * Finds and mines nearby ores based on current player location
     * 
     * @return true if started mining, false if no ores found
     */
    private boolean findAndMineNearbyOres() {
        String[] oreNames = config.metalType().getOreNames();
        int searchRadius = config.miningRange();

        // Get current ore counts for smart mining
        Map<String, Integer> currentOres = getCurrentOreInventory();

        updateStatus("Searching for ores within " + searchRadius + " tiles...");

        // Store initial inventory count to detect successful mining
        int initialInventoryCount = Rs2Inventory.count();

        // First priority: mine ores we need more of based on metal type ratios
        for (String oreName : oreNames) {
            if (needsMoreOre(oreName, currentOres)) {
                // Try exact ore name first
                if (Rs2GameObject.interact(oreName, "Mine")) {
                    updateStatus("Mining " + oreName + " (priority - need more)");
                    if (waitForMiningSuccess(initialInventoryCount)) {
                        return true;
                    }
                }

                // Try variations of ore names
                String[] variations = getOreNameVariations(oreName);
                for (String variation : variations) {
                    if (Rs2GameObject.interact(variation, "Mine")) {
                        updateStatus("Mining " + variation + " (priority)");
                        if (waitForMiningSuccess(initialInventoryCount)) {
                            return true;
                        }
                    }
                }
            }
        }

        // Second priority: mine any available ore for this metal type
        for (String oreName : oreNames) {
            // Try exact ore name
            if (Rs2GameObject.interact(oreName, "Mine")) {
                updateStatus("Mining " + oreName);
                if (waitForMiningSuccess(initialInventoryCount)) {
                    return true;
                }
            }

            // Try variations
            String[] variations = getOreNameVariations(oreName);
            for (String variation : variations) {
                if (Rs2GameObject.interact(variation, "Mine")) {
                    updateStatus("Mining " + variation);
                    if (waitForMiningSuccess(initialInventoryCount)) {
                        return true;
                    }
                }
            }
        }

        // Last resort: try generic rock names that might contain our ores
        String[] genericRocks = { "Rocks", "Rock", "Mining rocks" };
        for (String rockName : genericRocks) {
            if (Rs2GameObject.interact(rockName, "Mine")) {
                updateStatus("Mining " + rockName + " (generic)");
                if (waitForMiningSuccess(initialInventoryCount)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Waits for mining to complete and detects if it was successful
     * 
     * @param initialInventoryCount The inventory count before mining started
     * @return true if mining was successful (inventory increased), false otherwise
     */
    private boolean waitForMiningSuccess(int initialInventoryCount) {
        Rs2Player.waitForAnimation(3000);

        // Wait up to 10 seconds for inventory to change
        int waitTime = 0;
        int maxWaitTime = 10000; // 10 seconds max wait

        while (waitTime < maxWaitTime) {
            // Check if inventory count increased (ore was mined)
            if (Rs2Inventory.count() > initialInventoryCount) {
                onMiningSuccess();
                updateStatus("Successfully mined ore! Total ores: " + progress.getOresMined() + "/"
                        + config.targetQuantity());
                return true;
            }

            // Check if player is still animating/mining
            if (!Rs2Player.isAnimating() && !Rs2Player.isMoving() && waitTime > 2000) {
                // Mining animation finished but no ore gained - mining failed
                updateStatus("Mining attempt completed but no ore gained");
                return false;
            }

            sleep(100, 200);
            waitTime += 150;
        }

        // Timeout - assume mining failed
        updateStatus("Mining timeout - no ore gained after " + (maxWaitTime / 1000) + " seconds");
        return false;
    }

    /**
     * Gets common variations of ore names that might be found in game
     */
    private String[] getOreNameVariations(String oreName) {
        switch (oreName.toLowerCase()) {
            case "copper ore":
                return new String[] { "Copper", "Copper rock", "Copper rocks" };
            case "tin ore":
                return new String[] { "Tin", "Tin rock", "Tin rocks" };
            case "iron ore":
                return new String[] { "Iron", "Iron rock", "Iron rocks" };
            case "coal":
                return new String[] { "Coal rock", "Coal rocks" };
            case "mithril ore":
                return new String[] { "Mithril", "Mithril rock", "Mithril rocks" };
            case "adamantite ore":
                return new String[] { "Adamantite", "Adamantite rock", "Adamantite rocks" };
            case "runite ore":
                return new String[] { "Runite", "Runite rock", "Runite rocks" };
            default:
                return new String[] { oreName.replace(" ore", ""), oreName + " rock", oreName + " rocks" };
        }
    }

    /**
     * Handles banking operations based on current needs with intelligent item
     * management
     */
    private void handleBankingOperations() {
        updateStatus("Processing banking operations...");

        try {
            // First, deposit all items except tools
            updateStatus("Depositing items except tools...");
            depositItemsExceptTools();
            sleep(600, 1000);

            // IMPORTANT: Check if target mining quantity is reached after depositing ores
            // This ensures we transition to next phase right away if target is reached
            int totalOresMined = progress.getOresMined();
            int targetQuantity = config.targetQuantity();

            if (totalOresMined >= targetQuantity) {
                updateStatus(
                        "Target mining quantity reached after deposit! Ores: " + totalOresMined + "/" + targetQuantity);

                // Close bank and determine next phase immediately
                Rs2Bank.closeBank();
                sleep(500, 800);

                // MYTHICAL FIX: Respect starting phase configuration when determining next
                // phase
                if (config.startingPhase() == StartingPhase.SMELTING && progress.getBarsSmelted() < getRequiredBars()) {
                    // Force smelting phase when starting at smelting and haven't completed smelting
                    // yet
                    updateStatus("Starting phase is SMELTING - forcing smelting phase! Withdrawing ores...");

                    if (Rs2Bank.openBank()) {
                        sleep(600, 1000);
                        withdrawOresForSmelting();
                        Rs2Bank.closeBank();
                        sleep(500, 800);
                    }

                    currentPhase = ProcessPhase.WALKING; // Will walk to furnace
                }
                // Normal priority logic for other cases
                else if (config.smeltBars() && needsMoreOresForSmelting()) {
                    updateStatus("Moving to smelting phase - target reached! Withdrawing ores...");

                    // Reopen bank to withdraw ores for smelting
                    if (Rs2Bank.openBank()) {
                        sleep(600, 1000);
                        withdrawOresForSmelting();
                        Rs2Bank.closeBank();
                        sleep(500, 800);
                    }

                    currentPhase = ProcessPhase.WALKING; // Will walk to furnace
                } else if (config.smithItems() && needsBarsForSmithing()) {
                    updateStatus("Moving to smithing phase - target reached! Withdrawing bars...");

                    // Reopen bank to withdraw bars for smithing
                    if (Rs2Bank.openBank()) {
                        sleep(600, 1000);
                        withdrawBarsForSmithing();
                        Rs2Bank.closeBank();
                        sleep(500, 800);
                        
                        // CRITICAL: Verify we have bars after withdrawal
                        if (hasBarsToSmith()) {
                            updateStatus("Successfully withdrew bars - proceeding to anvil");
                            if (config.enableDebugLogs()) {
                                Microbot.log("SMITHING TRANSITION: Bars in inventory: " + 
                                           Rs2Inventory.count(config.metalType().getBarName()));
                            }
                        } else {
                            updateStatus("WARNING: No bars in inventory after withdrawal!");
                        }
                    }

                    currentPhase = ProcessPhase.WALKING; // Will walk to anvil
                } else {
                    updateStatus("Cycle completed - starting new mining cycle!");
                    
                    // Reset progress for new cycle
                    progress.setOresMined(0);
                    progress.setBarsSmelted(0);
                    progress.setItemsSmithed(0);
                    
                    currentPhase = ProcessPhase.WALKING; // Walk back to mining
                }
                return; // Exit banking immediately
            }

            // Continue with normal banking operations if more ores needed
            updateStatus("Still need more ores - continuing normal banking...");

            // Withdraw necessary tools based on next planned activity
            updateStatus("Withdrawing required tools...");
            withdrawRequiredTools();

            // Withdraw required items for next phase
            updateStatus("Withdrawing required items...");
            withdrawRequiredItems();

            // Close bank before determining next phase
            updateStatus("Closing bank...");
            Rs2Bank.closeBank();
            sleep(500, 800);

            // Determine next phase based on current progress and config
            updateStatus("Determining next phase...");
            determineNextPhaseFromBank();

        } catch (Exception e) {
            handleError("Banking operations failed", e);
        }
    }

    /**
     * MYTHICAL-LEVEL ENHANCEMENT: Deposits all items except essential tools with
     * smart handling
     * Properly handles ores, bars, and finished smithed items
     */
    private void depositItemsExceptTools() {
        // MYTHICAL ENHANCEMENT: Count items being deposited for progress tracking
        String[] oreNames = config.metalType().getOreNames();
        String barName = config.metalType().getBarName();

        // Count ores being deposited to update progress
        int totalOresDeposited = 0;
        for (String oreName : oreNames) {
            int oreCount = Rs2Inventory.count(oreName);
            if (oreCount > 0) {
                totalOresDeposited += oreCount;
                updateStatus("Depositing " + oreCount + " " + oreName);
            }
        }

        // Count bars being deposited (from smelting)
        int barsDeposited = Rs2Inventory.count(barName);
        if (barsDeposited > 0) {
            updateStatus("Depositing " + barsDeposited + " " + barName);
        }

        // MYTHICAL ENHANCEMENT: Count smithed items by checking known smithing products
        int smithedItemsDeposited = 0;
        for (SmithingProduct product : SmithingProduct.values()) {
            if (product.canSmithWithLevel(Rs2Player.getRealSkillLevel(Skill.SMITHING))) {
                int count = Rs2Inventory.count(product.getItemName());
                if (count > 0) {
                    smithedItemsDeposited += count;
                    updateStatus("Depositing " + count + " " + product.getItemName());
                }
            }
        }

        // Keep essential tools in inventory
        String[] toolsToKeep = getEssentialTools();

        // Deposit all non-essential items
        Rs2Bank.depositAllExcept(toolsToKeep);

        // MYTHICAL ENHANCEMENT: Update progress counters
        if (totalOresDeposited > 0) {
            progress.oresMined += totalOresDeposited;
            updateStatus("Progress updated: " + progress.oresMined + " total ores mined");
        }

        if (barsDeposited > 0) {
            progress.barsSmelted += barsDeposited;
            updateStatus("Progress updated: " + progress.barsSmelted + " total bars smelted");
        }

        if (smithedItemsDeposited > 0) {
            progress.itemsSmithed += smithedItemsDeposited;
            updateStatus("Progress updated: " + progress.itemsSmithed + " total items smithed");
        }
    }

    /**
     * Gets list of essential tools to keep in inventory
     */
    private String[] getEssentialTools() {
        List<String> tools = new ArrayList<>();

        // Always keep pickaxe for mining
        tools.add("pickaxe");

        // Keep hammer if we're smithing
        if (config.smithItems()) {
            tools.add("Hammer");
        }

        return tools.toArray(new String[0]);
    }

    /**
     * Withdraws required tools based on next activity
     */
    private void withdrawRequiredTools() {
        // Determine what phase we'll be doing next
        ProcessPhase nextPhase = determineNextPhase();

        // Withdraw pickaxe if needed for mining and not equipped
        if ((nextPhase == ProcessPhase.MINING || config.withdrawPickaxe()) && !hasPickaxe()) {
            if (!withdrawBestPickaxe()) {
                Rs2Bank.withdrawOne("Bronze pickaxe"); // Fallback
            }
        }

        // Only withdraw hammer if we're actually going to be smithing (not smelting!)
        if (nextPhase == ProcessPhase.SMITHING && config.smithItems() && !hasHammer()) {
            Rs2Bank.withdrawOne("Hammer");
            updateStatus("Withdrawing hammer for smithing");
        }

        // Withdraw special equipment if enabled
        if (config.useSpecialEquipment()) {
            withdrawSpecialEquipment();
        }
    }

    /**
     * Determines what the next phase will be without changing currentPhase
     */
    private ProcessPhase determineNextPhase() {
        // Check if we haven't reached the target ore quantity yet
        int totalOresMined = progress.getOresMined();
        int targetQuantity = config.targetQuantity();

        // Debug logging for troubleshooting starting phase issues
        if (config.enableDebugLogs()) {
            Microbot.log("=== Phase Determination Debug ===");
            Microbot.log("Total ores mined: " + totalOresMined + "/" + targetQuantity);
            Microbot.log("Current phase: " + currentPhase);
            Microbot.log("Starting phase: " + config.startingPhase());
            Microbot.log("Smelt bars enabled: " + config.smeltBars());
            Microbot.log("Smith items enabled: " + config.smithItems());
            Microbot.log("Needs more ores for smelting: " + needsMoreOresForSmelting());
            Microbot.log("Needs bars for smithing: " + needsBarsForSmithing());
        }

        // EXPERT FIX: Priority check for starting phase configuration in walking
        // destination logic
        // This ensures the walking destination respects the starting phase selection
        if (config.startingPhase() == StartingPhase.SMELTING && config.smeltBars() &&
                progress.getBarsSmelted() < getRequiredBars()) {
            if (config.enableDebugLogs()) {
                Microbot.log("Starting phase SMELTING override - directing to smelting");
            }
            return ProcessPhase.SMELTING;
        }

        if (config.startingPhase() == StartingPhase.SMITHING && config.smithItems() &&
                needsBarsForSmithing()) {
            if (config.enableDebugLogs()) {
                Microbot.log("Starting phase SMITHING override - directing to smithing");
            }
            return ProcessPhase.SMITHING;
        }

        if (totalOresMined < targetQuantity) {
            return ProcessPhase.MINING;
        } else if (config.smeltBars() && needsMoreOresForSmelting()) {
            return ProcessPhase.SMELTING;
        } else if (config.smithItems() && needsBarsForSmithing()) {
            return ProcessPhase.SMITHING;
        } else {
            // CONTINUOUS LOOP: Never complete, always start new mining cycle
            return ProcessPhase.MINING;
        }
    }

    /**
     * Withdraws the best available pickaxe
     */
    private boolean withdrawBestPickaxe() {
        String[] pickaxes = {
                "Dragon pickaxe", "Rune pickaxe", "Adamant pickaxe",
                "Mithril pickaxe", "Steel pickaxe", "Iron pickaxe", "Bronze pickaxe"
        };

        for (String pickaxe : pickaxes) {
            if (Rs2Bank.hasItem(pickaxe)) {
                return Rs2Bank.withdrawOne(pickaxe);
            }
        }
        return false;
    }

    /**
     * Withdraws special equipment for efficiency
     */
    private void withdrawSpecialEquipment() {
        // Ring of Forging for iron smelting
        if (config.metalType().getDisplayName().equals("Iron") &&
                Rs2Bank.hasItem("Ring of forging")) {
            Rs2Bank.withdrawOne("Ring of forging");
        }

        // Goldsmith gauntlets for gold smelting
        if (config.metalType().getDisplayName().equals("Gold") &&
                Rs2Bank.hasItem("Goldsmith gauntlets")) {
            Rs2Bank.withdrawOne("Goldsmith gauntlets");
        }

        // Coal bag for metals requiring coal
        if (config.useCoalBag() && needsCoal() && Rs2Bank.hasItem("Coal bag")) {
            Rs2Bank.withdrawOne("Coal bag");
        }
    }

    /**
     * Withdraws required items for the next phase
     */
    private void withdrawRequiredItems() {
        ProcessPhase nextPhase = determineNextPhase();
        updateStatus("Withdrawing items for next phase: " + nextPhase);

        switch (nextPhase) {
            case MINING:
                // No items needed for mining (just tools)
                updateStatus("No items needed for mining");
                break;
            case SMELTING:
                withdrawOresForSmelting();
                break;
            case SMITHING:
                withdrawBarsForSmithing();
                break;
            default:
                updateStatus("No items needed for phase: " + nextPhase);
                break;
        }
    }

    /**
     * Withdraws ores needed for smelting
     * CONTINUOUS LOOP: Respects target quantity while optimizing for inventory capacity
     */
    private void withdrawOresForSmelting() {
        updateStatus("Withdrawing ores for smelting...");

        String[] oreNames = config.metalType().getOreNames();
        int availableSlots = 28 - Rs2Inventory.count(); // Account for existing items/tools
        int targetQuantity = config.targetQuantity();
        int oresUsedForSmelting = progress.getOresUsedForSmelting();
        int remainingOresNeeded = Math.max(0, targetQuantity - oresUsedForSmelting);

        updateStatus("Available inventory slots: " + availableSlots);
        updateStatus("Target quantity: " + targetQuantity + ", Used for smelting: " + oresUsedForSmelting + ", Remaining: " + remainingOresNeeded);

        if (availableSlots <= 0) {
            updateStatus("No available inventory slots for ores");
            return;
        }

        if (remainingOresNeeded <= 0) {
            updateStatus("All target ores have been processed for smelting");
            return;
        }

        // Calculate how many ores to withdraw this trip
        // If target is 28 or more, fill inventory; if less, respect the target
        int oresToWithdrawThisTrip;
        if (targetQuantity >= 28) {
            // Target is full inventory or more - can take full inventory
            oresToWithdrawThisTrip = Math.min(availableSlots, remainingOresNeeded);
        } else {
            // Target is less than full inventory - respect the exact target
            oresToWithdrawThisTrip = Math.min(remainingOresNeeded, availableSlots);
        }

        // For simple metals like iron, just withdraw what we need
        if (oreNames.length == 1) {
            String oreName = oreNames[0];
            
            int oresInBank = Rs2Bank.count(oreName);
            int oresToWithdraw = Math.min(oresToWithdrawThisTrip, oresInBank);

            updateStatus("Withdrawing " + oresToWithdraw + " " + oreName + " for smelting (target: " + targetQuantity + ", remaining: " + remainingOresNeeded + ")");
            
            if (oresToWithdraw > 0) {
                if (oresToWithdraw == 1) {
                    Rs2Bank.withdrawOne(oreName);
                } else {
                    Rs2Bank.withdrawX(oreName, oresToWithdraw);
                }
                sleep(600, 1000); // Wait for withdrawal
                
                updateStatus("Withdrew " + oresToWithdraw + " ores for smelting");
            }
        } else {
            // For alloy metals (like bronze: copper + tin)
            Map<String, Integer> requiredOres = calculateRequiredOresForSmelting(oresToWithdrawThisTrip, availableSlots);

            int totalOresWithdrawn = 0;
            for (Map.Entry<String, Integer> entry : requiredOres.entrySet()) {
                String oreName = entry.getKey();
                int required = entry.getValue();
                int availableInBank = Rs2Bank.count(oreName);
                int toWithdraw = Math.min(required, availableInBank);

                if (toWithdraw > 0) {
                    updateStatus("Withdrawing " + toWithdraw + " " + oreName + " for alloy smelting");
                    if (toWithdraw == 1) {
                        Rs2Bank.withdrawOne(oreName);
                    } else {
                        Rs2Bank.withdrawX(oreName, toWithdraw);
                    }
                    sleep(400, 700);
                    totalOresWithdrawn += toWithdraw;
                }
            }
            
            if (totalOresWithdrawn > 0) {
                updateStatus("Withdrew " + totalOresWithdrawn + " ores for alloy smelting");
            }
        }
    }

    /**
     * Calculates how many of each ore to withdraw for optimal smelting based on
     * remaining needs
     */
    private Map<String, Integer> calculateRequiredOresForSmelting(int remainingOresNeeded, int availableSlots) {
        Map<String, Integer> requiredOres = new HashMap<>();

        // Get metal type requirements
        MetalType metalType = config.metalType();
        String[] oreNames = metalType.getOreNames();

        if (oreNames.length == 1) {
            // Simple case: only one ore type
            String oreName = oreNames[0];
            int needed = Math.min(remainingOresNeeded, availableSlots);
            requiredOres.put(oreName, needed);
        } else {
            // Complex case: multiple ores (e.g., bronze = copper + tin)
            if (metalType == MetalType.BRONZE) {
                // Bronze: 1 copper + 1 tin = 1 bar
                // We want to smelt as many bars as possible with remaining ores
                int maxBarsFromSpace = availableSlots / 2; // 2 ores per bar
                int maxBarsFromNeed = remainingOresNeeded / 2; // Assuming equal copper+tin count
                int barsToMake = Math.min(maxBarsFromSpace, maxBarsFromNeed);

                requiredOres.put("Copper ore", barsToMake);
                requiredOres.put("Tin ore", barsToMake);
            } else if (metalType == MetalType.STEEL) {
                // Steel: 1 iron + 2 coal = 1 bar
                int maxBarsFromSpace = availableSlots / 3; // 3 ores per bar
                int maxBarsFromNeed = remainingOresNeeded / 3; // Assuming proper iron+coal ratio
                int barsToMake = Math.min(maxBarsFromSpace, maxBarsFromNeed);

                requiredOres.put("Iron ore", barsToMake);
                requiredOres.put("Coal", barsToMake * 2);
            } else {
                // Default fallback: distribute evenly but respect remaining need
                int maxOresPerType = availableSlots / oreNames.length;
                int maxOresFromNeed = remainingOresNeeded / oreNames.length;
                int oresPerType = Math.min(maxOresPerType, maxOresFromNeed);

                for (String oreName : oreNames) {
                    requiredOres.put(oreName, oresPerType);
                }
            }
        }

        return requiredOres;
    }

    /**
     * MYTHICAL-LEVEL ENHANCEMENT: Comprehensive bar withdrawal for smithing
     * Withdraws bars and ensures hammer is available for smithing operations
     */
    private void withdrawBarsForSmithing() {
        String barName = config.metalType().getBarName();

        // MYTHICAL ENHANCEMENT: First ensure we have a hammer
        if (!hasHammer()) {
            updateStatus("Withdrawing hammer for smithing...");
            if (Rs2Bank.hasItem("Hammer")) {
                Rs2Bank.withdrawOne("Hammer");
                sleep(300, 600);
            } else {
                updateStatus("WARNING: No hammer found in bank!");
                return;
            }
        }

        // MYTHICAL ENHANCEMENT: Calculate optimal bars to withdraw (leave space for
        // hammer)
        int availableSlots = 28 - Rs2Inventory.count(); // Current free slots
        int hammerSlots = hasHammer() ? 0 : 1; // Reserve space for hammer if not already have one
        int maxBarsToWithdraw = availableSlots - hammerSlots;

        int barsInBank = Rs2Bank.count(barName);
        int barsToWithdraw = Math.min(maxBarsToWithdraw, barsInBank);

        if (barsToWithdraw > 0) {
            updateStatus("Withdrawing " + barsToWithdraw + " " + barName + " for smithing (max capacity: "
                    + maxBarsToWithdraw + ")");

            // MYTHICAL ENHANCEMENT: Use X withdrawal for precise control
            if (barsToWithdraw == barsInBank || barsToWithdraw >= 10) {
                Rs2Bank.withdrawAll(barName);
            } else {
                Rs2Bank.withdrawX(barName, barsToWithdraw);
            }

            sleep(600, 1000); // Wait for withdrawal to complete

            if (config.enableDebugLogs()) {
                Microbot.log("Bars withdrawal completed - Inventory count: " + Rs2Inventory.count(barName));
            }
        } else {
            updateStatus("No bars to withdraw or inventory full");
        }
    }

    /**
     * Determines the next phase based on current progress and available items
     * MYTHICAL-LEVEL ENHANCEMENT: Properly handles starting phase scenarios
     */
    private void determineNextPhaseFromBank() {
        updateStatus("Analyzing current progress to determine next phase...");

        int totalOresMined = progress.getOresMined();
        int targetQuantity = config.targetQuantity();
        int barsSmelted = progress.getBarsSmelted();
        int requiredBars = getRequiredBars();

        // MYTHICAL ENHANCEMENT: Better logging for debugging
        if (config.enableDebugLogs()) {
            Microbot.log("=== Phase Determination From Bank ===");
            Microbot.log("Ores mined: " + totalOresMined + "/" + targetQuantity);
            Microbot.log("Bars smelted: " + barsSmelted + "/" + requiredBars);
            Microbot.log("Starting phase: " + config.startingPhase());
            Microbot.log("Current phase: " + currentPhase);
            Microbot.log("Has inventory space: " + (Rs2Inventory.count() < 28));
            Microbot.log("Smithing enabled: " + config.smithItems());
            Microbot.log("Needs bars for smithing: " + needsBarsForSmithing());
            Microbot.log("Has bars to smith (inventory): " + hasBarsToSmith());
            Microbot.log("Should start new mining cycle: " + shouldStartNewMiningCycle());
        }

        // MYTHICAL LOGIC: Handle starting at smelting phase specially
        if (config.startingPhase() == StartingPhase.SMELTING && barsSmelted < requiredBars) {
            // When starting at smelting, prioritize smelting operations regardless of ore
            // mining progress
            if (Rs2Inventory.hasItem(config.metalType().getOreNames()[0])) {
                updateStatus("Starting smelting mode - have ores in inventory, going to furnace");
                currentPhase = ProcessPhase.WALKING; // Walk to furnace for smelting
            } else if (needsMoreOresForSmelting()) {
                updateStatus("Starting smelting mode - need ores, staying at bank for withdrawal");
                currentPhase = ProcessPhase.BANKING; // Stay at bank to get ores
            } else {
                updateStatus("Starting smelting mode - no ores available, task may be complete");
                currentPhase = ProcessPhase.COMPLETE;
            }
            updateStatus("Smelting start mode - Next phase: " + currentPhase);
            return;
        }

        // NORMAL LOGIC: Standard priority system with MYTHICAL-LEVEL ENHANCEMENT
        // Priority 1: Check if we haven't reached the target ore quantity yet
        if (totalOresMined < targetQuantity) {
            updateStatus(
                    "Target not reached - continuing mining (Ores: " + totalOresMined + "/" + targetQuantity + ")");
            currentPhase = ProcessPhase.WALKING; // Walk back to mining area
        }
        // Priority 2: MYTHICAL ENHANCEMENT - After target reached, FIRST check for
        // existing bars to smith
        else if (config.smithItems() && needsBarsForSmithing()) {
            updateStatus("Target reached! Found bars in bank - moving to smithing phase");

            // Check if we have bars in inventory after withdrawal
            if (hasBarsToSmith()) {
                updateStatus("Have bars in inventory - going to anvil for smithing");
                currentPhase = ProcessPhase.WALKING; // Walk to anvil
            } else {
                updateStatus("No bars in inventory - need to withdraw bars for smithing");
                currentPhase = ProcessPhase.BANKING; // Stay to withdraw bars
            }
        }
        // Priority 3: If no bars to smith, then check if we need to smelt more bars
        else if (config.smeltBars() && barsSmelted < requiredBars && needsMoreOresForSmelting()) {
            updateStatus("Target reached! Need to smelt " + (requiredBars - barsSmelted) + " more bars");
            // Check if we have ores in inventory already
            if (Rs2Inventory.hasItem(config.metalType().getOreNames()[0])) {
                currentPhase = ProcessPhase.WALKING; // Walk to furnace
            } else {
                currentPhase = ProcessPhase.BANKING; // Stay at bank to get ores first
            }
        }
        // Priority 4: CONTINUOUS LOOP ENHANCEMENT - If current cycle is complete, start new mining cycle
        else if (shouldStartNewMiningCycle()) {
            updateStatus("Cycle completed! Starting new mining cycle...");
            
            // Reset progress counters for new cycle
            progress.setOresMined(0);
            progress.setBarsSmelted(0);
            progress.setItemsSmithed(0);
            // Don't reset oresUsedForSmelting as it's cumulative
            
            updateStatus("Progress reset - starting new mining cycle");
            currentPhase = ProcessPhase.WALKING; // Walk back to mining area
        }
        // Priority 5: CRITICAL SAFETY CHECK - If we have bars in inventory, we MUST smith them
        else if (config.smithItems() && hasBarsToSmith()) {
            updateStatus("SAFETY: Found bars in inventory that need to be smithed!");
            currentPhase = ProcessPhase.WALKING; // Walk to anvil to smith them
        }
        // Priority 6: Default fallback - go back to mining if nothing else applies
        else {
            updateStatus("No specific action needed - defaulting to mining phase");
            currentPhase = ProcessPhase.WALKING; // Walk back to mining area
        }

        updateStatus("Next phase determined: " + currentPhase);
    }

    // Helper methods for banking logic

    private boolean needsCoal() {
        String[] oreNames = config.metalType().getOreNames();
        for (String ore : oreNames) {
            if (ore.toLowerCase().contains("coal")) {
                return true;
            }
        }
        return false;
    }

    private boolean needsMoreOresForSmelting() {
        if (!config.smeltBars()) {
            return false;
        }

        // CONTINUOUS LOOP LOGIC: Always check if we have ores available for smelting
        // In continuous mode, we don't track cumulative usage limits
        
        // Check if we have ores available (in bank or inventory)
        boolean hasOresInBank = Rs2Bank.hasItem(config.metalType().getOreNames()[0]);
        boolean hasOresInInventory = config.metalType().hasRequiredOres();

        if (config.enableDebugLogs()) {
            Microbot.log("=== Smelting Needs Analysis (Continuous Mode) ===");
            Microbot.log("Smelt bars enabled: " + config.smeltBars());
            Microbot.log("Has ores in bank: " + hasOresInBank);
            Microbot.log("Has ores in inventory: " + hasOresInInventory);
            Microbot.log("Needs more ores for smelting: " + (hasOresInBank || hasOresInInventory));
        }

        return hasOresInBank || hasOresInInventory;
    }

    /**
     * MYTHICAL-LEVEL ENHANCEMENT: Comprehensive check for smithing needs
     * Checks if smithing is enabled and if bars are available in bank for smithing
     */
    private boolean needsBarsForSmithing() {
        if (!config.smithItems()) {
            return false;
        }

        String barName = config.metalType().getBarName();

        // MYTHICAL ENHANCEMENT: Check if we have bars available in bank
        boolean hasBarsInBank = Rs2Bank.hasItem(barName);

        // MYTHICAL ENHANCEMENT: Also check if bars are already in inventory
        boolean hasBarsInInventory = Rs2Inventory.hasItem(barName);

        if (config.enableDebugLogs()) {
            Microbot.log("=== Smithing Needs Analysis ===");
            Microbot.log("Smithing enabled: " + config.smithItems());
            Microbot.log("Bar type: " + barName);
            Microbot.log("Bars in bank: " + hasBarsInBank + " (count: " + Rs2Bank.count(barName) + ")");
            Microbot.log("Bars in inventory: " + hasBarsInInventory + " (count: " + Rs2Inventory.count(barName) + ")");
        }

        return hasBarsInBank || hasBarsInInventory;
    }

    /**
     * CONTINUOUS LOOP ENHANCEMENT: Checks if current cycle is complete and should restart mining
     * Used to determine when to start a new mining cycle instead of completing
     */
    private boolean shouldStartNewMiningCycle() {
        int totalOresMined = progress.getOresMined();
        int targetQuantity = config.targetQuantity();
        int oresUsedForSmelting = progress.getOresUsedForSmelting();

        // Check if ore target is reached
        boolean oreTargetReached = totalOresMined >= targetQuantity;

        // Check if smelting is complete (if enabled) - based on ores used for smelting
        boolean smeltingComplete;
        if (!config.smeltBars()) {
            smeltingComplete = true;
        } else {
            // Smelting is complete when all target ores have been used for smelting attempts
            boolean allTargetOresUsed = oresUsedForSmelting >= targetQuantity;
            boolean noMoreOres = !Rs2Bank.hasItem(config.metalType().getOreNames()[0]);
            smeltingComplete = allTargetOresUsed || noMoreOres;
        }

        // Check if smithing is complete (if enabled) - no more bars available to smith
        boolean smithingComplete;
        if (!config.smithItems()) {
            smithingComplete = true;
        } else {
            // Smithing is complete when there are no bars in bank AND no bars in inventory
            boolean noBarsInBank = !Rs2Bank.hasItem(config.metalType().getBarName());
            boolean noBarsInInventory = !Rs2Inventory.hasItem(config.metalType().getBarName());
            smithingComplete = noBarsInBank && noBarsInInventory;
        }

        boolean cycleComplete = oreTargetReached && smeltingComplete && smithingComplete;

        if (config.enableDebugLogs()) {
            Microbot.log("=== Cycle Completion Check ===");
            Microbot.log("Ore target reached: " + oreTargetReached + " (" + totalOresMined + "/" + targetQuantity + ")");
            Microbot.log("Smelting complete: " + smeltingComplete + " (ores used: " + oresUsedForSmelting + "/" + targetQuantity + ")");
            Microbot.log("Smithing complete: " + smithingComplete + " (no bars in bank: " + 
                    !Rs2Bank.hasItem(config.metalType().getBarName()) + ", no bars in inventory: " + 
                    !Rs2Inventory.hasItem(config.metalType().getBarName()) + ")");
            Microbot.log("Cycle complete (start new mining): " + cycleComplete);
        }

        return cycleComplete;
    }

    /**
     * Gets the nearest bank using Microbot's intelligent bank detection
     */
    private BankLocation getNearestBank() {
        BankLocation nearestBank = Rs2Bank.getNearestBank();
        if (nearestBank != null) {
            updateStatus("Found nearest bank: " + nearestBank.toString());
            return nearestBank;
        }

        // Fallback to Al Kharid if no bank found (shouldn't happen)
        updateStatus("No bank detected - defaulting to Al Kharid");
        return BankLocation.AL_KHARID;
    }

    /**
     * Checks if player has a pickaxe
     */
    private boolean hasPickaxe() {
        return Rs2Inventory.hasItem("pickaxe") || Rs2Equipment.isWearing("pickaxe");
    }

    /**
     * Checks if player has a hammer
     */
    private boolean hasHammer() {
        return Rs2Inventory.hasItem("Hammer");
    }

    /**
     * Checks if player has required ores for smelting
     */
    private boolean hasRequiredOres() {
        return config.metalType().hasRequiredOres();
    }

    /**
     * Checks if player has bars to smith
     */
    private boolean hasBarsToSmith() {
        String barName = config.metalType().getBarName();
        return Rs2Inventory.hasItem(barName);
    }

    /**
     * Handles error logging and phase transitions
     */
    private void handleError(String message) {
        handleError(message, null);
    }

    private void handleError(String message, Exception e) {
        Microbot.log("ERROR: " + message);
        if (e != null) {
            log.error(message, e);
        }
        currentPhase = ProcessPhase.ERROR;
    }

    /**
     * Updates the status message
     */
    private void updateStatus(String status) {
        Microbot.status = status;
        if (config.enableDebugLogs()) {
            Microbot.log("Status: " + status);
        }
    }

    /**
     * Configures anti-ban settings
     */
    private void configureAntiban() {
        Rs2AntibanSettings.actionCooldownChance = 0.1;
        Rs2AntibanSettings.microBreakChance = 0.05;
        // Additional anti-ban configuration can be added here
    }

    /**
     * Selects the appropriate smelting option in the furnace interface
     * 
     * @return true if option was successfully selected
     */
    private boolean selectSmeltingOption() {
        try {
            // Find the correct bar based on metal type
            String barName = config.metalType().getBarName();
            updateStatus("Attempting to select " + barName + " for smelting");

            if (Rs2Widget.clickWidget(barName)) {
                updateStatus("Successfully clicked " + barName + " option");

                // Wait a bit for quantity interface
                sleep(800, 1200);

                // Select "Make All" by pressing space
                Rs2Keyboard.keyPress(' '); // Make All shortcut
                updateStatus("Pressed space for Make All");

                // Wait for the interface to close indicating smelting started
                boolean smeltingStarted = Rs2Widget.sleepUntilHasNotWidgetText("What would you like to smelt?", 270, 5,
                        false, 4000);

                if (smeltingStarted) {
                    updateStatus("Smelting started successfully");
                    return true;
                } else {
                    updateStatus("Interface did not close - smelting may not have started");
                    return false;
                }
            } else {
                updateStatus("Failed to click " + barName + " widget");
                return false;
            }

        } catch (Exception e) {
            handleError("Failed to select smelting option", e);
            return false;
        }
    }

    /**
     * Waits for smelting process to complete with intelligent monitoring
     */
    /**
     * Waits for smelting process to complete with intelligent monitoring
     * NEW LOGIC: Tracks ores used for smelting attempts instead of bars produced
     */
    private void waitForSmelting() {
        updateStatus("Smelting in progress...");
        long smeltingStartTime = System.currentTimeMillis();

        // Track initial ore count to see how many ores are consumed
        String[] oreNames = config.metalType().getOreNames();
        int initialOreCount = 0;
        for (String oreName : oreNames) {
            initialOreCount += Rs2Inventory.count(oreName);
        }

        updateStatus("Starting smelting with " + initialOreCount + " ores in inventory");

        while (Rs2Player.isAnimating() || Rs2Player.isMoving()) {
            // Safety timeout (5 minutes max)
            if (System.currentTimeMillis() - smeltingStartTime > 300000) {
                handleError("Smelting timeout exceeded");
                break;
            }

            // Check current ore count
            int currentOreCount = 0;
            for (String oreName : oreNames) {
                currentOreCount += Rs2Inventory.count(oreName);
            }

            // Check if we're still smelting - either animating OR have ores
            if (!Rs2Player.isAnimating() && currentOreCount == 0) {
                updateStatus("Smelting completed - no more ores in inventory");
                break;
            }

            // Check if inventory is full (edge case)
            if (Rs2Inventory.isFull() && currentOreCount == 0) {
                updateStatus("Smelting session complete - inventory full or no ores left");
                break;
            }

            sleep(600, 1000);
        }

        // Final ore count check to track total ores consumed in this session
        int finalOreCount = 0;
        for (String oreName : oreNames) {
            finalOreCount += Rs2Inventory.count(oreName);
        }

        // Calculate total ores consumed in this smelting session
        int oresConsumedThisSession = initialOreCount - finalOreCount;
        if (oresConsumedThisSession > 0) {
            progress.addOresUsedForSmelting(oresConsumedThisSession);
            updateStatus("Ores consumed this session: " + oresConsumedThisSession + 
                        " (Total used: " + progress.getOresUsedForSmelting() + "/" + config.targetQuantity() + ")");
        }

        updateStatus("Smelting session complete! Total ores used: " + progress.getOresUsedForSmelting() + "/"
                + config.targetQuantity());
    }

    /**
     * Selects the best available smithing option with mythical-level dynamic item
     * selection
     * MYTHICAL ENHANCEMENT: Progressive item selection based on current smithing
     * level and bar efficiency
     * 
     * @return true if option was successfully selected
     */
    private boolean selectBestSmithingOption() {
        try {
            // Wait for smithing interface to appear
            if (!Rs2Widget.hasWidget("What would you like to make?") && !Rs2Widget.hasWidget("Anvil")) {
                sleep(1000, 1500);
                return false;
            }

            // MYTHICAL ENHANCEMENT: Get current smithing level for dynamic item selection
            int currentSmithingLevel = Rs2Player.getRealSkillLevel(Skill.SMITHING);

            // MYTHICAL ENHANCEMENT: Determine best item to smith based on level,
            // efficiency, and available bars
            SmithingProduct bestItem = SmithingProduct.getBestAvailableItem(currentSmithingLevel);

            if (bestItem == null) {
                updateStatus("No smithable items available for current level: " + currentSmithingLevel);
                return false;
            }

            // MYTHICAL ENHANCEMENT: Enhanced logging for debugging
            if (config.enableDebugLogs()) {
                Microbot.log("=== Smithing Item Selection ===");
                Microbot.log("Current Smithing Level: " + currentSmithingLevel);
                Microbot.log("Selected Item: " + bestItem.getItemName());
                Microbot.log("Bars Required: " + bestItem.getBarsRequired());
                Microbot.log("XP per Bar: " + bestItem.getXpPerBar());
            }

            // MYTHICAL ENHANCEMENT: Try multiple widget interaction methods for reliability
            String itemName = bestItem.getItemName();
            boolean widgetClicked = false;

            // Try different ways to click the smithing option
            if (Rs2Widget.clickWidget(itemName)) {
                widgetClicked = true;
                updateStatus("Selected " + itemName + " for smithing (method 1)");
            } else if (Rs2Widget.clickWidget(itemName.toLowerCase())) {
                widgetClicked = true;
                updateStatus("Selected " + itemName + " for smithing (method 2)");
            } else if (Rs2Widget.clickWidget(itemName.toUpperCase())) {
                widgetClicked = true;
                updateStatus("Selected " + itemName + " for smithing (method 3)");
            }

            if (!widgetClicked) {
                updateStatus("Failed to click smithing widget for: " + itemName);
                return false;
            }

            // Wait for quantity interface to appear
            sleep(800, 1200);

            // MYTHICAL ENHANCEMENT: Smart quantity calculation
            int availableBars = Rs2Inventory.count(config.metalType().getBarName());
            int maxItems = bestItem.getMaxItemsFromBars(availableBars);

            if (maxItems > 0) {
                updateStatus("Smithing " + maxItems + " " + itemName + " using "
                        + (maxItems * bestItem.getBarsRequired()) + " bars");

                // MYTHICAL ENHANCEMENT: Optimized quantity input
                if (maxItems >= 10) {
                    Rs2Keyboard.keyPress(' '); // Make All (space bar)
                    updateStatus("Selected 'Make All' option");
                } else {
                    Rs2Keyboard.typeString(String.valueOf(maxItems));
                    Rs2Keyboard.keyPress(KeyEvent.VK_ENTER);
                    updateStatus("Entered quantity: " + maxItems);
                }
                return true;
            } else {
                updateStatus(
                        "Not enough bars to make any " + itemName + " (need " + bestItem.getBarsRequired() + " bars)");
                return false;
            }

        } catch (Exception e) {
            handleError("Failed to select smithing option", e);
            return false;
        }
    }

    /**
     * Waits for smithing process to complete with progress monitoring
     */
    private void waitForSmithing() {
        updateStatus("Smithing in progress...");
        long smithingStartTime = System.currentTimeMillis();
        int initialBarCount = Rs2Inventory.count(config.metalType().getBarName());

        while (Rs2Player.isAnimating() || Rs2Player.isMoving()) {
            // Monitor smithing progress
            int currentBarCount = Rs2Inventory.count(config.metalType().getBarName());
            if (currentBarCount < initialBarCount) {
                int barsUsed = initialBarCount - currentBarCount;
                for (int i = 0; i < barsUsed; i++) {
                    progress.incrementItemsSmithed();
                }
                initialBarCount = currentBarCount;
            }

            // Safety timeout (10 minutes max)
            if (System.currentTimeMillis() - smithingStartTime > 600000) {
                handleError("Smithing timeout exceeded");
                break;
            }

            // Check if we're still smithing
            if (!Rs2Player.isAnimating() && !hasBarsToSmith()) {
                updateStatus("Smithing completed");
                break;
            }

            sleep(600, 1000);
        }
    }

    /**
     * Intelligently determines the next walking destination based on current phase
     * and inventory
     * 
     * @return WorldPoint of the destination, or null if no movement needed
     */
    private WorldPoint determineWalkingDestination() {
        updateStatus("Determining walking destination for phase: " + currentPhase);

        switch (currentPhase) {
            case WALKING:
                // If we're in WALKING phase, determine where we should go based on what we need
                // to do next
                ProcessPhase nextPhase = determineNextPhase();
                updateStatus("Walking phase - next intended phase: " + nextPhase);

                switch (nextPhase) {
                    case MINING:
                        // CRITICAL: Double-check target before walking back to mining
                        int totalOresMined = progress.getOresMined();
                        int targetQuantity = config.targetQuantity();

                        if (totalOresMined >= targetQuantity) {
                            updateStatus("Target mining quantity reached - checking next phase or starting new cycle");
                            // Force transition to next appropriate phase instead
                            if (config.smeltBars() && needsMoreOresForSmelting()) {
                                currentPhase = ProcessPhase.SMELTING;
                                return FURNACE_LOCATION;
                            } else if (config.smithItems() && needsBarsForSmithing()) {
                                currentPhase = ProcessPhase.SMITHING;
                                return ANVIL_LOCATION;
                            } else {
                                // Start new mining cycle
                                updateStatus("Starting new mining cycle...");
                                currentPhase = ProcessPhase.MINING;
                                return currentMiningArea != null ? currentMiningArea : AL_KHARID_MINING_AREA;
                            }
                        }

                        // Ensure we have a valid mining area
                        if (currentMiningArea == null) {
                            currentMiningArea = detectCurrentMiningArea();
                            updateStatus("Re-detected mining area: " + getLocationName(currentMiningArea));
                        }
                        updateStatus("Walking back to mining area: " + getLocationName(currentMiningArea));
                        return currentMiningArea;

                    case SMELTING:
                        updateStatus("Walking to furnace for smelting");
                        return FURNACE_LOCATION;

                    case SMITHING:
                        updateStatus("Walking to anvil for smithing");
                        return ANVIL_LOCATION;

                    default:
                        updateStatus("No specific destination needed for: " + nextPhase);
                        return null;
                }

            case MINING:
                // CRITICAL: Check target before deciding to walk to mining area
                int totalOresMined = progress.getOresMined();
                int targetQuantity = config.targetQuantity();

                if (totalOresMined >= targetQuantity) {
                    updateStatus("Target mining quantity reached - not going to mining area");
                    currentPhase = ProcessPhase.BANKING; // Go bank to proceed to next phase
                    return null;
                }

                // Return current mining area or stay where we are if mining is possible
                if (currentMiningArea != null) {
                    updateStatus("Walking to current mining area: " + getLocationName(currentMiningArea));
                    return currentMiningArea;
                } else if (hasNearbyOres()) {
                    updateStatus("Ores nearby - no walking needed");
                    return null; // No need to walk, ores are nearby
                } else {
                    // Default to Al Kharid mining for iron
                    updateStatus("Walking to default mining area: Al Kharid");
                    return AL_KHARID_MINING_AREA;
                }

            case SMELTING:
                // CRITICAL FIX: Check for required ores before walking to furnace
                if (!hasRequiredOres()) {
                    updateStatus("No ores in inventory - going to bank for ores");
                    currentPhase = ProcessPhase.BANKING;
                    return null; // Banking will handle the walking
                }
                updateStatus("Walking to furnace for smelting");
                return FURNACE_LOCATION;

            case SMITHING:
                updateStatus("Walking to anvil");
                return ANVIL_LOCATION;

            case BANKING:
                // For banking phase, we'll let the banking method handle walking
                // Return null to avoid walking conflicts
                updateStatus("Banking phase - banking method will handle walking");
                return null;

            case ERROR:
            case COMPLETE:
            default:
                updateStatus("No destination needed for phase: " + currentPhase + " - defaulting to mining");
                // Force mining phase if in unexpected state
                currentPhase = ProcessPhase.MINING;
                return null;
        }
    }

    /**
     * Gets a human-readable name for a location
     */
    private String getLocationName(WorldPoint point) {
        if (point.equals(LUMBRIDGE_MINING_AREA))
            return "Lumbridge Mining Area";
        if (point.equals(AL_KHARID_MINING_AREA))
            return "Al Kharid Mining Area";
        if (point.equals(FURNACE_LOCATION))
            return "Al Kharid Furnace";
        if (point.equals(ANVIL_LOCATION))
            return "Varrock Anvil";

        // Check if this point matches any bank location
        BankLocation nearestBank = getNearestBank();
        if (nearestBank != null && point.equals(nearestBank.getWorldPoint())) {
            return nearestBank.toString() + " Bank";
        }

        // Fallback for legacy bank coordinates
        if (point.equals(LUMBRIDGE_BANK))
            return "Lumbridge Bank";
        if (point.equals(AL_KHARID_BANK))
            return "Al Kharid Bank";

        return "Mining Area";
    }

    /**
     * Updates the current phase after reaching a walking destination
     */
    private void updatePhaseAfterWalking(WorldPoint destination) {
        if (destination.equals(LUMBRIDGE_MINING_AREA) || destination.equals(AL_KHARID_MINING_AREA)
                || destination.equals(currentMiningArea)) {
            currentPhase = ProcessPhase.MINING;
            // Ensure current mining area is set to the destination we just reached
            if (currentMiningArea == null || !currentMiningArea.equals(destination)) {
                currentMiningArea = destination;
                updateStatus("Updated current mining area to: " + getLocationName(currentMiningArea));
            }
        } else if (destination.equals(FURNACE_LOCATION)) {
            currentPhase = ProcessPhase.SMELTING;
        } else if (destination.equals(ANVIL_LOCATION)) {
            currentPhase = ProcessPhase.SMITHING;
        } else {
            // Check if destination is a bank location
            BankLocation nearestBank = getNearestBank();
            if (nearestBank != null && destination.equals(nearestBank.getWorldPoint())) {
                currentPhase = ProcessPhase.BANKING;
            } else if (destination.equals(LUMBRIDGE_BANK) || destination.equals(AL_KHARID_BANK)) {
                // Legacy bank coordinate support
                currentPhase = ProcessPhase.BANKING;
            } else {
                // If destination is current mining area, switch to mining
                currentPhase = ProcessPhase.MINING;
                currentMiningArea = destination; // Set the destination as the new mining area
                updateStatus("Set new mining area to: " + getLocationName(currentMiningArea));
            }
        }

        updateStatus("Phase updated to: " + currentPhase + " after reaching destination");
    }

    /**
     * Calculates required number of bars based on target quantity and ore ratios
     * For iron smelting, accounts for 50% success rate by reducing expected bar
     * count
     * 
     * IRON SMELTING FLEXIBILITY: Iron ore has approximately 50% success rate
     * in-game,
     * meaning not all ores will successfully smelt into bars. This method adjusts
     * expectations to be more realistic and allows the plugin to continue working
     * with whatever bars are actually produced rather than expecting perfect
     * ratios.
     */
    private int getRequiredBars() {
        int theoreticalBars = config.targetQuantity() / config.metalType().getTotalOreCount(1);

        // For iron smelting, account for 50% success rate by expecting fewer bars
        if (config.metalType() == MetalType.IRON) {
            // Allow completion with as few as 60% of theoretical bars due to iron failure
            // rate
            return (int) (theoreticalBars * 0.6);
        }

        return theoreticalBars;
    }

    // Additional expert-level helper methods

    /**
     * Counts total ores in inventory for current metal type
     */

    // Event handler methods called from plugin

    /**
     * Called when coal bag is emptied
     */
    public void onCoalBagEmptied() {
        if (config.enableDebugLogs()) {
            Microbot.log("Coal bag emptied");
        }
    }

    /**
     * Called when coal bag is filled
     */
    public void onCoalBagFilled() {
        if (config.enableDebugLogs()) {
            Microbot.log("Coal bag filled");
        }
    }

    /**
     * Called when smelting is successful
     */
    public void onSmeltingSuccess() {
        progress.incrementBarsSmelted();
        if (config.enableDebugLogs()) {
            Microbot.log("Smelting successful - total bars: " + progress.getBarsSmelted());
        }
    }

    /**
     * Called when mining is successful
     */
    public void onMiningSuccess() {
        progress.incrementOresMined();
        updateProgressStatus();
        if (config.enableDebugLogs()) {
            Microbot.log("Mining successful - total ores: " + progress.getOresMined());
        }
    }

    /**
     * Updates the status with current progress information
     */
    private void updateProgressStatus() {
        int oresMined = progress.getOresMined();
        int targetQuantity = config.targetQuantity();
        String progressText = String.format("Progress: %d/%d ores mined (%.1f%%)",
                oresMined, targetQuantity, (oresMined * 100.0 / targetQuantity));
        updateStatus(progressText);
    }

    /**
     * Called when level requirement error occurs
     */
    public void onLevelRequirementError(String message) {
        handleError("Level requirement not met: " + message);
    }

    /**
     * Called when inventory changes
     */
    public void onInventoryChanged(ItemContainerChanged event) {
        // Update internal state based on inventory changes
        if (config.enableDebugLogs()) {
            Microbot.log("Inventory changed");
        }
    }

    /**
     * Gets the current configuration for external access
     */
    public AIOMetalWorkerConfig getConfig() {
        return config;
    }

    /**
     * Stops walker following WildyRuneMiner shutdown pattern
     */
    private void stopWalking() {
        Microbot.log("Stopping any active pathing...");
        Rs2Walker.setTarget(null); // This clears any current web-walking
    }

    /**
     * Override shutdown to properly clean up script state
     */
    @Override
    public void shutdown() {
        try {
            Microbot.log("AIO Metal Worker Script shutdown initiated...");

            // Set shutdown flag immediately to stop all loops
            isShuttingDown = true;

            // Stop all operations
            Microbot.log("Stopping all script operations...");

            // Stop the walker using the proven WildyRuneMiner pattern
            stopWalking();

            // Reset antiban settings to prevent interference with other scripts
            try {
                Rs2Antiban.resetAntibanSettings();
            } catch (Exception antibanEx) {
                Microbot.log("Error resetting antiban: " + antibanEx.getMessage());
            }

            // Close any open interfaces
            try {
                if (Rs2Bank.isOpen()) {
                    Rs2Bank.closeBank();
                }
            } catch (Exception bankEx) {
                Microbot.log("Error closing bank: " + bankEx.getMessage());
            }

            // Clear any scheduled tasks before calling super.shutdown()
            if (mainScheduledFuture != null && !mainScheduledFuture.isDone()) {
                try {
                    mainScheduledFuture.cancel(true);
                    mainScheduledFuture = null;
                } catch (Exception taskEx) {
                    Microbot.log("Error canceling scheduled task: " + taskEx.getMessage());
                }
            }

            // EXPERT FIX: Reset ALL state variables for proper restart
            currentMiningArea = null;
            failedActionCount = 0;
            // DON'T reset isShuttingDown here as it should remain true during shutdown

            // CRITICAL: Reset progress to allow fresh restart
            if (progress != null) {
                progress.setStartTime(null); // Clear start time to allow reinitialization
                Microbot.log("Progress tracker reset for fresh restart");
            }

            // Update status to show shutdown
            updateStatus("Script shutdown complete");

            // Call parent shutdown to handle remaining cleanup
            super.shutdown();

            Microbot.log("AIO Metal Worker Script shutdown completed");

        } catch (Exception e) {
            Microbot.log("Error during script shutdown: " + e.getMessage());
            // Still call parent shutdown even if there's an error
            try {
                super.shutdown();
            } catch (Exception ex) {
                Microbot.log("Error in parent shutdown: " + ex.getMessage());
            }
        }
    }

    // MYTHICAL-LEVEL HELPER METHODS

    /**
     * Progress tracking class
     */
    @Getter
    public static class ProgressTracker {
        private Instant startTime;
        private int oresMined = 0;
        private int barsSmelted = 0;
        private int itemsSmithed = 0;
        private int oresUsedForSmelting = 0; // Track ores used for smelting attempts
        private int miningXpGained = 0;
        private int smithingXpGained = 0;

        // Getter methods
        public Instant getStartTime() {
            return startTime;
        }

        public int getOresMined() {
            return oresMined;
        }

        public int getBarsSmelted() {
            return barsSmelted;
        }

        public int getItemsSmithed() {
            return itemsSmithed;
        }

        public int getOresUsedForSmelting() {
            return oresUsedForSmelting;
        }

        public int getMiningXpGained() {
            return miningXpGained;
        }

        public int getSmithingXpGained() {
            return smithingXpGained;
        }

        // Setter methods
        public void setStartTime(Instant startTime) {
            this.startTime = startTime;
        }

        public void incrementOresMined() {
            this.oresMined++;
        }

        public void setOresMined(int oresMined) {
            this.oresMined = oresMined;
        }

        public void incrementBarsSmelted() {
            this.barsSmelted++;
        }

        public void setBarsSmelted(int barsSmelted) {
            this.barsSmelted = barsSmelted;
        }

        public void incrementItemsSmithed() {
            this.itemsSmithed++;
        }

        public void setItemsSmithed(int itemsSmithed) {
            this.itemsSmithed = itemsSmithed;
        }

        public void incrementOresUsedForSmelting() {
            this.oresUsedForSmelting++;
        }

        public void addOresUsedForSmelting(int count) {
            this.oresUsedForSmelting += count;
        }

        public void addMiningXp(int xp) {
            this.miningXpGained += xp;
        }

        public void addSmithingXp(int xp) {
            this.smithingXpGained += xp;
        }
    }
}
