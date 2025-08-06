package net.runelite.client.plugins.microbot.woodcutting;

import net.runelite.api.GameObject;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.woodcutting.Rs2Woodcutting;
import net.runelite.client.plugins.microbot.woodcutting.enums.WoodcuttingResetOptions;
import net.runelite.client.plugins.microbot.woodcutting.enums.WoodcuttingTree;
import net.runelite.client.plugins.microbot.woodcutting.enums.WoodcuttingWalkBack;

import java.awt.event.KeyEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.api.gameval.AnimationID.*;
import static net.runelite.api.gameval.ItemID.TINDERBOX;

enum State {
    FIREMAKING,
    RESETTING,
    WOODCUTTING,
}

public class AutoWoodcuttingScript extends Script {

    public static String version = "1.6.5";
    public volatile boolean cannotLightFire = false;
	private boolean hasAutoHopMessageShown = false;

    State state = State.WOODCUTTING;
    private static WorldPoint returnPoint;
    private static final Integer[] FIRE_IDS = {26185, 49927};
    
    // Enhanced firemaking variables
    private WorldPoint lastFiremakingLocation = null;
    private WorldPoint previousPlayerLocation = null;
    private boolean isFiremakingInProgress = false;
    private boolean hasJustMovedEast = false;
    private long lastFireCompletionTime = 0;
    private long lastLocationCheckTime = 0;
    public static final List<Integer> BURNING_ANIMATION_IDS = List.of(
            FORESTRY_CAMPFIRE_BURNING_LOGS,
            FORESTRY_CAMPFIRE_BURNING_MAGIC_LOGS,
            FORESTRY_CAMPFIRE_BURNING_MAHOGANY_LOGS,
            FORESTRY_CAMPFIRE_BURNING_MAPLE_LOGS,
            FORESTRY_CAMPFIRE_BURNING_OAK_LOGS,
            FORESTRY_CAMPFIRE_BURNING_REDWOOD_LOGS,
            FORESTRY_CAMPFIRE_BURNING_TEAK_LOGS,
            FORESTRY_CAMPFIRE_BURNING_WILLOW_LOGS,
            FORESTRY_CAMPFIRE_BURNING_YEW_LOGS,
            HUMAN_CREATEFIRE
    );

