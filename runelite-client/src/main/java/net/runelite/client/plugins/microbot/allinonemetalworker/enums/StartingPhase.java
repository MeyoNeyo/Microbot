package net.runelite.client.plugins.microbot.allinonemetalworker.enums;

/**
 * Represents the starting phase options for debugging purposes
 */
public enum StartingPhase {
    /**
     * Start normally with mining phase
     */
    MINING("Mining", "Start with mining ores (normal operation)"),
    
    /**
     * Start directly at smelting phase for testing
     */
    SMELTING("Smelting", "Start directly at smelting phase (for debugging smelting)"),
    
    /**
     * Start directly at smithing phase for testing
     */
    SMITHING("Smithing", "Start directly at smithing phase (for debugging smithing)");

    private final String displayName;
    private final String description;
    
    StartingPhase(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
