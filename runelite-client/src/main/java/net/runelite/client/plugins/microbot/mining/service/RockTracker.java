package net.runelite.client.plugins.microbot.mining.service;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.mining.enums.Rocks;
import net.runelite.client.plugins.microbot.mining.model.TrackedRock;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages rock tracking across worlds for intelligent mining and world hopping
 */
@Slf4j
public class RockTracker {
    
    private static final Map<String, List<TrackedRock>> worldRockMap = new ConcurrentHashMap<>();
    private static final int MAX_ROCKS_PER_WORLD = 50; // Prevent memory issues
    private static final long CLEANUP_INTERVAL_MS = 300000; // 5 minutes
    private static long lastCleanup = 0;
    
    /**
     * Track a depleted rock at the current location and world
     */
    public static void trackDepletedRock(WorldPoint location, Rocks rockType, boolean inMiningGuild) {
        if (location == null || rockType == null) {
            return;
        }
        
        int currentWorld = Microbot.getClient().getWorld();
        String worldKey = String.valueOf(currentWorld);
        
        TrackedRock trackedRock = new TrackedRock(location, rockType, Instant.now(), currentWorld, inMiningGuild);
        
        worldRockMap.computeIfAbsent(worldKey, k -> new CopyOnWriteArrayList<>()).add(trackedRock);
        
        String guildText = inMiningGuild ? " (Mining Guild)" : "";
        log.debug("🗿 Tracked depleted {} at {} on world {}{}", rockType.getName(), location, currentWorld, guildText);
        
        // Cleanup old entries periodically
        performPeriodicCleanup();
        
        // Limit rocks per world to prevent memory issues
        limitRocksPerWorld(worldKey);
    }
    
    /**
     * Track a depleted rock (backward compatibility method)
     */
    public static void trackDepletedRock(WorldPoint location, Rocks rockType) {
        trackDepletedRock(location, rockType, false);
    }
    
    /**
     * Check if there are any rocks available for mining in the current area
     */
    public static boolean hasAvailableRocksInArea(WorldPoint playerLocation, Rocks rockType, int searchRadius) {
        int currentWorld = Microbot.getClient().getWorld();
        String worldKey = String.valueOf(currentWorld);
        
        List<TrackedRock> worldRocks = worldRockMap.get(worldKey);
        if (worldRocks == null || worldRocks.isEmpty()) {
            return false; // No tracked rocks means we haven't tested this world - should hop to explore
        }
        
        // Find depleted rocks in the search area that haven't respawned yet
        List<TrackedRock> depletedInArea = worldRocks.stream()
                .filter(rock -> rock.getRockType().equals(rockType))
                .filter(rock -> rock.getWorldPoint().distanceTo(playerLocation) <= searchRadius)
                .filter(rock -> !rock.hasRespawned())
                .collect(Collectors.toList());
        
        // Find rocks that have respawned and should be available
        List<TrackedRock> availableInArea = worldRocks.stream()
                .filter(rock -> rock.getRockType().equals(rockType))
                .filter(rock -> rock.getWorldPoint().distanceTo(playerLocation) <= searchRadius)
                .filter(TrackedRock::hasRespawned)
                .collect(Collectors.toList());
        
        log.debug("🔍 Found {} depleted and {} available {} rocks in {}tile radius", 
                depletedInArea.size(), availableInArea.size(), rockType.getName(), searchRadius);
        
        // Only consider rocks available if we have respawned rocks tracked
        return availableInArea.size() > 0;
    }
    
    /**
     * Get the best world to hop to for the given rock type and location
     */
    public static OptionalInt getBestWorldForRockType(Rocks rockType, WorldPoint location, int searchRadius) {
        Map<Integer, Integer> worldScores = new HashMap<>();
        int currentWorld = Microbot.getClient().getWorld();
        
        // First, score all tracked worlds
        for (Map.Entry<String, List<TrackedRock>> entry : worldRockMap.entrySet()) {
            try {
                int world = Integer.parseInt(entry.getKey());
                if (world == currentWorld) continue; // Skip current world
                
                List<TrackedRock> rocks = entry.getValue();
                
                // Count available rocks in the area for this world
                long availableRocks = rocks.stream()
                        .filter(rock -> rock.getRockType().equals(rockType))
                        .filter(rock -> rock.getWorldPoint().distanceTo(location) <= searchRadius)
                        .filter(TrackedRock::hasRespawned)
                        .count();
                
                // Count depleted rocks that will respawn soon (within 30 seconds)
                long soonAvailableRocks = rocks.stream()
                        .filter(rock -> rock.getRockType().equals(rockType))
                        .filter(rock -> rock.getWorldPoint().distanceTo(location) <= searchRadius)
                        .filter(rock -> !rock.hasRespawned())
                        .filter(rock -> rock.getRemainingRespawnMs() <= 30000) // within 30 seconds
                        .count();
                
                // Count total tracked rocks in area (to prefer less crowded worlds)
                long totalTracked = rocks.stream()
                        .filter(rock -> rock.getRockType().equals(rockType))
                        .filter(rock -> rock.getWorldPoint().distanceTo(location) <= searchRadius)
                        .count();
                
                // Score: higher is better (available rocks + soon available rocks, less total tracked)
                int score = (int)((availableRocks + soonAvailableRocks) * 10 - totalTracked);
                worldScores.put(world, score);
                
            } catch (NumberFormatException e) {
                log.warn("Invalid world key: {}", entry.getKey());
            }
        }
        
        // Find the best tracked world
        OptionalInt bestTrackedWorld = worldScores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0) // Only consider worlds with positive scores
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .map(OptionalInt::of)
                .orElse(OptionalInt.empty());
        
