package net.runelite.client.plugins.microbot.bossing.bryophyta;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.bossing.bryophyta.enums.BryophytaState;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;



@Slf4j
public class BryophytaScript extends Script {

    // Constants
    public static final String VERSION = "1.0.0";
    
    // Important locations
    public static final WorldPoint VARROCK_EAST_BANK = new WorldPoint(3253, 3420, 0);
    public static final WorldPoint VARROCK_CHURCH = new WorldPoint(3253, 3485, 0);
    public static final WorldPoint BRYOPHYTA_ENTRANCE = new WorldPoint(3174, 9900, 0);
    public static final WorldPoint BRYOPHYTA_LAIR_CENTER = new WorldPoint(3221, 9934, 0);
    
    // NPC and Object names/IDs
    public static final String BRYOPHYTA_NAME = "Bryophyta";
    public static final int BRYOPHYTA_ID = 8195;
    public static final String GROWTHLING_NAME = "Growthling";
    public static final int GROWTHLING_ID = 8194;
    public static final int GATE_ID = 32534;
    public static final String CHEST_NAME = "Chest";
    public static final int BRYOPHYTA_CHEST_ID = 14786; // From NpcID.BRYOPHYTA_CHEST
    public static final int CHEST_OBJECT_ID = 56370; // From your debug image
    
    // Simplified chest approach using exact WorldPoint
    public static final WorldPoint BRYOPHYTA_CHEST_LOCATION = new WorldPoint(3229, 9934, 0);
    public static final String CHEST_ACTION = "Open";
    
    // Projectile IDs
    public static final int BRYOPHYTA_MAGIC_PROJECTILE_ID = 139;
    
    // Item IDs and names
    public static final int MOSSY_KEY_ID = ItemID.MOSSY_KEY;
    public static final List<String> AXE_NAMES = Arrays.asList(
            "Dragon axe", "Rune axe", "Adamant axe", "Mithril axe", "Steel axe", "Iron axe", "Bronze axe"
    );
    
    // State tracking
    @Getter
    private static BryophytaState currentState = BryophytaState.IDLE;
    @Getter
    private static String botStatus = "OFF";
    @Getter
    private static int killCount = 0;
    @Getter
    private static int keysUsed = 0;
    @Getter
    private static Instant startTime;
    @Getter
    private static String currentTarget = "";
    
    // Chat message flags
    @Setter
    private static boolean chestClicked = false;
    @Setter
    private static boolean noMossyKey = false;
    @Setter
    private static boolean bossStillAlive = false;
    @Setter
    private static boolean chestLooted = false;
    
    // Equipment tracking
    private static String mainWeapon = "";
    private static boolean hasAxeEquipped = false;
    
    // Combat tracking
    private static boolean magicProjectileDetected = false;
    private static boolean bryophytaKilled = false;
    private static boolean needsChestLoot = false;
    
    // State tracking for timeout prevention
    private static BryophytaState lastState = BryophytaState.IDLE;
    private static Instant lastStateChange = Instant.now();
    private static final long STATE_TIMEOUT_SECONDS = 60; // 60 seconds timeout per state
    private static boolean initialized = false; // Track if plugin has been initialized
    
    private BryophytaConfig config;

    /**
     * Helper method to properly change states and update timing variables
     * This prevents timeout issues when manually changing states
     */
    private static void changeState(BryophytaState newState) {
        if (currentState != newState) {
            Microbot.log("State change: " + currentState + " -> " + newState);
            currentState = newState;
            lastState = newState;
            lastStateChange = Instant.now();
        }
    }

    public boolean run(BryophytaConfig config) {
        this.config = config;
        botStatus = "WAITING FOR LOGIN";
        startTime = Instant.now();
        
        Microbot.log("Bryophyta Fighter started. Waiting for player to log in...");
        
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                // First check: Make sure player is logged in
                if (!Microbot.isLoggedIn()) {
                    if (!botStatus.equals("WAITING FOR LOGIN")) {
                        botStatus = "WAITING FOR LOGIN";
                        Microbot.log("Player not logged in. Waiting...");
                    }
                    return;
                }
                if (!super.run()) return;

                // Initialize plugin state only once and only when logged in
                if (!initialized) {
                    botStatus = "INITIALIZING";
                    initializePluginState();
                    initialized = true;
                    botStatus = "RUNNING";
                }

                long startTime = System.currentTimeMillis();

                // Store main weapon on first run
                if (mainWeapon.isEmpty()) {
                    storeMainWeapon();
                }

                // Handle emergency situations first
                if (handleEmergency()) return;

                // Update current state
                updateState();

                // Check for state timeout to prevent infinite loops
                if (currentState != lastState) {
                    lastState = currentState;
                    lastStateChange = Instant.now();
                } else {
                    Duration stateTime = Duration.between(lastStateChange, Instant.now());
                    if (stateTime.getSeconds() > STATE_TIMEOUT_SECONDS) {
                        Microbot.log("State timeout detected! State '" + currentState + "' has been running for " + stateTime.getSeconds() + " seconds");
                        Microbot.log("Resetting to BANKING state to resolve the issue");
                        changeState(BryophytaState.BANKING);
                    }
                }

                // Execute based on current state
                switch (currentState) {
                    case BANKING:
                        if (!isInBryophytaLair()) {
                            handleBanking();
                        }
                        break;
                    case CHECKING_PRAYER:
                        if (!isInBryophytaLair()) {
                            handlePrayerCheck();
                        }
                        break;
                    case WALKING_TO_ENTRANCE:
                        if (!isInBryophytaLair()) {
                            walkToEntrance();
                        }else{
                            fightBoss();
                        }
                        break;
                    case ENTERING_LAIR:
                        if (!isInBryophytaLair()) {
                            enterLair();
                        }
                        break;
                    case FIGHTING_BOSS:
                        if (isInBryophytaLair()) {
                            fightBoss();
                        }
                        break;
                    case FIGHTING_GROWTHLINGS:
                        if (isInBryophytaLair()) {
                            fightGrowthlings();
                        }
                        break;
                    case LOOTING_CHEST:
                        if (isInBryophytaLair()) {
                            Rs2NpcModel bryophyta = findBryophyta();
                            if ((bryophyta == null || bryophyta.getHealthRatio() == 0) && !Rs2Player.isInCombat()) {
                                lootChest();
                            }
                        }
                        break;
                    case LOOTING_DROPS:
                        if (isInBryophytaLair()) {
                            lootDrops();
                        }
                        break;
                    case LEAVING_LAIR:
                        if (!isInBryophytaLair()) {
                            leaveLair();
                        }
                        break;
                    case TELEPORTING:
                        if (!isInBryophytaLair()) {
                            handleTeleport();
                        }
                        break;
                    case IDLE:
                        sleep(300, 500);
                        break;
                }

                long endTime = System.currentTimeMillis();
                long totalTime = endTime - startTime;
                System.out.println("[Bryophyta] Loop took: " + totalTime + "ms");

            } catch (Exception ex) {
                System.out.println("[Bryophyta] Error: " + ex.getMessage());
                ex.printStackTrace();
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        
        return true;
    }

    /**
     * Initialize plugin state based on current player location and inventory
     * Only call this when player is logged in and in a world
     */
    private void initializePluginState() {
        // Safety checks - ensure player is properly loaded
        if (!Microbot.isLoggedIn()) {
            Microbot.log("Cannot initialize - player not logged in");
            return;
        }
        
        if (Rs2Player.getWorldLocation() == null) {
            Microbot.log("Cannot initialize - player location not available");
            return;
        }
        
        Microbot.log("Initializing Bryophyta plugin state...");
        
        // Reset any previous state
        currentState = BryophytaState.IDLE;
        currentTarget = "Initializing";
        
        // Check current location and inventory
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        int foodCount = getFoodCount();
        boolean hasKeys = hasKeys();
        boolean hasAxe = hasAxe();
        
        Microbot.log("Current location: " + currentLocation);
        Microbot.log("Food count: " + foodCount);
        Microbot.log("Has mossy keys: " + hasKeys);
        Microbot.log("Has axe: " + hasAxe);
        
        // Determine initial state based on current situation
        if (isInBryophytaLair()) {
            Microbot.log("Player is in Bryophyta's lair - checking for boss/loot");
            currentState = BryophytaState.FIGHTING_BOSS; // updateState() will refine this
        } else if (hasRequiredSupplies()) {
            Microbot.log("Player has required supplies - can proceed to entrance");
            if (currentLocation.distanceTo(BRYOPHYTA_ENTRANCE) <= 20) {
                currentState = BryophytaState.ENTERING_LAIR;
            } else {
                currentState = BryophytaState.WALKING_TO_ENTRANCE;
            }
        } else {
            Microbot.log("Player needs supplies - going to bank");
            if (isAtBank()) {
                currentState = BryophytaState.BANKING;
            } else if (needsPrayer()) {
                currentState = BryophytaState.CHECKING_PRAYER;
            } else {
                // Need to go to bank - determine best route
                if (config.useVarrockTeleport() && hasVarrockTeleport()) {
                    currentState = BryophytaState.TELEPORTING;
                } else {
                    currentState = BryophytaState.BANKING; // Will walk there
                }
            }
        }
        
        Microbot.log("Initial state set to: " + currentState);
    }

