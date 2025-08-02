package net.runelite.client.plugins.microbot.allinonemetalworker;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.allinonemetalworker.enums.ProcessPhase;
import net.runelite.client.plugins.microbot.allinonemetalworker.enums.SmithingProduct;
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
            // Initialize progress tracking
            if (progress.getStartTime() == null) {
                progress.setStartTime(Instant.now());
                Microbot.log("AIO Metal Worker started - Target: " + config.targetQuantity() + " " + config.metalType().getDisplayName());
            }
            
            // Configure anti-ban if enabled
            if (config.enableAntiban()) {
                configureAntiban();
            }
            
            // Use proper Microbot scheduling to prevent client freezing
            mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
                try {
                    if (!super.run() || !Microbot.isLoggedIn()) {
                        return;
                    }
                    
                    // Check completion conditions first
                    if (isTaskComplete()) {
                        currentPhase = ProcessPhase.COMPLETE;
                        Microbot.log("Task completed successfully!");
                        shutdown();
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
                // Task is complete, script will exit
                break;
        }
    }

    /**
     * Mining phase - mines ores with intelligent location detection
     */
    private void executeMiningPhase() {
        updateStatus("Mining " + config.metalType().getDisplayName() + " ore");
        
        // First priority: Check if we've reached the target mining quantity
        int totalOresMined = progress.getOresMined();
        int targetQuantity = config.targetQuantity();
        
        if (totalOresMined >= targetQuantity) {
            updateStatus("Target mining quantity reached! Ores mined: " + totalOresMined + "/" + targetQuantity);
            currentPhase = ProcessPhase.BANKING; // Go to bank to proceed to next phase
            return;
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
            new WorldPoint(3289, 3365, 0)  // Al Kharid mine north
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
        if (currentMiningArea == null) return false;
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
        String[] genericRockNames = {"Rock", "Rocks", "Mining rocks", "Tin rocks", "Copper rocks", "Iron rocks", "Coal rocks"};
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
        updateStatus("Smelting " + config.metalType().getDisplayName() + " bars");
        
        // Check if we're at the furnace
        if (Rs2Player.getWorldLocation().distanceTo(FURNACE_LOCATION) > 3) {
            currentPhase = ProcessPhase.WALKING;
            return;
        }
        
        // Check if we have the required ores
        if (!hasRequiredOres()) {
            currentPhase = ProcessPhase.MINING;
            return;
        }
        
        // Smelt the bars
        if (Rs2GameObject.interact("Furnace", "Smelt")) {
            updateStatus("Opening smelting interface...");
            
            // Wait for smelting interface
            sleep(1500, 2500);
            
            // Select the bar type and amount
            if (selectSmeltingOption()) {
                // Wait for smelting to complete
                waitForSmelting();
                progress.incrementBarsSmelted();
            }
        }
    }

    /**
     * Smithing phase - smiths bars into items at Varrock anvil
     */
    private void executeSmithingPhase() {
        updateStatus("Smithing items");
        
        // Check if we're at the anvil
        if (Rs2Player.getWorldLocation().distanceTo(ANVIL_LOCATION) > 3) {
            currentPhase = ProcessPhase.WALKING;
            return;
        }
        
        // Check if we have bars to smith
        if (!hasBarsToSmith()) {
            currentPhase = ProcessPhase.SMELTING;
            return;
        }
        
        // Ensure we have a hammer
        if (!hasHammer()) {
            handleError("No hammer found for smithing");
            return;
        }
        
        // Smith items
        if (Rs2GameObject.interact("Anvil", "Smith")) {
            updateStatus("Opening smithing interface...");
            
            // Wait for smithing interface
            sleep(1500, 2500);
            
            // Select the item to smith
            if (selectSmithingOption()) {
                // Wait for smithing to complete
                waitForSmithing();
                progress.incrementItemsSmithed();
            }
        }
    }

    /**
     * Walking phase - handles movement between locations
     */
    private void executeWalkingPhase() {
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
            
            while (!walkingStarted && retryCount < maxRetries) {
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
                
                // Emergency fallback - if we're supposed to be banking, switch directly to banking
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
        String[] genericRocks = {"Rocks", "Rock", "Mining rocks"};
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
                updateStatus("Successfully mined ore! Total ores: " + progress.getOresMined() + "/" + config.targetQuantity());
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
                return new String[]{"Copper", "Copper rock", "Copper rocks"};
            case "tin ore":
                return new String[]{"Tin", "Tin rock", "Tin rocks"};
            case "iron ore":
                return new String[]{"Iron", "Iron rock", "Iron rocks"};
            case "coal":
                return new String[]{"Coal rock", "Coal rocks"};
            case "mithril ore":
                return new String[]{"Mithril", "Mithril rock", "Mithril rocks"};
            case "adamantite ore":
                return new String[]{"Adamantite", "Adamantite rock", "Adamantite rocks"};
            case "runite ore":
                return new String[]{"Runite", "Runite rock", "Runite rocks"};
            default:
                return new String[]{oreName.replace(" ore", ""), oreName + " rock", oreName + " rocks"};
        }
    }
    


    /**
     * Handles banking operations based on current needs with intelligent item management
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
                updateStatus("Target mining quantity reached after deposit! Ores: " + totalOresMined + "/" + targetQuantity);
                
                // Close bank and determine next phase immediately
                Rs2Bank.closeBank();
                sleep(500, 800);
                
                // Force transition to next phase (smelting/smithing/complete)
                if (config.smeltBars() && needsMoreOresForSmelting()) {
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
                    }
                    
                    currentPhase = ProcessPhase.WALKING; // Will walk to anvil
                } else {
                    updateStatus("All tasks completed - target reached!");
                    currentPhase = ProcessPhase.COMPLETE;
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
     * Deposits all items except essential tools
     */
    private void depositItemsExceptTools() {
        // Keep essential tools in inventory
        String[] toolsToKeep = getEssentialTools();
        
        // Deposit all non-essential items
        Rs2Bank.depositAllExcept(toolsToKeep);
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
        
        if (totalOresMined < targetQuantity) {
            return ProcessPhase.MINING;
        } else if (config.smeltBars() && needsMoreOresForSmelting()) {
            return ProcessPhase.SMELTING;
        } else if (config.smithItems() && needsBarsForSmithing()) {
            return ProcessPhase.SMITHING;
        } else {
            return ProcessPhase.COMPLETE;
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
     */
    private void withdrawOresForSmelting() {
        updateStatus("Withdrawing ores for smelting...");
        
        String[] oreNames = config.metalType().getOreNames();
        int availableSlots = 28 - Rs2Inventory.count(); // Account for existing items/tools
        
        updateStatus("Available inventory slots: " + availableSlots);
        
        // For simple metals like iron, just fill the inventory
        if (oreNames.length == 1) {
            String oreName = oreNames[0];
            updateStatus("Withdrawing " + availableSlots + " " + oreName + " for smelting");
            Rs2Bank.withdrawX(oreName, availableSlots);
        } else {
            // For alloy metals, use ratio-based withdrawal
            Map<String, Integer> requiredOres = calculateRequiredOres();
            
            for (Map.Entry<String, Integer> entry : requiredOres.entrySet()) {
                String oreName = entry.getKey();
                int required = entry.getValue();
                
                if (Rs2Bank.hasItem(oreName) && required > 0) {
                    updateStatus("Withdrawing " + required + " " + oreName);
                    Rs2Bank.withdrawX(oreName, required);
                    sleep(300, 600);
                }
            }
        }
    }
    
    /**
     * Calculates how many of each ore to withdraw for optimal smelting
     */
    private Map<String, Integer> calculateRequiredOres() {
        Map<String, Integer> requiredOres = new HashMap<>();
        
        // Calculate based on available inventory space and ore requirements
        int availableSlots = 28 - Rs2Inventory.count(); // Account for tools
        
        // For coal bag, we can carry more coal
        boolean hasCoalBag = Rs2Inventory.hasItem("Coal bag") || 
                           (config.useCoalBag() && Rs2Bank.hasItem("Coal bag"));
        
        int coalBagCapacity = hasCoalBag ? 36 : 0; // Coal bag holds 36 coal
        
        // Get ore requirements for one bar
        String[] oreNames = config.metalType().getOreNames();
        
        for (String oreName : oreNames) {
            int orePerBar = 1; // Simplified - would need actual ratio calculation
            if (oreName.toLowerCase().contains("coal")) {
                // Coal can use bag capacity
                int maxCoal = availableSlots + coalBagCapacity;
                requiredOres.put(oreName, Math.min(maxCoal, orePerBar * 10)); // Max 10 bars worth
            } else {
                requiredOres.put(oreName, Math.min(availableSlots / 2, orePerBar * 14)); // Conservative
            }
        }
        
        return requiredOres;
    }
    
    /**
     * Withdraws bars needed for smithing
     */
    private void withdrawBarsForSmithing() {
        String barName = config.metalType().getBarName();
        int barsToWithdraw = Math.min(27, Rs2Bank.count(barName)); // Leave space for hammer
        
        if (barsToWithdraw > 0) {
            Rs2Bank.withdrawX(barName, barsToWithdraw);
        }
    }
    
    /**
     * Determines the next phase based on current progress and available items
     */
    private void determineNextPhaseFromBank() {
        updateStatus("Analyzing current progress to determine next phase...");
        
        int totalOresMined = progress.getOresMined();
        int targetQuantity = config.targetQuantity();
        
        // Priority 1: Check if we haven't reached the target ore quantity yet
        if (totalOresMined < targetQuantity) {
            updateStatus("Target not reached - continuing mining (Ores: " + totalOresMined + "/" + targetQuantity + ")");
            currentPhase = ProcessPhase.WALKING; // Walk back to mining area
        } 
        // Priority 2: Target reached - check if smelting is enabled and we have ores in bank
        else if (config.smeltBars() && needsMoreOresForSmelting()) {
            updateStatus("Target reached! Moving to smelting phase - have ores in bank");
            currentPhase = ProcessPhase.WALKING; // Walk to furnace
        } 
        // Priority 3: Check if smithing is enabled and we have bars in bank
        else if (config.smithItems() && needsBarsForSmithing()) {
            updateStatus("Smelting complete! Moving to smithing phase - have bars in bank");
            currentPhase = ProcessPhase.WALKING; // Walk to anvil
        } 
        // Priority 4: All tasks completed
        else {
            updateStatus("All tasks completed!");
            currentPhase = ProcessPhase.COMPLETE;
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
        return config.smeltBars() && 
               (Rs2Bank.hasItem(config.metalType().getOreNames()[0]) || 
                hasOresForSmelting());
    }
    
    private boolean needsBarsForSmithing() {
        return config.smithItems() && Rs2Bank.hasItem(config.metalType().getBarName());
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
     * Checks if the task is complete based on target quantities
     */
    private boolean isTaskComplete() {
        // Check if we've reached target ore quantity and completed all phases
        boolean oreTargetReached = progress.getOresMined() >= config.targetQuantity();
        
        // If smelting is enabled, check if we've smelted enough bars
        boolean smeltingComplete = !config.smeltBars() || 
            progress.getBarsSmelted() >= getRequiredBars();
        
        // If smithing is enabled, check if we've smithed enough items
        boolean smithingComplete = !config.smithItems() || 
            progress.getItemsSmithed() >= getRequiredItems();
        
        return oreTargetReached && smeltingComplete && smithingComplete;
    }

    /**
     * Selects the appropriate smelting option in the furnace interface
     * @return true if option was successfully selected
     */
    private boolean selectSmeltingOption() {
        try {
            // Wait for smelting interface to appear
            if (!Rs2Widget.hasWidget("Furnace")) {
                sleep(1000, 1500);
                return false;
            }
            
            // Find the correct bar based on metal type
            String barName = config.metalType().getBarName();
            if (Rs2Widget.clickWidget(barName)) {
                updateStatus("Selected " + barName + " for smelting");
                
                // Wait for quantity interface
                sleep(800, 1200);
                
                // Select "Make All" by pressing space
                Rs2Keyboard.keyPress(' '); // Make All shortcut
                return true;
            }
            
        } catch (Exception e) {
            handleError("Failed to select smelting option", e);
        }
        return false;
    }
    
    /**
     * Waits for smelting process to complete with intelligent monitoring
     */
    private void waitForSmelting() {
        updateStatus("Smelting in progress...");
        long smeltingStartTime = System.currentTimeMillis();
        int initialOreCount = getTotalOreCount();
        
        while (Rs2Player.isAnimating() || Rs2Player.isMoving()) {
            // Monitor smelting progress
            int currentOreCount = getTotalOreCount();
            if (currentOreCount < initialOreCount) {
                progress.incrementBarsSmelted();
                initialOreCount = currentOreCount;
            }
            
            // Safety timeout (5 minutes max)
            if (System.currentTimeMillis() - smeltingStartTime > 300000) {
                handleError("Smelting timeout exceeded");
                break;
            }
            
            // Check if we're still smelting
            if (!Rs2Player.isAnimating() && !hasRequiredOres()) {
                updateStatus("Smelting completed");
                break;
            }
            
            sleep(600, 1000);
        }
    }
    
    /**
     * Selects the appropriate smithing option at the anvil
     * @return true if option was successfully selected
     */
    private boolean selectSmithingOption() {
        try {
            // Wait for smithing interface to appear
            if (!Rs2Widget.hasWidget("Anvil")) {
                sleep(1000, 1500);
                return false;
            }
            
            // Determine best item to smith based on level and efficiency
            SmithingProduct bestItem = SmithingProduct.getBestAvailableItem(
                Rs2Player.getRealSkillLevel(Skill.SMITHING)
            );
            
            // Click the selected item
            if (Rs2Widget.clickWidget(bestItem.getItemName())) {
                updateStatus("Selected " + bestItem.getItemName() + " for smithing");
                
                // Wait for quantity interface
                sleep(800, 1200);
                
                // Calculate how many we can make
                int availableBars = Rs2Inventory.count(config.metalType().getBarName());
                int maxItems = bestItem.getMaxItemsFromBars(availableBars);
                
                if (maxItems > 0) {
                    // Enter the quantity or press space for max
                    if (maxItems >= 10) {
                        Rs2Keyboard.keyPress(' '); // Make All
                    } else {
                        Rs2Keyboard.typeString(String.valueOf(maxItems));
                        Rs2Keyboard.keyPress(KeyEvent.VK_ENTER);
                    }
                    return true;
                }
            }
            
        } catch (Exception e) {
            handleError("Failed to select smithing option", e);
        }
        return false;
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
     * Intelligently determines the next walking destination based on current phase and inventory
     * @return WorldPoint of the destination, or null if no movement needed
     */
    private WorldPoint determineWalkingDestination() {
        updateStatus("Determining walking destination for phase: " + currentPhase);
        
        switch (currentPhase) {
            case WALKING:
                // If we're in WALKING phase, determine where we should go based on what we need to do next
                ProcessPhase nextPhase = determineNextPhase();
                updateStatus("Walking phase - next intended phase: " + nextPhase);
                
                switch (nextPhase) {
                    case MINING:
                        // CRITICAL: Double-check target before walking back to mining
                        int totalOresMined = progress.getOresMined();
                        int targetQuantity = config.targetQuantity();
                        
                        if (totalOresMined >= targetQuantity) {
                            updateStatus("Target mining quantity reached - not walking to mining area");
                            // Force transition to next appropriate phase instead
                            if (config.smeltBars()) {
                                currentPhase = ProcessPhase.SMELTING;
                                return FURNACE_LOCATION;
                            } else if (config.smithItems()) {
                                currentPhase = ProcessPhase.SMITHING;
                                return ANVIL_LOCATION;
                            } else {
                                currentPhase = ProcessPhase.COMPLETE;
                                return null;
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
                updateStatus("Walking to furnace");
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
                updateStatus("No destination needed for phase: " + currentPhase);
                return null;
        }
    }
    
    /**
     * Gets a human-readable name for a location
     */
    private String getLocationName(WorldPoint point) {
        if (point.equals(LUMBRIDGE_MINING_AREA)) return "Lumbridge Mining Area";
        if (point.equals(AL_KHARID_MINING_AREA)) return "Al Kharid Mining Area";
        if (point.equals(FURNACE_LOCATION)) return "Al Kharid Furnace";
        if (point.equals(ANVIL_LOCATION)) return "Varrock Anvil";
        
        // Check if this point matches any bank location
        BankLocation nearestBank = getNearestBank();
        if (nearestBank != null && point.equals(nearestBank.getWorldPoint())) {
            return nearestBank.toString() + " Bank";
        }
        
        // Fallback for legacy bank coordinates
        if (point.equals(LUMBRIDGE_BANK)) return "Lumbridge Bank";
        if (point.equals(AL_KHARID_BANK)) return "Al Kharid Bank";
        
        return "Mining Area";
    }
    
    /**
     * Updates the current phase after reaching a walking destination
     */
    private void updatePhaseAfterWalking(WorldPoint destination) {
        if (destination.equals(LUMBRIDGE_MINING_AREA) || destination.equals(AL_KHARID_MINING_AREA) || destination.equals(currentMiningArea)) {
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
     */
    private int getRequiredBars() {
        return config.targetQuantity() / config.metalType().getTotalOreCount(1);
    }
    
    /**
     * Calculates required number of items based on bars and smithing efficiency
     */
    private int getRequiredItems() {
        int bars = getRequiredBars();
        SmithingProduct bestItem = SmithingProduct.getBestAvailableItem(
            Rs2Player.getRealSkillLevel(Skill.SMITHING)
        );
        return bestItem.getMaxItemsFromBars(bars);
    }
    
    // Additional expert-level helper methods
    
    /**
     * Counts total ores in inventory for current metal type
     */
    private int getTotalOreCount() {
        int totalCount = 0;
        for (String oreName : config.metalType().getOreNames()) {
            totalCount += Rs2Inventory.count(oreName);
        }
        return totalCount;
    }
    
    /**
     * Checks if we have enough ores for at least one smelting operation
     */
    private boolean hasOresForSmelting() {
        return config.metalType().hasRequiredOres();
    }
    
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
     * Progress tracking class
     */
    @Getter
    public static class ProgressTracker {
        private Instant startTime;
        private int oresMined = 0;
        private int barsSmelted = 0;
        private int itemsSmithed = 0;
        private int miningXpGained = 0;
        private int smithingXpGained = 0;

        public void setStartTime(Instant startTime) {
            this.startTime = startTime;
        }

        public void incrementOresMined() {
            this.oresMined++;
        }

        public void incrementBarsSmelted() {
            this.barsSmelted++;
        }

        public void incrementItemsSmithed() {
            this.itemsSmithed++;
        }

        public void addMiningXp(int xp) {
            this.miningXpGained += xp;
        }

        public void addSmithingXp(int xp) {
            this.smithingXpGained += xp;
        }
    }
}