        if (bestTrackedWorld.isPresent()) {
            log.debug("🎯 Found best tracked world {} with score {}", bestTrackedWorld.getAsInt(), 
                    worldScores.get(bestTrackedWorld.getAsInt()));
            return bestTrackedWorld;
        }
        
        // If no good tracked worlds, prefer an untested world (not in our tracking data)
        // This gives new worlds a chance and prevents getting stuck in tracked worlds with no rocks
        log.debug("🌍 No good tracked worlds found, looking for untested world");
        return OptionalInt.empty(); // Let caller handle random world selection
    }
    
    /**
     * Check if we have too much tracking data for the current world (indicating it's been fully explored)
     */
    public static boolean isCurrentWorldFullyExplored(WorldPoint playerLocation, Rocks rockType, int searchRadius) {
        int currentWorld = Microbot.getClient().getWorld();
        String worldKey = String.valueOf(currentWorld);
        
        List<TrackedRock> worldRocks = worldRockMap.getOrDefault(worldKey, Collections.emptyList());
        
        long trackedRocksInArea = worldRocks.stream()
                .filter(rock -> rock.getRockType().equals(rockType))
                .filter(rock -> rock.getWorldPoint().distanceTo(playerLocation) <= searchRadius)
                .count();
        
        // Consider world fully explored if we have tracked more than 5 rocks in the area
        // and none are currently available
        return trackedRocksInArea >= 5 && getAvailableRocksCount(playerLocation, rockType, searchRadius) == 0;
    }
    
    /**
     * Get statistics about tracked rocks for the current world
     */
    public static String getRockTrackingStats(Rocks rockType, WorldPoint location, int searchRadius) {
        int currentWorld = Microbot.getClient().getWorld();
        String worldKey = String.valueOf(currentWorld);
        
        List<TrackedRock> worldRocks = worldRockMap.getOrDefault(worldKey, Collections.emptyList());
        
        List<TrackedRock> rocksInArea = worldRocks.stream()
                .filter(rock -> rock.getRockType().equals(rockType))
                .filter(rock -> rock.getWorldPoint().distanceTo(location) <= searchRadius)
                .collect(Collectors.toList());
        
        long depletedCount = rocksInArea.stream().filter(rock -> !rock.hasRespawned()).count();
        long availableCount = rocksInArea.stream().filter(TrackedRock::hasRespawned).count();
        
        if (rocksInArea.isEmpty()) {
            return "No rock data for current area";
        }
        
        return String.format("Tracked: %d | Available: %d | Depleted: %d", 
                rocksInArea.size(), availableCount, depletedCount);
    }
    
    /**
     * Get the count of available (respawned) rocks in the area
     */
    public static int getAvailableRocksCount(WorldPoint playerLocation, Rocks rockType, int searchRadius) {
        int currentWorld = Microbot.getClient().getWorld();
        String worldKey = String.valueOf(currentWorld);
        
        List<TrackedRock> worldRocks = worldRockMap.getOrDefault(worldKey, Collections.emptyList());
        
        return (int) worldRocks.stream()
                .filter(rock -> rock.getRockType().equals(rockType))
                .filter(rock -> rock.getWorldPoint().distanceTo(playerLocation) <= searchRadius)
                .filter(TrackedRock::hasRespawned)
                .count();
    }
    
    /**
     * Get the count of rocks that will respawn soon (within specified milliseconds)
     */
    public static int getSoonRespawningRocksCount(WorldPoint playerLocation, Rocks rockType, int searchRadius, long withinMs) {
        int currentWorld = Microbot.getClient().getWorld();
        String worldKey = String.valueOf(currentWorld);
        
        List<TrackedRock> worldRocks = worldRockMap.getOrDefault(worldKey, Collections.emptyList());
        
        return (int) worldRocks.stream()
                .filter(rock -> rock.getRockType().equals(rockType))
                .filter(rock -> rock.getWorldPoint().distanceTo(playerLocation) <= searchRadius)
                .filter(rock -> !rock.hasRespawned()) // Not yet respawned
                .filter(rock -> rock.getRemainingRespawnMs() <= withinMs) // But will respawn soon
                .count();
    }
    
    /**
     * Get a world with soon-respawning rocks (within 5 seconds) that we can hop to
     * @param rockType The type of rock we're looking for
     * @param location The location to search around
     * @param searchRadius The radius to search within
     * @return OptionalInt containing the best world, or empty if no world found
     */
    public static OptionalInt getWorldWithSoonRespawningRock(Rocks rockType, WorldPoint location, int searchRadius) {
        Map<Integer, Long> worldEarliestRespawn = new HashMap<>();
        
        for (Map.Entry<String, List<TrackedRock>> entry : worldRockMap.entrySet()) {
            try {
                int world = Integer.parseInt(entry.getKey());
                List<TrackedRock> rocks = entry.getValue();
                
                // Find rocks that will respawn within 5 seconds
                OptionalLong earliestRespawn = rocks.stream()
                        .filter(rock -> rock.getRockType().equals(rockType))
                        .filter(rock -> rock.getWorldPoint().distanceTo(location) <= searchRadius)
                        .filter(rock -> !rock.hasRespawned()) // Not yet respawned
                        .filter(rock -> rock.getRemainingRespawnMs() <= 5000) // Within 5 seconds
                        .mapToLong(TrackedRock::getRemainingRespawnMs)
                        .min();
                        
                if (earliestRespawn.isPresent()) {
                    worldEarliestRespawn.put(world, earliestRespawn.getAsLong());
                }
                
            } catch (NumberFormatException e) {
                log.warn("Invalid world key: {}", entry.getKey());
            }
        }
        
        // Return world with the rock that will respawn soonest
        return worldEarliestRespawn.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .map(OptionalInt::of)
                .orElse(OptionalInt.empty());
    }
    
    /**
     * Clear all tracking data (useful when changing locations or rock types)
     */
    public static void clearAllTracking() {
        worldRockMap.clear();
        log.info("🗿 Cleared all rock tracking data");
    }
    
    /**
     * Clear tracking data for current world only
     */
    public static void clearCurrentWorldTracking() {
        int currentWorld = Microbot.getClient().getWorld();
        String worldKey = String.valueOf(currentWorld);
        worldRockMap.remove(worldKey);
        log.info("🗿 Cleared rock tracking data for world {}", currentWorld);
    }
    
    /**
     * Cleanup expired rock entries periodically
     */
    private static void performPeriodicCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_INTERVAL_MS) {
            return;
        }
        
        lastCleanup = now;
        int removedCount = 0;
        
        for (List<TrackedRock> rocks : worldRockMap.values()) {
            // Remove rocks that have been respawned for more than 10 minutes
            int sizeBefore = rocks.size();
            rocks.removeIf(rock -> {
                long actualRespawnTime = rock.getRockType().getActualRespawnTimeMs(rock.isInMiningGuild());
                long timeSinceRespawn = now - (rock.getDepletedTime().toEpochMilli() + actualRespawnTime);
                return timeSinceRespawn > 600000; // 10 minutes after respawn
            });
            removedCount += (sizeBefore - rocks.size());
        }
        
        if (removedCount > 0) {
            log.debug("🧹 Cleaned up {} expired rock entries", removedCount);
        }
    }
    
    /**
     * Limit the number of tracked rocks per world to prevent memory issues
     */
    private static void limitRocksPerWorld(String worldKey) {
        List<TrackedRock> rocks = worldRockMap.get(worldKey);
        if (rocks != null && rocks.size() > MAX_ROCKS_PER_WORLD) {
            // Remove oldest entries
            int toRemove = rocks.size() - MAX_ROCKS_PER_WORLD;
            rocks.sort(Comparator.comparing(TrackedRock::getDepletedTime));
            rocks.subList(0, toRemove).clear();
            log.debug("🗿 Limited world {} to {} tracked rocks (removed {} oldest)", worldKey, MAX_ROCKS_PER_WORLD, toRemove);
        }
    }
    
    /**
     * Get total number of tracked rocks across all worlds
     */
    public static int getTotalTrackedRocks() {
        return worldRockMap.values().stream().mapToInt(List::size).sum();
    }
    
    /**
     * Get number of tracked worlds
     */
    public static int getTrackedWorldCount() {
        return worldRockMap.size();
    }
}
