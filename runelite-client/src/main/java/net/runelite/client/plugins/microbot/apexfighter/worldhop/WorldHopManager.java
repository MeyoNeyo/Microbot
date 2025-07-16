package net.runelite.client.plugins.microbot.apexfighter.worldhop;

import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class WorldHopManager {

    // Track last hop time to prevent rapid consecutive hops
    private static long lastHopTime = 0;
    private static final long HOP_COOLDOWN_MS = 15000; // 15 second cooldown between hops

    /**
     * Hops to a random safe world of the same type (members/free) as the current world.
     * Filters out dangerous, full, and current worlds. Runs on the client thread.
     */
    /**
     * Checks if scripts are paused due to world hopping and resumes them if the hop is complete.
     * Should be called regularly by scripts to ensure scripts resume after hop.
     */
    public static void processWorldHop() {
        // If scripts are paused and player is logged in and not hopping, resume scripts
        if (Microbot.pauseAllScripts.get()) {
            // Check if we're logged in and not in a hopping state
            GameState gameState = Microbot.getClient().getGameState();
            boolean isHopping = Microbot.isHopping();
            if (gameState == GameState.LOGGED_IN && !isHopping) {
                Microbot.log("[ApexFighter] processWorldHop: GameState=LOGGED_IN, isHopping=false - resuming scripts");
                Microbot.pauseAllScripts.compareAndSet(true, false);
                Microbot.log("[ApexFighter] World hop complete, resuming scripts.");
            } else {
                // Log current state for debugging every 10 seconds to avoid spam
                if (System.currentTimeMillis() % 10000 < 1000) {
                    Microbot.log("[ApexFighter] processWorldHop: GameState=" + gameState + ", isHopping=" + isHopping + " - still waiting");
                }
            }
        }
    }
    // Removed duplicate/broken hopWorlds method. Use safeHopWorlds or processWorldHop instead.

    /**
     * Safely hops worlds by pausing all scripts, performing the hop, and resuming scripts when safe.
     * This should be called by scripts that need to trigger a world hop due to area conditions.
     *
     * @param reason Optional log message for why the hop is occurring.
     */
    public static void safeHopWorlds(String reason) {
        // Check cooldown to prevent rapid consecutive hops
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastHopTime < HOP_COOLDOWN_MS) {
            Microbot.log("[ApexFighter] Skipping world hop - cooldown active. Time since last hop: " + (currentTime - lastHopTime) + "ms");
            return;
        }

        if (reason != null && !reason.isEmpty()) {
            Microbot.log("[ApexFighter] INITIATING WORLD HOP - Reason: " + reason);
        } else {
            Microbot.log("[ApexFighter] INITIATING WORLD HOP - No specific reason provided");
        }

        // Pause all scripts before hopping
        Microbot.pauseAllScripts.compareAndSet(false, true);
        Microbot.log("[ApexFighter] Scripts paused for world hop");

        // Perform the hop on the client thread
        Microbot.getClientThread().invokeLater(() -> {
            WorldResult worldResult = Microbot.getWorldService().getWorlds();
            if (worldResult == null) {
                Microbot.log("[ApexFighter] ERROR: Could not get world list for hop");
                Microbot.pauseAllScripts.compareAndSet(true, false); // Resume if failed
                return;
            }

            int currentWorld = Microbot.getClient().getWorld();
            boolean isMember = Microbot.getClient().getWorldType().contains(WorldType.MEMBERS);

            // Filter for safe, not-full, not-current, correct type worlds
            List<World> worlds = worldResult.getWorlds().stream()
                .filter(w -> w.getId() != currentWorld)
                .filter(w -> w.getPlayers() < 2000)
                .filter(w -> w.getTypes().stream().noneMatch(t ->
                    t.toString().equals("PVP") ||
                    t.toString().equals("DEADMAN") ||
                    t.toString().equals("HIGH_RISK") ||
                    t.toString().equals("SKILL_TOTAL") ||
                    t.toString().equals("QUEST_SPEEDRUNNING") ||
                    t.toString().equals("PVP_ARENA") ||
                    t.toString().equals("SEASONAL") ||
                    t.toString().equals("BETA_WORLD") ||
                    t.toString().equals("NOSAVE_MODE") ||
                    t.toString().equals("FRESH_START_WORLD")
                ))
                .filter(w -> isMember == w.getTypes().stream().anyMatch(t -> t.toString().equals("MEMBERS")))
                .collect(Collectors.toList());

            if (worlds.isEmpty()) {
                Microbot.log("[ApexFighter] ERROR: No suitable world found to hop - resuming scripts");
                Microbot.pauseAllScripts.compareAndSet(true, false);
                return;
            }

            World targetWorld = worlds.get(new Random().nextInt(worlds.size()));

            Microbot.log("[ApexFighter] Found " + worlds.size() + " suitable worlds, hopping to world: " + targetWorld.getId());
            
            // Use Microbot.hopToWorld like other scripts do
            boolean hopSuccess = Microbot.hopToWorld(targetWorld.getId());
            if (!hopSuccess) {
                Microbot.log("[ApexFighter] ERROR: Failed to initiate world hop to: " + targetWorld.getId() + " - resuming scripts");
                // Resume scripts if hop failed
                Microbot.pauseAllScripts.compareAndSet(true, false);
            } else {
                Microbot.log("[ApexFighter] World hop initiated successfully to: " + targetWorld.getId() + " - waiting for completion");
                // Update last hop time
                lastHopTime = System.currentTimeMillis();
            }
            // Note: Scripts will be resumed by processWorldHop() when hop completes
        });
    }

    /**
     * Hops to a specific world number, similar to other Microbot scripts.
     * @param worldNumber The world number to hop to
     * @return true if hop was initiated, false otherwise
     */
    public static boolean hopToWorld(int worldNumber) {
        // Pause all scripts before hopping
        Microbot.pauseAllScripts.compareAndSet(false, true);
        
        Microbot.log("[ApexFighter] Hopping to world: " + worldNumber);
        boolean hopSuccess = Microbot.hopToWorld(worldNumber);
        
        if (!hopSuccess) {
            Microbot.log("[ApexFighter] Failed to initiate world hop to: " + worldNumber);
            // Resume scripts if hop failed
            Microbot.pauseAllScripts.compareAndSet(true, false);
        } else {
            // Update last hop time
            lastHopTime = System.currentTimeMillis();
        }
        // Note: Scripts will be resumed by processWorldHop() when hop completes
        
        return hopSuccess;
    }

    /**
     * Checks if world hopping conditions are met and triggers a hop if needed
     * @param maxPlayers Maximum players allowed in area before hopping
     * @param maxSecondsWithoutMonsters Maximum seconds without monsters before hopping  
     * @param secondsWithoutMonsters Current seconds without monsters
     * @param playersInArea Current players detected in area
     */
    public static void handleWorldHopIfNeeded(int maxPlayers, int maxSecondsWithoutMonsters, int secondsWithoutMonsters, int playersInArea) {
        if (maxPlayers > 0 && playersInArea >= maxPlayers) {
            Microbot.log("[ApexFighter] WORLD HOP TRIGGERED - TOO MANY PLAYERS: Found " + playersInArea + " players in area (max allowed: " + maxPlayers + ")");
            safeHopWorlds("too many players in area (" + playersInArea + " >= " + maxPlayers + ")");
            return;
        }
        if (maxSecondsWithoutMonsters > 0 && secondsWithoutMonsters >= maxSecondsWithoutMonsters) {
            Microbot.log("[ApexFighter] WORLD HOP TRIGGERED - NO MONSTERS: No monsters found for " + secondsWithoutMonsters + " seconds (max allowed: " + maxSecondsWithoutMonsters + ")");
            safeHopWorlds("no monsters in area for " + secondsWithoutMonsters + " seconds");
            return;
        }
    }

    // The following logic is now handled by safeHopWorlds and should not be duplicated here.
}
