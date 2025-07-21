# Modular Consumable Tracking System

This system automatically detects and tracks the usage of consumable items without requiring hardcoded item ID lists. It intelligently categorizes items based on their properties, actions, and names.

## Key Features

### 1. Dynamic Detection
- **Food**: Automatically detects items with "Eat" action or uses Rs2Food.isFood()
- **Potions**: Detects items with "Drink" action or potion-related names
- **Ammunition**: Identifies stackable projectiles (arrows, bolts, darts, etc.)
- **Runes**: Detects magic runes and components with "Cast" actions
- **Teleport Items**: Finds items with teleport actions or teleport-related names
- **Other Consumables**: Advanced detection for light sources, crafting materials, etc.

### 2. Advanced Property-Based Detection
The system also analyzes item properties to catch consumables that might be missed by name/action patterns:
- Low-value stackable items with active actions
- Items with "Destroy" actions (temporary consumables)
- Non-equipable items with active interactions
- Items with consumable keywords (dose, charge, tablet, etc.)

### 3. Automatic Cost Tracking
- Automatically fetches GE prices for detected consumables
- Tracks quantity usage over time
- Calculates total consumption costs per session

## Usage

The system works automatically once initialized. To use it:

```java
// Initialize prices (call on client thread)
ConsumableTracker.getInstance().initializeConsumablePrices();

// Track inventory changes
ConsumableUsageMonitor.getInstance().updateInventoryState(currentInventory, config);

// Get categorized breakdown
Map<String, List<Rs2ItemModel>> categorized = ConsumableTracker.getInstance().getCategorizedConsumables();

// Log current trackables for debugging
ConsumableUsageMonitor.getInstance().logCurrentTrackables();
```

## Benefits

1. **No Hardcoded Lists**: Automatically adapts to new items and updates
2. **Comprehensive Coverage**: Catches items that might be missed by static lists
3. **Category Awareness**: Provides detailed categorization for better analysis
4. **User-Friendly**: Works transparently without user configuration
5. **Debugging Support**: Includes logging and diagnostic features

## Categories Tracked

- **Food**: All edible items that restore health
- **Potions**: All drinkable potions and brews
- **Ammunition**: Arrows, bolts, darts, cannonballs, etc.
- **Runes**: Magic runes and spell components
- **Teleports**: Teleport tablets, jewelry, and other transport items
- **Other**: Light sources, crafting materials, temporary items, etc.

This modular approach ensures comprehensive tracking while remaining flexible and maintainable.