    private void updateState() {
        // Don't change state if bank is open and we're actively banking
        if (Rs2Bank.isOpen()) {
            return;
        }
        
        // Check if we're in combat
        if (Rs2Player.isInCombat()) {
            Rs2NpcModel growthling = findGrowthling();
            if (growthling != null && !growthling.isDead()) {
                currentState = BryophytaState.FIGHTING_GROWTHLINGS;
                currentTarget = GROWTHLING_NAME;
                return;
            }
            
            Rs2NpcModel bryophyta = findBryophyta();
            if (bryophyta != null && !bryophyta.isDead()) {
                currentState = BryophytaState.FIGHTING_BOSS;
                currentTarget = BRYOPHYTA_NAME;
                return;
            }
        }

        // Check if we're in Bryophyta's lair
        if (isInBryophytaLair()) {
            Microbot.log("Player is in Bryophyta's lair");
            
            // Force state change from WALKING_TO_ENTRANCE to combat if in lair
            if (currentState == BryophytaState.WALKING_TO_ENTRANCE) {
                Microbot.log("Player in lair but state is WALKING_TO_ENTRANCE, changing to FIGHTING_BOSS");
                currentState = BryophytaState.FIGHTING_BOSS;
                return;
            }
            
            // Check if Bryophyta is dead and we need to loot
            Rs2NpcModel bryophyta = findBryophyta();
            if (bryophyta == null || bryophyta.isDead() || bryophyta.getHealthRatio() == 0) {
                Microbot.log("Bryophyta is dead/missing, entering loot phase - needsChestLoot: " + needsChestLoot + ", hasKeys: " + hasKeys());
                
                // Priority 1: Loot chest FIRST if we have keys and haven't looted yet
                // This must come before ground items because opening chest drops items to ground
                if (needsChestLoot && hasKeys()) {
                    GameObject chest = Rs2GameObject.getGameObject("Chest", true, Rs2Player.getWorldLocation(), 10);
                    if (chest != null) {
                        Microbot.log("Chest found nearby, setting state to LOOTING_CHEST");
                        bryophyta = findBryophyta();
                            if ((bryophyta == null || bryophyta.getHealthRatio() == 0) && !Rs2Player.isInCombat()) {
                                currentState = BryophytaState.LOOTING_CHEST;
                            }
                        return;
                    }
                    // If chest not found but we still need to loot it, try the enhanced detection
                    Microbot.log("Chest not found with basic search, trying enhanced detection - setting state to LOOTING_CHEST");
                    bryophyta = findBryophyta();
                            if ((bryophyta == null || bryophyta.getHealthRatio() == 0) && !Rs2Player.isInCombat()) {
                                currentState = BryophytaState.LOOTING_CHEST;
                            }
                    return;
                }
                
                // Priority 2: Loot ground items AFTER chest is looted
                if (Rs2GroundItem.exists(3, 1)) {
                    Microbot.log("Ground items detected, setting state to LOOTING_DROPS");
                    currentState = BryophytaState.LOOTING_DROPS;
                    return;
                }
            }
            
            // Look for growthlings first (prioritize over boss)
            Rs2NpcModel growthling = findGrowthling();
            if (growthling != null && !growthling.isDead()) {
                currentState = BryophytaState.FIGHTING_GROWTHLINGS;
                currentTarget = GROWTHLING_NAME;
                return;
            }
            
            // Look for Bryophyta to fight
            if (bryophyta != null && !bryophyta.isDead() && bryophyta.getHealthRatio() > 0) {
                currentState = BryophytaState.FIGHTING_BOSS;
                currentTarget = BRYOPHYTA_NAME;
                return;
            }
            
            // Check if we should leave (no keys, low resources, etc.) - only when in lair
            if (isInBryophytaLair() && shouldLeave()) {
                if (config.useVarrockTeleport() && hasVarrockTeleport()) {
                    Microbot.log("Should leave lair, using teleport");
                    currentState = BryophytaState.TELEPORTING;
                } else {
                    Microbot.log("Should leave lair, walking out");
                    currentState = BryophytaState.LEAVING_LAIR;
                }
                return;
            }
        }

        // Check if we need to go to bank
        if (needsBanking()) {
            Microbot.log("Player needs banking. Current state: " + currentState + ", At bank: " + isAtBank() + ", Distance to bank: " + Rs2Player.getWorldLocation().distanceTo(VARROCK_EAST_BANK));
            
            // If we're already at the bank and banking, don't change state to teleporting
            if (isAtBank() && Rs2Bank.isOpen()) {
                currentState = BryophytaState.BANKING;
                return;
            }
            
            // If we're already teleporting or banking, don't interfere
            if (currentState == BryophytaState.TELEPORTING || currentState == BryophytaState.BANKING) {
                Microbot.log("Already in " + currentState + " state, not changing");
                return; // Let the current process complete
            }
            
            // If we have teleport and not at bank, use teleport
            if (config.useVarrockTeleport() && hasVarrockTeleport()) {
                Microbot.log("Setting state to TELEPORTING");
                currentState = BryophytaState.TELEPORTING;
            } else {
                Microbot.log("No teleport available, setting state to LEAVING_LAIR");
                currentState = BryophytaState.LEAVING_LAIR;
            }
            return;
        }

        // Check if we need prayer
        if (needsPrayer()) {
            currentState = BryophytaState.CHECKING_PRAYER;
            return;
        }

        // Check if we have required supplies first (this should take priority over location)
        // BUT NOT if we're already in the lair
        if (hasRequiredSupplies() && !isInBryophytaLair()) {
            // If we're already at the entrance, don't override ENTERING_LAIR state
            if (Rs2Player.getWorldLocation().distanceTo(BRYOPHYTA_ENTRANCE) <= 5) {
                // Player is at entrance with supplies, they should be entering or already in lair
                if (currentState != BryophytaState.ENTERING_LAIR && currentState != BryophytaState.FIGHTING_BOSS && 
                    currentState != BryophytaState.FIGHTING_GROWTHLINGS && currentState != BryophytaState.LOOTING_CHEST && 
                    currentState != BryophytaState.LOOTING_DROPS && currentState != BryophytaState.LEAVING_LAIR) {
                    Microbot.log("Player at entrance with supplies, setting state to ENTERING_LAIR (was: " + currentState + ")");
                    currentState = BryophytaState.ENTERING_LAIR;
                } else {
                    Microbot.log("Player at entrance, keeping current state: " + currentState);
                }
                return;
            }
            // Player has supplies but not at entrance yet
            if (currentState != BryophytaState.WALKING_TO_ENTRANCE) {
                Microbot.log("Player has supplies but not at entrance, setting state to WALKING_TO_ENTRANCE");
            }
            currentState = BryophytaState.WALKING_TO_ENTRANCE;
            return;
        }

        // Check if we're at the bank and need banking
        if (isAtBank() && needsBanking()) {
            currentState = BryophytaState.BANKING;
            return;
        }

        // Default: if we need supplies but not at bank, go banking
        // BUT if we're in the lair, we should be fighting, not walking to entrance
        if (needsBanking()) {
            currentState = BryophytaState.BANKING;
        } else if (isInBryophytaLair()) {
            // If we're in the lair but reached this point, default to fighting
            Microbot.log("In lair with supplies, defaulting to FIGHTING_BOSS state");
            currentState = BryophytaState.FIGHTING_BOSS;
        } else {
            currentState = BryophytaState.WALKING_TO_ENTRANCE;
        }
    }

