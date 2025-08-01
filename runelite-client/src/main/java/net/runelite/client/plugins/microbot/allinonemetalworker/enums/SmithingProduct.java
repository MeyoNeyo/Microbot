package net.runelite.client.plugins.microbot.allinonemetalworker.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Represents smithable items with their requirements and properties.
 * Items are ordered by efficiency (XP per bar ratio) and level requirements.
 */
@Getter
@RequiredArgsConstructor
public enum SmithingProduct {
    // Single bar items - most efficient for XP
    DAGGER(1, 1, 12.5, 9, "Dagger"),
    AXE(1, 1, 12.5, 14, "Axe"),
    MACE(2, 1, 12.5, 15, "Mace"),
    MEDIUM_HELM(3, 1, 12.5, 24, "Medium helm"),
    SWORD(4, 1, 12.5, 10, "Sword"),
    NAILS(4, 1, 12.5, 23, "Nails"),
    DART_TIPS(7, 1, 12.5, 29, "Dart tips"),
    ARROWTIPS(5, 1, 12.5, 30, "Arrowtips"),
    KNIVES(7, 1, 12.5, 31, "Knives"),
    
    // Two bar items
    SCIMITAR(5, 2, 25.0, 11, "Scimitar"),
    LONG_SWORD(6, 2, 25.0, 12, "Long sword"),
    FULL_HELM(7, 2, 25.0, 25, "Full helm"),
    SQUARE_SHIELD(8, 2, 25.0, 26, "Square shield"),
    CLAWS(13, 2, 25.0, 18, "Claws"),
    
    // Three bar items
    TWO_HAND_SWORD(14, 3, 37.5, 13, "2-hand sword"),
    PLATE_LEGS(16, 3, 37.5, 20, "Plate legs"),
    PLATE_SKIRT(16, 3, 37.5, 21, "Plate skirt"),
    CHAIN_BODY(11, 3, 37.5, 19, "Chain body"),
    BATTLE_AXE(10, 3, 37.5, 17, "Battle axe"),
    WARHAMMER(9, 3, 37.5, 16, "Warhammer"),
    KITE_SHIELD(12, 3, 37.5, 27, "Kite shield"),
    
    // Five bar items - highest XP total but least efficient
    PLATE_BODY(18, 5, 62.5, 22, "Plate body");

    private final int levelRequired;
    private final int barsRequired;
    private final double xpGained;
    private final int widgetChildId;
    private final String itemName;
    
    /**
     * Calculates XP per bar ratio for efficiency comparison
     * @return XP gained per bar used
     */
    public double getXpPerBar() {
        return xpGained / barsRequired;
    }
    
    /**
     * Checks if the player can smith this item based on their current smithing level
     * @return true if the player meets the level requirement
     */
    public boolean canSmith() {
        return Rs2Player.getRealSkillLevel(Skill.SMITHING) >= levelRequired;
    }
    
    /**
     * Checks if the player can smith this item with a specific smithing level
     * @param smithingLevel The smithing level to check against
     * @return true if the level requirement is met
     */
    public boolean canSmithWithLevel(int smithingLevel) {
        return smithingLevel >= levelRequired;
    }
    
    /**
     * Finds the best smithable item for the given metal type and smithing level.
     * Prioritizes highest XP per bar ratio among available options.
     * @param smithingLevel Current smithing level
     * @return The most efficient smithable item available
     */
    public static SmithingProduct getBestAvailableItem(int smithingLevel) {
        SmithingProduct bestItem = DAGGER; // Default fallback
        double bestXpPerBar = 0;
        
        for (SmithingProduct item : values()) {
            if (item.canSmithWithLevel(smithingLevel)) {
                double currentXpPerBar = item.getXpPerBar();
                if (currentXpPerBar > bestXpPerBar) {
                    bestXpPerBar = currentXpPerBar;
                    bestItem = item;
                }
            }
        }
        
        return bestItem;
    }
    
    /**
     * Calculates how many complete items can be made with the given number of bars
     * @param availableBars Number of bars available
     * @return Number of complete items that can be made
     */
    public int getMaxItemsFromBars(int availableBars) {
        return availableBars / barsRequired;
    }
    
    @Override
    public String toString() {
        return String.format("%s (Level %d, %d bars, %.1f XP)", 
                itemName, levelRequired, barsRequired, xpGained);
    }
}
