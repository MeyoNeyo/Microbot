package net.runelite.client.plugins.microbot.allinonemetalworker.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.smelting.enums.Ores;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import java.util.Map;

/**
 * Represents different metal types with their ore requirements, ratios, and level requirements.
 * Each metal type defines what ores are needed and in what quantities to produce bars.
 */
@Getter
@RequiredArgsConstructor
public enum MetalType {
    /**
     * Bronze bars require copper and tin in 1:1 ratio
     */
    BRONZE("Bronze", 1, 15, 6.2, 
           Map.of(Ores.COPPER, 1, Ores.TIN, 1),
           ItemID.BRONZE_BAR),
    
    /**
     * Iron bars require only iron ore
     */
    IRON("Iron", 15, 30, 12.5,
         Map.of(Ores.IRON, 1),
         ItemID.IRON_BAR),
    
    /**
     * Steel bars require iron and coal in 1:2 ratio
     */
    STEEL("Steel", 30, 50, 17.5,
          Map.of(Ores.IRON, 1, Ores.COAL, 2),
          ItemID.STEEL_BAR),
    
    /**
     * Mithril bars require mithril ore and coal in 1:4 ratio
     */
    MITHRIL("Mithril", 50, 70, 30.0,
            Map.of(Ores.MITHRIL, 1, Ores.COAL, 4),
            ItemID.MITHRIL_BAR),
    
    /**
     * Adamantite bars require adamantite ore and coal in 1:6 ratio
     */
    ADAMANTITE("Adamantite", 70, 85, 37.5,
               Map.of(Ores.ADAMANTITE, 1, Ores.COAL, 6),
               ItemID.ADAMANTITE_BAR),
    
    /**
     * Runite bars require runite ore and coal in 1:8 ratio
     */
    RUNITE("Runite", 85, 99, 50.0,
           Map.of(Ores.RUNITE, 1, Ores.COAL, 8),
           ItemID.RUNITE_BAR);

    private final String displayName;
    private final int miningLevelRequired;
    private final int smithingLevelRequired;
    private final double smeltingXpPerBar;
    private final Map<Ores, Integer> oreRequirements;
    private final int barItemId;
    
    /**
     * Checks if the player has the required mining level for this metal type
     * @param currentMiningLevel The player's current mining level
     * @return true if the level requirement is met
     */
    public boolean hasRequiredMiningLevel(int currentMiningLevel) {
        return currentMiningLevel >= miningLevelRequired;
    }
    
    /**
     * Checks if the player has the required smithing level for this metal type
     * @param currentSmithingLevel The player's current smithing level
     * @return true if the level requirement is met
     */
    public boolean hasRequiredSmithingLevel(int currentSmithingLevel) {
        return currentSmithingLevel >= smithingLevelRequired;
    }
    
    /**
     * Calculates the total number of individual ore pieces needed for a given number of bars
     * @param numberOfBars The desired number of bars to produce
     * @return Total ore count needed
     */
    public int getTotalOreCount(int numberOfBars) {
        return oreRequirements.values().stream()
                .mapToInt(Integer::intValue)
                .sum() * numberOfBars;
    }
    
    /**
     * Calculates how many of a specific ore type is needed for the given number of bars
     * @param ore The specific ore type
     * @param numberOfBars The desired number of bars
     * @return Number of that specific ore needed
     */
    public int getOreCount(Ores ore, int numberOfBars) {
        return oreRequirements.getOrDefault(ore, 0) * numberOfBars;
    }
    
    /**
     * Gets the maximum number of bars that can be produced with one full inventory
     * Accounts for pickaxe taking up one slot
     * @param inventorySlots Available inventory slots (typically 26-28)
     * @return Maximum bars producible per inventory
     */
    public int getMaxBarsPerInventory(int inventorySlots) {
        int totalOrePerBar = getTotalOreCount(1);
        return inventorySlots / totalOrePerBar;
    }
    
    /**
     * Gets the ore names for mining
     * @return Array of ore names that can be mined for this metal type
     */
    public String[] getOreNames() {
        return oreRequirements.keySet().stream()
                .map(Ores::getName)
                .toArray(String[]::new);
    }
    
    /**
     * Gets the bar name for this metal type
     * @return The name of the bar produced from this metal
     */
    public String getBarName() {
        return displayName + " bar";
    }
    
    /**
     * Checks if the player has the required ores in inventory
     * @return true if all required ores are present in sufficient quantities
     */
    public boolean hasRequiredOres() {
        for (Map.Entry<Ores, Integer> entry : oreRequirements.entrySet()) {
            Ores ore = entry.getKey();
            int required = entry.getValue();
            
            // Check if we have enough of this ore type
            if (!Rs2Inventory.hasItemAmount(ore.getName(), required)) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