    private boolean handleEmergency() {
        int currentHP = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHP = Rs2Player.getRealSkillLevel(Skill.HITPOINTS);
        int healthPercent = (currentHP * 100) / maxHP;
        
        if (healthPercent <= config.emergencyTeleportHP() && config.emergencyTeleportHP() > 0) {
            if (config.useVarrockTeleport() && hasVarrockTeleport()) {
                log.info("Emergency teleport triggered at " + healthPercent + "% HP");
                Rs2Magic.cast(Rs2Spells.VARROCK_TELEPORT);
                sleepUntil(() -> !isInBryophytaLair(), 5000);
                
                // If we successfully teleported out, reset states and equipment
                if (!isInBryophytaLair()) {
                    Microbot.log("Successfully teleported out of lair, performing post-teleport cleanup");
                    
                    // Reset combat flags
                    needsChestLoot = false;
                    bryophytaKilled = false;
                    
                    // Ensure main weapon is equipped (in case axe was equipped during combat)
                    Microbot.log("Checking equipment after emergency teleport");
                    equipMainWeapon();
                    
                    // Small delay to let equipment change settle
                    sleep(500, 800);
                    
                    // Change to banking state
                    changeState(BryophytaState.BANKING);
                }
                return true;
            }
        }
        
        return false;
    }

    private void handleBanking() {
        currentTarget = "Banking";
        
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        double distanceToBank = currentLocation.distanceTo(VARROCK_EAST_BANK);
        Microbot.log("Banking - Current location: " + currentLocation + ", Distance to bank: " + distanceToBank);
        
        // Step 1: Make sure we're at the bank
        if (!isAtBank()) {
            Microbot.log("Not at bank, walking to Varrock East Bank...");
            Rs2Walker.walkTo(VARROCK_EAST_BANK);
            return;
        }

        // Step 2: Open bank if not open
        if (!Rs2Bank.isOpen()) {
            Microbot.log("Bank is not open, attempting to open bank...");
            if (Rs2Bank.openBank()) {
                Microbot.log("Bank open command sent, waiting for bank to open...");
                sleepUntil(Rs2Bank::isOpen, 3000);
                if (Rs2Bank.isOpen()) {
                    Microbot.log("Bank opened successfully!");
                } else {
                    Microbot.log("Failed to open bank after 3 seconds");
                    return; // Try again next iteration
                }
            } else {
                Microbot.log("Failed to send bank open command");
                return; // Try again next iteration
            }
        }

        // Step 3: Complete banking process (all steps together)
        if (Rs2Bank.isOpen()) {
            performBankingOperations();
        }
    }
    
    private void performBankingOperations() {
        Microbot.log("Bank is open, proceeding with complete banking process...");
        
        // Deposit all items except what we need to keep
        if (!Rs2Inventory.isEmpty()) {
            Microbot.log("Depositing all items. Current inventory size: " + Rs2Inventory.size());
            Rs2Bank.depositAll();
            sleepUntil(Rs2Inventory::isEmpty, 3000);
        }

        // Withdraw mossy keys first
        if (!Rs2Bank.hasItem(MOSSY_KEY_ID)) {
            if (config.stopOnNoKeys()) {
                log.info("No more mossy keys available. Stopping plugin.");
                shutdown();
                return;
            }
        } else {
            Microbot.log("Withdrawing mossy keys...");
            Rs2Bank.withdrawAll(MOSSY_KEY_ID); // Keys are stackable, only take 1 slot
            sleep(300, 500);
        }

        // Withdraw best available axe
        Microbot.log("Withdrawing axe...");
        withdrawBestAxe();

        // Withdraw potions if configured (prioritizing lowest dose first)
        if (!config.potionsToTake().trim().isEmpty()) {
            String[] potions = config.potionsToTake().split(",");
            for (String potion : potions) {
                String potionName = potion.trim();
                withdrawPotionLowestDoseFirst(potionName, config.potionQuantity());
            }
        }

        // Withdraw Varrock teleport runes if configured
        if (config.useVarrockTeleport()) {
            Microbot.log("Withdrawing Varrock teleport runes...");
            withdrawVarrockTeleportRunes();
        }

        // Fill remaining inventory with food
        Microbot.log("Current inventory before food: " + Rs2Inventory.size() + "/28, Empty slots: " + Rs2Inventory.emptySlotCount());
        Microbot.log("Bank is still open: " + Rs2Bank.isOpen());
        
        if (!Rs2Bank.isOpen()) {
            Microbot.log("ERROR: Bank closed unexpectedly before food withdrawal!");
            return; // Don't continue if bank is closed
        }
        
        fillWithFood();
        
        Microbot.log("Banking completed. Final inventory size: " + Rs2Inventory.size() + "/28");
        
        // Only close bank if it's still open
        if (Rs2Bank.isOpen()) {
            if (!Rs2Bank.closeBank()) {
                Microbot.log("Failed to close bank");
            } else {
                Microbot.log("Bank closed successfully");
            }
        } else {
            Microbot.log("Bank was already closed, skipping close command");
        }
        
        sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
        
        // After banking, check what we need to do next
        if (needsPrayer()) {
            Microbot.log("Banking completed but prayer needs restoration (below minimum). Going to church.");
            changeState(BryophytaState.CHECKING_PRAYER);
        } else if (needsPrayerRestoration()) {
            Microbot.log("Banking completed but prayer is below 80%. Going to church for restoration.");
            changeState(BryophytaState.CHECKING_PRAYER);
        } else if (hasRequiredSupplies()) {
            Microbot.log("Banking successful, has all required supplies. Going to entrance.");
            changeState(BryophytaState.WALKING_TO_ENTRANCE);
        } else {
            Microbot.log("Still missing supplies after banking:");
            Microbot.log("- Has keys: " + hasKeys());
            Microbot.log("- Has axe: " + hasAxe());
            Microbot.log("- Food count: " + getFoodCount() + " (min required: " + config.minFoodCount() + ")");
            Microbot.log("- Prayer points: " + Rs2Player.getBoostedSkillLevel(Skill.PRAYER) + " (min required: " + config.minPrayerPoints() + ")");
            
            // Stay in banking if we still need basic supplies (not prayer)
            if (needsBanking()) {
                Microbot.log("Still need basic banking supplies, staying in banking state");
                // Will try banking again next iteration
            } else {
                Microbot.log("Only missing prayer, going to church");
                changeState(BryophytaState.CHECKING_PRAYER);
            }
        }
    }

    private void withdrawBestAxe() {
        boolean axeWithdrawn = false;
        for (String axeName : AXE_NAMES) {
            if (Rs2Bank.hasItem(axeName)) {
                Microbot.log("Found " + axeName + " in bank, withdrawing...");
                if (Rs2Bank.withdrawX(axeName, 1)) {
                    Microbot.log("Successfully withdrew " + axeName);
                    axeWithdrawn = true;
                    sleep(300, 500);
                    break;
                } else {
                    Microbot.log("Failed to withdraw " + axeName);
                }
            }
        }
        
        if (!axeWithdrawn) {
            Microbot.log("Warning: No axe found in bank! Available axes: Dragon axe, Rune axe, Adamant axe, Mithril axe, Steel axe, Iron axe, Bronze axe");
        }
    }

    private void withdrawVarrockTeleportRunes() {
        int teleportSets = config.varrockTeleportQuantity();
        Microbot.log("Withdrawing runes for " + teleportSets + " Varrock teleport(s)...");
        
        // Varrock teleport needs: 1 law rune, 3 air runes, 1 fire rune
        if (Rs2Bank.hasItem("Air rune")) {
            Microbot.log("Withdrawing " + (teleportSets * 3) + " air rune(s)...");
            Rs2Bank.withdrawX("Air rune", teleportSets * 3);
            sleep(300, 500);
        }
        if (Rs2Bank.hasItem("Law rune")) {
            Microbot.log("Withdrawing " + teleportSets + " law rune(s)...");
            Rs2Bank.withdrawX("Law rune", teleportSets);
            sleep(300, 500);
        }
        if (Rs2Bank.hasItem("Fire rune")) {
            Microbot.log("Withdrawing " + teleportSets + " fire rune(s)...");
            Rs2Bank.withdrawX("Fire rune", teleportSets);
            sleep(300, 500);
        }
    }