    public boolean run(AutoWoodcuttingConfig config) {
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyWoodcuttingSetup();
        Rs2AntibanSettings.dynamicActivity = true;
        Rs2AntibanSettings.dynamicIntensity = true;
        initialPlayerLocation = null;
        if (config.firemakeOnly()){
            state = State.FIREMAKING;
        }
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {

                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                if(Rs2AntibanSettings.actionCooldownActive) return;

				if (!hasAutoHopMessageShown && config.hopWhenPlayerDetected()) {
					Microbot.showMessage("Make sure autologin plugin is enabled and randomWorld checkbox is checked!");
					hasAutoHopMessageShown = true;
				}

                if (initialPlayerLocation == null) {
                    initialPlayerLocation = Rs2Player.getWorldLocation();
                }

                if (returnPoint == null) {
                    returnPoint = Rs2Player.getWorldLocation();
                }

                // Enhanced firemaking logic: Check for immediate movement detection
                if (config.resetOptions() == WoodcuttingResetOptions.FIREMAKE || 
                    config.resetOptions() == WoodcuttingResetOptions.CAMPFIRE_FIREMAKE) {
                    checkForEastMovementAfterFire(config);
                }

                if (!config.TREE().hasRequiredLevel()) {
                    Microbot.showMessage("You do not have the required woodcutting level to cut this tree.");
                    shutdown();
                    return;
                }
                
                if (!Rs2Inventory.hasItem("axe")) {
                    if (!Rs2Equipment.hasEquippedContains("axe")) {
                        Microbot.showMessage("Unable to find axe in inventory/equipped");
                        shutdown();
                        return;
                    }
                }

                if (state != State.RESETTING && (Rs2Player.isMoving() || Rs2Player.isAnimating()))
                {
                    // Enhanced firemaking logic: Check if player moved east after completing fire
                    checkForEastMovementAfterFire(config);
                    return;
                }

                if (Rs2AntibanSettings.actionCooldownActive)
                    return;

                switch (state) {
                    case WOODCUTTING:

                        if (config.hopWhenPlayerDetected()) {
                            if (Rs2Player.logoutIfPlayerDetected(1, 10000))
                                return;
                        }

                        if (Rs2Woodcutting.isWearingAxeWithSpecialAttack())
                            Rs2Combat.setSpecState(true, 1000);

                        if (Rs2Inventory.isFull()) {
                            state = State.RESETTING;
                            return;
                        }

                        GameObject tree = Rs2GameObject.findReachableObject(config.TREE().getName(), true, config.distanceToStray(), getInitialPlayerLocation(), config.TREE().equals(WoodcuttingTree.REDWOOD),config.TREE().getAction());

                        if (tree != null) {
                            if (Rs2GameObject.interact(tree, config.TREE().getAction())) {
                                Rs2Player.waitForAnimation();
                                Rs2Antiban.actionCooldown();

                                if (config.walkBack().equals(WoodcuttingWalkBack.LAST_LOCATION)) {
                                    returnPoint = Rs2Player.getWorldLocation();
                                }
                            }
                        }
                        break;
                    case FIREMAKING:
                        Microbot.log("Starting Firemaking only mode");

                        if (!Rs2Inventory.hasItem(TINDERBOX)) {
                            Rs2Bank.openBank();
                            sleepUntil(Rs2Bank::isOpen, 20000);
                            Rs2Bank.withdrawItem(true,"Tinderbox");
                        }

                        if (!Rs2Inventory.hasItem(config.TREE().getLog())) {
                            Microbot.log("Opening bank");
                            Rs2Bank.openBank();
                            sleepUntil(Rs2Bank::isOpen, 20000);
                            Rs2Bank.withdrawAll(config.TREE().getLog());
                            Rs2Bank.closeBank();
                            sleep(500, 1200);;
                        }

                        walkBack(config);

                        state = State.RESETTING;
                        break;

                    case RESETTING:
                        resetInventory(config);
                        break;
                }
            } catch (Exception ex) {
                Microbot.log(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private void resetInventory(AutoWoodcuttingConfig config) {
        switch (config.resetOptions()) {
            case DROP:
                Rs2Inventory.dropAllExcept(false, config.interactOrder(), "axe", "tinderbox", "crystal shard", "demon tear");
                state = State.WOODCUTTING;
                break;
            case BANK:
                List<String> itemNames = Arrays.stream(config.itemsToBank().split(",")).map(String::toLowerCase).collect(Collectors.toList());

                if (!Rs2Bank.bankItemsAndWalkBackToOriginalPosition(itemNames, getReturnPoint(config)))
                    return;

                state = State.WOODCUTTING;
                break;
            case CAMPFIRE_FIREMAKE:
            case FIREMAKE:
                burnLog(config);

                if (Rs2Inventory.contains(config.TREE().getLog())) return;

                walkBack(config);

                if (config.firemakeOnly()){
                    state = State.FIREMAKING;
                } else {
                    state = State.WOODCUTTING;
                }
                break;
            case FLETCH_ARROWSHAFT:
                fletchArrowShaft(config);
                
                walkBack(config);
                state = State.WOODCUTTING;
                break;
        }
    }

    private void burnLog(AutoWoodcuttingConfig config) {
        WorldPoint fireSpot;
        boolean useCampfire = false;
        GameObject fire = Rs2GameObject.getGameObject(FIRE_IDS,6);
        if(config.resetOptions() == WoodcuttingResetOptions.CAMPFIRE_FIREMAKE) {

            if (fire != null) {
                useCampfire = true;

            }
        }
        if ((Rs2Player.isStandingOnGameObject() || cannotLightFire) && !Rs2Player.isAnimating() && !useCampfire) {
            fireSpot = fireSpot(1);
            Rs2Walker.walkFastCanvas(fireSpot);
            cannotLightFire = false;
        }
        if (!isFiremake() && !useCampfire) {
            Rs2Inventory.waitForInventoryChanges(() -> {
                Rs2Inventory.use("tinderbox");
                sleepUntil(Rs2Inventory::isItemSelected);
                Rs2Inventory.useLast(config.TREE().getLogID());
                // Mark that firemaking is starting and set initial location tracking
                isFiremakingInProgress = true;
                lastFiremakingLocation = Rs2Player.getWorldLocation();
                previousPlayerLocation = Rs2Player.getWorldLocation();
            }, 300, 100);
        }
        else if (!isFiremake() && useCampfire) {
            if (fire != null) {
                Rs2Inventory.useItemOnObject(config.TREE().getLogID(),fire.getId());
                sleepUntil(() -> (!Rs2Player.isMoving() && Rs2Widget.findWidget("How many would you like to burn?", null, false) != null), 5000);
                Rs2Random.waitEx(400,200);
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            }
        }
        
        // Don't use long sleeps - let the movement detection handle the timing
        sleepUntil(() -> !isFiremake(), 100); // Much shorter timeout
        if (!isFiremake()) {sleepUntil(() -> cannotLightFire, 1000);} // Reduced timeout
        
        // Only wait for XP if movement detection hasn't triggered
        if (!cannotLightFire && isFiremake() && !hasJustMovedEast) {
            sleepUntil(() -> Rs2Player.waitForXpDrop(Skill.FIREMAKING, 5000), 5000); // Reduced timeout
        }
    }

    private WorldPoint fireSpot(int distance) {
        List<WorldPoint> worldPoints = Rs2Tile.getWalkableTilesAroundPlayer(distance);
        WorldPoint playerLocation = Rs2Player.getWorldLocation();

        // Create a map to group tiles by their distance from the player
        Map<Integer, WorldPoint> distanceMap = new HashMap<>();

        for (WorldPoint walkablePoint : worldPoints) {
            if (Rs2GameObject.getGameObject(o -> o.getWorldLocation().equals(walkablePoint), distance) == null) {
                int tileDistance = playerLocation.distanceTo(walkablePoint);
                distanceMap.putIfAbsent(tileDistance, walkablePoint);
            }
        }

        // Find the minimum distance that has walkable points
        Optional<Integer> minDistanceOpt = distanceMap.keySet().stream().min(Integer::compare);

        if (minDistanceOpt.isPresent()) {
            return distanceMap.get(minDistanceOpt.get());
        }

        // Recursively increase the distance if no valid point is found
        return fireSpot(distance + 1);
    }

    private boolean isFiremake() {
        if (cannotLightFire) return false;
        return Rs2Player.isAnimating(1800) && BURNING_ANIMATION_IDS.contains(Rs2Player.getLastAnimationID());
    }
    
    private void fletchArrowShaft(AutoWoodcuttingConfig config) {
        Rs2Inventory.combineClosest("knife", config.TREE().getLog());
        sleepUntil(Rs2Widget::isProductionWidgetOpen, 5000);
        Rs2Widget.clickWidget("arrow shafts");
        Rs2Player.waitForAnimation();
        sleepUntil(() -> !isFlectching(), 5000);
    }
    
    private boolean isFlectching() {
        return Rs2Player.isAnimating(3000) && Rs2Player.getLastAnimationID() == 1248; // FLETCHING_BOW_CUTTING
    }

    public static WorldPoint getReturnPoint(AutoWoodcuttingConfig config) {
        if (config.walkBack().equals(WoodcuttingWalkBack.LAST_LOCATION)) {
            return returnPoint == null ? Rs2Player.getWorldLocation() : returnPoint;
        } else {
            return initialPlayerLocation == null ? Rs2Player.getWorldLocation() : initialPlayerLocation;
        }
    }

    private void walkBack(AutoWoodcuttingConfig config) {
        Rs2Walker.walkTo(new WorldPoint(getReturnPoint(config).getX() - Rs2Random.between(-1, 1), getReturnPoint(config).getY() - Rs2Random.between(-1, 1), getReturnPoint(config).getPlane()));
        sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(getReturnPoint(config)) <= 4);
    }

    /**
     * Enhanced firemaking logic: Checks if player moved east after completing a fire
     * and immediately starts the next fire. Uses real-time position checking for faster detection.
     */
    private void checkForEastMovementAfterFire(AutoWoodcuttingConfig config) {
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        long currentTime = System.currentTimeMillis();
        
        // Only proceed if we're in a firemaking mode
        if (!(config.resetOptions() == WoodcuttingResetOptions.FIREMAKE || 
              config.resetOptions() == WoodcuttingResetOptions.CAMPFIRE_FIREMAKE)) {
            return;
        }

        // Initialize tracking if this is the first time
        if (lastFiremakingLocation == null) {
            lastFiremakingLocation = currentLocation;
            previousPlayerLocation = currentLocation;
            lastLocationCheckTime = currentTime;
            return;
        }

        // Check for position changes every tick to catch movement immediately
        if (previousPlayerLocation != null && !currentLocation.equals(previousPlayerLocation)) {
            // Player has moved - check if it's eastward movement after firemaking
            if (isFiremakingInProgress && 
                currentLocation.getX() > previousPlayerLocation.getX() && 
                currentLocation.getY() == previousPlayerLocation.getY() &&
                currentLocation.getPlane() == previousPlayerLocation.getPlane()) {
                
                // Player moved east! This means fire was successfully lit
                Microbot.log("Player moved east - fire completed, starting next fire immediately");
                isFiremakingInProgress = false;
                hasJustMovedEast = true;
                lastFireCompletionTime = currentTime;
                
                // Immediately attempt to start next fire if we have supplies
                if (Rs2Inventory.hasItem(TINDERBOX) && Rs2Inventory.hasItem(config.TREE().getLog())) {
                    quickStartNextFire(config);
                }
            }
            
            // Update previous location for next comparison
            previousPlayerLocation = currentLocation;
        }

        // If player isn't moving but we were tracking firemaking, check if animation stopped
        if (isFiremakingInProgress && !isFiremake() && !Rs2Player.isMoving()) {
            // Animation stopped without east movement - firemaking completed or failed
            isFiremakingInProgress = false;
            lastFireCompletionTime = currentTime;
            Microbot.log("Firemaking animation ended without movement");
        }

        // Reset east movement flag if player moves in other directions or after delay
        if (hasJustMovedEast && (currentTime - lastFireCompletionTime > 2000 || 
            (currentLocation.getY() != lastFiremakingLocation.getY()))) {
            hasJustMovedEast = false;
        }

        // Update tracking location when player stops moving
        if (!Rs2Player.isMoving() && currentTime - lastLocationCheckTime > 100) {
            lastFiremakingLocation = currentLocation;
            lastLocationCheckTime = currentTime;
        }
    }

    /**
     * Quickly starts the next fire without delays for optimal firemaking speed
     */
    private void quickStartNextFire(AutoWoodcuttingConfig config) {
        if (!Rs2Inventory.hasItem(TINDERBOX) || !Rs2Inventory.hasItem(config.TREE().getLog())) {
            return;
        }

        // Don't start if we're already firemaking or if there's already a fire here
        if (isFiremake() || Rs2Player.isStandingOnGameObject()) {
            return;
        }

        Microbot.log("Quick-starting next fire - player moved east");
        
        // Use tinderbox on log immediately without waiting
        Rs2Inventory.use("tinderbox");
        sleepUntil(Rs2Inventory::isItemSelected, 300); // Reduced timeout for faster response
        
        if (Rs2Inventory.isItemSelected()) {
            Rs2Inventory.useLast(config.TREE().getLogID());
            isFiremakingInProgress = true;
            // Reset the east movement flag since we're starting a new fire
            hasJustMovedEast = false;
            // Update our tracking location for the new fire
            lastFiremakingLocation = Rs2Player.getWorldLocation();
            previousPlayerLocation = Rs2Player.getWorldLocation();
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        returnPoint = null;
        initialPlayerLocation = null;
		hasAutoHopMessageShown = false;
        
        // Reset enhanced firemaking tracking
        lastFiremakingLocation = null;
        previousPlayerLocation = null;
        isFiremakingInProgress = false;
        hasJustMovedEast = false;
        lastFireCompletionTime = 0;
        lastLocationCheckTime = 0;
        
        Rs2Antiban.resetAntibanSettings();
    }
}