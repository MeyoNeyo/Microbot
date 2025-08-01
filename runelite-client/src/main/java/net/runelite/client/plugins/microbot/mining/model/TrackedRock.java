package net.runelite.client.plugins.microbot.mining.model;

import lombok.Getter;
import lombok.AllArgsConstructor;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.mining.enums.Rocks;

import java.time.Instant;

/**
 * Represents a tracked rock for respawn monitoring across worlds
 */
@Getter
@AllArgsConstructor
public class TrackedRock {
    private final WorldPoint worldPoint;
    private final Rocks rockType;
    private final Instant depletedTime;
    private final int world;
    private final boolean inMiningGuild; // Track if this rock was in mining guild when depleted
    
    /**
     * Constructor for backward compatibility
     */
    public TrackedRock(WorldPoint worldPoint, Rocks rockType, Instant depletedTime, int world) {
        this(worldPoint, rockType, depletedTime, world, false);
    }
    
    /**
     * Check if this rock should have respawned based on its type's respawn time
     */
    public boolean hasRespawned() {
        long actualRespawnTime = rockType.getActualRespawnTimeMs(inMiningGuild);
        if (actualRespawnTime <= 0) {
            return true; // No respawn tracking for this rock type
        }
        
        long elapsedMs = Instant.now().toEpochMilli() - depletedTime.toEpochMilli();
        return elapsedMs >= actualRespawnTime;
    }
    
    /**
     * Get remaining respawn time in milliseconds
     */
    public long getRemainingRespawnMs() {
        if (hasRespawned()) {
            return 0;
        }
        
        long actualRespawnTime = rockType.getActualRespawnTimeMs(inMiningGuild);
        long elapsedMs = Instant.now().toEpochMilli() - depletedTime.toEpochMilli();
        return actualRespawnTime - elapsedMs;
    }
    
    /**
     * Get remaining respawn time in seconds
     */
    public long getRemainingRespawnSeconds() {
        return getRemainingRespawnMs() / 1000;
    }
    
    /**
     * Check if this tracked rock matches the given location
     */
    public boolean matchesLocation(WorldPoint location) {
        return worldPoint.equals(location);
    }
    
    /**
     * Check if this tracked rock is on the given world
     */
    public boolean isOnWorld(int worldNumber) {
        return world == worldNumber;
    }
    
    @Override
    public String toString() {
        String guildText = inMiningGuild ? " (Mining Guild)" : "";
        return String.format("TrackedRock{%s at %s, world=%d, respawnsIn=%ds%s}", 
            rockType.getName(), worldPoint, world, getRemainingRespawnSeconds(), guildText);
    }
}
