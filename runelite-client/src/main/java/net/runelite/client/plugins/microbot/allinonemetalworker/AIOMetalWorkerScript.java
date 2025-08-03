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
    
    // LEVEL TRACKING: Track smithing level to only recheck when level increases
    private int lastKnownSmithingLevel = 0;
    private int currentSelectedItemChildId = -1; // Track currently selected smithing item
    
    // LEFTOVER BARS TRACKING: Flag to force dynamic item selection for leftover bars
    private boolean forceItemReselection = false;

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

            // FORCE DEBUG LOGGING FOR TROUBLESHOOTING
            Microbot.log("=== AIO METAL WORKER DEBUG SESSION START ===");
            Microbot.log("Starting phase configuration: " + config.startingPhase());
            Microbot.log("Metal type: " + config.metalType().getDisplayName());
            Microbot.log("Debug logs enabled: " + config.enableDebugLogs());
            Microbot.log("Smelt bars: " + config.smeltBars());
            Microbot.log("Smith items: " + config.smithItems());
            
            // TEMPORARY DEBUG OVERRIDE: Force enable debug logs for smithing start mode troubleshooting
            if (config.startingPhase() == StartingPhase.SMITHING) {
                Microbot.log("SMITHING START MODE DETECTED - Enabling enhanced debug logging for troubleshooting");
            }

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
                        // For smithing start mode, we need to set progress based on actual inventory/bank state
                        // Not simulated progress that might not match reality
                        progress.setOresMined(config.targetQuantity()); // Assume target reached for logic
                        
                        // SMITHING START MODE FIX: Set bars smelted based on what's actually available
                        // This prevents the disconnect between simulated progress and reality
                        String barName = config.metalType().getBarName();
                        int actualBarsAvailable = 0;
                        
                        // Try to count bars in inventory if accessible (when player is logged in)
                        try {
                            actualBarsAvailable += Rs2Inventory.count(barName);
                        } catch (Exception e) {
                            // Ignore - player might not be logged in yet
                        }
                        
                        // Set progress to reflect reality or use a safe default
                        if (actualBarsAvailable > 0) {
                            progress.setBarsSmelted(actualBarsAvailable);
                            Microbot.log("Starting at SMITHING phase - found " + actualBarsAvailable + " " + barName + " available");
                        } else {
                            // No bars available - reset everything so script will mine first
                            progress.setOresMined(0);
                            progress.setBarsSmelted(0);
                            Microbot.log("Starting at SMITHING phase - no bars available, will start from mining");
                        }
                        
                        progress.setOresUsedForSmelting(0); // Reset for fresh start
                        Microbot.log("Starting at SMITHING phase (adaptive mode) - ores: " + progress.getOresMined() 
                                + "/" + config.targetQuantity() + ", bars: " + progress.getBarsSmelted() + "/" + getRequiredBars());
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
                progress.setOresUsedForSmelting(0); // FIXED: Reset for new cycle
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
        if (playerLocation.distanceTo(AL_KHARID_MINING_AREA) <= MINING_RADIUS) {
            updateStatus("Detected location: Al Kharid mining area");
            return AL_KHARID_MINING_AREA;
        }
        if (playerLocation.distanceTo(LUMBRIDGE_MINING_AREA) <= MINING_RADIUS) {
            updateStatus("Detected location: Lumbridge mining area");
            return LUMBRIDGE_MINING_AREA;
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
                AL_KHARID_MINING_AREA,
                LUMBRIDGE_MINING_AREA,
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
            updateStatus("Not at furnace - walking to Al Kharid furnace");
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

                    // FIXED: Check if there are still ores left to smelt before going to bank
                    if (hasRequiredOres()) {
                        updateStatus("Smelting session complete but still have ores - continuing to smelt");
                        // Stay in smelting phase to smelt remaining ores
                        return;
                    } else {
                        // No more ores in inventory - go to bank for next phase
                        currentPhase = ProcessPhase.BANKING;
                        updateStatus("All ores smelted - proceeding to banking");
                    }
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

        if (config.enableDebugLogs()) {
            Microbot.log("=== SMITHING PHASE DEBUG ===");
            Microbot.log("Current phase: " + currentPhase);
            Microbot.log("Starting phase: " + config.startingPhase());
            Microbot.log("Player location: " + Rs2Player.getWorldLocation());
            Microbot.log("Anvil location: " + ANVIL_LOCATION);
            Microbot.log("Distance to anvil: " + Rs2Player.getWorldLocation().distanceTo(ANVIL_LOCATION));
            String barName = config.metalType().getBarName();
            Microbot.log("Looking for bars: " + barName);
            Microbot.log("Bars in inventory: " + Rs2Inventory.count(barName));
            Microbot.log("Has bars to smith: " + hasBarsToSmith());
            Microbot.log("Has hammer: " + hasHammer());
        }

        // Check if we're at the anvil
        if (Rs2Player.getWorldLocation().distanceTo(ANVIL_LOCATION) > 3) {
            updateStatus("MYTHICAL: Not at anvil - walking to Varrock anvil");
            if (config.enableDebugLogs()) {
                Microbot.log("SMITHING: Too far from anvil, switching to WALKING phase");
            }
            currentPhase = ProcessPhase.WALKING;
            return;
        }

        // MYTHICAL ENHANCEMENT: Check if we have bars to smith in inventory
        if (!hasBarsToSmith()) {
            updateStatus("MYTHICAL: No bars in inventory - going to bank to get bars");
            if (config.enableDebugLogs()) {
                Microbot.log("SMITHING PHASE DEBUG: Missing bars in inventory");
                String barName = config.metalType().getBarName();
                Microbot.log("  Looking for: " + barName + " (count: " + Rs2Inventory.count(barName) + ")");
                Microbot.log("  Switching to BANKING phase to get bars");
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

        // EXPERT FIX: Smith items using proven Varrock Anvil approach
        // EXPERT FIX: Use specific GameObject ID like working plugin (2097 = anvil)
        System.out.println("[DEBUG] Attempting to interact with anvil (GameObject 2097)");
        
        if (Rs2GameObject.interact(2097)) {
            updateStatus("EXPERT FIX: Using anvil (GameObject 2097)...");
            System.out.println("[DEBUG] Successfully clicked anvil, waiting for interface...");

            // EXPERT FIX: Wait for anvil interface using specific widget like working plugin
            boolean interfaceOpened = sleepUntil(() -> {
                boolean hasInterface = Rs2Widget.getWidget(312, 1) != null;
                if (!hasInterface) {
                    System.out.println("[DEBUG] Waiting for anvil interface 312,1...");
                } else {
                    System.out.println("[DEBUG] Anvil interface 312,1 detected!");
                }
                return hasInterface;
            }, 10000);
            
            if (interfaceOpened) {
                updateStatus("EXPERT FIX: Anvil interface opened (Widget 312) - selecting best item");
                sleep(186, 480); // Match working plugin timing

                // LEFTOVER BARS ENHANCEMENT: Check if we need to force item reselection for leftover bars
                // or use normal level-based selection
                boolean selectionSuccess = false;
                
                if (forceItemReselection) {
                    updateStatus("LEFTOVER BARS: Using dynamic item selection for remaining bars");
                    System.out.println("[DEBUG] LEFTOVER BARS: Force reselection flag set - using dynamic selection");
                    
                    // Log current inventory state before dynamic selection
                    String barName = config.metalType().getBarName();
                    int currentBars = Rs2Inventory.count(barName);
                    System.out.println("[DEBUG] LEFTOVER BARS: Current bars in inventory: " + currentBars + " " + barName);
                    
                    // Use dynamic selection to pick the best item for remaining bars
                    selectionSuccess = selectBestSmithingOptionDynamic();
                    
                    // Clear the flag after using it
                    forceItemReselection = false;
                    
                    if (selectionSuccess) {
                        updateStatus("LEFTOVER BARS: Successfully selected optimal item for remaining bars");
                        System.out.println("[DEBUG] LEFTOVER BARS: Dynamic selection successful");
                    } else {
                        updateStatus("LEFTOVER BARS: Failed to select item dynamically - falling back to level-based selection");
                        System.out.println("[DEBUG] LEFTOVER BARS: Dynamic selection failed, trying level-based");
                        selectionSuccess = selectSmithingItemOnLevelUp();
                    }
                } else {
                    // Normal level-based selection
                    selectionSuccess = selectSmithingItemOnLevelUp();
                }

                if (selectionSuccess) {
                    updateStatus("SMITHING: Selected smithing option - smithing remaining bars");
                    System.out.println("[DEBUG] Smithing option selected, waiting for completion");
                    
                    // Wait for full smithing session to complete (all bars in inventory)
                    waitForFullSmithingSession();

                    // INTELLIGENT LEFTOVER BAR HANDLING: After smithing session, check if there are leftover bars
                    // that can still be smithed into different items
                    if (hasBarsToSmith()) {
                        String barName = config.metalType().getBarName();
                        int remainingBars = Rs2Inventory.count(barName);
                        int currentSmithingLevel = Rs2Player.getRealSkillLevel(Skill.SMITHING);
                        String metalType = config.metalType().getDisplayName().toLowerCase();
                        
                        updateStatus("LEFTOVER BARS: Found " + remainingBars + " bars remaining after smithing session");
                        System.out.println("[DEBUG] LEFTOVER BARS: " + remainingBars + " " + barName + " remaining, checking for suitable items");
                        
                        // Check if we can make a different item with the remaining bars
                        int bestItemForRemainingBars = getBestSmithingItemChildIdDynamic(metalType, currentSmithingLevel, remainingBars);
                        
                        if (bestItemForRemainingBars != -1) {
                            updateStatus("LEFTOVER BARS: Found suitable item for " + remainingBars + " remaining bars - continuing smithing");
                            System.out.println("[DEBUG] LEFTOVER BARS: Can make item with child ID " + bestItemForRemainingBars + " using " + remainingBars + " bars");
                            
                            // Set flag to force dynamic item selection on next anvil interaction
                            forceItemReselection = true;
                            
                            // Continue smithing with the remaining bars - the next anvil interaction will select the appropriate item
                            // Don't change phase - stay in smithing to handle the leftover bars
                        } else {
                            updateStatus("LEFTOVER BARS: No suitable items for " + remainingBars + " remaining bars - going to bank");
                            System.out.println("[DEBUG] LEFTOVER BARS: No suitable items for " + remainingBars + " bars, going to bank");
                            
                            // Can't make anything useful with remaining bars - go to bank to deposit them
                            if (hasSmithedItemsToDeposit()) {
                                updateStatus("LEFTOVER BARS: Have finished items to deposit along with unusable bars");
                                currentPhase = ProcessPhase.BANKING;
                            } else {
                                updateStatus("LEFTOVER BARS: Going to bank to deposit unusable bars and check for more");
                                currentPhase = ProcessPhase.BANKING;
                            }
                        }
                    } else {
                        updateStatus("LEVEL-UP: No more bars in inventory - checking bank for continuous smithing");
                        
                        // CONTINUOUS SMITHING: Check if we have smithed items and need to bank them
                        if (hasSmithedItemsToDeposit()) {
                            updateStatus("CONTINUOUS SMITHING: Have finished items to deposit - going to bank");
                            currentPhase = ProcessPhase.BANKING;
                        } else {
                            updateStatus("CONTINUOUS SMITHING: No bars available - going to bank to check for more bars");
                            // Always go to bank to check for more bars for continuous smithing
                            currentPhase = ProcessPhase.BANKING; // Banking phase will check for more bars
                        }
                    }
                } else {
                    updateStatus("SMITHING: Failed to select smithing option - will retry");
                    System.out.println("[DEBUG] Failed to select smithing option");
                }
            } else {
                updateStatus("EXPERT FIX: Anvil interface (312,1) did not appear - will retry");
                System.out.println("[DEBUG] Anvil interface did not appear after 10 seconds");
            }
        } else {
            updateStatus("EXPERT FIX: Failed to interact with anvil (2097) - will retry");
            System.out.println("[DEBUG] Failed to interact with anvil GameObject 2097");
            
            // Check if player is moving before retrying
            if (Rs2Player.isMoving()) {
                System.out.println("[DEBUG] Player is moving, waiting...");
                return;
            }
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

        if (config.enableDebugLogs()) {
            Microbot.log("=== WALKING PHASE DEBUG ===");
            Microbot.log("Current phase: " + currentPhase);
            Microbot.log("Starting phase: " + config.startingPhase());
            Microbot.log("Player location: " + Rs2Player.getWorldLocation());
            Microbot.log("Determined destination: " + destination);
            if (destination != null) {
                Microbot.log("Destination name: " + getLocationName(destination));
                Microbot.log("Distance to destination: " + Rs2Player.getWorldLocation().distanceTo(destination));
            }
        }

        if (destination != null) {
            updateStatus("Walking to " + getLocationName(destination));

            // Check if already close to destination
            if (Rs2Player.getWorldLocation().distanceTo(destination) <= 3) {
                if (config.enableDebugLogs()) {
                    Microbot.log("WALKING: Already close to destination, updating phase");
                }
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
        
        if (config.enableDebugLogs()) {
            Microbot.log("=== BANKING OPERATIONS DEBUG ===");
            Microbot.log("Current phase: " + currentPhase);
            Microbot.log("Starting phase: " + config.startingPhase());
            Microbot.log("Metal type: " + config.metalType().getDisplayName());
            Microbot.log("Bar name: " + config.metalType().getBarName());
            Microbot.log("Smelt bars enabled: " + config.smeltBars());
            Microbot.log("Smith items enabled: " + config.smithItems());
            Microbot.log("Progress - Ores mined: " + progress.getOresMined() + "/" + config.targetQuantity());
            Microbot.log("Progress - Bars smelted: " + progress.getBarsSmelted() + "/" + getRequiredBars());
            Microbot.log("Progress - Items smithed: " + progress.getItemsSmithed());
            
            // Log current inventory state
            String barName = config.metalType().getBarName();
            Microbot.log("Inventory - Bars: " + Rs2Inventory.count(barName));
            for (String oreName : config.metalType().getOreNames()) {
                Microbot.log("Inventory - " + oreName + ": " + Rs2Inventory.count(oreName));
            }
            
            // Check what's in bank
            if (Rs2Bank.isOpen()) {
                Microbot.log("Bank - " + barName + ": " + Rs2Bank.count(barName));
                for (String oreName : config.metalType().getOreNames()) {
                    Microbot.log("Bank - " + oreName + ": " + Rs2Bank.count(oreName));
                }
            }
        }

        try {
            // CONTINUOUS SMITHING ENHANCEMENT: Handle smithing workflow specifically
            boolean hasSmithedItems = hasSmithedItemsToDeposit();
            String barName = config.metalType().getBarName();
            
            // First, deposit all items except tools
            updateStatus("Depositing items except tools...");
            depositItemsExceptTools();
            sleep(600, 1000);

            // CONTINUOUS SMITHING: If we just deposited smithed items, check for more bars immediately
            if (hasSmithedItems && config.smithItems()) {
                updateStatus("CONTINUOUS SMITHING: Deposited finished items - checking for more bars to smith");
                
                int barsInBank = Rs2Bank.count(barName);
                if (barsInBank > 0) {
                    updateStatus("CONTINUOUS SMITHING: Found " + barsInBank + " " + barName + " in bank - continuing smithing");
                    
                    // Withdraw bars for continuous smithing
                    withdrawBarsForSmithing();
                    Rs2Bank.closeBank();
                    sleep(500, 800);
                    
                    currentPhase = ProcessPhase.WALKING; // Walk back to anvil
                    return; // Exit banking - continue smithing cycle
                } else {
                    updateStatus("CONTINUOUS SMITHING: No more bars available - smithing cycle complete");
                    // Continue with normal logic to determine next phase
                }
            }

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
                    progress.setOresUsedForSmelting(0); // FIXED: Reset for new cycle
                    
                    currentPhase = ProcessPhase.WALKING; // Walk back to mining
                }
                return; // Exit banking immediately
            }

            // Continue with normal banking operations if more ores needed
            updateStatus("Still need more ores - continuing normal banking...");

            // Withdraw necessary tools based on next planned activity
            updateStatus("Withdrawing required tools...");
            withdrawRequiredTools();

            // SMITHING STARTING PHASE FIX: Handle smithing-only mode when starting phase is SMITHING
            if (isSmithingOnlyMode() && config.smithItems() && needsBarsForSmithing()) {
                updateStatus("SMITHING START MODE: Starting phase is smithing - only working with available bars");
                
                if (config.enableDebugLogs()) {
                    Microbot.log("SMITHING START MODE FIX: Starting phase smithing detected");
                    Microbot.log("  Current phase: " + currentPhase);
                    Microbot.log("  Bar name: " + barName);
                    Microbot.log("  Bars in bank: " + Rs2Bank.count(barName));
                }
                
                int barsInBank = Rs2Bank.count(barName);
                
                if (barsInBank > 0) {
                    updateStatus("SMITHING START MODE: Found " + barsInBank + " " + barName + " in bank - withdrawing for smithing");
                    if (config.enableDebugLogs()) {
                        Microbot.log("SMITHING START MODE: Withdrawing bars for smithing-only cycle");
                    }
                    withdrawBarsForSmithing();
                    
                    Rs2Bank.closeBank();
                    sleep(500, 800);
                    
                    // Stay in smithing phase - go back to anvil
                    currentPhase = ProcessPhase.WALKING;
                    return;
                } else {
                    updateStatus("SMITHING START MODE: No bars available - smithing cycle complete, switching to mining");
                    if (config.enableDebugLogs()) {
                        Microbot.log("SMITHING START MODE: No bars in bank, smithing complete - defaulting to mining phase");
                    }
                    
                    Rs2Bank.closeBank();
                    sleep(500, 800);
                    
                    // Switch to mining phase since no bars available for smithing-only mode
                    currentPhase = ProcessPhase.WALKING; // Will go to mining area
                    return;
                }
            }
            
            // SMITHING PRIORITY FIX: Handle mixed-workflow smithing context (not starting phase smithing)
            // If we came from smithing phase and need bars, prioritize getting bars
            else if (!isSmithingOnlyMode() && currentPhase == ProcessPhase.SMITHING && config.smithItems() && needsBarsForSmithing()) {
                updateStatus("SMITHING CONTEXT: Mixed workflow smithing phase - checking for bars and ores");
                
                if (config.enableDebugLogs()) {
                    Microbot.log("SMITHING CONTEXT FIX: Mixed workflow detected smithing phase with missing bars");
                    Microbot.log("  Current phase: " + currentPhase);
                    Microbot.log("  Bar name: " + barName);
                    Microbot.log("  Bars in bank: " + Rs2Bank.count(barName));
                }
                
                // Check if we have bars in bank first
                int barsInBank = Rs2Bank.count(barName);
                
                if (barsInBank > 0) {
                    updateStatus("SMITHING CONTEXT: Found " + barsInBank + " " + barName + " in bank - withdrawing for smithing");
                    if (config.enableDebugLogs()) {
                        Microbot.log("SMITHING CONTEXT FIX: Withdrawing bars for smithing - count: " + barsInBank);
                    }
                    withdrawBarsForSmithing();
                    
                    Rs2Bank.closeBank();
                    sleep(500, 800);
                    
                    // Stay in smithing phase - go back to anvil
                    currentPhase = ProcessPhase.WALKING;
                    return;
                } else {
                    updateStatus("SMITHING CONTEXT: No bars in bank - switching to smelting to make bars");
                    if (config.enableDebugLogs()) {
                        Microbot.log("SMITHING CONTEXT FIX: No bars in bank, checking for ores to smelt");
                    }
                    
                    // Check if we have ores to smelt
                    String[] oreNames = config.metalType().getOreNames();
                    boolean hasOresInBank = false;
                    for (String oreName : oreNames) {
                        int oreCount = Rs2Bank.count(oreName);
                        if (config.enableDebugLogs()) {
                            Microbot.log("  " + oreName + " in bank: " + oreCount);
                        }
                        if (oreCount > 0) {
                            hasOresInBank = true;
                            break;
                        }
                    }
                    
                    if (hasOresInBank) {
                        updateStatus("SMITHING CONTEXT: Found ores in bank - withdrawing for smelting");
                        if (config.enableDebugLogs()) {
                            Microbot.log("SMITHING CONTEXT FIX: Withdrawing ores for smelting instead");
                        }
                        withdrawOresForSmelting();
                        Rs2Bank.closeBank();
                        sleep(500, 800);
                        
                        // Switch to smelting phase to make bars
                        currentPhase = ProcessPhase.WALKING; // Will determine smelting in walking phase
                        return;
                    } else {
                        updateStatus("SMITHING CONTEXT: No ores or bars available - need to mine first");
                        if (config.enableDebugLogs()) {
                            Microbot.log("SMITHING CONTEXT FIX: No ores or bars available, falling through to normal logic");
                        }
                        // Fall through to normal logic
                    }
                }
            }
            
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

        // SMITHING START MODE FIX: Special handling for smithing starting phase
        if (config.startingPhase() == StartingPhase.SMITHING && config.smithItems()) {
            String barName = config.metalType().getBarName();
            boolean hasBarsInBank = Rs2Bank.hasItem(barName);
            boolean hasBarsInInventory = Rs2Inventory.hasItem(barName);
            
            // ENHANCED DEBUG for smithing start mode
            Microbot.log("=== SMITHING START PHASE LOGIC ===");
            Microbot.log("Bars in bank: " + hasBarsInBank + " (count: " + Rs2Bank.count(barName) + ")");
            Microbot.log("Bars in inventory: " + hasBarsInInventory + " (count: " + Rs2Inventory.count(barName) + ")");
            Microbot.log("Needs bars for smithing: " + needsBarsForSmithing());
            
            // If we have bars anywhere, continue with smithing
            if (hasBarsInBank || hasBarsInInventory) {
                Microbot.log("Starting phase SMITHING override - directing to smithing (bars available)");
                return ProcessPhase.SMITHING;
            } else {
                // No bars available - go to banking first to deposit items and get pickaxe for mining
                Microbot.log("Starting phase SMITHING override - no bars available, going to banking then mining");
                return ProcessPhase.BANKING;
            }
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
        
        if (config.enableDebugLogs()) {
            Microbot.log("=== WITHDRAWING BARS FOR SMITHING ===");
            Microbot.log("Bar name: " + barName);
            Microbot.log("Bars in bank: " + Rs2Bank.count(barName));
            Microbot.log("Bars in inventory: " + Rs2Inventory.count(barName));
            Microbot.log("Has hammer: " + hasHammer());
            Microbot.log("Available inventory slots: " + (28 - Rs2Inventory.count()));
        }

        // MYTHICAL ENHANCEMENT: First ensure we have a hammer
        if (!hasHammer()) {
            updateStatus("Withdrawing hammer for smithing...");
            if (config.enableDebugLogs()) {
                Microbot.log("HAMMER CHECK: No hammer in inventory, checking bank...");
                Microbot.log("Bank has hammer: " + Rs2Bank.hasItem("Hammer"));
            }
            
            if (Rs2Bank.hasItem("Hammer")) {
                Rs2Bank.withdrawOne("Hammer");
                sleep(300, 600);
                if (config.enableDebugLogs()) {
                    Microbot.log("HAMMER: Withdrew hammer, now has hammer: " + hasHammer());
                }
            } else {
                updateStatus("WARNING: No hammer found in bank!");
                if (config.enableDebugLogs()) {
                    Microbot.log("ERROR: No hammer in bank! Cannot proceed with smithing");
                }
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

        if (config.enableDebugLogs()) {
            Microbot.log("BAR WITHDRAWAL CALCULATION:");
            Microbot.log("  Available slots: " + availableSlots);
            Microbot.log("  Hammer slots needed: " + hammerSlots);
            Microbot.log("  Max bars to withdraw: " + maxBarsToWithdraw);
            Microbot.log("  Bars in bank: " + barsInBank);
            Microbot.log("  Bars to withdraw: " + barsToWithdraw);
        }

        if (barsToWithdraw > 0) {
            updateStatus("Withdrawing " + barsToWithdraw + " " + barName + " for smithing (max capacity: "
                    + maxBarsToWithdraw + ")");

            if (config.enableDebugLogs()) {
                Microbot.log("ATTEMPTING BAR WITHDRAWAL: " + barsToWithdraw + " " + barName);
            }

            // MYTHICAL ENHANCEMENT: Use X withdrawal for precise control
            if (barsToWithdraw == barsInBank || barsToWithdraw >= 10) {
                if (config.enableDebugLogs()) {
                    Microbot.log("Using withdrawAll for " + barName);
                }
                Rs2Bank.withdrawAll(barName);
            } else {
                if (config.enableDebugLogs()) {
                    Microbot.log("Using withdrawX for " + barsToWithdraw + " " + barName);
                }
                Rs2Bank.withdrawX(barName, barsToWithdraw);
            }

            sleep(600, 1000); // Wait for withdrawal to complete

            if (config.enableDebugLogs()) {
                Microbot.log("Bars withdrawal completed - Inventory count: " + Rs2Inventory.count(barName));
                Microbot.log("BAR WITHDRAWAL SUCCESS: Now have " + Rs2Inventory.count(barName) + " " + barName + " in inventory");
            }
        } else {
            updateStatus("No bars to withdraw or inventory full");
            if (config.enableDebugLogs()) {
                Microbot.log("BAR WITHDRAWAL FAILED: barsToWithdraw = " + barsToWithdraw);
                Microbot.log("  Reasons: barsInBank=" + barsInBank + ", maxBarsToWithdraw=" + maxBarsToWithdraw);
                Microbot.log("  Inventory full? " + Rs2Inventory.isFull());
            }
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

        // SMITHING START MODE FIX: Handle starting at smithing phase specially
        if (config.startingPhase() == StartingPhase.SMITHING && config.smithItems()) {
            String barName = config.metalType().getBarName();
            boolean hasBarsInBank = Rs2Bank.hasItem(barName);
            boolean hasBarsInInventory = Rs2Inventory.hasItem(barName);
            
            // ENHANCED DEBUG for smithing start mode troubleshooting
            Microbot.log("=== SMITHING START MODE ANALYSIS ===");
            Microbot.log("Bars in bank: " + hasBarsInBank + " (count: " + Rs2Bank.count(barName) + ")");
            Microbot.log("Bars in inventory: " + hasBarsInInventory + " (count: " + Rs2Inventory.count(barName) + ")");
            
            if (hasBarsInInventory) {
                updateStatus("Smithing start mode - have bars in inventory, going to anvil");
                Microbot.log("Smithing start mode - have bars in inventory, going to anvil");
                currentPhase = ProcessPhase.WALKING; // Walk to anvil for smithing
            } else if (hasBarsInBank) {
                updateStatus("Smithing start mode - have bars in bank, withdrawing bars");
                Microbot.log("Smithing start mode - have bars in bank, withdrawing bars");
                currentPhase = ProcessPhase.BANKING; // Stay at bank to withdraw bars
            } else {
                updateStatus("Smithing start mode - no bars available, banking to get pickaxe for mining");
                Microbot.log("Smithing start mode - no bars available, banking to get pickaxe for mining");
                currentPhase = ProcessPhase.BANKING; // Go to banking to deposit items and get pickaxe
            }
            updateStatus("Smithing start mode - Next phase: " + currentPhase);
            Microbot.log("Smithing start mode - Next phase: " + currentPhase);
            return;
        }

        // NORMAL LOGIC: Standard priority system with MYTHICAL-LEVEL ENHANCEMENT
        // Priority 1: Check if we haven't reached the target ore quantity yet
        if (totalOresMined < targetQuantity) {
            updateStatus(
                    "Target not reached - continuing mining (Ores: " + totalOresMined + "/" + targetQuantity + ")");
            currentPhase = ProcessPhase.WALKING; // Walk back to mining area
        }
        // Priority 2: CONTINUOUS SMITHING ENHANCEMENT - Check for bars to smith 
        // This handles the continuous smithing loop: deposit items → withdraw more bars → smith again
        else if (config.smithItems() && needsBarsForSmithing()) {
            updateStatus("CONTINUOUS SMITHING: Found bars in bank - continuing smithing cycle");

            // Check if we have bars in inventory after withdrawal
            if (hasBarsToSmith()) {
                updateStatus("CONTINUOUS SMITHING: Have bars in inventory - going to anvil for smithing");
                currentPhase = ProcessPhase.WALKING; // Walk to anvil
            } else {
                updateStatus("CONTINUOUS SMITHING: No bars in inventory - need to withdraw bars for smithing");
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
            progress.setOresUsedForSmelting(0); // FIXED: Reset for new cycle in continuous loop
            
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

    /**
     * SMITHING START MODE FIX: Determines if we're in smithing-only mode
     * Returns true when starting phase is SMITHING, indicating we should only work with existing bars
     */
    private boolean isSmithingOnlyMode() {
        boolean isSmithingOnly = config.startingPhase() == StartingPhase.SMITHING;
        if (config.enableDebugLogs()) {
            Microbot.log("SMITHING-ONLY MODE CHECK: " + isSmithingOnly + " (starting phase: " + config.startingPhase() + ")");
        }
        return isSmithingOnly;
    }

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

        // SMITHING START MODE FIX: When starting at smithing phase with no bars,
        // don't fall back to smelting unless explicitly configured to do so
        if (config.startingPhase() == StartingPhase.SMITHING) {
            String barName = config.metalType().getBarName();
            boolean hasBarsInBank = Rs2Bank.hasItem(barName);
            boolean hasBarsInInventory = Rs2Inventory.hasItem(barName);
            
            // If starting at smithing and no bars exist, don't try to smelt - go to mining instead
            if (!hasBarsInBank && !hasBarsInInventory) {
                // ENHANCED DEBUG for smithing start mode (always log)
                Microbot.log("=== SMITHING START MODE - Smelting Check ===");
                Microbot.log("Starting at smithing phase with no bars available");
                Microbot.log("Not switching to smelting - will go to mining instead");
                return false;
            }
        }

        // FIXED: Check if we've already processed the target quantity of ores for smelting
        int targetQuantity = config.targetQuantity();
        int oresUsedForSmelting = progress.getOresUsedForSmelting();
        
        // If we've already processed all target ores, no more smelting needed
        if (oresUsedForSmelting >= targetQuantity) {
            if (config.enableDebugLogs()) {
                Microbot.log("=== Smelting Target Complete ===");
                Microbot.log("Target ores: " + targetQuantity);
                Microbot.log("Ores used for smelting: " + oresUsedForSmelting);
                Microbot.log("Smelting complete - moving to smithing phase");
            }
            return false;
        }
        
        // Check if we have ores available (in bank or inventory) AND haven't reached target
        boolean hasOresInBank = Rs2Bank.hasItem(config.metalType().getOreNames()[0]);
        boolean hasOresInInventory = config.metalType().hasRequiredOres();
        int remainingOresNeeded = targetQuantity - oresUsedForSmelting;

        if (config.enableDebugLogs()) {
            Microbot.log("=== Smelting Needs Analysis ===");
            Microbot.log("Smelt bars enabled: " + config.smeltBars());
            Microbot.log("Target quantity: " + targetQuantity);
            Microbot.log("Ores used for smelting: " + oresUsedForSmelting);
            Microbot.log("Remaining ores needed: " + remainingOresNeeded);
            Microbot.log("Has ores in bank: " + hasOresInBank);
            Microbot.log("Has ores in inventory: " + hasOresInInventory);
            Microbot.log("Needs more ores for smelting: " + ((hasOresInBank || hasOresInInventory) && remainingOresNeeded > 0));
        }

        return (hasOresInBank || hasOresInInventory) && remainingOresNeeded > 0;
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

        // SMITHING START MODE FIX: Special handling for smithing starting phase
        if (config.startingPhase() == StartingPhase.SMITHING) {
            // For smithing start mode, only return true if bars actually exist somewhere
            boolean hasBarsInBank = Rs2Bank.hasItem(barName);
            boolean hasBarsInInventory = Rs2Inventory.hasItem(barName);
            
            // ENHANCED DEBUG for smithing start mode (always log)
            Microbot.log("=== SMITHING START MODE - Bars Analysis ===");
            Microbot.log("Smithing start mode enabled: true");
            Microbot.log("Bar type: " + barName);
            Microbot.log("Bars in bank: " + hasBarsInBank + " (count: " + Rs2Bank.count(barName) + ")");
            Microbot.log("Bars in inventory: " + hasBarsInInventory + " (count: " + Rs2Inventory.count(barName) + ")");
            Microbot.log("Needs bars for smithing: " + (hasBarsInBank || hasBarsInInventory));
            
            return hasBarsInBank || hasBarsInInventory;
        }

        // MYTHICAL ENHANCEMENT: Normal mode - Check if we have bars available in bank
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
     * Checks if there are smithed items in inventory that need to be deposited
     * Excludes bars, ores, hammers, and pickaxes
     */
    private boolean hasSmithedItemsToDeposit() {
        String barName = config.metalType().getBarName();
        
        // Get all item names in inventory
        Map<String, Integer> inventory = getCurrentInventorySnapshot();
        
        for (String itemName : inventory.keySet()) {
            String nameLower = itemName.toLowerCase();
            
            // Exclude tools and raw materials
            boolean isBar = itemName.equals(barName);
            boolean isOre = nameLower.contains("ore");
            boolean isHammer = nameLower.contains("hammer");
            boolean isPickaxe = nameLower.contains("pickaxe");
            boolean isCoal = nameLower.contains("coal");
            
            // If it's not a tool or raw material, it's likely a smithed item
            boolean isSmithedItem = !isBar && !isOre && !isHammer && !isPickaxe && !isCoal;
            
            if (isSmithedItem) {
                if (config.enableDebugLogs()) {
                    System.out.println("[DEBUG] Found smithed item to deposit: " + itemName);
                }
                return true;
            }
        }
        
        return false;
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
     * IMPROVED LOGIC: Properly waits until all ores are consumed or smelting stops
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

        int noAnimationTime = 0;
        int maxNoAnimationTime = 15000; // 15 seconds max without animation before stopping

        // IMPROVED: Keep smelting while we have ores AND are still making progress
        while (true) {
            // Safety timeout (10 minutes max)
            if (System.currentTimeMillis() - smeltingStartTime > 600000) {
                handleError("Smelting timeout exceeded (10 minutes)");
                break;
            }

            // Check current ore count
            int currentOreCount = 0;
            for (String oreName : oreNames) {
                currentOreCount += Rs2Inventory.count(oreName);
            }

            // If no ores left, smelting is complete
            if (currentOreCount == 0) {
                updateStatus("All ores smelted - smelting complete!");
                break;
            }

            // Check if player is animating (smelting)
            if (Rs2Player.isAnimating()) {
                noAnimationTime = 0; // Reset no-animation timer
                updateStatus("Smelting... (" + currentOreCount + " ores remaining)");
            } else {
                noAnimationTime += 1000;
                
                // If no animation for too long, check if smelting interface is still open
                if (noAnimationTime > maxNoAnimationTime) {
                    if (!Rs2Widget.hasWidget("What would you like to smelt?")) {
                        updateStatus("Smelting interface closed and no animation - smelting may have stopped");
                        break;
                    }
                    
                    // Try to restart smelting if interface is still open but not animating
                    updateStatus("No smelting animation for " + (noAnimationTime/1000) + "s - attempting to restart");
                    Rs2Keyboard.keyPress(' '); // Press space to continue smelting
                    noAnimationTime = 0; // Reset timer after attempting restart
                }
            }

            // Check if inventory is full (can't smelt more)
            if (Rs2Inventory.isFull() && currentOreCount > 0) {
                updateStatus("Inventory full but still have ores - may need to continue smelting");
                // Don't break here - let it continue if still animating
            }

            sleep(1000, 1500); // Check every 1-1.5 seconds
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

        updateStatus("Smelting session complete! Remaining ores: " + finalOreCount + 
                    ", Total ores used: " + progress.getOresUsedForSmelting() + "/" + config.targetQuantity());
    }

    /**
     * LEVEL-UP OPTIMIZATION: Selects smithing option only when smithing level has increased
     * This method checks if the player's smithing level has gone up since the last check,
     * and only then selects a better smithing item. Otherwise, uses the previously selected item.
     * 
     * @return true if option was successfully selected
     */
    private boolean selectSmithingItemOnLevelUp() {
        try {
            updateStatus("LEVEL-UP: Waiting for anvil interface (Widget 312) with level-up checking...");
            System.out.println("[DEBUG] Level-up smithing - checking for anvil interface widget 312,1");
            
            // Use specific widget ID like working Varrock plugin (312 = anvil interface)
            boolean interfaceFound = sleepUntil(() -> {
                boolean hasWidget = Rs2Widget.getWidget(312, 1) != null;
                if (!hasWidget) {
                    System.out.println("[DEBUG] Level-up - waiting for anvil interface...");
                } else {
                    System.out.println("[DEBUG] Level-up - anvil interface detected!");
                }
                return hasWidget;
            }, 10000);
            
            if (!interfaceFound) {
                updateStatus("LEVEL-UP: Anvil interface (312,1) not detected - retrying anvil interaction");
                System.out.println("[DEBUG] Level-up - failed to find anvil interface widget 312,1 after 10 seconds");
                return false;
            }

            sleep(186, 480); // Match working plugin timing

            // LEVEL-UP CHECK: Get current smithing level and compare with last known level
            int currentSmithingLevel = Rs2Player.getRealSkillLevel(Skill.SMITHING);
            int availableBars = Rs2Inventory.count(config.metalType().getBarName());
            String metalType = config.metalType().getDisplayName().toLowerCase();

            updateStatus("LEVEL-UP: Current Level: " + currentSmithingLevel + ", Last Known: " + lastKnownSmithingLevel + ", Bars: " + availableBars);
            System.out.println("[DEBUG] LEVEL-UP CHECK - Current: " + currentSmithingLevel + ", Last: " + lastKnownSmithingLevel + ", Bars: " + availableBars);

            // LEVEL-UP: Set quantity to "All" first
            int currentVarbit = Microbot.getVarbitPlayerValue(2224);
            System.out.println("[DEBUG] Level-up - current varbit 2224 value: " + currentVarbit + ", available bars: " + availableBars);
            
            if (currentVarbit < availableBars) {
                updateStatus("LEVEL-UP: Setting quantity to 'All' (Widget 312,7)");
                System.out.println("[DEBUG] Level-up - clicking 'All' button at widget 312,7");
                Rs2Widget.clickWidget(312, 7);
                sleep(186, 480);
            }

            // LEVEL-UP LOGIC: Only select new item if level has increased OR if no item selected yet
            int itemChildId;
            if (currentSmithingLevel > lastKnownSmithingLevel || currentSelectedItemChildId == -1) {
                // Level has increased or first time - select best item for new level
                updateStatus("LEVEL-UP: Level increased from " + lastKnownSmithingLevel + " to " + currentSmithingLevel + " - selecting better item!");
                System.out.println("[DEBUG] LEVEL-UP - Level increased! Selecting new best item for level " + currentSmithingLevel);
                
                itemChildId = getBestSmithingItemChildIdDynamic(metalType, currentSmithingLevel, availableBars);
                
                if (itemChildId != -1) {
                    // Update tracking variables
                    lastKnownSmithingLevel = currentSmithingLevel;
                    currentSelectedItemChildId = itemChildId;
                    updateStatus("LEVEL-UP: Selected new item for level " + currentSmithingLevel + " (Widget 312," + itemChildId + ")");
                    System.out.println("[DEBUG] LEVEL-UP - New item selected: widget 312," + itemChildId + " for level " + currentSmithingLevel);
                } else {
                    updateStatus("LEVEL-UP: No suitable items found for level " + currentSmithingLevel);
                    System.out.println("[DEBUG] LEVEL-UP - No suitable items found for level " + currentSmithingLevel);
                    return false;
                }
            } else {
                // Level hasn't increased - use previously selected item
                itemChildId = currentSelectedItemChildId;
                updateStatus("LEVEL-UP: Level unchanged (" + currentSmithingLevel + ") - using previously selected item (Widget 312," + itemChildId + ")");
                System.out.println("[DEBUG] LEVEL-UP - Level unchanged, using previous item: widget 312," + itemChildId);
                
                if (itemChildId == -1) {
                    // Fallback: select best item if no previous selection
                    updateStatus("LEVEL-UP: No previous selection - selecting best item for level " + currentSmithingLevel);
                    itemChildId = getBestSmithingItemChildIdDynamic(metalType, currentSmithingLevel, availableBars);
                    if (itemChildId != -1) {
                        lastKnownSmithingLevel = currentSmithingLevel;
                        currentSelectedItemChildId = itemChildId;
                    } else {
                        return false;
                    }
                }
            }

            // Click the selected smithing item
            updateStatus("LEVEL-UP: Clicking smithing item at child ID: " + itemChildId);
            System.out.println("[DEBUG] Level-up - clicking widget 312," + itemChildId);

            boolean itemSelected = Rs2Widget.clickWidget(312, itemChildId);
            
            if (itemSelected) {
                updateStatus("LEVEL-UP: Successfully selected smithing item (Widget 312," + itemChildId + ") for level " + currentSmithingLevel);
                System.out.println("[DEBUG] Level-up - successfully clicked widget 312," + itemChildId + " for level " + currentSmithingLevel);
                sleep(186, 480); // Match working plugin timing
                return true;
            } else {
                updateStatus("LEVEL-UP: Failed to click smithing item at child ID: " + itemChildId);
                System.out.println("[DEBUG] Level-up - failed to click widget 312," + itemChildId);
                return false;
            }

        } catch (Exception e) {
            handleError("LEVEL-UP: Failed to select smithing option", e);
            System.out.println("[DEBUG] Level-up - exception in selectSmithingItemOnLevelUp: " + e.getMessage());
            return false;
        }
    }

    /**
     * LEVEL-UP OPTIMIZATION: Waits for full smithing session to complete
     * Waits until all bars in inventory are smithed, rather than stopping after each action
     */
    private void waitForFullSmithingSession() {
        updateStatus("LEVEL-UP: Waiting for full smithing session to complete...");
        long sessionStartTime = System.currentTimeMillis();
        int initialBarCount = Rs2Inventory.count(config.metalType().getBarName());
        
        // Track initial inventory for item counting
        Map<String, Integer> initialInventory = getCurrentInventorySnapshot();
        boolean smithingInProgress = true;

        System.out.println("[DEBUG] LEVEL-UP - Starting full smithing session with " + initialBarCount + " bars");

        while (smithingInProgress && (System.currentTimeMillis() - sessionStartTime < 300000)) { // 5 minutes max per session
            // Check if still smithing (player animating or bars being consumed)
            int currentBarCount = Rs2Inventory.count(config.metalType().getBarName());
            
            if (currentBarCount == 0) {
                updateStatus("LEVEL-UP: All bars smithed - session complete!");
                System.out.println("[DEBUG] LEVEL-UP - All bars consumed, session complete");
                smithingInProgress = false;
                break;
            }
            
            // Wait for XP drops to track progress
            if (Rs2Player.waitForXpDrop(Skill.SMITHING, 3000)) {
                updateStatus("LEVEL-UP: Smithing XP drop detected - progress continues...");
                System.out.println("[DEBUG] LEVEL-UP - XP drop detected, bars remaining: " + currentBarCount);
                
                // Count items created this session
                Map<String, Integer> currentInventory = getCurrentInventorySnapshot();
                int newItemsCreated = countNewItemsCreated(initialInventory, currentInventory);
                
                if (newItemsCreated > 0) {
                    for (int i = 0; i < newItemsCreated; i++) {
                        progress.incrementItemsSmithed();
                    }
                    updateStatus("LEVEL-UP: Created " + newItemsCreated + " items! Total: " + progress.getItemsSmithed() + ", Bars remaining: " + currentBarCount);
                    
                    // Update initial inventory for next comparison
                    initialInventory = currentInventory;
                }
            }
            
            // Check if smithing animation stopped but still have bars
            if (!Rs2Player.isAnimating() && !Rs2Player.isMoving() && currentBarCount > 0) {
                // Check if bars decreased since last check
                if (currentBarCount < initialBarCount) {
                    updateStatus("LEVEL-UP: Progress made - bars: " + initialBarCount + " -> " + currentBarCount);
                    initialBarCount = currentBarCount;
                } else {
                    // No progress for a while - session might be stuck
                    updateStatus("LEVEL-UP: No progress detected - checking if session is complete");
                    sleep(1000, 1500);
                    
                    // Double-check bar count after waiting
                    int recheckBarCount = Rs2Inventory.count(config.metalType().getBarName());
                    if (recheckBarCount == 0) {
                        smithingInProgress = false;
                        break;
                    } else if (recheckBarCount == currentBarCount) {
                        // No change - might be done smithing
                        updateStatus("LEVEL-UP: No bar consumption detected - session may be complete");
                        smithingInProgress = false;
                        break;
                    }
                }
            }

            // Brief sleep between checks
            sleep(500, 800);
        }

        if (smithingInProgress) {
            updateStatus("LEVEL-UP: Session timeout - proceeding anyway");
            System.out.println("[DEBUG] LEVEL-UP - Session timeout after 5 minutes");
        }

        int finalBarCount = Rs2Inventory.count(config.metalType().getBarName());
        updateStatus("LEVEL-UP: Smithing session complete! Bars: " + initialBarCount + " -> " + finalBarCount);
        System.out.println("[DEBUG] LEVEL-UP - Full smithing session completed, bars remaining: " + finalBarCount);
    }

    /**
     * DYNAMIC LEVEL PROGRESSION: Selects smithing option with real-time level checking
     * This method checks the current smithing level EVERY TIME it's called, allowing
     * for progressive item upgrades as the player levels up during smithing sessions
     * 
     * @return true if option was successfully selected
     */
    private boolean selectBestSmithingOptionDynamic() {
        try {
            updateStatus("DYNAMIC: Waiting for anvil interface (Widget 312) with real-time level check...");
            System.out.println("[DEBUG] Dynamic smithing - checking for anvil interface widget 312,1");
            
            // Use specific widget ID like working Varrock plugin (312 = anvil interface)
            boolean interfaceFound = sleepUntil(() -> {
                boolean hasWidget = Rs2Widget.getWidget(312, 1) != null;
                if (!hasWidget) {
                    System.out.println("[DEBUG] Dynamic - waiting for anvil interface...");
                } else {
                    System.out.println("[DEBUG] Dynamic - anvil interface detected!");
                }
                return hasWidget;
            }, 10000);
            
            if (!interfaceFound) {
                updateStatus("DYNAMIC: Anvil interface (312,1) not detected - retrying anvil interaction");
                System.out.println("[DEBUG] Dynamic - failed to find anvil interface widget 312,1 after 10 seconds");
                return false;
            }

            sleep(186, 480); // Match working plugin timing

            // DYNAMIC CHECK: Get current smithing level and available bars FRESH each time
            int currentSmithingLevel = Rs2Player.getRealSkillLevel(Skill.SMITHING);
            int availableBars = Rs2Inventory.count(config.metalType().getBarName());
            String metalType = config.metalType().getDisplayName().toLowerCase();

            updateStatus("DYNAMIC: Anvil interface detected! Current Level: " + currentSmithingLevel + ", Bars: " + availableBars + ", Metal: " + metalType);
            System.out.println("[DEBUG] DYNAMIC CHECK - Smithing level: " + currentSmithingLevel + ", Available bars: " + availableBars + ", Metal: " + metalType);

            // DYNAMIC: Select "All" quantity first (like working plugin does)
            int currentVarbit = Microbot.getVarbitPlayerValue(2224);
            System.out.println("[DEBUG] Dynamic - current varbit 2224 value: " + currentVarbit + ", available bars: " + availableBars);
            
            if (currentVarbit < availableBars) {
                updateStatus("DYNAMIC: Setting quantity to 'All' (Widget 312,7)");
                System.out.println("[DEBUG] Dynamic - clicking 'All' button at widget 312,7");
                Rs2Widget.clickWidget(312, 7);
                sleep(186, 480);
            }

            // DYNAMIC: Get the best smithing item child ID based on CURRENT level and bars
            int itemChildId = getBestSmithingItemChildIdDynamic(metalType, currentSmithingLevel, availableBars);
            
            if (itemChildId == -1) {
                updateStatus("DYNAMIC: No suitable smithing items found for current level " + currentSmithingLevel);
                System.out.println("[DEBUG] Dynamic - no suitable items found for level " + currentSmithingLevel + " with " + availableBars + " bars");
                return false;
            }

            updateStatus("DYNAMIC: Clicking best smithing item for level " + currentSmithingLevel + " at child ID: " + itemChildId);
            System.out.println("[DEBUG] Dynamic - clicking widget 312," + itemChildId + " for level " + currentSmithingLevel);

            // DYNAMIC: Click the specific widget child ID
            boolean itemSelected = Rs2Widget.clickWidget(312, itemChildId);
            
            if (itemSelected) {
                updateStatus("DYNAMIC: Successfully selected smithing item for level " + currentSmithingLevel + " (Widget 312," + itemChildId + ")");
                System.out.println("[DEBUG] Dynamic - successfully clicked widget 312," + itemChildId + " for level " + currentSmithingLevel);
                sleep(186, 480); // Match working plugin timing
                return true;
            } else {
                updateStatus("DYNAMIC: Failed to click smithing item at child ID: " + itemChildId);
                System.out.println("[DEBUG] Dynamic - failed to click widget 312," + itemChildId);
                return false;
            }

        } catch (Exception e) {
            handleError("DYNAMIC: Failed to select smithing option", e);
            System.out.println("[DEBUG] Dynamic - exception in selectBestSmithingOptionDynamic: " + e.getMessage());
            return false;
        }
    }

    /**
     * EXPERT FIX: Selects smithing option using proven Varrock Anvil approach
     * Uses specific widget IDs and proper anvil interface detection like working plugin
     * 
     * @return true if option was successfully selected
     */
    private boolean selectBestSmithingOption() {
        try {
            updateStatus("Waiting for anvil interface (Widget 312)...");
            System.out.println("[DEBUG] Checking for anvil interface widget 312,1");
            
            // EXPERT FIX: Use specific widget ID like working Varrock plugin (312 = anvil interface)
            boolean interfaceFound = sleepUntil(() -> {
                boolean hasWidget = Rs2Widget.getWidget(312, 1) != null;
                if (!hasWidget) {
                    System.out.println("[DEBUG] Widget 312,1 not found yet...");
                } else {
                    System.out.println("[DEBUG] Widget 312,1 FOUND!");
                }
                return hasWidget;
            }, 10000);
            
            if (!interfaceFound) {
                updateStatus("Anvil interface (312,1) not detected - retrying anvil interaction");
                System.out.println("[DEBUG] Failed to find anvil interface widget 312,1 after 10 seconds");
                return false;
            }

            sleep(186, 480); // Match working plugin timing

            // Get current smithing level and available bars
            int currentSmithingLevel = Rs2Player.getRealSkillLevel(Skill.SMITHING);
            int availableBars = Rs2Inventory.count(config.metalType().getBarName());
            String metalType = config.metalType().getDisplayName().toLowerCase();

            updateStatus("Anvil interface detected! Level: " + currentSmithingLevel + ", Bars: " + availableBars + ", Metal: " + metalType);
            System.out.println("[DEBUG] Smithing level: " + currentSmithingLevel + ", Available bars: " + availableBars + ", Metal: " + metalType);

            // EXPERT FIX: Select "All" quantity first (like working plugin does)
            // Check if we need to set quantity to "All" (Varbit 2224)
            int currentVarbit = Microbot.getVarbitPlayerValue(2224);
            System.out.println("[DEBUG] Current varbit 2224 value: " + currentVarbit + ", available bars: " + availableBars);
            
            if (currentVarbit < availableBars) {
                updateStatus("Setting quantity to 'All' (Widget 312,7)");
                System.out.println("[DEBUG] Clicking 'All' button at widget 312,7");
                Rs2Widget.clickWidget(312, 7);
                sleep(186, 480);
            }

            // EXPERT FIX: Get the best smithing item child ID based on level and bars
            int itemChildId = getBestSmithingItemChildId(metalType, currentSmithingLevel, availableBars);
            
            if (itemChildId == -1) {
                updateStatus("No suitable smithing items found for current level");
                System.out.println("[DEBUG] No suitable items found for level " + currentSmithingLevel + " with " + availableBars + " bars");
                return false;
            }

            updateStatus("Clicking smithing item at child ID: " + itemChildId);
            System.out.println("[DEBUG] Clicking widget 312," + itemChildId);

            // EXPERT FIX: Click the specific widget child ID (like working plugin)
            boolean itemSelected = Rs2Widget.clickWidget(312, itemChildId);
            
            if (itemSelected) {
                updateStatus("Successfully selected smithing item (Widget 312," + itemChildId + ")");
                System.out.println("[DEBUG] Successfully clicked widget 312," + itemChildId);
                sleep(186, 480); // Match working plugin timing
                return true;
            } else {
                updateStatus("Failed to click smithing item at child ID: " + itemChildId);
                System.out.println("[DEBUG] Failed to click widget 312," + itemChildId);
                return false;
            }

        } catch (Exception e) {
            handleError("Failed to select smithing option", e);
            System.out.println("[DEBUG] Exception in selectBestSmithingOption: " + e.getMessage());
            return false;
        }
    }

    /**
     * DYNAMIC LEVEL PROGRESSION: Gets the widget child ID for the best smithing item with real-time level checking
     * This method evaluates the CURRENT smithing level each time to select progressively better items
     * Based on actual RuneScape smithing levels and progressive bar usage optimization
     */
    private int getBestSmithingItemChildIdDynamic(String metalType, int smithingLevel, int availableBars) {
        updateStatus("DYNAMIC: Determining best smithing item for " + metalType + " (level " + smithingLevel + ")");
        System.out.println("[DEBUG] DYNAMIC SELECTION - Metal: " + metalType + ", Level: " + smithingLevel + ", Bars: " + availableBars);
        System.out.println("[DEBUG] DYNAMIC - Real-time smithing level check: " + Rs2Player.getRealSkillLevel(Skill.SMITHING));

        // DYNAMIC LEVEL-AWARE PROGRESSION: Use actual RuneScape smithing level requirements
        // Progressive item selection - items that use more bars are better for efficiency
        // Listed in order of preference (most bars first) with CORRECT level requirements
        
        String metalTypeLower = metalType.toLowerCase();
        System.out.println("[DEBUG] DYNAMIC - Metal type (lowercase): '" + metalTypeLower + "'");
        
        if (metalTypeLower.equals("iron")) {
            // Iron items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 33 && availableBars >= 5) { // Iron platebody requires level 33
                System.out.println("[DEBUG] DYNAMIC - Selected Iron Platebody (5 bars, level 33) for level " + smithingLevel);
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 31 && availableBars >= 3) { // Iron platelegs requires level 31
                System.out.println("[DEBUG] DYNAMIC - Selected Iron Platelegs (3 bars, level 31) for level " + smithingLevel);
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 31 && availableBars >= 3) { // Iron plateskirt requires level 31
                System.out.println("[DEBUG] DYNAMIC - Selected Iron Plateskirt (3 bars, level 31) for level " + smithingLevel);
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 29 && availableBars >= 3) { // Iron 2h sword requires level 29
                System.out.println("[DEBUG] DYNAMIC - Selected Iron 2H Sword (3 bars, level 29) for level " + smithingLevel);
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 25 && availableBars >= 3) { // Iron battleaxe requires level 25
                System.out.println("[DEBUG] DYNAMIC - Selected Iron Battleaxe (3 bars, level 25) for level " + smithingLevel);
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 21 && availableBars >= 2) { // Iron longsword requires level 21
                System.out.println("[DEBUG] DYNAMIC - Selected Iron Longsword (2 bars, level 21) for level " + smithingLevel);
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 20 && availableBars >= 2) { // Iron scimitar requires level 20
                System.out.println("[DEBUG] DYNAMIC - Selected Iron Scimitar (2 bars, level 20) for level " + smithingLevel);
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 16 && availableBars >= 1) { // Iron axe requires level 16
                System.out.println("[DEBUG] DYNAMIC - Selected Iron Axe (1 bar, level 16) for level " + smithingLevel);
                return 14; // Axe (1 bar)
            }
            if (smithingLevel >= 15 && availableBars >= 1) { // Iron dagger requires level 15
                System.out.println("[DEBUG] DYNAMIC - Selected Iron Dagger (1 bar, level 15) for level " + smithingLevel);
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("steel")) {
            // Steel items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 48 && availableBars >= 5) { // Steel platebody requires level 48
                System.out.println("[DEBUG] DYNAMIC - Selected Steel Platebody (5 bars, level 48) for level " + smithingLevel);
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 46 && availableBars >= 3) { // Steel platelegs requires level 46
                System.out.println("[DEBUG] DYNAMIC - Selected Steel Platelegs (3 bars, level 46) for level " + smithingLevel);
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 46 && availableBars >= 3) { // Steel plateskirt requires level 46
                System.out.println("[DEBUG] DYNAMIC - Selected Steel Plateskirt (3 bars, level 46) for level " + smithingLevel);
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 44 && availableBars >= 3) { // Steel 2h sword requires level 44
                System.out.println("[DEBUG] DYNAMIC - Selected Steel 2H Sword (3 bars, level 44) for level " + smithingLevel);
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 40 && availableBars >= 3) { // Steel battleaxe requires level 40
                System.out.println("[DEBUG] DYNAMIC - Selected Steel Battleaxe (3 bars, level 40) for level " + smithingLevel);
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 36 && availableBars >= 2) { // Steel longsword requires level 36
                System.out.println("[DEBUG] DYNAMIC - Selected Steel Longsword (2 bars, level 36) for level " + smithingLevel);
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 35 && availableBars >= 2) { // Steel scimitar requires level 35
                System.out.println("[DEBUG] DYNAMIC - Selected Steel Scimitar (2 bars, level 35) for level " + smithingLevel);
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 31 && availableBars >= 1) { // Steel axe requires level 31
                System.out.println("[DEBUG] DYNAMIC - Selected Steel Axe (1 bar, level 31) for level " + smithingLevel);
                return 14; // Axe (1 bar)
            }
            if (smithingLevel >= 30 && availableBars >= 1) { // Steel dagger requires level 30
                System.out.println("[DEBUG] DYNAMIC - Selected Steel Dagger (1 bar, level 30) for level " + smithingLevel);
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("mithril")) {
            // Mithril items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 68 && availableBars >= 5) { // Mithril platebody requires level 68
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril Platebody (5 bars, level 68) for level " + smithingLevel);
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 66 && availableBars >= 3) { // Mithril platelegs requires level 66
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril Platelegs (3 bars, level 66) for level " + smithingLevel);
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 66 && availableBars >= 3) { // Mithril plateskirt requires level 66
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril Plateskirt (3 bars, level 66) for level " + smithingLevel);
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 64 && availableBars >= 3) { // Mithril 2h sword requires level 64
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril 2H Sword (3 bars, level 64) for level " + smithingLevel);
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 60 && availableBars >= 3) { // Mithril battleaxe requires level 60
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril Battleaxe (3 bars, level 60) for level " + smithingLevel);
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 56 && availableBars >= 2) { // Mithril longsword requires level 56
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril Longsword (2 bars, level 56) for level " + smithingLevel);
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 55 && availableBars >= 2) { // Mithril scimitar requires level 55
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril Scimitar (2 bars, level 55) for level " + smithingLevel);
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 51 && availableBars >= 1) { // Mithril axe requires level 51
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril Axe (1 bar, level 51) for level " + smithingLevel);
                return 14; // Axe (1 bar)
            }
            if (smithingLevel >= 50 && availableBars >= 1) { // Mithril dagger requires level 50
                System.out.println("[DEBUG] DYNAMIC - Selected Mithril Dagger (1 bar, level 50) for level " + smithingLevel);
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("adamant")) {
            // Adamant items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 88 && availableBars >= 5) { // Adamant platebody requires level 88
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant Platebody (5 bars, level 88) for level " + smithingLevel);
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 86 && availableBars >= 3) { // Adamant platelegs requires level 86
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant Platelegs (3 bars, level 86) for level " + smithingLevel);
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 86 && availableBars >= 3) { // Adamant plateskirt requires level 86
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant Plateskirt (3 bars, level 86) for level " + smithingLevel);
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 84 && availableBars >= 3) { // Adamant 2h sword requires level 84
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant 2H Sword (3 bars, level 84) for level " + smithingLevel);
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 80 && availableBars >= 3) { // Adamant battleaxe requires level 80
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant Battleaxe (3 bars, level 80) for level " + smithingLevel);
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 76 && availableBars >= 2) { // Adamant longsword requires level 76
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant Longsword (2 bars, level 76) for level " + smithingLevel);
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 75 && availableBars >= 2) { // Adamant scimitar requires level 75
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant Scimitar (2 bars, level 75) for level " + smithingLevel);
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 71 && availableBars >= 1) { // Adamant axe requires level 71
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant Axe (1 bar, level 71) for level " + smithingLevel);
                return 14; // Axe (1 bar)
            }
            if (smithingLevel >= 70 && availableBars >= 1) { // Adamant dagger requires level 70
                System.out.println("[DEBUG] DYNAMIC - Selected Adamant Dagger (1 bar, level 70) for level " + smithingLevel);
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("rune")) {
            // Rune items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 99 && availableBars >= 5) { // Rune platebody requires level 99
                System.out.println("[DEBUG] DYNAMIC - Selected Rune Platebody (5 bars, level 99) for level " + smithingLevel);
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 96 && availableBars >= 3) { // Rune platelegs requires level 96
                System.out.println("[DEBUG] DYNAMIC - Selected Rune Platelegs (3 bars, level 96) for level " + smithingLevel);
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 96 && availableBars >= 3) { // Rune plateskirt requires level 96
                System.out.println("[DEBUG] DYNAMIC - Selected Rune Plateskirt (3 bars, level 96) for level " + smithingLevel);
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 94 && availableBars >= 3) { // Rune 2h sword requires level 94
                System.out.println("[DEBUG] DYNAMIC - Selected Rune 2H Sword (3 bars, level 94) for level " + smithingLevel);
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 90 && availableBars >= 3) { // Rune battleaxe requires level 90
                System.out.println("[DEBUG] DYNAMIC - Selected Rune Battleaxe (3 bars, level 90) for level " + smithingLevel);
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 91 && availableBars >= 2) { // Rune longsword requires level 91
                System.out.println("[DEBUG] DYNAMIC - Selected Rune Longsword (2 bars, level 91) for level " + smithingLevel);
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 90 && availableBars >= 2) { // Rune scimitar requires level 90
                System.out.println("[DEBUG] DYNAMIC - Selected Rune Scimitar (2 bars, level 90) for level " + smithingLevel);
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 86 && availableBars >= 1) { // Rune axe requires level 86
                System.out.println("[DEBUG] DYNAMIC - Selected Rune Axe (1 bar, level 86) for level " + smithingLevel);
                return 14; // Axe (1 bar)
            }
            if (smithingLevel >= 85 && availableBars >= 1) { // Rune dagger requires level 85
                System.out.println("[DEBUG] DYNAMIC - Selected Rune Dagger (1 bar, level 85) for level " + smithingLevel);
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("bronze")) {
            // Bronze items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 18 && availableBars >= 5) { // Bronze platebody requires level 18
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze Platebody (5 bars, level 18) for level " + smithingLevel);
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 16 && availableBars >= 3) { // Bronze platelegs requires level 16
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze Platelegs (3 bars, level 16) for level " + smithingLevel);
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 16 && availableBars >= 3) { // Bronze plateskirt requires level 16
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze Plateskirt (3 bars, level 16) for level " + smithingLevel);
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 14 && availableBars >= 3) { // Bronze 2h sword requires level 14
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze 2H Sword (3 bars, level 14) for level " + smithingLevel);
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 10 && availableBars >= 3) { // Bronze battleaxe requires level 10
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze Battleaxe (3 bars, level 10) for level " + smithingLevel);
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 6 && availableBars >= 2) { // Bronze longsword requires level 6
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze Longsword (2 bars, level 6) for level " + smithingLevel);
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 5 && availableBars >= 2) { // Bronze scimitar requires level 5
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze Scimitar (2 bars, level 5) for level " + smithingLevel);
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 1 && availableBars >= 1) { // Bronze axe requires level 1
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze Axe (1 bar, level 1) for level " + smithingLevel);
                return 14; // Axe (1 bar)
            }
            if (smithingLevel >= 1 && availableBars >= 1) { // Bronze dagger requires level 1
                System.out.println("[DEBUG] DYNAMIC - Selected Bronze Dagger (1 bar, level 1) for level " + smithingLevel);
                return 9; // Dagger (1 bar)
            }
        }

        // Fallback: if no specific items available, try basic items
        if (availableBars >= 1) {
            System.out.println("[DEBUG] DYNAMIC - Fallback to basic item for level " + smithingLevel);
            return 9; // Dagger (basic fallback)
        }

        System.out.println("[DEBUG] DYNAMIC - No suitable items found for " + metalType + " level " + smithingLevel + " with " + availableBars + " bars");
        return -1; // No suitable item found
    }

    /**
     * EXPERT FIX: Gets the widget child ID for the best smithing item using REAL level requirements
     * Based on actual RuneScape smithing levels and progressive bar usage optimization
     */
    private int getBestSmithingItemChildId(String metalType, int smithingLevel, int availableBars) {
        updateStatus("Determining best smithing item child ID for " + metalType + " (level " + smithingLevel + ")");
        System.out.println("[DEBUG] Selecting smithing item for " + metalType + " with level " + smithingLevel + " and " + availableBars + " bars");
        System.out.println("[DEBUG] Current player smithing level: " + Rs2Player.getRealSkillLevel(Skill.SMITHING));

        // LEVEL-AWARE PROGRESSION: Use actual RuneScape smithing level requirements
        // Progressive item selection - items that use more bars are better for efficiency
        // Listed in order of preference (most bars first) with CORRECT level requirements
        
        String metalTypeLower = metalType.toLowerCase();
        System.out.println("[DEBUG] Metal type (lowercase): '" + metalTypeLower + "'");
        
        if (metalTypeLower.equals("iron")) {
            // Iron items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 33 && availableBars >= 5) { // Iron platebody requires level 33
                System.out.println("[DEBUG] Selected Iron Platebody (5 bars, level 33)");
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 31 && availableBars >= 3) { // Iron platelegs requires level 31
                System.out.println("[DEBUG] Selected Iron Platelegs (3 bars, level 31)");
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 31 && availableBars >= 3) { // Iron plateskirt requires level 31
                System.out.println("[DEBUG] Selected Iron Plateskirt (3 bars, level 31)");
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 29 && availableBars >= 3) { // Iron 2h sword requires level 29
                System.out.println("[DEBUG] Selected Iron 2H Sword (3 bars, level 29)");
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 25 && availableBars >= 3) { // Iron battleaxe requires level 25
                System.out.println("[DEBUG] Selected Iron Battleaxe (3 bars, level 25)");
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 21 && availableBars >= 2) { // Iron longsword requires level 21
                System.out.println("[DEBUG] Selected Iron Longsword (2 bars, level 21)");
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 20 && availableBars >= 2) { // Iron scimitar requires level 20
                System.out.println("[DEBUG] Selected Iron Scimitar (2 bars, level 20)");
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 15 && availableBars >= 1) { // Iron dagger requires level 15
                System.out.println("[DEBUG] Selected Iron Dagger (1 bar, level 15)");
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("bronze")) {
            // Bronze items (ordered by bars required) - REAL LEVELS
            if (smithingLevel >= 18 && availableBars >= 5) { // Bronze platebody requires level 18
                System.out.println("[DEBUG] Selected Bronze Platebody (5 bars, level 18)");
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 16 && availableBars >= 3) { // Bronze platelegs requires level 16
                System.out.println("[DEBUG] Selected Bronze Platelegs (3 bars, level 16)");
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 16 && availableBars >= 3) { // Bronze plateskirt requires level 16
                System.out.println("[DEBUG] Selected Bronze Plateskirt (3 bars, level 16)");
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 14 && availableBars >= 3) { // Bronze 2h sword requires level 14
                System.out.println("[DEBUG] Selected Bronze 2H Sword (3 bars, level 14)");
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 10 && availableBars >= 3) { // Bronze battleaxe requires level 10
                System.out.println("[DEBUG] Selected Bronze Battleaxe (3 bars, level 10)");
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 6 && availableBars >= 2) { // Bronze longsword requires level 6
                System.out.println("[DEBUG] Selected Bronze Longsword (2 bars, level 6)");
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 5 && availableBars >= 2) { // Bronze scimitar requires level 5
                System.out.println("[DEBUG] Selected Bronze Scimitar (2 bars, level 5)");
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 1 && availableBars >= 1) { // Bronze dagger requires level 1
                System.out.println("[DEBUG] Selected Bronze Dagger (1 bar, level 1)");
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("steel")) {
            // Steel items (ordered by bars required) - REAL LEVELS
            if (smithingLevel >= 48 && availableBars >= 5) { // Steel platebody requires level 48
                System.out.println("[DEBUG] Selected Steel Platebody (5 bars, level 48)");
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 46 && availableBars >= 3) { // Steel platelegs requires level 46
                System.out.println("[DEBUG] Selected Steel Platelegs (3 bars, level 46)");
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 46 && availableBars >= 3) { // Steel plateskirt requires level 46
                System.out.println("[DEBUG] Selected Steel Plateskirt (3 bars, level 46)");
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 44 && availableBars >= 3) { // Steel 2h sword requires level 44
                System.out.println("[DEBUG] Selected Steel 2H Sword (3 bars, level 44)");
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 40 && availableBars >= 3) { // Steel battleaxe requires level 40
                System.out.println("[DEBUG] Selected Steel Battleaxe (3 bars, level 40)");
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 36 && availableBars >= 2) { // Steel longsword requires level 36
                System.out.println("[DEBUG] Selected Steel Longsword (2 bars, level 36)");
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 35 && availableBars >= 2) { // Steel scimitar requires level 35
                System.out.println("[DEBUG] Selected Steel Scimitar (2 bars, level 35)");
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 30 && availableBars >= 1) { // Steel dagger requires level 30
                System.out.println("[DEBUG] Selected Steel Dagger (1 bar, level 30)");
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("mithril")) {
            // Mithril items (ordered by bars required) - REAL LEVELS
            if (smithingLevel >= 68 && availableBars >= 5) { // Mithril platebody requires level 68
                System.out.println("[DEBUG] Selected Mithril Platebody (5 bars, level 68)");
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 66 && availableBars >= 3) { // Mithril platelegs requires level 66
                System.out.println("[DEBUG] Selected Mithril Platelegs (3 bars, level 66)");
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 66 && availableBars >= 3) { // Mithril plateskirt requires level 66
                System.out.println("[DEBUG] Selected Mithril Plateskirt (3 bars, level 66)");
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 64 && availableBars >= 3) { // Mithril 2h sword requires level 64
                System.out.println("[DEBUG] Selected Mithril 2H Sword (3 bars, level 64)");
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 60 && availableBars >= 3) { // Mithril battleaxe requires level 60
                System.out.println("[DEBUG] Selected Mithril Battleaxe (3 bars, level 60)");
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 56 && availableBars >= 2) { // Mithril longsword requires level 56
                System.out.println("[DEBUG] Selected Mithril Longsword (2 bars, level 56)");
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 55 && availableBars >= 2) { // Mithril scimitar requires level 55
                System.out.println("[DEBUG] Selected Mithril Scimitar (2 bars, level 55)");
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 50 && availableBars >= 1) { // Mithril dagger requires level 50
                System.out.println("[DEBUG] Selected Mithril Dagger (1 bar, level 50)");
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("adamantite") || metalTypeLower.equals("adamant")) {
            // Adamantite items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 88 && availableBars >= 5) { // Adamantite platebody requires level 88
                System.out.println("[DEBUG] Selected Adamantite Platebody (5 bars, level 88)");
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 86 && availableBars >= 3) { // Adamantite platelegs requires level 86
                System.out.println("[DEBUG] Selected Adamantite Platelegs (3 bars, level 86)");
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 86 && availableBars >= 3) { // Adamantite plateskirt requires level 86
                System.out.println("[DEBUG] Selected Adamantite Plateskirt (3 bars, level 86)");
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 84 && availableBars >= 3) { // Adamantite 2h sword requires level 84
                System.out.println("[DEBUG] Selected Adamantite 2H Sword (3 bars, level 84)");
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 82 && availableBars >= 3) { // Adamantite kiteshield requires level 82
                System.out.println("[DEBUG] Selected Adamantite Kiteshield (3 bars, level 82)");
                return 27; // Kiteshield (3 bars)
            }
            if (smithingLevel >= 81 && availableBars >= 3) { // Adamantite chainbody requires level 81
                System.out.println("[DEBUG] Selected Adamantite Chainbody (3 bars, level 81)");
                return 19; // Chainbody (3 bars)
            }
            if (smithingLevel >= 80 && availableBars >= 3) { // Adamantite battleaxe requires level 80
                System.out.println("[DEBUG] Selected Adamantite Battleaxe (3 bars, level 80)");
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 79 && availableBars >= 3) { // Adamantite warhammer requires level 79
                System.out.println("[DEBUG] Selected Adamantite Warhammer (3 bars, level 79)");
                return 16; // Warhammer (3 bars)
            }
            if (smithingLevel >= 78 && availableBars >= 2) { // Adamantite square shield requires level 78
                System.out.println("[DEBUG] Selected Adamantite Square Shield (2 bars, level 78)");
                return 26; // Square shield (2 bars)
            }
            if (smithingLevel >= 77 && availableBars >= 2) { // Adamantite full helm requires level 77
                System.out.println("[DEBUG] Selected Adamantite Full Helm (2 bars, level 77)");
                return 25; // Full helm (2 bars)
            }
            if (smithingLevel >= 77 && availableBars >= 1) { // Adamantite knife requires level 77
                System.out.println("[DEBUG] Selected Adamantite Knife (1 bar, level 77)");
                return 31; // Knife (1 bar)
            }
            if (smithingLevel >= 76 && availableBars >= 2) { // Adamantite longsword requires level 76
                System.out.println("[DEBUG] Selected Adamantite Longsword (2 bars, level 76)");
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 75 && availableBars >= 2) { // Adamantite scimitar requires level 75
                System.out.println("[DEBUG] Selected Adamantite Scimitar (2 bars, level 75)");
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 74 && availableBars >= 1) { // Adamantite sword requires level 74
                System.out.println("[DEBUG] Selected Adamantite Sword (1 bar, level 74)");
                return 10; // Sword (1 bar)
            }
            if (smithingLevel >= 73 && availableBars >= 1) { // Adamantite med helm requires level 73
                System.out.println("[DEBUG] Selected Adamantite Med Helm (1 bar, level 73)");
                return 24; // Med helm (1 bar)
            }
            if (smithingLevel >= 72 && availableBars >= 1) { // Adamantite mace requires level 72
                System.out.println("[DEBUG] Selected Adamantite Mace (1 bar, level 72)");
                return 15; // Mace (1 bar)
            }
            if (smithingLevel >= 71 && availableBars >= 1) { // Adamantite axe requires level 71
                System.out.println("[DEBUG] Selected Adamantite Axe (1 bar, level 71)");
                return 14; // Axe (1 bar)
            }
            if (smithingLevel >= 70 && availableBars >= 1) { // Adamantite dagger requires level 70
                System.out.println("[DEBUG] Selected Adamantite Dagger (1 bar, level 70)");
                return 9; // Dagger (1 bar)
            }
        }
        else if (metalTypeLower.equals("runite") || metalTypeLower.equals("rune")) {
            // Runite items (ordered by bars required - most bars first) - REAL LEVELS
            if (smithingLevel >= 99 && availableBars >= 5) { // Runite platebody requires level 99
                System.out.println("[DEBUG] Selected Runite Platebody (5 bars, level 99)");
                return 22; // Platebody (5 bars)
            }
            if (smithingLevel >= 99 && availableBars >= 3) { // Runite platelegs requires level 99
                System.out.println("[DEBUG] Selected Runite Platelegs (3 bars, level 99)");
                return 20; // Platelegs (3 bars)
            }
            if (smithingLevel >= 99 && availableBars >= 3) { // Runite plateskirt requires level 99
                System.out.println("[DEBUG] Selected Runite Plateskirt (3 bars, level 99)");
                return 21; // Plateskirt (3 bars)
            }
            if (smithingLevel >= 99 && availableBars >= 3) { // Runite 2h sword requires level 99
                System.out.println("[DEBUG] Selected Runite 2H Sword (3 bars, level 99)");
                return 13; // Two-handed sword (3 bars)
            }
            if (smithingLevel >= 97 && availableBars >= 3) { // Runite kiteshield requires level 97
                System.out.println("[DEBUG] Selected Runite Kiteshield (3 bars, level 97)");
                return 27; // Kiteshield (3 bars)
            }
            if (smithingLevel >= 96 && availableBars >= 3) { // Runite chainbody requires level 96
                System.out.println("[DEBUG] Selected Runite Chainbody (3 bars, level 96)");
                return 19; // Chainbody (3 bars)
            }
            if (smithingLevel >= 95 && availableBars >= 3) { // Runite battleaxe requires level 95
                System.out.println("[DEBUG] Selected Runite Battleaxe (3 bars, level 95)");
                return 17; // Battleaxe (3 bars)
            }
            if (smithingLevel >= 94 && availableBars >= 3) { // Runite warhammer requires level 94
                System.out.println("[DEBUG] Selected Runite Warhammer (3 bars, level 94)");
                return 16; // Warhammer (3 bars)
            }
            if (smithingLevel >= 93 && availableBars >= 2) { // Runite square shield requires level 93
                System.out.println("[DEBUG] Selected Runite Square Shield (2 bars, level 93)");
                return 26; // Square shield (2 bars)
            }
            if (smithingLevel >= 92 && availableBars >= 2) { // Runite full helm requires level 92
                System.out.println("[DEBUG] Selected Runite Full Helm (2 bars, level 92)");
                return 25; // Full helm (2 bars)
            }
            if (smithingLevel >= 92 && availableBars >= 1) { // Runite knife requires level 92
                System.out.println("[DEBUG] Selected Runite Knife (1 bar, level 92)");
                return 31; // Knife (1 bar)
            }
            if (smithingLevel >= 91 && availableBars >= 2) { // Runite longsword requires level 91
                System.out.println("[DEBUG] Selected Runite Longsword (2 bars, level 91)");
                return 12; // Longsword (2 bars)
            }
            if (smithingLevel >= 90 && availableBars >= 2) { // Runite scimitar requires level 90
                System.out.println("[DEBUG] Selected Runite Scimitar (2 bars, level 90)");
                return 11; // Scimitar (2 bars)
            }
            if (smithingLevel >= 89 && availableBars >= 1) { // Runite sword requires level 89
                System.out.println("[DEBUG] Selected Runite Sword (1 bar, level 89)");
                return 10; // Sword (1 bar)
            }
            if (smithingLevel >= 88 && availableBars >= 1) { // Runite med helm requires level 88
                System.out.println("[DEBUG] Selected Runite Med Helm (1 bar, level 88)");
                return 24; // Med helm (1 bar)
            }
            if (smithingLevel >= 87 && availableBars >= 1) { // Runite mace requires level 87
                System.out.println("[DEBUG] Selected Runite Mace (1 bar, level 87)");
                return 15; // Mace (1 bar)
            }
            if (smithingLevel >= 86 && availableBars >= 1) { // Runite axe requires level 86
                System.out.println("[DEBUG] Selected Runite Axe (1 bar, level 86)");
                return 14; // Axe (1 bar)
            }
            if (smithingLevel >= 85 && availableBars >= 1) { // Runite dagger requires level 85
                System.out.println("[DEBUG] Selected Runite Dagger (1 bar, level 85)");
                return 9; // Dagger (1 bar)
            }
        }
        else {
            // Unknown metal type
            System.out.println("[DEBUG] Unknown metal type: '" + metalTypeLower + "' - using fallback dagger selection");
            updateStatus("Unknown metal type: " + metalTypeLower + ", falling back to basic smithing");
        }

        // Fallback to basic dagger if nothing else works and we have bars
        if (availableBars >= 1) {
            System.out.println("[DEBUG] Fallback to basic dagger with " + availableBars + " bars");
            return 9; // AnvilItem.DAGGER
        }
        
        System.out.println("[DEBUG] No suitable item found - no bars available");
        return -1; // No suitable item found
    }

    /**
     * Waits for smithing process to complete with progress monitoring
     * EXPERT FIX: Enhanced with proper XP drop monitoring like working plugin
     */
    /**
     * Creates a snapshot of current inventory to track item changes
     */
    private Map<String, Integer> getCurrentInventorySnapshot() {
        Map<String, Integer> inventory = new HashMap<>();
        
        // Get all items in inventory
        Rs2Inventory.items().forEach(item -> {
            if (item != null && item.getName() != null) {
                String itemName = item.getName();
                int currentCount = inventory.getOrDefault(itemName, 0);
                inventory.put(itemName, currentCount + item.getQuantity());
            }
        });
        
        return inventory;
    }
    
    /**
     * Counts new items created by comparing before/after inventory snapshots
     * Excludes bars and hammers to only count actual smithed items
     */
    private int countNewItemsCreated(Map<String, Integer> before, Map<String, Integer> after) {
        int newItemsCount = 0;
        String barName = config.metalType().getBarName();
        
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            String itemName = entry.getKey();
            int afterCount = entry.getValue();
            int beforeCount = before.getOrDefault(itemName, 0);
            
            // Only count items that increased and are not bars or hammers
            if (afterCount > beforeCount && 
                !itemName.equals(barName) && 
                !itemName.contains("hammer") && 
                !itemName.contains("Hammer")) {
                
                int itemsAdded = afterCount - beforeCount;
                newItemsCount += itemsAdded;
                System.out.println("[DEBUG] New smithed item detected: " + itemName + " (+"+itemsAdded+")");
            }
        }
        
        return newItemsCount;
    }

    /**
     * DYNAMIC LEVEL PROGRESSION: Waits for a single smithing action to complete
     * This allows for real-time level checking after each smithing action, enabling
     * progressive item upgrades as the player levels up during smithing sessions
     */
    private void waitForSingleSmithingAction() {
        updateStatus("DYNAMIC: Waiting for single smithing action to complete...");
        long actionStartTime = System.currentTimeMillis();
        int initialBarCount = Rs2Inventory.count(config.metalType().getBarName());
        
        // Track initial inventory for item counting
        Map<String, Integer> initialInventory = getCurrentInventorySnapshot();
        boolean expectingXPDrop = true;
        boolean actionCompleted = false;

        System.out.println("[DEBUG] DYNAMIC - Starting single smithing action wait with " + initialBarCount + " bars");

        while (!actionCompleted && (System.currentTimeMillis() - actionStartTime < 60000)) { // 1 minute max per action
            // Wait for XP drop to indicate smithing action completed
            if (expectingXPDrop && Rs2Player.waitForXpDrop(Skill.SMITHING, 7500)) {
                updateStatus("DYNAMIC: Smithing XP drop detected - single action completed!");
                System.out.println("[DEBUG] DYNAMIC - XP drop detected, single action completed");
                
                // Count actual items created (not bars used)
                Map<String, Integer> currentInventory = getCurrentInventorySnapshot();
                int newItemsCreated = countNewItemsCreated(initialInventory, currentInventory);
                
                if (newItemsCreated > 0) {
                    for (int i = 0; i < newItemsCreated; i++) {
                        progress.incrementItemsSmithed();
                    }
                    updateStatus("DYNAMIC: Created " + newItemsCreated + " new items! Total: " + progress.getItemsSmithed());
                    System.out.println("[DEBUG] DYNAMIC - Created " + newItemsCreated + " items, total: " + progress.getItemsSmithed());
                }
                
                actionCompleted = true;
                break;
            }

            // Check if smithing animation stopped (action may be complete)
            if (!Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
                // Check if bar count changed (indicating smithing occurred)
                int currentBarCount = Rs2Inventory.count(config.metalType().getBarName());
                if (currentBarCount != initialBarCount) {
                    updateStatus("DYNAMIC: Bar count changed - single action completed (bars: " + initialBarCount + " -> " + currentBarCount + ")");
                    System.out.println("[DEBUG] DYNAMIC - Bar count changed from " + initialBarCount + " to " + currentBarCount);
                    actionCompleted = true;
                    break;
                }
                
                // If no bars left, action is definitely complete
                if (currentBarCount == 0) {
                    updateStatus("DYNAMIC: No bars remaining - action completed");
                    System.out.println("[DEBUG] DYNAMIC - No bars remaining, action completed");
                    actionCompleted = true;
                    break;
                }
            }

            // Brief sleep between checks
            sleep(200, 400);
        }

        if (!actionCompleted) {
            updateStatus("DYNAMIC: Single action timeout - proceeding anyway");
            System.out.println("[DEBUG] DYNAMIC - Single action timeout after 60 seconds");
        }

        System.out.println("[DEBUG] DYNAMIC - Single smithing action wait completed");
    }

    private void waitForSmithing() {
        updateStatus("Smithing in progress...");
        long smithingStartTime = System.currentTimeMillis();
        int initialBarCount = Rs2Inventory.count(config.metalType().getBarName());
        
        // Track actual items created for proper counting
        Map<String, Integer> initialInventory = getCurrentInventorySnapshot();
        boolean expectingXPDrop = true;

        while (Rs2Player.isAnimating() || Rs2Player.isMoving() || expectingXPDrop) {
            // EXPERT FIX: Use XP drop monitoring like working plugin
            if (expectingXPDrop && Rs2Player.waitForXpDrop(Skill.SMITHING, 7500)) {
                updateStatus("Smithing XP drop detected - checking for new items...");
                
                // Count actual items created (not bars used)
                Map<String, Integer> currentInventory = getCurrentInventorySnapshot();
                int newItemsCreated = countNewItemsCreated(initialInventory, currentInventory);
                
                if (newItemsCreated > 0) {
                    for (int i = 0; i < newItemsCreated; i++) {
                        progress.incrementItemsSmithed();
                    }
                    updateStatus("Created " + newItemsCreated + " new items! Total items smithed: " + progress.getItemsSmithed());
                    
                    // Update initial inventory for next comparison
                    initialInventory = currentInventory;
                }
                
                // Reset expectation for next XP drop
                expectingXPDrop = Rs2Player.isAnimating();
            }

            // Monitor bar consumption for status updates
            int currentBarCount = Rs2Inventory.count(config.metalType().getBarName());
            if (currentBarCount != initialBarCount) {
                updateStatus("Bars remaining: " + currentBarCount + ", Items smithed: " + progress.getItemsSmithed());
                initialBarCount = currentBarCount;
            }

            // Safety timeout (10 minutes max)
            if (System.currentTimeMillis() - smithingStartTime > 600000) {
                handleError("Smithing timeout exceeded");
                break;
            }

            // Check if we're still smithing
            if (!Rs2Player.isAnimating() && !hasBarsToSmith()) {
                updateStatus("Smithing completed - no more bars");
                break;
            }

            // EXPERT FIX: Use working plugin sleep timing
            sleep(256, 789);
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

        if (config.enableDebugLogs()) {
            Microbot.log("=== DETERMINING WALKING DESTINATION ===");
            Microbot.log("Current phase: " + currentPhase);
            Microbot.log("Starting phase: " + config.startingPhase());
            Microbot.log("Progress - Ores mined: " + progress.getOresMined() + "/" + config.targetQuantity());
            Microbot.log("Progress - Bars smelted: " + progress.getBarsSmelted() + "/" + getRequiredBars());
            Microbot.log("Player location: " + Rs2Player.getWorldLocation());
        }

        switch (currentPhase) {
            case WALKING:
                // If we're in WALKING phase, determine where we should go based on what we need
                // to do next
                ProcessPhase nextPhase = determineNextPhase();
                
                if (config.enableDebugLogs()) {
                    Microbot.log("WALKING DESTINATION: Next phase determined as " + nextPhase);
                }
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
                        if (config.enableDebugLogs()) {
                            Microbot.log("SMITHING DESTINATION: Walking to anvil at " + ANVIL_LOCATION);
                        }
                        return ANVIL_LOCATION;

                    default:
                        updateStatus("No specific destination needed for: " + nextPhase);
                        if (config.enableDebugLogs()) {
                            Microbot.log("WALKING: No specific destination for phase " + nextPhase);
                        }
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

        public void setOresUsedForSmelting(int oresUsedForSmelting) {
            this.oresUsedForSmelting = oresUsedForSmelting;
        }

        public void addMiningXp(int xp) {
            this.miningXpGained += xp;
        }

        public void addSmithingXp(int xp) {
            this.smithingXpGained += xp;
        }
    }
}
