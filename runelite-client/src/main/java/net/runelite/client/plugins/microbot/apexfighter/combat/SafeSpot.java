package net.runelite.client.plugins.microbot.apexfighter.combat;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterPlugin;
import net.runelite.client.plugins.microbot.apexfighter.enums.State;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import java.util.concurrent.TimeUnit;

public class SafeSpot extends Script {

    public WorldPoint currentSafeSpot = null;
    private boolean messageShown = false;
    private static boolean priorityMovementRequested = false;
    private static long priorityRequestTime = 0;

public boolean run(ApexFighterConfig config) {
    mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
        try {
            if (ApexFighterPlugin.getState().equals(State.BANKING) || ApexFighterPlugin.getState().equals(State.WALKING)) return;
            if (!Microbot.isLoggedIn() || !super.run() || !config.toggleSafeSpot()) return;
            
            // Allow movement if priority is requested, even if player is currently moving
            // Also check if priority request is recent (within 3 seconds) to prevent stale requests
            boolean recentPriorityRequest = priorityMovementRequested && 
                                          (System.currentTimeMillis() - priorityRequestTime) < 3000;
            
            if (!recentPriorityRequest && Rs2Player.isMoving()) return;

            currentSafeSpot = config.safeSpot();
            if (isDefaultSafeSpot(currentSafeSpot)) {
                if(!messageShown){
                    Microbot.showMessage("Please set a safespot location");
                    messageShown = true;
                }
                return;
            }

            messageShown = false;

            if (!isPlayerAtSafeSpot(currentSafeSpot)) {
                // Clear priority flag since we're handling the movement now
                priorityMovementRequested = false;
                
                // Log priority movement for debugging
                if (recentPriorityRequest) {
                    Microbot.log("[SafeSpot] PRIORITY MOVEMENT: Immediately moving to safespot after looting");
                }
                
                // Improved logic: click if visible, pathfind if not
                LocalPoint localPoint = LocalPoint.fromWorld(Microbot.getClient().getTopLevelWorldView(), currentSafeSpot);
                if (localPoint != null && Rs2Camera.isTileOnScreen(localPoint)) {
                    // Tile is on screen, click it directly
                    Rs2Walker.walkFastCanvas(currentSafeSpot);
                Microbot.pauseAllScripts.compareAndSet(false, true);
                } else {
                    // Tile is not on screen, use pathfinding
                    Rs2Walker.walkTo(currentSafeSpot);
                Microbot.pauseAllScripts.compareAndSet(false, true);
                }
                sleepUntil(() -> isPlayerAtSafeSpot(currentSafeSpot));
                Microbot.pauseAllScripts.compareAndSet(true, false);
            } else if (priorityMovementRequested) {
                // Player is already at safespot, just clear the priority flag
                priorityMovementRequested = false;
            }


        } catch (Exception ex) {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }
    }, 0, 150, TimeUnit.MILLISECONDS);  // Reduced to 150ms for even faster response
    return true;
}

private boolean isDefaultSafeSpot(WorldPoint safeSpot) {
    return safeSpot.getX() == 0 && safeSpot.getY() == 0;
}

private boolean isPlayerAtSafeSpot(WorldPoint safeSpot) {
    return safeSpot.equals(Microbot.getClient().getLocalPlayer().getWorldLocation());
}

/**
 * Request priority safespot movement - will trigger movement even if player is currently moving
 * Used by LootScript to immediately move to safespot after looting
 */
public static void requestPrioritySafespotMovement() {
    priorityMovementRequested = true;
    priorityRequestTime = System.currentTimeMillis();
    Microbot.log("[SafeSpot] Priority movement requested - will override normal movement restrictions");
}

    @Override
    public void shutdown() {
        super.shutdown();
    }
}
