package net.runelite.client.plugins.microbot.allinonemetalworker.enums;

/**
 * Represents the current phase of the all-in-one metal working process.
 * The plugin progresses through these phases sequentially.
 */
public enum ProcessPhase {
    /**
     * Initial phase where ores are mined according to specified ratios
     */
    MINING,
    
    /**
     * Second phase where collected ores are smelted into bars at furnace
     */
    SMELTING,
    
    /**
     * Final phase where bars are smithed into finished items at anvil
     */
    SMITHING,
    
    /**
     * Intermediate phase for banking activities between main phases
     */
    BANKING,
    
    /**
     * Movement phase when walking between locations
     */
    WALKING,
    
    /**
     * All phases completed successfully
     */
    COMPLETE,
    
    /**
     * Error state when plugin encounters unrecoverable issues
     */
    ERROR
}