    /**
     * Withdraws potions prioritizing lowest dose first (e.g., (1) before (2) before (3) before (4))
     * @param basePotionName The base name of the potion (e.g., "Strength potion")
     * @param quantity The total quantity to withdraw
     */
    private void withdrawPotionLowestDoseFirst(String basePotionName, int quantity) {
        int remainingToWithdraw = quantity;
        
        Microbot.log("Attempting to withdraw " + quantity + " " + basePotionName + " (prioritizing lowest dose first)");
        
        // Try doses from 1 to 4
        for (int dose = 1; dose <= 4 && remainingToWithdraw > 0; dose++) {
            String potionNameWithDose = basePotionName + "(" + dose + ")";
            
            if (Rs2Bank.hasItem(potionNameWithDose)) {
                // Get the quantity available in bank for this dose
                int availableQuantity = Rs2Bank.count(potionNameWithDose);
                int toWithdraw = Math.min(remainingToWithdraw, availableQuantity);
                
                if (toWithdraw > 0) {
                    Microbot.log("Withdrawing " + toWithdraw + " " + potionNameWithDose + " (available: " + availableQuantity + ")");
                    
                    if (Rs2Bank.withdrawX(potionNameWithDose, toWithdraw)) {
                        remainingToWithdraw -= toWithdraw;
                        Microbot.log("Successfully withdrew " + toWithdraw + " " + potionNameWithDose + ". Remaining to withdraw: " + remainingToWithdraw);
                        sleep(300, 500);
                    } else {
                        Microbot.log("Failed to withdraw " + potionNameWithDose);
                    }
                }
            }
        }
        
        // If we still couldn't get the exact potion name, try the base name without dose
        if (remainingToWithdraw > 0 && Rs2Bank.hasItem(basePotionName)) {
            Microbot.log("Trying base potion name: " + basePotionName);
            int availableQuantity = Rs2Bank.count(basePotionName);
            int toWithdraw = Math.min(remainingToWithdraw, availableQuantity);
            
            if (toWithdraw > 0) {
                Microbot.log("Withdrawing " + toWithdraw + " " + basePotionName);
                if (Rs2Bank.withdrawX(basePotionName, toWithdraw)) {
                    remainingToWithdraw -= toWithdraw;
                    Microbot.log("Successfully withdrew " + toWithdraw + " " + basePotionName + ". Remaining to withdraw: " + remainingToWithdraw);
                    sleep(300, 500);
                }
            }
        }
        
        if (remainingToWithdraw > 0) {
            Microbot.log("Warning: Could not withdraw full quantity of " + basePotionName + ". Missing: " + remainingToWithdraw);
        } else {
            Microbot.log("Successfully withdrew all requested " + basePotionName + " potions");
        }
    }

    private void fillWithFood() {
        // First check if bank is still open
        if (!Rs2Bank.isOpen()) {
            Microbot.log("ERROR: Bank is not open when trying to withdraw food!");
            return;
        }
        
        // Use the utility method to get empty slots
        int emptySlots = Rs2Inventory.emptySlotCount();
        
        Microbot.log("Attempting to withdraw food. Empty slots available: " + emptySlots);
        
        if (emptySlots <= 0) {
            Microbot.log("No inventory space available for food.");
            return;
        }

        // Try to withdraw food to fill most of the available slots, leaving 1-2 for loot
        int targetFoodSlots = Math.max(1, emptySlots - 1);

        Microbot.log("Attempting to withdraw " + targetFoodSlots + " food items.");

        // Find the best food available in bank using Rs2Food utility
        Rs2ItemModel bestFood = getBestFoodFromBank();
        
        if (bestFood != null) {
            Microbot.log("Found best food: " + bestFood.getName() + " (heals " + getHealValue(bestFood.getId()) + " HP), attempting to withdraw " + targetFoodSlots);
            
            if (Rs2Bank.withdrawX(bestFood.getId(), targetFoodSlots)) {
                Microbot.log("Successfully initiated withdrawal of " + bestFood.getName());
                sleep(300, 500);
                
                // Verify the withdrawal worked
                int newEmptySlots = Rs2Inventory.emptySlotCount();
                if (newEmptySlots < emptySlots) {
                    Microbot.log("Withdrawal successful! Empty slots reduced from " + emptySlots + " to " + newEmptySlots);
                } else {
                    Microbot.log("Withdrawal may have failed - empty slots didn't change");
                }
            } else {
                Microbot.log("Failed to initiate withdrawal of " + bestFood.getName());
            }
        } else {
            Microbot.log("Warning: No edible food found in bank!");
            Microbot.log("Please ensure you have edible food in your bank for the plugin to work properly");
        }
        
        Microbot.log("Food withdrawal completed. Final inventory size: " + Rs2Inventory.size());
    }
    
    /**
     * Gets the best healing food available in the bank
     * Based on Rs2InventorySetup.handleHealing() implementation
     * @return the best food item or null if no food is found
     */
    private Rs2ItemModel getBestFoodFromBank() {
        // Exclude special foods that have unique uses
        Set<String> excluded = Set.of("karambwan", "anglerfish", "rock cake", "dwarven rock cake");
        
        return Rs2Bank.bankItems().stream()
            .filter(item -> Arrays.stream(Rs2Food.values())
                .anyMatch(food -> food.getId() == item.getId()))
            .filter(item -> !excluded.contains(item.getName().toLowerCase()))
            .filter(item -> isEdibleFood(item))
            .max(Comparator.comparingInt(item ->
                Arrays.stream(Rs2Food.values())
                    .filter(food -> food.getId() == item.getId())
                    .findFirst()
                    .map(Rs2Food::getHeal)
                    .orElse(0)))
            .orElse(null);
    }
    
    /**
     * Check if an item is edible food (not raw)
     */
    private boolean isEdibleFood(Rs2ItemModel item) {
        String name = item.getName().toLowerCase();
        // Exclude raw foods that can't be eaten
        return !name.contains("raw") && 
               !name.contains("burnt") && 
               Arrays.stream(item.getInventoryActions()).anyMatch("eat"::equalsIgnoreCase);
    }
    
    /**
     * Get heal value for a food item ID
     */
    private int getHealValue(int itemId) {
        return Arrays.stream(Rs2Food.values())
            .filter(food -> food.getId() == itemId)
            .findFirst()
            .map(Rs2Food::getHeal)
            .orElse(0);
    }
    

