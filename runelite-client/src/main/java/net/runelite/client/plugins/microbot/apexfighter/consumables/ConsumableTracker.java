package net.runelite.client.plugins.microbot.apexfighter.consumables;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.apexfighter.CostTracker;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;

import java.util.*;

/**
 * Tracks consumable items and automatically detects what should be tracked for cost calculation.
 * This system is modular and doesn't rely on hardcoded item IDs.
 */
public class ConsumableTracker {
    private static final ConsumableTracker INSTANCE = new ConsumableTracker();
    
    private ConsumableTracker() {}
    
    public static ConsumableTracker getInstance() {
        return INSTANCE;
    }
    
    /**
     * Initializes GE prices for all consumable items currently in the player's inventory.
     * This should be called on the client thread at session start.
     */
    public void initializeConsumablePrices() {
        // Get all inventory items
        var allItems = Rs2Inventory.all();
        
        for (Rs2ItemModel item : allItems) {
            if (item.getId() <= 0) continue;
            
            if (isTrackableConsumable(item)) {
                try {
                    int gePrice = Microbot.getItemManager().getItemPrice(item.getId());
                    CostTracker.getInstance().setGEPrice(item.getId(), gePrice);
                } catch (Exception e) {
                    Microbot.log("Failed to get price for item: " + item.getName() + " (" + item.getId() + ")");
                }
            }
        }
    }
    
    /**
     * Checks if an item should be tracked as a consumable for cost calculation.
     * This includes food, potions, ammunition, runes, and other consumables.
     */
    public boolean isTrackableConsumable(Rs2ItemModel item) {
        if (item == null || item.getId() <= 0) return false;
        
        return isFood(item) || 
               isPotion(item) || 
               isAmmunition(item) || 
               isRune(item) || 
               isTeleportItem(item) ||
               isOtherConsumable(item) ||
               isConsumableByItemProperties(item);
    }
    
