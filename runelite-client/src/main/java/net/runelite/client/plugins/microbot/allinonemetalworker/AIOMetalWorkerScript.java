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
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
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
    private boolean shouldStop = false;
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
            
            // Main execution loop
            while (!shouldStop && super.run()) {
                try {
                    executeCurrentPhase();
                    
                    // Check completion conditions
                    if (isTaskComplete()) {
                        currentPhase = ProcessPhase.COMPLETE;
                        Microbot.log("Task completed successfully!");
                        shouldStop = true;
                        break;
                    }
                    
                    // Anti-ban sleep
                    if (config.enableAntiban()) {
                        Rs2Antiban.actionCooldown();
                    } else {
                        sleep(config.actionDelay(), config.actionDelay() + 100);
                    }
                    
                } catch (Exception e) {
                    handleError("Error in main execution loop", e);
                }
            }
            
        } catch (Exception e) {
            handleError("Critical error in script execution", e);
        }
        
        return false;
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
        
        // Auto-detect current mining area if not set
        if (currentMiningArea == null) {
            currentMiningArea = detectCurrentMiningArea();
        }
        
        // Check if we're near a suitable mining area
        if (currentMiningArea == null || !isNearMiningArea()) {
            // Try to find nearby ores instead of walking to hardcoded location
            if (!hasNearbyOres()) {
                handleError("No suitable mining area found nearby. Please move to a mining location with " + 
                          config.metalType().getDisplayName() + " ores.");
                currentPhase = ProcessPhase.ERROR;
                return;
            }
        }
        
        // Check if inventory is full
        if (Rs2Inventory.isFull()) {
            currentPhase = ProcessPhase.BANKING;
            return;
        }
        
        // Ensure we have a pickaxe
        if (!hasPickaxe()) {
            handleError("No pickaxe found in inventory or equipped");
            currentPhase = ProcessPhase.BANKING; // Try to get pickaxe from bank
            return;
        }
        
        // Smart ore mining based on metal type requirements
        if (mineRequiredOres()) {
            // Successfully mining, wait for animation
            Rs2Player.waitForAnimation();
        } else {
            // No ore found or failed to start mining, search for alternative spots
            searchForAlternativeOres();
        }
        
        // Anti-ban random mouse movement
        if (config.enableAntiban() && Math.random() < 0.05) {
            Rs2Antiban.moveMouseRandomly();
        }
    }
    
    /**
     * Detects the current mining area based on nearby ore availability
     */
    private WorldPoint detectCurrentMiningArea() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        
        // Check if we're near any known mining areas
        if (playerLocation.distanceTo(LUMBRIDGE_MINING_AREA) <= MINING_RADIUS) {
            return LUMBRIDGE_MINING_AREA;
        }
        if (playerLocation.distanceTo(AL_KHARID_MINING_AREA) <= MINING_RADIUS) {
            return AL_KHARID_MINING_AREA;
        }
        
        // If not near known areas, check if there are suitable ores nearby
        if (hasNearbyOres()) {
            return playerLocation; // Use current location as mining area
        }
        
        return null;
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
            if (Rs2GameObject.findObject(oreName, false, 15, false, Rs2Player.getWorldLocation()) != null) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Intelligently mines ores based on metal type requirements and current inventory ratios
     */
    private boolean mineRequiredOres() {
        // Get current ore counts in inventory
        Map<String, Integer> currentOres = getCurrentOreInventory();
        
        // Determine which ore to prioritize based on requirements
        String[] oreNames = config.metalType().getOreNames();
        
        for (String oreName : oreNames) {
            // Check if we need more of this ore type based on required ratios
            if (needsMoreOre(oreName, currentOres)) {
                if (Rs2GameObject.interact(oreName, "Mine")) {
                    updateStatus("Mining " + oreName + "...");
                    return true;
                }
            }
        }
        
        // If no specific ore needed, mine any available ore for this metal type
        for (String oreName : oreNames) {
            if (Rs2GameObject.interact(oreName, "Mine")) {
                updateStatus("Mining " + oreName + "...");
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
        
        // Walk to nearest bank based on current location
        WorldPoint bankLocation = getNearestBank();
        if (!Rs2Bank.isOpen() && Rs2Player.getWorldLocation().distanceTo(bankLocation) > 5) {
            currentPhase = ProcessPhase.WALKING;
            return;
        }
        
        // Open bank
        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) {
                sleep(1000, 2000);
                return;
            }
        }
        
        // Banking logic based on current needs
        if (currentPhase == ProcessPhase.BANKING) {
            handleBankingOperations();
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
            
            if (Rs2Walker.walkTo(destination)) {
                // Wait for arrival
                Rs2Player.waitForWalking();
                
                // Update phase based on destination
                updatePhaseAfterWalking(destination);
            }
        } else {
            handleError("Unable to determine walking destination");
        }
    }

    /**
     * Error handling phase
     */
    private void handleErrorPhase() {
        updateStatus("Handling error state");
        
        failedActionCount++;
        if (failedActionCount >= config.maxFailedActions()) {
            Microbot.log("Too many failed actions, stopping script");
            shouldStop = true;
            return;
        }
        
        // Try to recover
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
        }
        
        // Reset to mining phase and try again
        currentPhase = ProcessPhase.MINING;
        sleep(2000, 4000);
    }

    // Helper Methods

    /**
     * Checks if the task is complete
     */
    private boolean isTaskComplete() {
        return progress.getOresMined() >= config.targetQuantity() && 
               (!config.smeltBars() || progress.getBarsSmelted() >= getRequiredBars()) &&
               (!config.smithItems() || progress.getItemsSmithed() >= getRequiredItems());
    }

    /**
     * Handles banking operations based on current needs with intelligent item management
     */
    private void handleBankingOperations() {
        updateStatus("Processing banking operations...");
        
        try {
            // First, deposit all items except tools
            depositItemsExceptTools();
            sleep(600, 1000);
            
            // Withdraw necessary tools based on next planned activity
            withdrawRequiredTools();
            
            // Withdraw required items for next phase
            withdrawRequiredItems();
            
            // Determine next phase based on current progress and config
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
        // Withdraw pickaxe if needed and not equipped
        if (config.withdrawPickaxe() && !hasPickaxe()) {
            if (!withdrawBestPickaxe()) {
                Rs2Bank.withdrawOne("Bronze pickaxe"); // Fallback
            }
        }
        
        // Withdraw hammer if needed for smithing
        if (config.smithItems() && !hasHammer()) {
            Rs2Bank.withdrawOne("Hammer");
        }
        
        // Withdraw special equipment if enabled
        if (config.useSpecialEquipment()) {
            withdrawSpecialEquipment();
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
        if (needsMoreOresForSmelting()) {
            withdrawOresForSmelting();
        } else if (needsBarsForSmithing()) {
            withdrawBarsForSmithing();
        }
    }
    
    /**
     * Withdraws ores needed for smelting
     */
    private void withdrawOresForSmelting() {
        updateStatus("Withdrawing ores for smelting...");
        
        Map<String, Integer> requiredOres = calculateRequiredOres();
        
        for (Map.Entry<String, Integer> entry : requiredOres.entrySet()) {
            String oreName = entry.getKey();
            int required = entry.getValue();
            
            if (Rs2Bank.hasItem(oreName)) {
                Rs2Bank.withdrawX(oreName, required);
                sleep(300, 600);
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
        if (needsMoreOres()) {
            currentPhase = ProcessPhase.MINING;
        } else if (config.smeltBars() && needsMoreOresForSmelting()) {
            currentPhase = ProcessPhase.SMELTING;
        } else if (config.smithItems() && needsBarsForSmithing()) {
            currentPhase = ProcessPhase.SMITHING;
        } else {
            currentPhase = ProcessPhase.COMPLETE;
        }
        
        updateStatus("Next phase: " + currentPhase);
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
     * Determines the nearest bank based on current location
     */
    private WorldPoint getNearestBank() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        
        if (playerLocation.distanceTo(AL_KHARID_BANK) < playerLocation.distanceTo(LUMBRIDGE_BANK)) {
            return AL_KHARID_BANK;
        }
        return LUMBRIDGE_BANK;
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
        switch (currentPhase) {
            case MINING:
                // Return current mining area or stay where we are if mining is possible
                if (currentMiningArea != null) {
                    return currentMiningArea;
                } else if (hasNearbyOres()) {
                    return null; // No need to walk, ores are nearby
                } else {
                    // Default to Al Kharid mining for iron
                    return AL_KHARID_MINING_AREA;
                }
                
            case SMELTING:
                return FURNACE_LOCATION;
                
            case SMITHING:
                return ANVIL_LOCATION;
                
            case BANKING:
                // Choose bank based on next planned activity
                if (needsMoreOres()) {
                    return AL_KHARID_BANK; // Closer to Al Kharid mining area
                } else if (config.smeltBars() && hasOresForSmelting()) {
                    return AL_KHARID_BANK; // Closer to furnace
                } else {
                    return AL_KHARID_BANK; // Default to Al Kharid
                }
                
            default:
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
        if (point.equals(LUMBRIDGE_BANK)) return "Lumbridge Bank";
        if (point.equals(AL_KHARID_BANK)) return "Al Kharid Bank";
        return "Mining Area";
    }
    
    /**
     * Updates the current phase after reaching a walking destination
     */
    private void updatePhaseAfterWalking(WorldPoint destination) {
        if (destination.equals(LUMBRIDGE_MINING_AREA) || destination.equals(AL_KHARID_MINING_AREA)) {
            currentPhase = ProcessPhase.MINING;
            currentMiningArea = destination; // Set the current mining area
        } else if (destination.equals(FURNACE_LOCATION)) {
            currentPhase = ProcessPhase.SMELTING;
        } else if (destination.equals(ANVIL_LOCATION)) {
            currentPhase = ProcessPhase.SMITHING;
        } else if (destination.equals(LUMBRIDGE_BANK) || destination.equals(AL_KHARID_BANK)) {
            currentPhase = ProcessPhase.BANKING;
        } else {
            // If destination is current mining area, switch to mining
            currentPhase = ProcessPhase.MINING;
        }
    }
    
    /**
     * Checks if we need to mine more ores based on target quantity and current progress
     */
    private boolean needsMoreOres() {
        return progress.getOresMined() < config.targetQuantity() || 
               !hasMinimumOresForOperation();
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
    
    /**
     * Checks if we have minimum ores needed to continue operation efficiently
     */
    private boolean hasMinimumOresForOperation() {
        int currentOres = getTotalOreCount();
        int minimumRequired = config.metalType().getTotalOreCount(5); // At least 5 bars worth
        return currentOres >= minimumRequired;
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
        if (config.enableDebugLogs()) {
            Microbot.log("Mining successful - total ores: " + progress.getOresMined());
        }
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
