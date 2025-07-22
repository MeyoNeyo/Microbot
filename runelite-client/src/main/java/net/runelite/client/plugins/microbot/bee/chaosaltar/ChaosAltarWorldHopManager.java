package net.runelite.client.plugins.microbot.bee.chaosaltar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2PlayerModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class ChaosAltarWorldHopManager {

    // Track last hop time to prevent rapid consecutive hops
    private static long lastHopTime = 0;
    private static final long HOP_COOLDOWN_MS = 500; // Faster cooldown for wilderness scenarios
    
    // Player detection tracking
    private static final Map<String, Long> playerDetectionTimes = new ConcurrentHashMap<>();
    private static final long PLAYER_DETECTION_TIMEOUT_MS = 3000; // 3 seconds
    
    // World hop state tracking
    private static boolean isHopping = false;
    private static long hopStartTime = 0;
    private static final long MAX_HOP_WAIT_TIME_MS = 3000; // 3 seconds max wait for hop completion
    
    // World hopping enabled state
    private static boolean hoppingEnabled = false;
    
    // Enhanced threaded monitoring system (like wilderness runite miner)
    private static Thread playerDetectionThread;
    private static Thread worldHopThread;
    private static Thread combatMonitorThread;
    private static final AtomicBoolean threadsRunning = new AtomicBoolean(false);
    private static ChaosAltarConfig currentConfig;
    
    // Cache for performance optimization
    private static WorldPoint lastKnownLocation = null;
    private static long lastLocationUpdate = 0;
    private static final long LOCATION_CACHE_DURATION = 300; // Cache location for 300ms for faster response
    
    // Enhanced player tracking (like wildyruniteminer)
    private static final Set<String> recentAttackers = new HashSet<>();
    private static long lastPlayerCountUpdate = 0;
    private static int cachedPlayerCount = 0;
    private static final long PLAYER_COUNT_CACHE_DURATION = 500; // Cache player count for 500ms

    /**
     * Initialize and start the enhanced threaded monitoring system (like wilderness runite miner)
     * This system continuously monitors for players and manages world hopping when in wilderness
     */
    public static void startThreadedMonitoring(ChaosAltarConfig config) {
        if (threadsRunning.get()) {
            log.debug("[ChaosAltar] Threaded monitoring already running");
            return;
        }
        
        currentConfig = config;
        threadsRunning.set(true);
        
        log.info("[ChaosAltar] Starting enhanced threaded player detection and world hopping system");
        
        startPlayerDetectionThread();
        startWorldHopThread();
        startCombatMonitorThread();
    }
    
    /**
     * Stop all monitoring threads
     */
    public static void stopThreadedMonitoring() {
        log.info("[ChaosAltar] Stopping threaded monitoring system");
        
        threadsRunning.set(false);
        
        if (playerDetectionThread != null && !playerDetectionThread.isInterrupted()) {
            playerDetectionThread.interrupt();
        }
        
        if (worldHopThread != null && !worldHopThread.isInterrupted()) {
            worldHopThread.interrupt();
        }
        
        if (combatMonitorThread != null && !combatMonitorThread.isInterrupted()) {
            combatMonitorThread.interrupt();
        }
        
        // Clear detection data when stopping
        clearPlayerDetections();
        resetHoppingState();
        recentAttackers.clear();
    }
    
    /**
     * Player detection thread - constantly monitors for players when in wilderness
     * Similar to wilderness runite miner's monitoring approach with enhanced logic
     */
    private static void startPlayerDetectionThread() {
        playerDetectionThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && threadsRunning.get()) {
                try {
                    // Only monitor when in wilderness and hopping is enabled
                    if (!Rs2Pvp.isInWilderness() || !hoppingEnabled) {
                        Thread.sleep(1000); // Check less frequently when not needed
                        continue;
                    }
                    
                    // Get current location with caching
                    WorldPoint currentLocation = getCachedLocation();
                    if (currentLocation == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    
                    // Get nearby players
                    List<Rs2PlayerModel> nearbyPlayers = getPlayersInRadius(
                        currentConfig != null ? currentConfig.playerDetectionRadius() : 20
                    );
                    
                    // Clean up old detection times
                    cleanupOldDetections();
                    
                    // Update detection times for current players
                    long currentTime = System.currentTimeMillis();
                    for (Rs2PlayerModel player : nearbyPlayers) {
                        playerDetectionTimes.putIfAbsent(player.getName(), currentTime);
                    }
                    
                    // Check if we should trigger a world hop based on config
                    if (shouldHopBasedOnPlayers(nearbyPlayers)) {
                        String reason = buildHopReason(nearbyPlayers);
                        log.info("[ChaosAltar] Player detection thread triggering hop: {}", reason);
                        triggerWorldHop(reason);
                    }
                    
                    Thread.sleep(250); // Check every 250ms for very responsive detection
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[ChaosAltar] Error in player detection thread", e);
                    try {
                        Thread.sleep(1000); // Brief pause on error
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        
        playerDetectionThread.setName("ChaosAltar-PlayerDetection");
        playerDetectionThread.start();
    }
    
    /**
     * Combat monitoring thread - watches for player attacks and emergency situations
     * Similar to wildyruniteminer's combat detection
    /**
     * Combat monitoring thread - watches for player attacks and emergency situations
     * Similar to wildyruniteminer's combat detection
     */
    private static void startCombatMonitorThread() {
        combatMonitorThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && threadsRunning.get()) {
                try {
                    // Only monitor when in wilderness
                    if (!Rs2Pvp.isInWilderness()) {
                        Thread.sleep(2000); // Check less frequently when safe
                        continue;
                    }
                    
                    Player localPlayer = Microbot.getClient().getLocalPlayer();
                    if (localPlayer == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    
                    // Check if being attacked by another player
                    if (Rs2Player.isInCombat()) {
                        Rs2Player.getPlayers(p -> p != null && 
                                           !p.getPlayer().equals(localPlayer) && 
                                           p.getPlayer().getInteracting() == localPlayer)
                                .findFirst()
                                .ifPresent(attacker -> {
                                    String attackerName = attacker.getName();
                                    if (!recentAttackers.contains(attackerName)) {
                                        recentAttackers.add(attackerName);
                                        log.warn("[ChaosAltar] Under attack by player: {}", attackerName);
                                        
                                        // Emergency hop if configured
                                        if (currentConfig != null && currentConfig.emergencyHopOnAttack()) {
                                            log.warn("[ChaosAltar] EMERGENCY HOP - Being attacked by player!");
                                            triggerWorldHop("EMERGENCY: Being attacked by " + attackerName);
                                        }
                                    }
                                });
                    } else {
                        // Clear recent attackers when not in combat and in safe area
                        WorldPoint location = getCachedLocation();
                        if (location != null && (location.getY() < 3520 || 
                            location.distanceTo(new WorldPoint(2949, 3820, 0)) > 50)) {
                            recentAttackers.clear();
                        }
                    }
                    
                    Thread.sleep(300); // Check combat frequently for immediate response
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[ChaosAltar] Error in combat monitor thread", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        
        combatMonitorThread.setName("ChaosAltar-CombatMonitor");
        combatMonitorThread.start();
    }
    // Separated to prevent blocking the detection thread
     
    private static void startWorldHopThread() {
        worldHopThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && threadsRunning.get()) {
                try {
                    // Only process hops when needed
                    if (!isHopping) {
                        Thread.sleep(1000);
                        continue;
                    }
                    
                    // Handle hop completion checking
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - hopStartTime > MAX_HOP_WAIT_TIME_MS) {
                        log.warn("[ChaosAltar] World hop taking too long, resetting hop state");
                        isHopping = false;
                        continue;
                    }
                    
                    // Check if hop is complete
                    GameState gameState = Microbot.getClient().getGameState();
                    boolean microbotHopping = Microbot.isHopping();
                    
                    if (gameState == GameState.LOGGED_IN && !microbotHopping) {
                        log.info("[ChaosAltar] World hop complete, resuming normal operation");
                        isHopping = false;
                    }
                    
                    Thread.sleep(500); // Check hop completion frequently
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[ChaosAltar] Error in world hop thread", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        
        worldHopThread.setName("ChaosAltar-WorldHop");
        worldHopThread.start();
    }
    
    /**
     * Optimized location getter with caching (like wilderness runite miner)
     */
    private static WorldPoint getCachedLocation() {
        long currentTime = System.currentTimeMillis();
        if (lastKnownLocation == null || (currentTime - lastLocationUpdate) > LOCATION_CACHE_DURATION) {
            lastKnownLocation = Rs2Player.getWorldLocation();
            lastLocationUpdate = currentTime;
        }
        return lastKnownLocation;
    }
    
    /**
     * Check if we should hop based on detected players and configuration
     */
    private static boolean shouldHopBasedOnPlayers(List<Rs2PlayerModel> nearbyPlayers) {
        if (currentConfig == null || nearbyPlayers.isEmpty()) {
            return false;
        }
        
        // Check cooldown (unless spam hopping mode is enabled)
        long hopCooldownMs = currentConfig.hopCooldownSeconds() * 1000L;
        boolean isSpamHopping = (currentConfig.hopCooldownSeconds() == 0);
        
        if (!isSpamHopping && System.currentTimeMillis() - lastHopTime < hopCooldownMs) {
            return false;
        }
        
        // Instant hop mode - hop on any player detection
        if (currentConfig.instantHop() && !nearbyPlayers.isEmpty()) {
            return true;
        }
        
        // Max players threshold
        int maxPlayers = currentConfig.maxPlayersBeforeHop();
        if (maxPlayers > 0 && nearbyPlayers.size() >= maxPlayers) {
            return true;
        }
        
        // Default behavior - hop on any player if max is set to 0
        if (maxPlayers == 0 && !nearbyPlayers.isEmpty()) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Build descriptive reason for hop for logging
     */
    private static String buildHopReason(List<Rs2PlayerModel> nearbyPlayers) {
        if (currentConfig == null) {
            return "Players detected";
        }
        
        if (currentConfig.instantHop()) {
            return "Player detected (instant hop mode): " + nearbyPlayers.size() + " players";
        }
        
        return "Too many players detected: " + nearbyPlayers.size() + " >= " + currentConfig.maxPlayersBeforeHop();
    }
    
    /**
     * Check if being attacked by another player (not NPC)
     */
    private static boolean isBeingAttackedByPlayer() {
        Player localPlayer = Microbot.getClient().getLocalPlayer();
        if (localPlayer == null || localPlayer.getInteracting() == null) {
            return false;
        }
        
        return localPlayer.getInteracting() instanceof Player;
    }
    
    /**
     * Trigger a world hop from the detection system
     */
    private static void triggerWorldHop(String reason) {
        if (isHopping) {
            return; // Already hopping
        }
        
        // Check cooldown
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastHopTime < HOP_COOLDOWN_MS) {
            return;
        }
        
        Microbot.log("[ChaosAltar] " + reason + " - hopping worlds");
        safeHopWorlds(reason);
    }
    public static void setHoppingEnabled(boolean enabled) {
        hoppingEnabled = enabled;
        if (enabled) {
            log.info("World hopping enabled");
        } else {
            log.info("World hopping disabled");
        }
    }

    /**
     * Check if world hopping is currently enabled
     * @return true if world hopping is enabled
     */
    public static boolean isHoppingEnabled() {
        return hoppingEnabled;
    }

    /**
     * Checks if dangerous players are detected in the chaos altar area and triggers world hop if needed.
     * This method should be called regularly by the chaos altar script.
     * 
     * @param detectionRadius The radius around the player to check for other players
     * @param maxPlayersBeforeHop Maximum number of players allowed before hopping
     * @param instantHop If true, hop immediately when any player is detected
     * @param hopCooldownSeconds Minimum time between hops in seconds
     * @return true if a world hop was triggered
     */
    public static boolean checkAndHopIfPlayersDetected(int detectionRadius, int maxPlayersBeforeHop, boolean instantHop, int hopCooldownSeconds) {
        // Don't check if world hopping is disabled
        if (!hoppingEnabled) {
            return false;
        }
        
        // Don't check if we're currently hopping or in cooldown (unless spam hopping mode)
        long hopCooldownMs = hopCooldownSeconds * 1000L;
        boolean isSpamHopping = (hopCooldownSeconds == 0); // No cooldown means spam hopping mode
        
        if (isHopping || (!isSpamHopping && System.currentTimeMillis() - lastHopTime < hopCooldownMs)) {
            return false;
        }

        // Only check for players in wilderness
        if (!Rs2Pvp.isInWilderness()) {
            playerDetectionTimes.clear();
            return false;
        }

        List<Rs2PlayerModel> nearbyPlayers = getPlayersInRadius(detectionRadius);
        
        // Clean up old detection times
        cleanupOldDetections();
        
        // Update detection times for current players
        long currentTime = System.currentTimeMillis();
        for (Rs2PlayerModel player : nearbyPlayers) {
            playerDetectionTimes.putIfAbsent(player.getName(), currentTime);
        }
        
        // Check if we should hop
        boolean shouldHop = false;
        String hopReason = "";
        
        if (instantHop && !nearbyPlayers.isEmpty()) {
            shouldHop = true;
            hopReason = "Player detected (instant hop mode)";
        } else if (nearbyPlayers.size() >= maxPlayersBeforeHop) {
            shouldHop = true;
            hopReason = "Too many players detected: " + nearbyPlayers.size() + " >= " + maxPlayersBeforeHop;
        }
        
        if (shouldHop) {
            log.info("[ChaosAltar] Hopping worlds - {}", hopReason);
            Microbot.log("[ChaosAltar] " + hopReason + " - hopping worlds");
            safeHopWorlds(hopReason);
            return true;
        }
        
        return false;
    }

    /**
     * Gets a list of potentially dangerous players within the specified radius.
     * Filters out players that cannot attack the local player based on combat levels.
     */
    private static List<Rs2PlayerModel> getPlayersInRadius(int radius) {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        Player localPlayer = Microbot.getClient().getLocalPlayer();
        
        return Rs2Player.getPlayers(player -> {
            if (player == null || player.getPlayer() == localPlayer) {
                return false;
            }
            
            // Check distance
            if (player.getWorldLocation().distanceTo(playerLocation) > radius) {
                return false;
            }
            
            // In wilderness, only consider players that can attack us or we can attack
            if (Rs2Pvp.isInWilderness()) {
                return Rs2Pvp.isAttackable(player);
            }
            
            return true;
        }).collect(Collectors.toList());
    }

    /**
     * Removes old player detection entries to prevent memory leaks.
     */
    private static void cleanupOldDetections() {
        long currentTime = System.currentTimeMillis();
        playerDetectionTimes.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > PLAYER_DETECTION_TIMEOUT_MS);
    }

    /**
     * Safely hops worlds by pausing scripts, performing the hop, and managing state.
     * Based on ApexFighter's implementation but optimized for wilderness scenarios.
     */
    public static void safeHopWorlds(String reason) {
        // Check cooldown to prevent rapid consecutive hops
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastHopTime < HOP_COOLDOWN_MS) {
            log.debug("[ChaosAltar] Skipping world hop - cooldown active. Time since last hop: {}ms", 
                     currentTime - lastHopTime);
            return;
        }

        if (isHopping) {
            log.debug("[ChaosAltar] Already hopping worlds, ignoring new hop request");
            return;
        }

        log.info("[ChaosAltar] Initiating world hop - Reason: {}", reason != null ? reason : "No reason provided");

        // Set hopping state
        isHopping = true;
        hopStartTime = currentTime;

        // Perform the hop on the client thread
        Microbot.getClientThread().invokeLater(() -> {
            try {
                WorldResult worldResult = Microbot.getWorldService().getWorlds();
                if (worldResult == null) {
                    log.error("[ChaosAltar] Could not get world list for hop");
                    isHopping = false;
                    return;
                }

                int currentWorld = Microbot.getClient().getWorld();
                boolean isMember = Microbot.getClient().getWorldType().contains(WorldType.MEMBERS);

                // Filter for safe, not-full, not-current, correct type worlds
                List<World> worlds = worldResult.getWorlds().stream()
                    .filter(w -> w.getId() != currentWorld)
                    .filter(w -> w.getPlayers() < 1800) // Slightly more conservative than ApexFighter
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
                        t.toString().equals("FRESH_START_WORLD") ||
                        t.toString().equals("TOURNAMENT_WORLD")
                    ))
                    .filter(w -> isMember == w.getTypes().stream().anyMatch(t -> t.toString().equals("MEMBERS")))
                    .collect(Collectors.toList());

                if (worlds.isEmpty()) {
                    log.error("[ChaosAltar] No suitable world found to hop");
                    isHopping = false;
                    return;
                }

                World targetWorld = worlds.get(new Random().nextInt(worlds.size()));

                log.info("[ChaosAltar] Found {} suitable worlds, hopping to world: {}", worlds.size(), targetWorld.getId());
                
                // Use Microbot.hopToWorld like other scripts do
                boolean hopSuccess = Microbot.hopToWorld(targetWorld.getId());
                if (!hopSuccess) {
                    log.error("[ChaosAltar] Failed to initiate world hop to: {}", targetWorld.getId());
                    isHopping = false;
                } else {
                    log.info("[ChaosAltar] World hop initiated successfully to: {}", targetWorld.getId());
                    // Update last hop time
                    lastHopTime = System.currentTimeMillis();
                }
            } catch (Exception e) {
                log.error("[ChaosAltar] Error during world hop", e);
                isHopping = false;
            }
        });
    }

    /**
     * Checks if world hopping is in progress and handles hop completion.
     * Should be called regularly by the chaos altar script.
     * 
     * @return true if currently hopping worlds
     */
    public static boolean processWorldHop() {
        if (!isHopping) {
            return false;
        }

        // Check if we've been waiting too long for hop to complete
        long currentTime = System.currentTimeMillis();
        if (currentTime - hopStartTime > MAX_HOP_WAIT_TIME_MS) {
            log.warn("[ChaosAltar] World hop taking too long, resetting hop state");
            isHopping = false;
            return false;
        }

        // Check if hop is complete
        GameState gameState = Microbot.getClient().getGameState();
        boolean microbotHopping = Microbot.isHopping();
        
        if (gameState == GameState.LOGGED_IN && !microbotHopping) {
            log.info("[ChaosAltar] World hop complete, resuming script");
            isHopping = false;
            return false;
        }

        // Still hopping
        return true;
    }

    /**
     * Checks if currently hopping worlds.
     */
    public static boolean isCurrentlyHopping() {
        return isHopping;
    }

    /**
     * Forces reset of hopping state (for emergency situations).
     */
    public static void resetHoppingState() {
        isHopping = false;
        hopStartTime = 0;
        log.info("[ChaosAltar] World hop state reset");
    }

    /**
     * Gets time since last hop in milliseconds.
     */
    public static long getTimeSinceLastHop() {
        return System.currentTimeMillis() - lastHopTime;
    }

    /**
     * Emergency hop when being attacked - bypasses cooldown for maximum safety
     */
    public static boolean emergencyHopOnAttack() {
        if (isHopping) {
            return false; // Already hopping
        }

        // Only trigger in wilderness when actually being attacked by a player
        if (!Rs2Pvp.isInWilderness() || !Rs2Player.isInCombat()) {
            return false;
        }

        // Check if being attacked by another player (not NPC)
        if (Microbot.getClient().getLocalPlayer().getInteracting() instanceof Player) {
            log.warn("[ChaosAltar] EMERGENCY HOP - Being attacked by player!");
            Microbot.log("[ChaosAltar] EMERGENCY HOP - PKer detected, hopping immediately!");
            safeHopWorlds("EMERGENCY: Being attacked by player");
            return true;
        }

        return false;
    }

    /**
     * Checks if a specific player has been detected for longer than the threshold.
     * 
     * @param playerName The name of the player to check
     * @param thresholdMs Time in milliseconds
     * @return true if player has been detected longer than threshold
     */
    public static boolean isPlayerDetectedFor(String playerName, long thresholdMs) {
        Long detectionTime = playerDetectionTimes.get(playerName);
        if (detectionTime == null) {
            return false;
        }
        return System.currentTimeMillis() - detectionTime >= thresholdMs;
    }

    /**
     * Gets the number of currently tracked players.
     */
    public static int getTrackedPlayerCount() {
        cleanupOldDetections();
        return playerDetectionTimes.size();
    }

    /**
     * Clears all player detection data.
     */
    public static void clearPlayerDetections() {
        playerDetectionTimes.clear();
    }
}