    /**
     * Advanced consumable detection based on item composition properties.
     * This catches items that might not be detected by name/action patterns.
     */
    public boolean isConsumableByItemProperties(Rs2ItemModel item) {
        if (item.isNoted()) return false;
        
        try {
            // Get the item composition for more detailed analysis
            var composition = Microbot.getItemManager().getItemComposition(item.getId());
            String name = composition.getName().toLowerCase();
            String[] actions = composition.getInventoryActions();
            
            // Items that are stackable and have low item values are often consumables
            boolean isLowValueStackable = item.isStackable() && composition.getPrice() > 0 && composition.getPrice() < 1000;
            
            // Items with "destroy" option are often consumables or temporary items
            boolean hasDestroyAction = actions != null && Arrays.stream(actions)
                    .anyMatch(action -> action != null && action.toLowerCase().contains("destroy"));
            
            // Items that are not equipable but have active actions are often consumables
            boolean isActiveNonEquipable = !isEquipable(composition) && hasActiveActions(actions);
            
            // Items with specific consumable keywords in their examine text or name
            boolean hasConsumableKeywords = hasConsumableKeywords(name);
            
            return (isLowValueStackable && hasActiveActions(actions)) ||
                   (hasDestroyAction && !name.contains("key") && !name.contains("scroll")) ||
                   (isActiveNonEquipable && composition.getPrice() < 10000) ||
                   hasConsumableKeywords;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Checks if an item is equipable based on its composition.
     */
    private boolean isEquipable(net.runelite.api.ItemComposition composition) {
        String[] actions = composition.getInventoryActions();
        return actions != null && Arrays.stream(actions)
                .anyMatch(action -> action != null && 
                        (action.equalsIgnoreCase("Wear") || 
                         action.equalsIgnoreCase("Wield") ||
                         action.equalsIgnoreCase("Equip")));
    }
    
    /**
     * Checks if an item has actions that suggest it's consumable.
     */
    private boolean hasActiveActions(String[] actions) {
        if (actions == null) return false;
        
        return Arrays.stream(actions)
                .anyMatch(action -> action != null && !action.equals("Drop") && !action.equals("Examine") && 
                        !action.equals("") && !action.equalsIgnoreCase("null"));
    }
    
    /**
     * Checks for consumable keywords that indicate the item is likely consumed.
     */
    private boolean hasConsumableKeywords(String name) {
        return name.contains("dose") ||
               name.contains("charge") ||
               name.contains("tablet") ||
               name.contains("scroll") && !name.contains("clue") ||
               name.contains("seed") ||
               name.contains("sapling") ||
               name.contains("compost") ||
               name.contains("potion") ||
               name.contains("elixir") ||
               name.contains("tonic") ||
               name.contains("brew") ||
               name.contains("mixture") ||
               name.contains("powder") ||
               name.contains("dust") && !name.contains("cosmic") ||
               name.contains("crystal") && (name.contains("seed") || name.contains("shard"));
    }
    
    /**
     * Checks if an item is food.
     */
    public boolean isFood(Rs2ItemModel item) {
        if (item.isNoted()) return false;
        
        // Use the existing isFood method from Rs2ItemModel
        try {
            return item.isFood();
        } catch (Exception e) {
            // Fallback to name-based detection
            String name = item.getName().toLowerCase();
            return Arrays.stream(item.getInventoryActions())
                    .anyMatch(action -> action != null && action.equalsIgnoreCase("eat")) ||
                   name.contains("jug of wine");
        }
    }
    
    /**
     * Checks if an item is a potion (drinkable).
     */
    public boolean isPotion(Rs2ItemModel item) {
        if (item.isNoted()) return false;
        
        try {
            String[] actions = item.getInventoryActions();
            return actions != null && Arrays.stream(actions)
                    .anyMatch(action -> action != null && action.equalsIgnoreCase("drink"));
        } catch (Exception e) {
            // Fallback to name-based detection
            String name = item.getName().toLowerCase();
            return name.contains("potion") || name.contains("dose") || name.contains("barbarian");
        }
    }
    
    /**
     * Checks if an item is ammunition (arrows, bolts, darts, etc.).
     */
    public boolean isAmmunition(Rs2ItemModel item) {
        if (item.isNoted()) return false;
        
        try {
            String name = item.getName().toLowerCase();
            String[] actions = item.getInventoryActions();
            
            // Check if it's stackable ammunition (most ammo is stackable)
            if (!item.isStackable()) {
                // Some special ammunition might not be stackable, check by name and actions
                return isProjectileAmmo(name, actions);
            }
            
            // Check for common ammunition patterns
            return isProjectileAmmo(name, actions) ||
                   name.contains("cannonball") ||
                   name.contains("granite") && (name.contains("dust") || name.contains("cannonball"));
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Helper method to identify projectile ammunition by name and actions.
     */
    private boolean isProjectileAmmo(String name, String[] actions) {
        // Check for ammunition keywords
        boolean hasAmmoName = name.contains("arrow") ||
                             name.contains("bolt") ||
                             name.contains("dart") ||
                             name.contains("javelin") ||
                             name.contains("knife") && (name.contains("throwing") || name.contains("iron") || name.contains("steel") || name.contains("mithril") || name.contains("adamant") || name.contains("rune")) ||
                             name.contains("chinchompa") ||
                             name.contains("throwing") ||
                             (name.contains("shot") && !name.contains("shortbow")) ||
                             name.contains("bone") && (name.contains("bolt") || name.contains("crossbow")) ||
                             name.contains("broad") && (name.contains("bolt") || name.contains("arrow"));
        
        // Check for "Wield" action which is common for ammunition
        boolean hasWieldAction = actions != null && Arrays.stream(actions)
                .anyMatch(action -> action != null && action.equalsIgnoreCase("Wield"));
        
        return hasAmmoName || (hasWieldAction && name.contains("ammunition"));
    }
    
    /**
     * Checks if an item is a rune.
     */
    public boolean isRune(Rs2ItemModel item) {
        if (item.isNoted()) return false;
        
        try {
            String name = item.getName().toLowerCase();
            String[] actions = item.getInventoryActions();
            
            // Primary check: item name contains "rune" but exclude non-rune items
            boolean isRuneName = name.contains("rune") && 
                                !name.contains("runite") && 
                                !name.contains("pouch") && 
                                !name.contains("pickaxe") && 
                                !name.contains("axe") && 
                                !name.contains("sword") && 
                                !name.contains("dagger") && 
                                !name.contains("scimitar");
            
            // Secondary check: stackable items that are commonly used for magic
            boolean isMagicConsumable = item.isStackable() && actions != null && 
                                      Arrays.stream(actions).anyMatch(action -> action != null && 
                                              action.toLowerCase().contains("cast"));
            
            return isRuneName || isMagicConsumable;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Checks if an item is a teleport item (tablets, jewelry, etc.).
     */
    public boolean isTeleportItem(Rs2ItemModel item) {
        if (item.isNoted()) return false;
        
        try {
            String name = item.getName().toLowerCase();
            String[] actions = item.getInventoryActions();
            
            // Check for teleport actions
            boolean hasTeleportAction = actions != null && Arrays.stream(actions)
                    .anyMatch(action -> action != null && 
                            (action.toLowerCase().contains("teleport") || 
                             action.toLowerCase().contains("break") ||
                             action.toLowerCase().contains("rub")));
            
            // Check for teleport item names
            boolean isTeleportName = name.contains("tablet") ||
                                   name.contains("teleport") ||
                                   (name.contains("ring") && (name.contains("dueling") || name.contains("games") || name.contains("wealth"))) ||
                                   (name.contains("amulet") && name.contains("glory")) ||
                                   name.contains("ectophial") ||
                                   name.contains("chronicle");
            
            return hasTeleportAction || isTeleportName;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Checks if an item is another type of consumable that should be tracked.
     */
    public boolean isOtherConsumable(Rs2ItemModel item) {
        if (item.isNoted()) return false;
        
        try {
            String name = item.getName().toLowerCase();
            String[] actions = item.getInventoryActions();
            
            // Check for consumable actions that indicate usage
            boolean hasConsumableAction = actions != null && Arrays.stream(actions)
                    .anyMatch(action -> action != null && 
                            (action.toLowerCase().contains("light") ||    // candles, torches
                             action.toLowerCase().contains("bury") ||     // bones (if not auto-burying)
                             action.toLowerCase().contains("scatter") ||  // ashes (if not auto-scattering)
                             action.toLowerCase().contains("use") ||      // general consumables
                             action.toLowerCase().contains("cast") ||     // spell components
                             action.toLowerCase().contains("apply") ||    // salves, potions
                             action.toLowerCase().contains("activate"))); // special items
            
            // Check for consumable item patterns by name
            boolean isConsumableName = isLightSource(name) ||
                                     isSpecialPotion(name) ||
                                     isBonesOrAshes(name) ||
                                     isCraftingMaterial(name) ||
                                     isTemporaryBoost(name);
            
            return hasConsumableAction || isConsumableName;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Checks if item is a light source that gets consumed.
     */
    private boolean isLightSource(String name) {
        return name.contains("candle") ||
               name.contains("torch") ||
               name.contains("lamp") && !name.contains("genie") ||
               name.contains("lantern");
    }
    
    /**
     * Checks if item is a special potion or consumable.
     */
    private boolean isSpecialPotion(String name) {
        return name.contains("antifire") ||
               name.contains("energy") ||
               name.contains("stamina") ||
               name.contains("restore") ||
               name.contains("prayer") ||
               name.contains("combat") ||
               name.contains("ranging") ||
               name.contains("magic") ||
               name.contains("strength") ||
               name.contains("attack") ||
               name.contains("defence") ||
               name.contains("agility") ||
               name.contains("fishing") ||
               name.contains("hunter") ||
               name.contains("saradomin") ||
               name.contains("zamorak") ||
               name.contains("guthix") ||
               name.contains("barbarian") ||
               name.contains("relicym");
    }
    
    /**
     * Checks if item is bones or ashes.
     */
    private boolean isBonesOrAshes(String name) {
        return name.contains("bone") ||
               name.contains("ash") ||
               name.contains("ashes");
    }
    
    /**
     * Checks if item is a crafting material that gets consumed.
     */
    private boolean isCraftingMaterial(String name) {
        return (name.contains("thread") && !name.contains("needle")) ||
               name.contains("chisel") ||
               name.contains("needle") ||
               name.contains("pestle") ||
               name.contains("vial") ||
               name.contains("essence") ||
               name.contains("talisman") ||
               name.contains("tiara");
    }
    
    /**
     * Checks if item provides temporary boost that gets consumed.
     */
    private boolean isTemporaryBoost(String name) {
        return name.contains("pie") ||
               name.contains("stew") ||
               name.contains("cake") ||
               name.contains("bread") ||
               name.contains("wine") ||
               name.contains("ale") ||
               name.contains("beer") ||
               name.contains("cider") ||
               name.contains("mature");
    }
    
    /**
     * Gets all known food item IDs from the Rs2Food enum for comparison.
     */
    public Set<Integer> getKnownFoodIds() {
        return Rs2Food.getIds();
    }
    
    /**
     * Gets all trackable consumable items currently in the player's inventory.
     * This is useful for initializing tracking or analyzing current consumable usage.
     */
    public Map<Integer, Rs2ItemModel> getCurrentTrackableConsumables() {
        Map<Integer, Rs2ItemModel> trackableItems = new HashMap<>();
        
        for (Rs2ItemModel item : Rs2Inventory.all()) {
            if (isTrackableConsumable(item)) {
                trackableItems.put(item.getId(), item);
            }
        }
        
        return trackableItems;
    }
    
    /**
     * Gets a categorized breakdown of all trackable consumables in inventory.
     * Useful for debugging or advanced tracking features.
     */
    public Map<String, List<Rs2ItemModel>> getCategorizedConsumables() {
        Map<String, List<Rs2ItemModel>> categories = new HashMap<>();
        categories.put("Food", new ArrayList<>());
        categories.put("Potions", new ArrayList<>());
        categories.put("Ammunition", new ArrayList<>());
        categories.put("Runes", new ArrayList<>());
        categories.put("Teleports", new ArrayList<>());
        categories.put("Other", new ArrayList<>());
        
        for (Rs2ItemModel item : Rs2Inventory.all()) {
            if (isFood(item)) {
                categories.get("Food").add(item);
            } else if (isPotion(item)) {
                categories.get("Potions").add(item);
            } else if (isAmmunition(item)) {
                categories.get("Ammunition").add(item);
            } else if (isRune(item)) {
                categories.get("Runes").add(item);
            } else if (isTeleportItem(item)) {
                categories.get("Teleports").add(item);
            } else if (isOtherConsumable(item) || isConsumableByItemProperties(item)) {
                categories.get("Other").add(item);
            }
        }
        
        return categories;
    }
    
    /**
     * Tracks the usage of a consumable item.
     */
    public void trackConsumableUsage(int itemId, int quantity) {
        CostTracker.getInstance().addUsage(itemId, quantity);
    }
    
    /**
     * Updates the GE price for a specific item.
     */
    public void updateItemPrice(int itemId) {
        try {
            int gePrice = Microbot.getItemManager().getItemPrice(itemId);
            CostTracker.getInstance().setGEPrice(itemId, gePrice);
        } catch (Exception e) {
            Microbot.log("Failed to update price for item ID: " + itemId);
        }
    }
}