    private void handlePrayerCheck() {
        currentTarget = "Prayer Check";
        
        int currentPrayer = Rs2Player.getBoostedSkillLevel(Skill.PRAYER);
        int maxPrayer = Rs2Player.getRealSkillLevel(Skill.PRAYER);
        double prayerPercentage = (double) currentPrayer / maxPrayer * 100;
        
        Microbot.log("Prayer restoration - Current: " + currentPrayer + "/" + maxPrayer + " (" + String.format("%.1f", prayerPercentage) + "%)");
        
        // Check if prayer is already full
        if (currentPrayer >= maxPrayer) {
            Microbot.log("Prayer is full, proceeding to walk to entrance");
            changeState(BryophytaState.WALKING_TO_ENTRANCE);
            return;
        }
        
        // Check if we're at the church
        if (!isAtChurch()) {
            Microbot.log("Walking to Varrock Church for prayer restoration...");
            Rs2Walker.walkTo(VARROCK_CHURCH);
            Rs2Player.waitForWalking();
            return;
        }

        // Use altar to restore prayer
        GameObject altar = Rs2GameObject.getGameObject("Altar");
        if (altar == null) {
            // Try finding altar by ID if name search fails
            altar = Rs2GameObject.getGameObject(409); // Common altar ID
        }
        
        if (altar != null) {
            Microbot.log("Found altar, attempting to pray...");
            if (Rs2GameObject.interact(altar, "Pray-at")) {
                sleepUntil(() -> Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= Rs2Player.getRealSkillLevel(Skill.PRAYER), 5000);
                
                if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= Rs2Player.getRealSkillLevel(Skill.PRAYER)) {
                    Microbot.log("Prayer restored successfully, proceeding to entrance");
                    changeState(BryophytaState.WALKING_TO_ENTRANCE);
                }
            }
        } else {
            Microbot.log("Altar not found at church location");
            // If we can't find altar, just proceed (maybe player needs to move)
            sleep(300, 500);
        }
    }

    private void walkToEntrance() {
        currentTarget = "Walking to Entrance";
        
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        double distanceToEntrance = currentLocation.distanceTo(BRYOPHYTA_ENTRANCE);
        
        // Check if we're already at the entrance
        if (distanceToEntrance <= 5) {
            Microbot.log("Already at entrance (distance: " + String.format("%.1f", distanceToEntrance) + "), transitioning to enter lair");
            changeState(BryophytaState.ENTERING_LAIR);
            return;
        }
        
        Microbot.log("Walking to Bryophyta entrance... Current distance: " + String.format("%.1f", distanceToEntrance));
        Rs2Walker.walkTo(BRYOPHYTA_ENTRANCE);
        
        // Wait for player to reach the entrance
        if (sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(BRYOPHYTA_ENTRANCE) <= 5, 15000)) {
            Microbot.log("Reached entrance (distance: " + String.format("%.1f", Rs2Player.getWorldLocation().distanceTo(BRYOPHYTA_ENTRANCE)) + "), transitioning to enter lair");
            changeState(BryophytaState.ENTERING_LAIR);
        } else {
            Microbot.log("Failed to reach entrance within timeout, current distance: " + String.format("%.1f", Rs2Player.getWorldLocation().distanceTo(BRYOPHYTA_ENTRANCE)));
        }
    }

    private void enterLair() {
        currentTarget = "Entering Lair";
        
        try {
            Microbot.log("Attempting to enter Bryophyta lair...");
            
            // Use potion once before entering if we have potions
            usePotionBeforeEntering();
            
            // Try multiple methods to find the gate
            TileObject gate = null;
            
            // Method 1: Try as GameObject
            gate = Rs2GameObject.getGameObject(GATE_ID);
            
            // Method 2: Try as WallObject if GameObject fails
            if (gate == null) {
                gate = Rs2GameObject.getWallObject(GATE_ID);
                if (gate != null) {
                    Microbot.log("Found gate as WallObject");
                }
            }
            
            // Method 3: Try by name if ID search fails
            if (gate == null) {
                gate = Rs2GameObject.getGameObject("Gate");
                if (gate != null) {
                    Microbot.log("Found gate by name");
                }
            }
            
            // Method 4: Try searching with distance parameter
            if (gate == null) {
                gate = Rs2GameObject.getGameObject(GATE_ID, BRYOPHYTA_ENTRANCE, 10);
                if (gate != null) {
                    Microbot.log("Found gate with distance search");
                }
            }
            
            // Method 5: Try WallObject with distance
            if (gate == null) {
                gate = Rs2GameObject.getWallObject(GATE_ID, BRYOPHYTA_ENTRANCE, 10);
                if (gate != null) {
                    Microbot.log("Found gate as WallObject with distance");
                }
            }
            
            if (gate == null) {
                Microbot.log("Gate not found with any method - debugging object detection");
                
                // Debug: List all nearby objects
                List<GameObject> nearbyObjects = Rs2GameObject.getGameObjects(10);
                Microbot.log("Found " + nearbyObjects.size() + " GameObjects nearby");
                for (GameObject obj : nearbyObjects) {
                    try {
                        ObjectComposition comp = Rs2GameObject.convertToObjectComposition(obj);
                        String name = comp != null ? comp.getName() : "null";
                        Microbot.log("GameObject: ID=" + obj.getId() + ", Name=" + name);
                    } catch (Exception e) {
                        Microbot.log("GameObject: ID=" + obj.getId() + ", Name=error");
                    }
                }
                
                List<WallObject> nearbyWalls = Rs2GameObject.getWallObjects(10);
                Microbot.log("Found " + nearbyWalls.size() + " WallObjects nearby");
                for (WallObject wall : nearbyWalls) {
                    try {
                        ObjectComposition comp = Rs2GameObject.convertToObjectComposition(wall);
                        String name = comp != null ? comp.getName() : "null";
                        Microbot.log("WallObject: ID=" + wall.getId() + ", Name=" + name);
                    } catch (Exception e) {
                        Microbot.log("WallObject: ID=" + wall.getId() + ", Name=error");
                    }
                }
                
                Microbot.log("Gate not found - moving closer to entrance");
                Rs2Walker.walkTo(BRYOPHYTA_ENTRANCE);
                Rs2Player.waitForWalking();
                return;
            }
            
            Microbot.log("Found gate, attempting to interact");
            
            // Interact with the gate
            if (Rs2GameObject.interact(gate, "Open")) {
                Microbot.log("Clicked on gate, waiting for dialog...");
                sleepUntil(() -> Rs2Dialogue.isInDialogue(), 3000);
                
                // Handle the first dialog: "Click here to continue"
                if (Rs2Dialogue.isInDialogue()) {
                    Microbot.log("Dialog opened, clicking to continue...");
                    Rs2Dialogue.clickContinue();
                    sleepUntil(() -> Rs2Dialogue.hasSelectAnOption(), 3000);
                    
                    // Handle the choice dialog: "Yes, let's go!"
                    if (Rs2Dialogue.hasSelectAnOption()) {
                        Microbot.log("Choice dialog detected, selecting 'Yes, let's go!'");
                        if (Rs2Dialogue.clickOption("Yes, let's go!")) {
                            Microbot.log("Selected option, waiting for teleport...");
                            sleepUntil(() -> !Rs2Player.getWorldLocation().equals(BRYOPHYTA_ENTRANCE), 5000);
                            sleepUntil(() -> !Rs2Player.isMoving(), 3000);
                            
                            if (isInBryophytaLair()) {
                                Microbot.log("Successfully entered Bryophyta lair!");
                                changeState(BryophytaState.FIGHTING_BOSS);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Microbot.log("Error entering lair: " + e.getMessage());
        }
    }

    private void fightBoss() {
        currentTarget = BRYOPHYTA_NAME;
        
        // Handle emergency situations first
        if (handleEmergency()) return;
        
        // Check for magic projectile to activate/deactivate protect from magic
        boolean magicProjectilePresent = false;
        for (Projectile projectile : Microbot.getClient().getProjectiles()) {
            if (projectile.getId() == BRYOPHYTA_MAGIC_PROJECTILE_ID) {
                magicProjectilePresent = true;
                break;
            }
        }
        
        // Handle protection prayer based on projectile detection
        if (config.useProtectFromMagic()) {
            if (magicProjectilePresent) {
                // Turn ON protection when projectile is detected
                if (config.useQuickPrayer()) {
                    if (!Rs2Prayer.isQuickPrayerEnabled()) {
                        Rs2Prayer.toggleQuickPrayer(true);
                        Microbot.log("Activating quick prayer - magic projectile detected!");
                    }
                } else {
                    if (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC)) {
                        Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, true);
                        Microbot.log("Activating Protect from Magic - magic projectile detected!");
                    }
                }
            } else {
                // Turn OFF protection when no projectile is detected
                if (config.useQuickPrayer()) {
                    if (Rs2Prayer.isQuickPrayerEnabled()) {
                        Rs2Prayer.toggleQuickPrayer(false);
                        Microbot.log("Deactivating quick prayer - no magic projectile detected");
                    }
                } else {
                    if (Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC)) {
                        Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, false);
                        Microbot.log("Deactivating Protect from Magic - no magic projectile detected");
                    }
                }
            }
        }

        // PRIORITY 1: Check for growthlings first (they can spawn multiple times during boss fight)
        Rs2NpcModel growthling = findGrowthling();
        if (growthling != null && !growthling.isDead()) {
            Microbot.log("Growthlings spawned during boss fight, attacking them directly");
            
            // Equip axe for fighting growthlings
            equipAxe();
            
            // Attack the growthling directly
            if (!Rs2Player.isInCombat() || (growthling != null && !growthling.isDead())) {
                Microbot.log("Found growthling to attack (ID: " + growthling.getId() + ")");
                
                // Check if camera needs adjustment
                if (!Rs2Camera.isTileOnScreen(growthling.getLocalLocation())) {
                    Microbot.log("Adjusting camera to target growthling");
                    
                    try {
                        int angle = Rs2Camera.getCharacterAngle(growthling);
                        Rs2Camera.setAngle(angle, 20);
                        
                        // Wait for camera adjustment
                        long cameraStartTime = System.currentTimeMillis();
                        while ((System.currentTimeMillis() - cameraStartTime) < 2000) {
                            if (Rs2Camera.isTileOnScreen(growthling.getLocalLocation())) {
                                break;
                            }
                            sleep(300, 500);
                        }
                    } catch (Exception e) {
                        Microbot.log("Error adjusting camera for growthling: " + e.getMessage());
                    }
                }
                
                Microbot.log("Attacking growthling with axe");
                boolean attackSuccess = Rs2Npc.interact(growthling, "Attack");
                
                if (attackSuccess) {
                    Microbot.log("Successfully initiated attack on growthling!");
                    sleep(300, 500);
                } else {
                    Microbot.log("Failed to attack growthling, will retry next cycle");
                }
            }
            
            // Stay in this method to continue fighting growthlings until all are dead
            return;
        }
        
        // All growthlings are dead, switch back to main weapon for boss
        equipMainWeapon();

        // Eat food if health is low
        handleFood();

        // PRIORITY 3: Find and attack Bryophyta
        Rs2NpcModel bryophyta = findBryophyta();
        if (bryophyta != null && !bryophyta.isDead() && bryophyta.getHealthRatio() > 0) {
                Microbot.log("Found Bryophyta to attack (ID: " + bryophyta.getId() + ", Health: " + bryophyta.getHealthRatio() + ")");
                
                // Check if camera needs adjustment - similar to ApexFighter
                if (!Rs2Camera.isTileOnScreen(bryophyta.getLocalLocation())) {
                    Microbot.log("Adjusting camera to target Bryophyta");
                    
                    try {
                        // Use character angle to turn camera
                        int angle = Rs2Camera.getCharacterAngle(bryophyta);
                        Rs2Camera.setAngle(angle, 20);
                        
                        // Wait for camera movement with timeout
                        long cameraStartTime = System.currentTimeMillis();
                        boolean cameraSuccess = false;
                        
                        while ((System.currentTimeMillis() - cameraStartTime) < 3000) {
                            if (Rs2Camera.isTileOnScreen(bryophyta.getLocalLocation())) {
                                cameraSuccess = true;
                                break;
                            }
                            sleep(300, 500);
                        }
                        
                        if (!cameraSuccess) {
                            Microbot.log("Camera adjustment timed out, trying alternative approach");
                            Rs2Camera.centerTileOnScreen(bryophyta.getLocalLocation(), 15.0);
                            
                            // Short wait for alternative method
                            sleepUntil(() -> Rs2Camera.isTileOnScreen(bryophyta.getLocalLocation()), 1500);
                        }
                        
                        if (!Rs2Camera.isTileOnScreen(bryophyta.getLocalLocation())) {
                            Microbot.log("Failed to adjust camera to Bryophyta, skipping attack this cycle");
                            return;
                        }
                        
                        Microbot.log("Camera successfully positioned for Bryophyta");
                        
                    } catch (Exception e) {
                        Microbot.log("Error during camera adjustment: " + e.getMessage());
                        return;
                    }
                }
                
                Microbot.log("Attempting to attack Bryophyta");
                boolean attackSuccess = Rs2Npc.interact(bryophyta, "Attack");
                
                if (attackSuccess) {
                    Microbot.log("Successfully initiated attack on Bryophyta!");
                    sleep(300, 500);
                } else {
                    Microbot.log("Failed to attack Bryophyta, will retry next cycle");
                }
        } else {
            // Bryophyta is dead or not found, set flags for chest looting
            Microbot.log("Bryophyta not found or dead, proceeding to loot");
            bryophytaKilled = true;
            needsChestLoot = true;
            killCount++;
            Microbot.log("Bryophyta killed! Kill count: " + killCount);
            
            // Check if we need to loot chest first
            if (needsChestLoot && hasKeys()) {
                            if ((bryophyta == null || bryophyta.getHealthRatio() == 0) && !Rs2Player.isInCombat()) {
                                currentState = BryophytaState.LOOTING_CHEST;
                            }
            } else {
                changeState(BryophytaState.LOOTING_DROPS);
            }
        }
    }

    private void fightGrowthlings() {
        currentTarget = GROWTHLING_NAME;
        
        // Handle emergency situations first
        if (handleEmergency()) return;
        
        // Turn off protect from magic when fighting growthlings (they don't use magic attacks)
        if (config.useProtectFromMagic()) {
            if (config.useQuickPrayer()) {
                if (Rs2Prayer.isQuickPrayerEnabled()) {
                    Rs2Prayer.toggleQuickPrayer(false);
                    Microbot.log("Deactivating quick prayer while fighting growthlings");
                }
            } else {
                if (Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC)) {
                    Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, false);
                    Microbot.log("Deactivating Protect from Magic while fighting growthlings");
                }
            }
        }
        
        // PRIORITY 1: Equip axe for fighting growthlings (always ensure axe is equipped)
        equipAxe();

        // Eat food if health is low
        handleFood();

        // PRIORITY 2: Find and attack growthlings
        Rs2NpcModel growthling = findGrowthling();
        if (growthling != null && !growthling.isDead()) {
            // Always attack growthlings when they are present, regardless of current combat state
            // This is because growthlings are high priority targets that need immediate attention
            Microbot.log("Found growthling to attack (ID: " + growthling.getId() + ")");
            
            // Check if camera needs adjustment
            if (!Rs2Camera.isTileOnScreen(growthling.getLocalLocation())) {
                    Microbot.log("Adjusting camera to target growthling");
                    
                    try {
                        int angle = Rs2Camera.getCharacterAngle(growthling);
                        Rs2Camera.setAngle(angle, 20);
                        
                        // Wait for camera adjustment
                        long cameraStartTime = System.currentTimeMillis();
                        while ((System.currentTimeMillis() - cameraStartTime) < 2000) {
                            if (Rs2Camera.isTileOnScreen(growthling.getLocalLocation())) {
                                break;
                            }
                            sleep(300, 500);
                        }
                    } catch (Exception e) {
                        Microbot.log("Error adjusting camera for growthling: " + e.getMessage());
                    }
                }
                
                Microbot.log("Attacking growthling with axe");
                boolean attackSuccess = Rs2Npc.interact(growthling, "Attack");
                
                if (attackSuccess) {
                    Microbot.log("Successfully initiated attack on growthling!");
                    sleep(300, 500);
                } else {
                    Microbot.log("Failed to attack growthling, will retry next cycle");
                }
            }else 
            {
            // PRIORITY 3: No more growthlings visible, switch back to main weapon and return to boss
            Microbot.log("No more growthlings found, switching back to main weapon and returning to boss fight");
            equipMainWeapon();
            
            // Check if Bryophyta is still alive to continue fighting
            Rs2NpcModel bryophyta = findBryophyta();
            if (bryophyta != null && !bryophyta.isDead() && bryophyta.getHealthRatio() > 0) {
                Microbot.log("Bryophyta still alive, continuing boss fight");
                changeState(BryophytaState.FIGHTING_BOSS);
            } else {
                // Bryophyta died while fighting growthlings
                Microbot.log("Bryophyta died while fighting growthlings");
                bryophytaKilled = true;
                needsChestLoot = true;
                killCount++;
                
                if (needsChestLoot && hasKeys()) {
                    bryophyta = findBryophyta();
                            if ((bryophyta == null || bryophyta.getHealthRatio() == 0) && !Rs2Player.isInCombat()) {
                                currentState = BryophytaState.LOOTING_CHEST;
                            }
                } else {
                    changeState(BryophytaState.LOOTING_DROPS);
                }
            }
        }
    }

    private void lootChest() {
        currentTarget = "Looting Chest";
        Microbot.log("Starting chest looting process...");
        
        // Wait a moment for chest to spawn after boss death
        Microbot.log("Waiting for chest to spawn...");
        sleep(Rs2Random.between(2000, 3500));
        
        if (!hasKeys()) {
            Microbot.log("No mossy keys available, skipping chest");
            needsChestLoot = false;
            changeState(BryophytaState.LOOTING_DROPS);
            return;
        }

        // Try multiple times with delays to find the chest
        int attempts = 0;
        int maxAttempts = 3;
        
        while (attempts < maxAttempts) {
            attempts++;
            Microbot.log("Chest interaction attempt " + attempts + "/" + maxAttempts);
            
            // Get player position and chest distance
            WorldPoint playerPos = Rs2Player.getWorldLocation();
            int distanceToChest = playerPos.distanceTo(BRYOPHYTA_CHEST_LOCATION);
            
            Microbot.log("Player position: " + playerPos);
            Microbot.log("Chest location: " + BRYOPHYTA_CHEST_LOCATION);
            Microbot.log("Distance to chest: " + distanceToChest + " tiles");
            
            // Move closer if too far
            if (distanceToChest > 10) {
                Microbot.log("Too far from chest, moving closer...");
                Rs2Walker.walkTo(BRYOPHYTA_CHEST_LOCATION);
                sleep(1000, 1500);
                continue; // Try again after moving
            }

            // Reset interaction flags
            chestClicked = false;
            chestLooted = false;
            noMossyKey = false;
            bossStillAlive = false;
            
            // Use Rs2GameObject.interact with WorldPoint and action
            Microbot.log("Attempting to interact with chest at " + BRYOPHYTA_CHEST_LOCATION + " using action '" + CHEST_ACTION + "'");
            boolean interactionSuccess = Rs2GameObject.interact(BRYOPHYTA_CHEST_LOCATION, CHEST_ACTION);
            
            if (interactionSuccess) {
                Microbot.log("Successfully interacted with chest!");
                
                // Wait for chest interaction response
                Microbot.log("Waiting for chest interaction response...");
                sleepUntil(() -> chestClicked || noMossyKey || bossStillAlive || chestLooted, 5000);
                
                // Check for loot spawning
                sleep(Rs2Random.between(1000, 2000));
                boolean lootFound = Rs2GroundItem.exists(ItemID.MOSSY_KEY, 15) || 
                                  Rs2GroundItem.exists("Nature rune", 15) ||
                                  Rs2GroundItem.exists("Law rune", 15) ||
                                  Rs2GroundItem.exists("Death rune", 15);
                
                if (lootFound || chestLooted) {
                    Microbot.log("Chest successfully opened! Loot appeared on ground.");
                    chestLooted = true;
                    needsChestLoot = false;
                    changeState(BryophytaState.LOOTING_DROPS);
                    return;
                } else if (noMossyKey) {
                    Microbot.log("No mossy key message received");
                    needsChestLoot = false;
                    changeState(BryophytaState.LOOTING_DROPS);
                    return;
                } else if (bossStillAlive) {
                    Microbot.log("Boss still alive message received");
                    changeState(BryophytaState.FIGHTING_BOSS);
                    return;
                } else {
                    Microbot.log("Chest interaction completed but no clear result, checking for loot and proceeding");
                    // Fall through to try again or exit
                }
            } else {
                Microbot.log("Failed to interact with chest at WorldPoint " + BRYOPHYTA_CHEST_LOCATION + " (attempt " + attempts + ")");
            }
            
            // Wait before retrying
            if (attempts < maxAttempts) {
                sleep(2000, 3000);
            }
        }

        // If all attempts failed
        Microbot.log("All chest interaction attempts failed, proceeding to loot drops anyway");
        needsChestLoot = false;
        changeState(BryophytaState.LOOTING_DROPS);
    }

    private void lootDrops() {
        currentTarget = "Looting Drops";
        
        // First check for mossy key specifically
        if (Rs2GroundItem.exists(ItemID.MOSSY_KEY, 20)) {
            Microbot.log("Mossy key found on ground! Picking it up...");
            if (Rs2GroundItem.loot(ItemID.MOSSY_KEY)) {
                Microbot.log("Successfully picked up mossy key!");
                sleep(300, 500);
            }
        }
        
        // Keep looting valuable ground items until no more are found
        Microbot.log("Looting ground items");
        int lootAttempts = 0;
        int maxLootAttempts = 10; // Prevent infinite loops
        
        while (lootAttempts < maxLootAttempts) {
            boolean foundItems = Rs2GroundItem.lootItemBasedOnValue(1000, 20);
            if (!foundItems) {
                // No more valuable items found
                break;
            }
            sleep(300, 500);
            lootAttempts++;
        }
        
        Microbot.log("Ground item looting completed after " + lootAttempts + " attempts");
        
        // Wait a bit for looting to complete
        sleep(300, 500);
        
        // Reset flags after looting - but keep needsChestLoot if we haven't looted chest yet
        bryophytaKilled = false;
        // Only reset needsChestLoot if we actually don't have keys or already looted chest
        // This prevents the flag from being reset prematurely when only ground items are looted
        if (!hasKeys()) {
            needsChestLoot = false;
            Microbot.log("Reset needsChestLoot flag - no keys available");
        } else {
            Microbot.log("Keeping needsChestLoot flag - chest still needs to be looted");
        }
        
        // Check what we need to do next - don't teleport immediately after boss kill
        Microbot.log("Loot phase completed, checking next action...");
        
        // Emergency teleport check (but only if health is very low)
        if (handleEmergency()) return;
        
        // Check if we need banking (supplies depleted)
        if (needsBanking()) {
            Microbot.log("Need banking - supplies depleted");
            if (config.useVarrockTeleport() && hasVarrockTeleport()) {
                changeState(BryophytaState.TELEPORTING);
            } else {
                changeState(BryophytaState.LEAVING_LAIR);
            }
        } else if (needsPrayer()) {
            Microbot.log("Need prayer restoration");
            changeState(BryophytaState.LEAVING_LAIR); // Will go to church after leaving
        } else {
            // We have supplies, continue fighting
            Microbot.log("Still have supplies, continuing to fight");
            changeState(BryophytaState.FIGHTING_BOSS);
        }
    }

    private void leaveLair() {
        currentTarget = "Leaving Lair";
        
        if (!isInBryophytaLair()) {
            return;
        }

        // Walk to gate and exit
        GameObject gate = Rs2GameObject.getGameObject(GATE_ID);
        if (gate != null) {
            Rs2GameObject.interact(gate, "Open");
            sleepUntil(() -> !isInBryophytaLair(), 5000);
        }
    }

    private void handleTeleport() {
        currentTarget = "Teleporting";
        
        // Check if we're already at Varrock/bank area
        if (isAtBank()) {
            Microbot.log("Already at bank, transitioning to BANKING state");
            currentState = BryophytaState.BANKING;
            lastStateChange = Instant.now();
            return;
        }
        
        // Check if we're already in Varrock (close to bank) but not quite at bank
        double distanceToBank = Rs2Player.getWorldLocation().distanceTo(VARROCK_EAST_BANK);
        if (distanceToBank <= 50) { // If we're already in Varrock area
            Microbot.log("Already in Varrock area (distance: " + distanceToBank + "), walking to bank instead of teleporting");
            currentState = BryophytaState.BANKING;
            lastStateChange = Instant.now();
            return;
        }
        
        // Only teleport if we have the means to teleport and we're far from Varrock
        if (hasVarrockTeleport()) {
            Microbot.log("Casting Varrock teleport...");
            if (Rs2Magic.cast(Rs2Spells.VARROCK_TELEPORT)) {
                Microbot.log("Teleport cast successful, waiting to arrive...");
                sleepUntil(() -> isAtBank() || Rs2Player.getWorldLocation().distanceTo(VARROCK_EAST_BANK) <= 50, 8000);
                
                // After teleporting, always transition to banking
                Microbot.log("Teleport completed! Transitioning to BANKING state");
                currentState = BryophytaState.BANKING;
                lastStateChange = Instant.now();
            } else {
                Microbot.log("Failed to cast Varrock teleport, transitioning to BANKING anyway");
                currentState = BryophytaState.BANKING;
                lastStateChange = Instant.now();
            }
        } else {
            Microbot.log("No Varrock teleport available, transitioning to BANKING to try walking");
            currentState = BryophytaState.BANKING;
            lastStateChange = Instant.now();
        }
    }

    // Utility methods
    private void storeMainWeapon() {
        if (Rs2Equipment.isWearing(EquipmentInventorySlot.WEAPON)) {
            mainWeapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName();
        }
    }

    private void equipMainWeapon() {
        if (!mainWeapon.isEmpty() && hasAxeEquipped) {
            if (Rs2Inventory.hasItem(mainWeapon)) {
                Microbot.log("Switching back to main weapon: " + mainWeapon);
                Rs2Inventory.wield(mainWeapon);
                hasAxeEquipped = false;
                sleep(300, 500);
            }
        }
    }

    private void equipAxe() {
        if (hasAxeEquipped) return;
        
        // Store main weapon before switching to axe
        if (mainWeapon.isEmpty() && Rs2Equipment.isWearing(EquipmentInventorySlot.WEAPON)) {
            mainWeapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName();
            Microbot.log("Stored main weapon: " + mainWeapon);
        }
        
        for (String axeName : AXE_NAMES) {
            if (Rs2Inventory.hasItem(axeName)) {
                Microbot.log("Equipping axe for growthlings: " + axeName);
                Rs2Inventory.wield(axeName);
                hasAxeEquipped = true;
                sleep(300, 500);
                break;
            }
        }
    }

    private void handleFood() {
        int currentHP = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHP = Rs2Player.getRealSkillLevel(Skill.HITPOINTS);
        int healthPercent = (currentHP * 100) / maxHP;
        
        if (healthPercent <= config.eatAtHealthPercent()) {
            Rs2Player.useFood();
        }
    }

    // State checking methods
    private boolean isInBryophytaLair() {
        if (!Microbot.isLoggedIn()) return false;
        WorldPoint location = Rs2Player.getWorldLocation();
        if (location == null) return false;
        
        // Check if player is within 30 tiles of the lair center
        double distanceToLairCenter = location.distanceTo(BRYOPHYTA_LAIR_CENTER);
        boolean inLair = distanceToLairCenter <= 30;
        
        // Debug logging - always show distance for troubleshooting
        Microbot.log("Lair check - Player location: " + location + ", Distance to lair center: " + String.format("%.1f", distanceToLairCenter) + " tiles, In lair: " + inLair);
        
        return inLair;
    }

    private boolean isAtBank() {
        if (!Microbot.isLoggedIn()) return false;
        WorldPoint location = Rs2Player.getWorldLocation();
        if (location == null) return false;
        return location.distanceTo(VARROCK_EAST_BANK) <= 10;
    }

    private boolean isAtChurch() {
        if (!Microbot.isLoggedIn()) return false;
        WorldPoint location = Rs2Player.getWorldLocation();
        if (location == null) return false;
        return location.distanceTo(VARROCK_CHURCH) <= 10;
    }

    private boolean hasKeys() {
        return Rs2Inventory.hasItem(MOSSY_KEY_ID);
    }

    private boolean hasAxe() {
        for (String axeName : AXE_NAMES) {
            if (Rs2Inventory.hasItem(axeName) || Rs2Equipment.isWearing(axeName)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVarrockTeleport() {
        return Rs2Inventory.hasItem("Law rune") && 
               Rs2Inventory.hasItem("Air rune") && 
               Rs2Inventory.hasItem("Fire rune");
    }

    private boolean needsBanking() {
        return !hasKeys() || 
               !hasAxe() ||
               getFoodCount() < config.minFoodCount();
    }

    private boolean needsPrayer() {
        return Rs2Player.getBoostedSkillLevel(Skill.PRAYER) < config.minPrayerPoints() && 
               !isInBryophytaLair();
    }

    /**
     * Check if prayer needs restoration after banking (below 80% of total prayer)
     * This is separate from needsPrayer() which checks against minimum config value
     */
    private boolean needsPrayerRestoration() {
        int currentPrayer = Rs2Player.getBoostedSkillLevel(Skill.PRAYER);
        int maxPrayer = Rs2Player.getRealSkillLevel(Skill.PRAYER);
        double prayerPercentage = (double) currentPrayer / maxPrayer;
        
        // Only check for restoration if not in the lair (don't leave mid-fight for prayer)
        boolean needsRestoration = prayerPercentage < 0.8 && !isInBryophytaLair();
        
        if (needsRestoration) {
            Microbot.log("Prayer restoration needed: " + currentPrayer + "/" + maxPrayer + " (" + String.format("%.1f", prayerPercentage * 100) + "%)");
        }
        
        return needsRestoration;
    }

    private boolean hasRequiredSupplies() {
        return hasKeys() && 
               hasAxe() &&
               getFoodCount() >= config.minFoodCount() &&
               Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= config.minPrayerPoints();
    }

    private boolean shouldLeave() {
        return !hasKeys() || 
               getFoodCount() < config.minFoodCount() ||
               Rs2Player.getBoostedSkillLevel(Skill.PRAYER) < config.minPrayerPoints();
    }

    // Static getter methods for overlay
    public static int getFoodCount() {
        return Rs2Inventory.getInventoryFood().size();
    }

    /**
     * Use potion once before entering the lair
     */
    private void usePotionBeforeEntering() {
        if (config.potionsToTake().trim().isEmpty()) {
            return; // No potions configured
        }
        
        String[] potions = config.potionsToTake().split(",");
        for (String potion : potions) {
            String potionName = potion.trim();
            if (Rs2Inventory.hasItem(potionName)) {
                Microbot.log("Using potion before entering: " + potionName);
                Rs2Inventory.interact(potionName, "Drink");
                sleep(300, 500);
                break; // Only use one potion
            }
        }
    }

    /**
     * Find Bryophyta NPC by name and ID
     */
    private Rs2NpcModel findBryophyta() {
        // Try by name first
        Rs2NpcModel bryophyta = Rs2Npc.getNpc(BRYOPHYTA_NAME);
        if (bryophyta != null) {
            Microbot.log("Found Bryophyta by name: " + bryophyta.getName() + " (ID: " + bryophyta.getId() + ")");
            return bryophyta;
        }
        
        // Try by ID as fallback
        bryophyta = Rs2Npc.getNpc(BRYOPHYTA_ID);
        if (bryophyta != null) {
            Microbot.log("Found Bryophyta by ID: " + bryophyta.getId() + " (Name: " + bryophyta.getName() + ")");
            return bryophyta;
        }
        
        return null;
    }

    /**
     * Find Growthling NPC by name and ID
     */
    private Rs2NpcModel findGrowthling() {
        // Try by name first
        Rs2NpcModel growthling = Rs2Npc.getNpc(GROWTHLING_NAME);
        if (growthling != null) {
            Microbot.log("GROWTHLING_NAME found");
            return growthling;
        }
        
        // Try by ID as fallback
        growthling = Rs2Npc.getNpc(GROWTHLING_ID);
        if (growthling != null) {
            Microbot.log("GROWTHLING_ID found");
            return growthling;
        }
        
        return null;
    }

    public static int getKeysInInventory() {
        return Rs2Inventory.count(MOSSY_KEY_ID);
    }

    public static String getRuntime() {
        if (startTime == null) return "00:00:00";
        
        Duration duration = Duration.between(startTime, Instant.now());
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public void shutdown() {
        Microbot.log("Shutting down Bryophyta Fighter plugin...");
        
        // Reset all plugin state variables
        currentState = BryophytaState.IDLE;
        lastState = BryophytaState.IDLE;
        lastStateChange = Instant.now();
        initialized = false; // Reset initialization flag
        botStatus = "OFF";
        currentTarget = "";
        startTime = null;
        mainWeapon = "";
        
        // Log final statistics if we were actually running
        if (initialized) {
            log.info("Bryophyta Fighter stopped. Runtime: " + getRuntime() + ", Kills: " + killCount + ", Keys used: " + keysUsed);
        } else {
            log.info("Bryophyta Fighter stopped before initialization completed.");
        }
        
        // Call parent shutdown (this will handle stopping the walker)
        super.shutdown();
    }
}
