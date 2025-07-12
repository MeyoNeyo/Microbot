package net.runelite.client.plugins.microbot.wineofzamorak;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.wineofzamorak.enums.WineOfZamorakState;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class WineOfZamorakScript extends Script {

    public static String version = "1.0.0";
    public static WineOfZamorakState state = WineOfZamorakState.IDLE;
    
    private WineOfZamorakConfig config;
    private int winesCollected = 0;
    private int worldsHopped = 0;
    private long lastWorldHop = 0;
    private Set<Integer> recentlyVisitedWorlds = new HashSet<>();
    private int currentWorldIndex = 0;
    private volatile boolean isRunning = false;
    
    // Constants
    private static final int WINE_OF_ZAMORAK_ID = 245;
    private static final int LAW_RUNE_ID = 563;
    private static final int AIR_RUNE_ID = 556;
    private static final int AIR_STAFF_ID = 1397;
    private static final int ZAMORAK_ROBE_TOP_ID = 1033;
    private static final int ZAMORAK_ROBE_BOTTOM_ID = 1035;
    
    // Locations
    private static final WorldPoint CHAOS_TEMPLE_ENTRANCE = new WorldPoint(2941,3517,0);
    private static final WorldPoint WINE_TABLE_2ND_FLOOR = new WorldPoint(2939, 3517, 1); // Updated wine spot coordinates
    private static final WorldPoint FALADOR_BANK = new WorldPoint(2946, 3368, 0);

    public boolean run(WineOfZamorakConfig config) {
        Microbot.log("WineOfZamorakScript: Starting run method");
        this.config = config;
        isRunning = true;

        // Check if player is already near the Chaos Temple or at the wine table
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc.distanceTo(WINE_TABLE_2ND_FLOOR) <= 3) {
            state = WineOfZamorakState.WAITING_FOR_WINE;
            Microbot.log("Player is already at the wine table. Skipping travel from Falador.");
        } else if (playerLoc.distanceTo(CHAOS_TEMPLE_ENTRANCE) <= 20) {
            state = WineOfZamorakState.TRAVELING_TO_WINE_SPOT;
            Microbot.log("Player is near the Chaos Temple. Skipping travel from Falador.");
        } else {
            state = WineOfZamorakState.CHECKING_PRECONDITIONS;
        }

        new Thread(() -> {
            while (isRunning) {
                try {
                    Microbot.log("WineOfZamorakScript: Current state: " + state);
                    switch (state) {
                        case IDLE:
                            Microbot.log("State: IDLE - Checking preconditions");
                            state = WineOfZamorakState.CHECKING_PRECONDITIONS;
                            break;
                        case CHECKING_PRECONDITIONS:
                            Microbot.log("State: CHECKING_PRECONDITIONS");
                            if (Rs2Inventory.isFull()) {
                                Microbot.log("Inventory is full. Going to bank.");
                                state = WineOfZamorakState.BANKING;
                                break;
                            }
                            if (!checkPreconditions()) {
                                Microbot.log("Preconditions not met. Returning to IDLE.");
                                state = WineOfZamorakState.IDLE;
                                Thread.sleep(1000);
                                continue;
                            }
                            state = WineOfZamorakState.TRAVELING_TO_WINE_SPOT;
                            break;
                        case TRAVELING_TO_WINE_SPOT:
                            Microbot.log("State: TRAVELING_TO_WINE_SPOT");
                            travelToWineSpot();
                            break;
                        case WAITING_FOR_WINE:
                            Microbot.log("State: WAITING_FOR_WINE");
                            waitForWine();
                            break;
                        case CASTING_TELEKINETIC_GRAB:
                            Microbot.log("State: CASTING_TELEKINETIC_GRAB");
                            castTelekineticGrab();
                            break;
                        case WORLD_HOPPING:
                            Microbot.log("State: WORLD_HOPPING");
                            hopWorld();
                            break;
                        case BANKING:
                            Microbot.log("State: BANKING");
                            depositWines();
                            break;
                        case STOPPING:
                            Microbot.log("State: STOPPING");
                            isRunning = false;
                            break;
                    }
                    Thread.sleep(200);
                } catch (Exception e) {
                    Microbot.log("Exception in main loop: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            Microbot.log("WineOfZamorakScript: Exiting run loop");
        }).start();
        return true;
    }
    
    private void executeState() {
        switch (state) {
            case IDLE:
                state = WineOfZamorakState.CHECKING_PRECONDITIONS;
                break;
                
            case CHECKING_PRECONDITIONS:
                if (checkPreconditions()) {
                    if (Rs2Inventory.isFull()) {
                        state = WineOfZamorakState.BANKING;
                    } else if (!isAtWineTable()) {
                        state = WineOfZamorakState.TRAVELING_TO_WINE_SPOT;
                    } else {
                        state = WineOfZamorakState.WAITING_FOR_WINE;
                    }
                } else {
                    Microbot.status = "Preconditions failed - stopping";
                    shutdown();
                }
                break;
                
            case TRAVELING_TO_WINE_SPOT:
                travelToWineSpot();
                break;
                
            case WAITING_FOR_WINE:
                if (isWineAvailable()) {
                    state = WineOfZamorakState.CASTING_TELEKINETIC_GRAB;
                } else if (config.enableWorldHopping()) {
                    state = WineOfZamorakState.WORLD_HOPPING;
                } else {
                    sleep(2000, 3000);
                }
                break;
                
            case CASTING_TELEKINETIC_GRAB:
                castTelekineticGrab();
                break;
                
            case WORLD_HOPPING:
                hopWorld();
                break;
                
            case BANKING:
                depositWines();
                break;
                
            case STOPPING:
                shutdown();
                break;
        }
    }
    
    private boolean checkPreconditions() {
        Microbot.log("Checking preconditions...");
        if (Microbot.getClient().getLocalPlayer() == null) {
            Microbot.log("Precondition failed: Local player is null");
            return false;
        }
        int totalLevel = Microbot.getClient().getTotalLevel();
        Microbot.log("Player total level: " + totalLevel);
        int magicLevel = Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.MAGIC);
        if (magicLevel < 33) {
            Microbot.log("Precondition failed: Magic level too low (" + magicLevel + " < 33)");
            return false;
        }
        // Check for law runes
        int lawRunes = Rs2Inventory.count(563);
        if (lawRunes < 1) {
            Microbot.log("Precondition failed: Not enough law runes (" + lawRunes + ")");
            return false;
        }
        // Check for air runes or air staff
        int airRunes = Rs2Inventory.count(556);
        boolean hasAirStaff = Rs2Equipment.isWearing("staff of air") || Rs2Equipment.isWearing("mystic air staff") || Rs2Equipment.isWearing("air battlestaff") || Rs2Equipment.isWearing("kodai wand");
        if (airRunes < 3 && !hasAirStaff) {
            Microbot.log("Precondition failed: Not enough air runes and no air staff equipped (air runes: " + airRunes + ", hasAirStaff: " + hasAirStaff + ")");
            return false;
        }
        // Check for inventory space
        if (Rs2Inventory.isFull()) {
            Microbot.log("Precondition failed: Inventory is full");
            return false;
        }
        Microbot.log("All preconditions met.");
        return true;
    }
    
    private boolean isWearingZamorakRobes() {
        return Rs2Equipment.isWearing(ZAMORAK_ROBE_TOP_ID) && Rs2Equipment.isWearing(ZAMORAK_ROBE_BOTTOM_ID);
    }
    
    private boolean hasRequiredRunes() {
        boolean hasLawRunes = Rs2Inventory.hasItem(LAW_RUNE_ID);
        boolean hasAirRunes = Rs2Inventory.hasItem(AIR_RUNE_ID) || Rs2Equipment.isWearing(AIR_STAFF_ID);
        
        return hasLawRunes && hasAirRunes;
    }
    
    private boolean isAtWineTable() {
        return Rs2Player.getWorldLocation().distanceTo(WINE_TABLE_2ND_FLOOR) <= 3;
    }
    
    private boolean isWineAvailable() {
        return Rs2GroundItem.exists(WINE_OF_ZAMORAK_ID, 5);
    }
    
    private void travelToWineSpot() {
        Microbot.log("Traveling to wine spot...");
        
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        
        // If not at Chaos Temple area
        if (currentLocation.distanceTo(CHAOS_TEMPLE_ENTRANCE) > 20) {
            Rs2Walker.walkTo(CHAOS_TEMPLE_ENTRANCE);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(CHAOS_TEMPLE_ENTRANCE) <= 1, 10000);
        }
        // Find and climb the ladder to go upstairs
        boolean climbed = Rs2GameObject.interact("Ladder", "Climb-up");
        if (climbed) {
            Microbot.log("Successfully interacted with ladder by name, attempting to climb.");
            sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() == 1, 8000);
            state = WineOfZamorakState.WAITING_FOR_WINE;
        } else {
            Microbot.log("Ladder not found or could not interact by name at Chaos Temple entrance.");
        }
        

    }
    
    private void waitForWine() {
        Microbot.log("Waiting for wine...");
        if (isWineAvailable()) {
            state = WineOfZamorakState.CASTING_TELEKINETIC_GRAB;
        } else if (config.enableWorldHopping()) {
            state = WineOfZamorakState.WORLD_HOPPING;
        } else {
            sleep(2000, 3000);
        }
    }
    
    private void castTelekineticGrab() {
        Microbot.log("Casting telekinetic grab...");
        // Check if wine is on the ground within 5 tiles
        boolean wineExists = Rs2GroundItem.exists(245, 5);
        if (!wineExists) {
            Microbot.log("No Wine of Zamorak ground item found to telegrab.");
            state = WineOfZamorakState.WAITING_FOR_WINE;
            return;
        }
        // Cast Telekinetic Grab spell
        boolean spellCasted = Rs2Magic.cast(MagicAction.TELEKINETIC_GRAB);
        if (!spellCasted) {
            Microbot.log("Failed to cast Telekinetic Grab spell.");
            state = WineOfZamorakState.WAITING_FOR_WINE;
            return;
        }
        // Click the wine ground item
        boolean clicked = Rs2GroundItem.interact(245, "Cast", 5);
        if (clicked) {
            Microbot.log("Clicked Wine of Zamorak with Telekinetic Grab.");
            sleepUntil(() -> !Rs2GroundItem.exists(245, 5), 5000);
            winesCollected++;
        } else {
            Microbot.log("Failed to click Wine of Zamorak after casting spell.");
        }
        // Inventory logic handled elsewhere
        if (Rs2Inventory.isFull()) {
            Microbot.log("Inventory full after looting, going to bank.");
            state = WineOfZamorakState.BANKING;
        } else {
            Microbot.log("Inventory not full, hopping world to continue looting.");
            state = WineOfZamorakState.WORLD_HOPPING;
        }
    }
    
    private void hopWorld() {
        Microbot.log("Hopping world...");
        
        if (!canWorldHop()) {
            state = WineOfZamorakState.WAITING_FOR_WINE;
            return;
        }
        
        Microbot.status = "World hopping...";
        
        try {
            int currentWorld = Microbot.getClient().getWorld();
            net.runelite.api.World targetWorld = getNextValidApiWorld();
            if (targetWorld != null) {
                boolean hopped = Microbot.hopToWorld(targetWorld.getId());
                if (hopped) {
                    // Wait for world to change
                    boolean worldChanged = sleepUntil(() -> Microbot.getClient().getWorld() != currentWorld, 10000);
                    // Wait for player to be fully loaded in new world (LOGGED_IN and valid location)
                    boolean loaded = false;
                    if (worldChanged) {
                        loaded = sleepUntil(() ->
                            Microbot.getClient().getGameState() == net.runelite.api.GameState.LOGGED_IN &&
                            Rs2Player.getWorldLocation() != null &&
                            Rs2Player.getWorldLocation().getX() > 0,
                            10000);
                    }
                    if (worldChanged && loaded) {
                        recentlyVisitedWorlds.add(currentWorld);
                        lastWorldHop = System.currentTimeMillis();
                        worldsHopped++;
                        if (recentlyVisitedWorlds.size() > 10) {
                            recentlyVisitedWorlds.clear();
                        }
                        if (Rs2Inventory.isFull()) {
                            Microbot.log("Inventory full after world hop, going to bank.");
                            state = WineOfZamorakState.BANKING;
                        } else {
                            Microbot.log("Inventory not full after world hop, returning to WAITING_FOR_WINE.");
                            state = WineOfZamorakState.WAITING_FOR_WINE;
                        }
                    } else {
                        Microbot.log("World hop failed or player not loaded in new world");
                        state = WineOfZamorakState.WAITING_FOR_WINE;
                    }
                } else {
                    Microbot.log("World hop failed");
                    state = WineOfZamorakState.WAITING_FOR_WINE;
                }
            } else {
                Microbot.status = "No valid worlds found";
                state = WineOfZamorakState.WAITING_FOR_WINE;
            }
        } catch (Exception e) {
            Microbot.log("Error world hopping: " + e.getMessage());
            state = WineOfZamorakState.WAITING_FOR_WINE;
        }
    }
    
    private boolean canWorldHop() {
        // Check if enough time has passed since last hop
        long timeSinceLastHop = System.currentTimeMillis() - lastWorldHop;
        return timeSinceLastHop >= (config.worldHopDelay() * 1000L);
    }
    
    private net.runelite.api.World getNextValidApiWorld() {
        List<net.runelite.api.World> availableWorlds = new ArrayList<>();
        try {
            // Use WorldService to get the world list (more reliable than getClient().getWorldList())
            List<net.runelite.http.api.worlds.World> worldList = Microbot.getWorldService().getWorlds().getWorlds();
            if (worldList == null || worldList.isEmpty()) {
                Microbot.log("WorldService.getWorlds() returned null or empty");
            } else {
                for (net.runelite.http.api.worlds.World apiWorld : worldList) {
                    // Convert to net.runelite.api.World for compatibility
                    net.runelite.api.World world = Microbot.getClient().createWorld();
                    if (world != null) {
                        world.setId(apiWorld.getId());
                        world.setTypes(net.runelite.client.util.WorldUtil.toWorldTypes(apiWorld.getTypes()));
                        world.setPlayerCount(apiWorld.getPlayers());
                        world.setActivity(apiWorld.getActivity());
                        world.setAddress(apiWorld.getAddress());
                        world.setLocation(apiWorld.getLocation());
                        if (isValidApiWorld(world)) {
                            availableWorlds.add(world);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Microbot.log("Error getting world list: " + e.getMessage());
        }
        if (availableWorlds.isEmpty()) {
            Microbot.log("No available worlds after filtering");
            return null;
        }
        // Find next world that hasn't been recently visited
        for (int i = 0; i < availableWorlds.size(); i++) {
            currentWorldIndex = (currentWorldIndex + 1) % availableWorlds.size();
            net.runelite.api.World world = availableWorlds.get(currentWorldIndex);
            if (!recentlyVisitedWorlds.contains(world.getId()) && 
                world.getId() != Microbot.getClient().getWorld()) {
                return world;
            }
        }
        // If all worlds have been recently visited, return the next one anyway
        currentWorldIndex = (currentWorldIndex + 1) % availableWorlds.size();
        return availableWorlds.get(currentWorldIndex);
    }
    
    private boolean isValidApiWorld(net.runelite.api.World world) {
        if (world == null) return false;

        // Skip current world
        if (world.getId() == Microbot.getClient().getWorld()) {
            return false;
        }

        // Skip worlds with high player count
        if (world.getPlayerCount() >= 1950) {
            return false;
        }

        EnumSet<net.runelite.api.WorldType> types = world.getTypes();

        // Skip members worlds if player is F2P
        if (!Microbot.getClient().getWorldType().contains(net.runelite.api.WorldType.MEMBERS)
            && types.contains(net.runelite.api.WorldType.MEMBERS)) {
            return false;
        }

        // Skip PvP and high-risk worlds if configured
        if (config.avoidPvpWorlds()) {
            if (types.contains(net.runelite.api.WorldType.PVP) ||
                types.contains(net.runelite.api.WorldType.HIGH_RISK) ||
                types.contains(net.runelite.api.WorldType.DEADMAN)) {
                return false;
            }
        }

        // Skip skill total requirement worlds
        if (types.contains(net.runelite.api.WorldType.SKILL_TOTAL)) {
            return false;
        }

        // Skip Deadman worlds (already covered above), and any other restricted types that exist in your API
        // Add more filters here if your RuneLite API defines more restricted world types

        return true;
    }
    
    private void depositWines() {
        Microbot.log("Depositing wines...");

        // Rs2Walker will automatically use teleports if available and efficient
        Rs2Walker.walkTo(FALADOR_BANK);
        sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(FALADOR_BANK) <= 5, 15000);

        // Open bank and deposit
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 5000);
        }

        if (Rs2Bank.isOpen()) {
            Rs2Bank.depositAll(WINE_OF_ZAMORAK_ID);
            sleep(1000, 1500);

            // Check if we still have required runes
            if (!hasRequiredRunes()) {
                Microbot.status = "Out of required runes - waiting for user to add more";
                Microbot.log("Out of required runes. Please add more runes to inventory and the script will continue.");
                state = WineOfZamorakState.IDLE;
                return;
            }

            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 3000);

            // Go back to wine spot
            state = WineOfZamorakState.TRAVELING_TO_WINE_SPOT;
        }
    }

    private boolean shouldStop() {
        return worldsHopped >= config.maxWorldsToTry() && config.enableWorldHopping();
    }
    
    @Override
    public void shutdown() {
        Rs2Walker.setTarget(null); // Stop any walking
        super.shutdown();
        isRunning = false;
        Microbot.log("WineOfZamorakScript: Shutdown called, walking stopped, script stopped.");
    }
    
    public WineOfZamorakState getState() {
        return state;
    }
    
    // Getter methods for overlay
    public int getWinesCollected() {
        return winesCollected;
    }
    
    public int getWorldsHopped() {
        return worldsHopped;
    }
}
