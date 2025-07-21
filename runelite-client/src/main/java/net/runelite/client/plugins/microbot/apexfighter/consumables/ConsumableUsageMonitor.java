package net.runelite.client.plugins.microbot.apexfighter.consumables;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig;
import net.runelite.client.plugins.microbot.apexfighter.CostTracker;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors inventory changes and tracks consumable usage for cost calculation.
 * This integrates with the existing inventory change tracking in ApexFighterPlugin.
 */
public class ConsumableUsageMonitor {
    private static final ConsumableUsageMonitor INSTANCE = new ConsumableUsageMonitor();
    private final Map<Integer, Integer> lastKnownQuantities = new ConcurrentHashMap<>();
    private final ConsumableTracker tracker = ConsumableTracker.getInstance();
    
    private ConsumableUsageMonitor() {}
    
    public static ConsumableUsageMonitor getInstance() {
        return INSTANCE;
    }
    
    /**
     * Updates the tracking state with current inventory contents.
     * Should be called whenever inventory changes are detected.
     */
    public void updateInventoryState(Map<Integer, Integer> currentInventory, ApexFighterConfig config) {
        // Compare current inventory with last known state
        for (Map.Entry<Integer, Integer> entry : currentInventory.entrySet()) {
            int itemId = entry.getKey();
            int currentQty = entry.getValue();
            int lastQty = lastKnownQuantities.getOrDefault(itemId, 0);
            
            if (currentQty < lastQty) {
                // Item quantity decreased - this might be consumption
                int consumed = lastQty - currentQty;
                handlePotentialConsumption(itemId, consumed, config);
            }
        }
        
        // Check for items that were completely removed from inventory
        for (Map.Entry<Integer, Integer> entry : lastKnownQuantities.entrySet()) {
            int itemId = entry.getKey();
            if (!currentInventory.containsKey(itemId)) {
                // Item no longer in inventory
                int consumed = entry.getValue();
                handlePotentialConsumption(itemId, consumed, config);
            }
        }
        
        // Update our tracking state
        lastKnownQuantities.clear();
        lastKnownQuantities.putAll(currentInventory);
    }
    
    /**
     * Handles when an item quantity decreases, determining if it should be tracked as consumption.
     */
    private void handlePotentialConsumption(int itemId, int quantityDecrease, ApexFighterConfig config) {
        try {
            // Get item information to determine what type of consumption this is
            var itemComposition = Microbot.getItemManager().getItemComposition(itemId);
            String itemName = itemComposition.getName();
            if (itemName == null) return;
            
            // Create a temporary Rs2ItemModel for checking item type
            Rs2ItemModel tempItem = createTempItemModel(itemId, itemName, itemComposition);
            
            // Only track as a cost if the item was actually consumed/lost, not banked
            if (shouldTrackConsumption(tempItem, config) && !wasItemBanked(itemId, quantityDecrease, tempItem)) {
                tracker.trackConsumableUsage(itemId, quantityDecrease);
                if (CostTracker.getInstance().getGEPrice(itemId) == 0) {
                    tracker.updateItemPrice(itemId);
                }
                String category = getConsumableCategory(tempItem);
                Microbot.log("Tracked " + category + " consumption: " + quantityDecrease + "x " + itemName);
            }
        } catch (Exception e) {
            Microbot.log("Error tracking consumption for item " + itemId + ": " + e.getMessage());
        }
    }

    /**
     * Determines if the item was banked (deposited) rather than consumed/lost.
     * This should be replaced with a more robust context-aware check if available.
     */
    private boolean wasItemBanked(int itemId, int quantityDecrease, Rs2ItemModel item) {
        // TODO: Replace with a more robust check if banking context is available
        // For now, we assume that if the player is at the bank interface, items are being banked
        // You may want to add a flag or context from the plugin to indicate banking actions
        return net.runelite.client.plugins.microbot.util.bank.Rs2Bank.isOpen();
    }
    
    /**
     * Gets the category of a consumable item for logging purposes.
     */
    private String getConsumableCategory(Rs2ItemModel item) {
        if (tracker.isFood(item)) return "Food";
        if (tracker.isPotion(item)) return "Potion";
        if (tracker.isAmmunition(item)) return "Ammunition";
        if (tracker.isRune(item)) return "Rune";
        if (tracker.isTeleportItem(item)) return "Teleport";
        return "Other Consumable";
    }
    
    /**
     * Determines if a decrease in item quantity should be tracked as consumption.
     */
    private boolean shouldTrackConsumption(Rs2ItemModel item, ApexFighterConfig config) {
        if (!tracker.isTrackableConsumable(item)) {
            return false;
        }
        
        // Don't track items that are specifically handled by other systems
        if (isBone(item) && config.toggleBuryBones()) {
            return false; // Bones are buried, not consumed for profit
        }
        
        if (isAsh(item) && config.toggleScatter()) {
            return false; // Ashes are scattered, not consumed for profit
        }
        
        return true;
    }
    
    /**
     * Creates a temporary Rs2ItemModel for item type checking.
     */
    private Rs2ItemModel createTempItemModel(int itemId, String itemName, net.runelite.api.ItemComposition composition) {
        try {
            // Use the cache constructor and override methods to provide the composition data
            return new Rs2ItemModel(new net.runelite.api.Item(itemId, 1), composition, 0) {
                @Override
                public String getName() {
                    return itemName;
                }
            };
        } catch (Exception e) {
            // Fallback: create a minimal item model using the cache method
            return Rs2ItemModel.createFromCache(itemId, 1, 0);
        }
    }
    
    /**
     * Checks if an item is a bone by examining its actions.
     */
    private boolean isBone(Rs2ItemModel item) {
        try {
            String[] actions = item.getInventoryActions();
            if (actions != null) {
                for (String action : actions) {
                    if (action != null && action.equalsIgnoreCase("Bury")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Fallback to name check
        }
        
        String name = item.getName().toLowerCase();
        return name.contains("bone") || name.contains("bones");
    }
    
    /**
     * Checks if an item is ash by examining its actions.
     */
    private boolean isAsh(Rs2ItemModel item) {
        try {
            String[] actions = item.getInventoryActions();
            if (actions != null) {
                for (String action : actions) {
                    if (action != null && action.equalsIgnoreCase("Scatter")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Fallback to name check
        }
        
        String name = item.getName().toLowerCase();
        return name.contains("ash") || name.contains("ashes");
    }
    
    /**
     * Initializes the monitor with current inventory state.
     */
    public void initialize(Map<Integer, Integer> currentInventory) {
        lastKnownQuantities.clear();
        lastKnownQuantities.putAll(currentInventory);
    }
    
    /**
     * Resets the monitor state.
     */
    public void reset() {
        lastKnownQuantities.clear();
    }
    
    /**
     * Logs all currently trackable consumables in inventory for debugging purposes.
     */
    public void logCurrentTrackables() {
        Map<String, List<Rs2ItemModel>> categorized = tracker.getCategorizedConsumables();
        
        Microbot.log("=== Current Trackable Consumables ===");
        for (Map.Entry<String, List<Rs2ItemModel>> entry : categorized.entrySet()) {
            String category = entry.getKey();
            List<Rs2ItemModel> items = entry.getValue();
            
            if (!items.isEmpty()) {
                Microbot.log(category + " (" + items.size() + " items):");
                for (Rs2ItemModel item : items) {
                    Microbot.log("  - " + item.getName() + " x" + item.getQuantity());
                }
            }
        }
        Microbot.log("=====================================");
    }
}
