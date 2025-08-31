package net.runelite.client.plugins.microbot.bossing.bryophyta;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
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
import net.runelite.client.plugins.microbot.util.gameobject.Rs2ObjectModel;

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

        // Withdraw potions if configured
        if (!config.potionsToTake().trim().isEmpty()) {
            String[] potions = config.potionsToTake().split(",");
            for (String potion : potions) {
                String potionName = potion.trim();
                if (Rs2Bank.hasItem(potionName)) {
                    Microbot.log("Withdrawing " + config.potionQuantity() + " " + potionName + "(s)...");
                    Rs2Bank.withdrawX(potionName, config.potionQuantity());
                    sleep(300, 500);
                }
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
            Microbot.log("Banking completed but prayer needs restoration. Going to church.");
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
        
        // Check if prayer is already full
        if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= Rs2Player.getRealSkillLevel(Skill.PRAYER)) {
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

    /**
     * Enhanced chest detection using Rs2ObjectModel for comprehensive object type analysis.
     * This method searches ALL TileObject types (GameObject, GroundObject, WallObject, DecorativeObject)
     * and uses advanced filtering based on actions, names, and IDs.
     */
    private GameObject findChestWithRs2ObjectModel() {
        Microbot.log("=== Starting Rs2ObjectModel-based chest detection ===");
        
        WorldPoint playerPos = Rs2Player.getWorldLocation();
        Microbot.log("Player position: " + playerPos);
        
        // Get ALL TileObjects in the area (not just GameObjects)
        List<TileObject> allTileObjects = Rs2GameObject.getAll(obj -> {
            if (obj == null || obj.getWorldLocation() == null) return false;
            int distance = playerPos.distanceTo(obj.getWorldLocation());
            return distance <= 15; // Only objects actually within 15 tiles
        });
        
        Microbot.log("Found " + allTileObjects.size() + " TileObjects within 15 tiles for analysis");
        
        // Convert to Rs2ObjectModel for enhanced analysis
        List<Rs2ObjectModel> objectModels = new ArrayList<>();
        for (TileObject tileObject : allTileObjects) {
            try {
                // Get the tile for this object - we need to find it
                Tile objectTile = findTileForObject(tileObject);
                if (objectTile != null) {
                    Rs2ObjectModel model = new Rs2ObjectModel(tileObject, objectTile);
                    objectModels.add(model);
                }
            } catch (Exception e) {
                Microbot.log("Error creating Rs2ObjectModel: " + e.getMessage());
            }
        }
        
        Microbot.log("Created " + objectModels.size() + " Rs2ObjectModel instances for analysis");
        
        // Analyze each object using Rs2ObjectModel capabilities
        for (Rs2ObjectModel model : objectModels) {
            try {
                String name = model.getName();
                int id = model.getId();
                Rs2ObjectModel.ObjectType objectType = model.getObjectType();
                int distance = model.getDistanceFromPlayer();
                String[] actions = model.getActions();
                
                Microbot.log("Analyzing " + objectType.getTypeName() + ": ID=" + id + ", Name='" + name + "', Distance=" + distance);
                
                // Log available actions for debugging
                if (actions != null && actions.length > 0) {
                    StringBuilder actionStr = new StringBuilder();
                    for (String action : actions) {
                        if (action != null && !action.trim().isEmpty()) {
                            if (actionStr.length() > 0) actionStr.append(", ");
                            actionStr.append("'").append(action).append("'");
                        }
                    }
                    if (actionStr.length() > 0) {
                        Microbot.log("  Available actions: " + actionStr);
                    }
                }
                
                // Enhanced chest detection criteria
                boolean isChestById = (id == CHEST_OBJECT_ID || id == BRYOPHYTA_CHEST_ID);
                boolean isChestByName = (name != null && (
                    name.toLowerCase().contains("chest") ||
                    name.toLowerCase().contains("loot") ||
                    name.toLowerCase().contains("treasure") ||
                    name.toLowerCase().contains("bryophyta")
                ));
                boolean hasChestAction = model.hasAction("Open") || 
                                       model.hasAction("Use") || 
                                       model.hasAction("Search") ||
                                       model.hasAction("Loot");
                
                // Check if this could be our chest
                if (isChestById || isChestByName || hasChestAction) {
                    Microbot.log("*** POTENTIAL CHEST FOUND ***");
                    Microbot.log("  Type: " + objectType.getTypeName());
                    Microbot.log("  ID: " + id);
                    Microbot.log("  Name: '" + name + "'");
                    Microbot.log("  Distance: " + distance);
                    Microbot.log("  Has chest action: " + hasChestAction);
                    Microbot.log("  Match criteria: ID=" + isChestById + ", Name=" + isChestByName + ", Action=" + hasChestAction);
                    
                    // If it's a GameObject, we can return it directly
                    if (objectType == Rs2ObjectModel.ObjectType.GAME_OBJECT) {
                        Microbot.log("*** CONFIRMED: Found chest as GameObject! ***");
                        return (GameObject) model.getTileObject();
                    } else {
                        Microbot.log("*** FOUND: Chest is a " + objectType.getTypeName() + " (not GameObject) ***");
                        // For non-GameObjects, we might need special handling
                        // But we can still try to interact with the TileObject
                        TileObject chestTileObject = model.getTileObject();
                        Microbot.log("Will attempt interaction with " + objectType.getTypeName() + " chest");
                        
                        // Try to interact directly with the TileObject
                        if (tryInteractWithTileObject(chestTileObject, model)) {
                            // Interaction successful, mark chest as looted
                            return null; // Return null but handle the interaction
                        }
                    }
                }
                
            } catch (Exception e) {
                Microbot.log("Error analyzing Rs2ObjectModel: " + e.getMessage());
            }
        }
        
        Microbot.log("=== Rs2ObjectModel chest detection completed - no chest found ===");
        return null;
    }
    
    /**
     * Helper method to find the Tile for a given TileObject.
     * This is needed for Rs2ObjectModel construction.
     */
    private Tile findTileForObject(TileObject tileObject) {
        try {
            WorldPoint objectLocation = tileObject.getWorldLocation();
            
            // Convert world point to local point to find the tile
            LocalPoint localPoint = LocalPoint.fromWorld(Microbot.getClient().getLocalPlayer().getWorldView(), objectLocation);
            if (localPoint == null) return null;
            
            // Get the scene and find the tile
            Tile[][][] tiles = Microbot.getClient().getLocalPlayer().getWorldView().getScene().getTiles();
            int plane = objectLocation.getPlane();
            
            if (plane < 0 || plane >= tiles.length) return null;
            
            int sceneX = localPoint.getSceneX();
            int sceneY = localPoint.getSceneY();
            
            if (sceneX < 0 || sceneX >= tiles[plane].length || 
                sceneY < 0 || sceneY >= tiles[plane][sceneX].length) {
                return null;
            }
            
            return tiles[plane][sceneX][sceneY];
            
        } catch (Exception e) {
            Microbot.log("Error finding tile for object: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Attempts to interact with a TileObject that is not a GameObject.
     * Handles GroundObject, WallObject, and DecorativeObject interactions.
     */
    private boolean tryInteractWithTileObject(TileObject tileObject, Rs2ObjectModel model) {
        try {
            Microbot.log("Attempting to interact with " + model.getObjectType().getTypeName() + " chest");
            
            // Try to use Rs2GameObject.interact with the TileObject
            boolean interactionSuccess = false;
            
            // Try different actions based on what's available
            String[] actions = model.getActions();
            if (actions != null) {
                for (String action : actions) {
                    if (action != null && !action.trim().isEmpty()) {
                        String lowerAction = action.toLowerCase();
                        if (lowerAction.contains("open") || lowerAction.contains("use") || 
                            lowerAction.contains("search") || lowerAction.contains("loot")) {
                            
                            Microbot.log("Trying action: '" + action + "' on " + model.getObjectType().getTypeName());
                            
                            if (Rs2GameObject.interact(tileObject, action)) {
                                Microbot.log("Successfully used '" + action + "' on " + model.getObjectType().getTypeName() + " chest!");
                                interactionSuccess = true;
                                break;
                            }
                        }
                    }
                }
            }
            
            // If specific actions failed, try default interaction
            if (!interactionSuccess) {
                Microbot.log("Trying default interaction on " + model.getObjectType().getTypeName());
                interactionSuccess = Rs2GameObject.interact(tileObject);
            }
            
            if (interactionSuccess) {
                Microbot.log("Chest interaction successful! Waiting for loot to appear...");
                sleep(Rs2Random.between(2000, 3000));
                
                // Check if loot appeared on ground
                boolean lootFound = Rs2GroundItem.exists(ItemID.MOSSY_KEY, 15) || 
                                  Rs2GroundItem.exists("Nature rune", 15) ||
                                  Rs2GroundItem.exists("Law rune", 15) ||
                                  Rs2GroundItem.exists("Death rune", 15);
                
                if (lootFound) {
                    Microbot.log("Loot appeared on ground after chest interaction!");
                    chestLooted = true;
                    needsChestLoot = false;
                    changeState(BryophytaState.LOOTING_DROPS);
                    return true;
                } else {
                    Microbot.log("No loot found on ground after chest interaction");
                }
            }
            
            return interactionSuccess;
            
        } catch (Exception e) {
            Microbot.log("Error interacting with TileObject: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enhanced chest detection with multiple fallback strategies and comprehensive logging
     */
    private GameObject findChestWithBackup() {
        Microbot.log("Starting comprehensive chest detection...");
        
        // Get player position for distance calculations
        WorldPoint playerPos = Rs2Player.getWorldLocation();
        Microbot.log("Player position: " + playerPos);
        
        // Strategy 1: Use Rs2ObjectModel for comprehensive object type analysis (NEW)
        Microbot.log("Strategy 1: Rs2ObjectModel-based detection (all TileObject types)");
        GameObject rs2ModelChest = findChestWithRs2ObjectModel();
        if (rs2ModelChest != null) {
            Microbot.log("*** CHEST FOUND using Rs2ObjectModel approach! ***");
            return rs2ModelChest;
        }
        
        // Strategy 2: Search for objects using proximity-based filtering
        Microbot.log("Strategy 2: Proximity-based search for chest objects within 15 tiles");
        
        // Use getGameObjects with explicit distance parameter and filter by actual distance
        List<GameObject> allNearbyObjects = Rs2GameObject.getGameObjects(obj -> {
            if (obj == null || obj.getWorldLocation() == null) return false;
            int distance = playerPos.distanceTo(obj.getWorldLocation());
            return distance <= 15; // Only objects actually within 15 tiles
        }, playerPos, 15);
        
        Microbot.log("Found " + allNearbyObjects.size() + " GameObjects actually within 15 tiles");
        
        // If no objects found nearby at all, the chest might not have spawned yet
        if (allNearbyObjects.isEmpty()) {
            Microbot.log("WARNING: No GameObjects found within 15 tiles - chest may not have spawned yet");
            return null; // Return null to trigger retry logic
        }
        
        for (GameObject obj : allNearbyObjects) {
            try {
                ObjectComposition comp = Rs2GameObject.convertToObjectComposition(obj);
                if (comp != null) {
                    String name = comp.getName();
                    int id = comp.getId();
                    int distance = playerPos.distanceTo(obj.getWorldLocation());
                    
                    Microbot.log("Nearby object: ID=" + id + ", Name='" + name + "', Distance=" + distance);
                    
                    // Check if this is a chest
                    if (id == CHEST_OBJECT_ID || id == BRYOPHYTA_CHEST_ID || 
                        (name != null && (name.toLowerCase().contains("chest") || 
                                        name.toLowerCase().contains("loot") ||
                                        name.toLowerCase().contains("treasure")))) {
                        Microbot.log("*** FOUND LOCAL CHEST: ID=" + id + ", Name='" + name + "', Distance=" + distance + " ***");
                        return obj;
                    }
                }
            } catch (Exception e) {
                Microbot.log("Error examining nearby object: " + e.getMessage());
            }
        }
        
        // Strategy 2.5: Check GroundObjects and WallObjects for chest (might not be a GameObject)
        Microbot.log("Strategy 2.5: Checking GroundObjects and WallObjects for chest");
        
        // Check all TileObjects in the area
        List<TileObject> allTileObjects = Rs2GameObject.getAll(obj -> {
            if (obj == null || obj.getWorldLocation() == null) return false;
            int distance = playerPos.distanceTo(obj.getWorldLocation());
            return distance <= 15;
        });
        
        Microbot.log("Found " + allTileObjects.size() + " TileObjects within 15 tiles");
        
        for (TileObject obj : allTileObjects) {
            try {
                ObjectComposition comp = Rs2GameObject.convertToObjectComposition(obj);
                if (comp != null) {
                    String name = comp.getName();
                    int id = comp.getId();
                    int distance = playerPos.distanceTo(obj.getWorldLocation());
                    String objectType = obj.getClass().getSimpleName();
                    
                    Microbot.log("TileObject: ID=" + id + ", Name='" + name + "', Type=" + objectType + ", Distance=" + distance);
                    
                    // Check if this is a chest
                    if (id == CHEST_OBJECT_ID || id == BRYOPHYTA_CHEST_ID || 
                        (name != null && (name.toLowerCase().contains("chest") || 
                                        name.toLowerCase().contains("loot") ||
                                        name.toLowerCase().contains("treasure") ||
                                        name.toLowerCase().contains("bryophyta")))) {
                        Microbot.log("*** FOUND CHEST AS " + objectType + ": ID=" + id + ", Name='" + name + "' ***");
                        
                        // If it's a GameObject, return it directly
                        if (obj instanceof GameObject) {
                            return (GameObject) obj;
                        } else {
                            // For GroundObject/WallObject, we need to handle differently
                            Microbot.log("Chest found as " + objectType + " - will need special interaction handling");
                            // Create a fake GameObject wrapper or handle interaction differently
                        }
                    }
                }
            } catch (Exception e) {
                // Continue searching
            }
        }
        
        // Strategy 3: Try the specific object ID from debug image (56370) with distance filtering
        Microbot.log("Strategy 3: Searching for chest with ID " + CHEST_OBJECT_ID);
        GameObject chest = Rs2GameObject.getGameObject(CHEST_OBJECT_ID);
        if (chest != null) {
            WorldPoint chestPos = chest.getWorldLocation();
            int distance = playerPos.distanceTo(chestPos);
            Microbot.log("Found chest using ID " + CHEST_OBJECT_ID + " at position: " + chestPos + ", distance: " + distance);
            if (distance <= 15) {
                return chest;
            } else {
                Microbot.log("Chest too far away: " + distance + " tiles (ignoring distant chest)");
            }
        } else {
            Microbot.log("No chest found with ID " + CHEST_OBJECT_ID);
        }
        
        // Strategy 4: Try current name-based approach with distance limit
        Microbot.log("Strategy 4: Searching for chest with name: " + CHEST_NAME);
        chest = Rs2GameObject.getGameObject(CHEST_NAME);
        if (chest != null) {
            WorldPoint chestPos = chest.getWorldLocation();
            int distance = playerPos.distanceTo(chestPos);
            Microbot.log("Found chest using name '" + CHEST_NAME + "' at position: " + chestPos + ", distance: " + distance);
            if (distance <= 15) {
                return chest;
            } else {
                Microbot.log("Chest too far away: " + distance + " tiles (ignoring distant chest)");
            }
        } else {
            Microbot.log("No chest found with name: " + CHEST_NAME);
        }
        
        // Strategy 3: Use the specific Bryophyta chest ID as backup
        Microbot.log("Strategy 3: Searching for chest with Bryophyta ID: " + BRYOPHYTA_CHEST_ID);
        chest = Rs2GameObject.getGameObject(BRYOPHYTA_CHEST_ID);
        if (chest != null) {
            WorldPoint chestPos = chest.getWorldLocation();
            int distance = playerPos.distanceTo(chestPos);
            Microbot.log("Found Bryophyta chest using ID " + BRYOPHYTA_CHEST_ID + " at position: " + chestPos + ", distance: " + distance);
            if (distance <= 15) {
                return chest;
            } else {
                Microbot.log("Chest too far away: " + distance + " tiles");
            }
        } else {
            Microbot.log("No chest found with Bryophyta ID: " + BRYOPHYTA_CHEST_ID);
        }
        
        // Strategy 4: Try searching for "Open chest" specifically
        Microbot.log("Strategy 4: Searching for 'Open chest'");
        chest = Rs2GameObject.getGameObject("Open chest");
        if (chest != null) {
            WorldPoint chestPos = chest.getWorldLocation();
            int distance = playerPos.distanceTo(chestPos);
            Microbot.log("Found chest with name 'Open chest' at position: " + chestPos + ", distance: " + distance);
            if (distance <= 15) {
                return chest;
            } else {
                Microbot.log("Chest too far away: " + distance + " tiles");
            }
        } else {
            Microbot.log("No chest found with name 'Open chest'");
        }
        
        // Strategy 5: Search for any chest-type objects in the area using predicate
        Microbot.log("Strategy 5: Using predicate search for any chest-like object");
        chest = Rs2GameObject.getGameObject(obj -> {
            try {
                ObjectComposition comp = Rs2GameObject.convertToObjectComposition(obj);
                if (comp != null) {
                    String name = comp.getName();
                    int id = comp.getId();
                    if (name != null) {
                        boolean isChest = name.toLowerCase().contains("chest");
                        if (isChest) {
                            Microbot.log("Found potential chest: ID=" + id + ", Name='" + name + "'");
                        }
                        return isChest;
                    }
                }
            } catch (Exception e) {
                Microbot.log("Error in predicate search: " + e.getMessage());
            }
            return false;
        });
        
        if (chest != null) {
            WorldPoint chestPos = chest.getWorldLocation();
            int distance = playerPos.distanceTo(chestPos);
            Microbot.log("Found chest using predicate search at position: " + chestPos + ", distance: " + distance);
            if (distance <= 15) {
                return chest;
            } else {
                Microbot.log("Chest too far away: " + distance + " tiles");
            }
        } else {
            Microbot.log("No chest found with predicate search");
        }
        
        // Strategy 5.5: Try a range of chest-like object IDs commonly used in RuneScape
        Microbot.log("Strategy 5.5: Trying common chest object IDs");
        int[] commonChestIds = {172, 173, 174, 375, 376, 377, 378, 379, 380, 14786, 56370, 56378, 56371, 56372, 56373, 56374, 56375, 56376, 56377, 56379, 56380};
        for (int chestId : commonChestIds) {
            chest = Rs2GameObject.getGameObject(chestId);
            if (chest != null) {
                WorldPoint chestPos = chest.getWorldLocation();
                int distance = playerPos.distanceTo(chestPos);
                if (distance <= 15) {
                    Microbot.log("Found chest with common ID " + chestId + " at distance " + distance);
                    return chest;
                }
            }
        }
        Microbot.log("No chest found with common IDs");
        
        // Strategy 6: Search for any objects that might appear as chests
        Microbot.log("Strategy 6: Searching for any object that could be a chest within 15 tiles");
        
        // Get all objects within 15 tiles of player
        List<TileObject> allObjects = Rs2GameObject.getAll(obj -> {
            if (obj == null || obj.getWorldLocation() == null) return false;
            int distance = playerPos.distanceTo(obj.getWorldLocation());
            return distance <= 15;
        });
        
        Microbot.log("Found " + allObjects.size() + " objects within 15 tiles:");
        for (int i = 0; i < allObjects.size() && i < 30; i++) {
            TileObject obj = allObjects.get(i);
            try {
                ObjectComposition comp = Rs2GameObject.convertToObjectComposition(obj);
                if (comp != null) {
                    String name = comp.getName();
                    int id = comp.getId();
                    int distance = playerPos.distanceTo(obj.getWorldLocation());
                    
                    Microbot.log("TileObject " + i + ": ID=" + id + ", Name='" + name + "', Type=" + obj.getClass().getSimpleName() + ", Distance=" + distance);
                    
                    // Check if this could be our chest
                    if (id == CHEST_OBJECT_ID || id == BRYOPHYTA_CHEST_ID || 
                        (name != null && (name.toLowerCase().contains("chest") || 
                                        name.toLowerCase().contains("loot") ||
                                        name.toLowerCase().contains("treasure") ||
                                        name.toLowerCase().contains("bryophyta")))) {
                        Microbot.log("*** POTENTIAL CHEST FOUND VIA TILEOBJECT SEARCH: ID=" + id + ", Name='" + name + "' ***");
                        if (obj instanceof GameObject && distance <= 15) {
                            return (GameObject) obj;
                        }
                    }
                }
            } catch (Exception e) {
                Microbot.log("Error examining TileObject " + i + ": " + e.getMessage());
            }
        }
        
        // Strategy 6.5: Check GroundObjects and WallObjects specifically
        Microbot.log("Strategy 6.5: Checking GroundObjects and WallObjects for chest");
        
        // Check GroundObjects
        GroundObject groundChest = Rs2GameObject.getGroundObject(obj -> {
            try {
                ObjectComposition comp = Rs2GameObject.convertToObjectComposition(obj);
                if (comp != null) {
                    String name = comp.getName();
                    int id = comp.getId();
                    if (id == CHEST_OBJECT_ID || id == BRYOPHYTA_CHEST_ID ||
                        (name != null && name.toLowerCase().contains("chest"))) {
                        int distance = playerPos.distanceTo(obj.getWorldLocation());
                        if (distance <= 15) {
                            Microbot.log("Found GroundObject chest: ID=" + id + ", Name='" + name + "'");
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                // Continue searching
            }
            return false;
        });
        
        if (groundChest != null) {
            Microbot.log("*** FOUND CHEST AS GROUNDOBJECT ***");
            // GroundObjects can't be directly cast to GameObject, but we can try to interact with them
            // This might be the issue - the chest might be a GroundObject!
        }
        
        // Strategy 7: List all nearby GameObjects for debugging
        Microbot.log("Strategy 7: Listing all nearby GameObjects for debugging");
        List<GameObject> nearbyObjects = Rs2GameObject.getGameObjects(15);
        Microbot.log("Found " + nearbyObjects.size() + " nearby GameObjects:");
        
        // Check if any objects are actually within the expected range
        int actualNearbyCount = 0;
        for (int i = 0; i < Math.min(nearbyObjects.size(), 20); i++) { // Limit to first 20 to avoid spam
            GameObject obj = nearbyObjects.get(i);
            try {
                ObjectComposition comp = Rs2GameObject.convertToObjectComposition(obj);
                if (comp != null) {
                    String name = comp.getName();
                    int id = comp.getId();
                    WorldPoint objPos = obj.getWorldLocation();
                    int distance = playerPos.distanceTo(objPos);
                    
                    if (distance <= 15) {
                        actualNearbyCount++;
                    }
                    
                    Microbot.log("Object " + i + ": ID=" + id + ", Name='" + name + "', Distance=" + distance + " (pos: " + objPos + ")");
                    
                    // Check if this could be our chest
                    if (id == CHEST_OBJECT_ID || id == BRYOPHYTA_CHEST_ID || 
                        (name != null && (name.contains("chest") || name.contains("Chest")))) {
                        Microbot.log("*** POTENTIAL CHEST FOUND: ID=" + id + ", Name='" + name + "' ***");
                        if (distance <= 15) {
                            return obj;
                        }
                    }
                }
            } catch (Exception e) {
                Microbot.log("Error examining object " + i + ": " + e.getMessage());
            }
        }
        
        Microbot.log("Objects actually within 15 tiles: " + actualNearbyCount + " out of " + nearbyObjects.size());
        
        if (actualNearbyCount == 0) {
            Microbot.log("WARNING: getGameObjects(15) is not returning objects within 15 tiles!");
            Microbot.log("This suggests a bug in the search method or the chest hasn't spawned yet.");
        }
        
        Microbot.log("No chest found with any detection method");
        return null;
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
        int maxAttempts = 5; // Increased from 3 to 5
        GameObject chest = null;
        
        while (attempts < maxAttempts && chest == null) {
            attempts++;
            Microbot.log("Chest search attempt " + attempts + "/" + maxAttempts);
            
            chest = findChestWithBackup();
            
            if (chest == null && attempts < maxAttempts) {
                Microbot.log("Chest not found, waiting longer before retry...");
                sleep(Rs2Random.between(3000, 5000)); // Increased wait time
            }
        }

        if (chest != null) {
            // Get detailed chest information
            WorldPoint playerPos = Rs2Player.getWorldLocation();
            WorldPoint chestPos = chest.getWorldLocation();
            int distance = playerPos.distanceTo(chestPos);
            
            try {
                ObjectComposition comp = Rs2GameObject.convertToObjectComposition(chest);
                if (comp != null) {
                    Microbot.log("Found chest - ID: " + comp.getId() + ", Name: '" + comp.getName() + "', Distance: " + distance);
                    
                    // Log available actions
                    String[] actions = comp.getActions();
                    if (actions != null) {
                        Microbot.log("Available chest actions: " + Arrays.toString(actions));
                    }
                } else {
                    Microbot.log("Could not get chest composition");
                }
            } catch (Exception e) {
                Microbot.log("Error getting chest details: " + e.getMessage());
            }
            
            if (distance > 15) {
                Microbot.log("Chest is too far away: " + distance + " tiles, moving closer");
                Rs2Walker.walkTo(chestPos);
                sleep(1000, 1500);
                return;
            }
            
            Microbot.log("Attempting to interact with chest at distance: " + distance + " tiles");
            
            // Check available actions first
            try {
                ObjectComposition comp = Rs2GameObject.convertToObjectComposition(chest);
                if (comp != null) {
                    String[] actions = comp.getActions();
                    if (actions != null) {
                        Microbot.log("Available chest actions: " + String.join(", ", actions));
                        
                        // Look for the best action to use
                        String bestAction = null;
                        for (String action : actions) {
                            if (action != null) {
                                String lowerAction = action.toLowerCase();
                                if (lowerAction.contains("open")) {
                                    bestAction = action;
                                    break;
                                } else if (lowerAction.contains("use") || lowerAction.contains("search")) {
                                    bestAction = action;
                                }
                            }
                        }
                        
                        if (bestAction != null) {
                            Microbot.log("Using best action: '" + bestAction + "'");
                            if (Rs2GameObject.interact(chest, bestAction)) {
                                Microbot.log("Successfully used '" + bestAction + "' action on chest!");
                                
                                // Wait for chest to open and drop loot
                                Microbot.log("Waiting for chest to open and drop loot...");
                                sleep(Rs2Random.between(2000, 3000));
                                
                                // Check if loot appeared on ground
                                // Check for specific valuable items or mossy key
                                boolean lootFound = Rs2GroundItem.exists(ItemID.MOSSY_KEY, 15) || 
                                                  Rs2GroundItem.exists("Nature rune", 15) ||
                                                  Rs2GroundItem.exists("Law rune", 15) ||
                                                  Rs2GroundItem.exists("Death rune", 15);
                                
                                if (lootFound) {
                                    Microbot.log("Loot appeared on ground after opening chest!");
                                    chestLooted = true;
                                    needsChestLoot = false;
                                    changeState(BryophytaState.LOOTING_DROPS);
                                    return;
                                } else {
                                    Microbot.log("No loot found on ground after chest interaction");
                                }
                            } else {
                                Microbot.log("Failed to use '" + bestAction + "' action");
                            }
                        } else {
                            Microbot.log("No suitable action found, trying default interaction");
                            if (Rs2GameObject.interact(chest)) {
                                Microbot.log("Used default interaction");
                                sleep(Rs2Random.between(2000, 3000));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Microbot.log("Error checking chest actions: " + e.getMessage());
            }
            
            // Reset interaction flags
            chestClicked = false;
            chestLooted = false;
            noMossyKey = false;
            bossStillAlive = false;
            
            // Legacy interaction methods as backup
            Microbot.log("Attempting legacy interaction methods as backup...");
            
            // Try multiple interaction methods
            boolean interacted = false;
            
            // Method 1: Try "Open" action
            Microbot.log("Method 1: Trying 'Open' action");
            if (Rs2GameObject.interact(chest, "Open")) {
                Microbot.log("Successfully used 'Open' action");
                interacted = true;
            } else {
                Microbot.log("Failed to use 'Open' action");
            }
            
            if (!interacted) {
                // Method 2: Try "Use" action  
                Microbot.log("Method 2: Trying 'Use' action");
                if (Rs2GameObject.interact(chest, "Use")) {
                    Microbot.log("Successfully used 'Use' action");
                    interacted = true;
                } else {
                    Microbot.log("Failed to use 'Use' action");
                }
            }
            
            if (!interacted) {
                // Method 3: Try default interaction (click)
                Microbot.log("Method 3: Trying default interaction");
                if (Rs2GameObject.interact(chest)) {
                    Microbot.log("Successfully used default interaction");
                    interacted = true;
                } else {
                    Microbot.log("Failed to use default interaction");
                }
            }
            
            if (!interacted) {
                // Method 4: Try right-click menu
                Microbot.log("Method 4: Trying right-click approach");
                try {
                    // Move camera to look at chest
                    Rs2Camera.turnTo(chest);
                    sleep(300, 500);
                    
                    // Try right-click and select first option
                    if (Rs2GameObject.interact(chest, "Open")) {
                        Microbot.log("Successfully used right-click 'Open'");
                        interacted = true;
                    }
                } catch (Exception e) {
                    Microbot.log("Error with right-click method: " + e.getMessage());
                }
            }
            
            if (!interacted) {
                Microbot.log("All interaction methods failed, skipping chest");
                needsChestLoot = false;
                changeState(BryophytaState.LOOTING_DROPS);
                return;
            }
            
            // Wait for chest interaction response
            Microbot.log("Waiting for chest interaction response...");
            sleepUntil(() -> chestClicked || noMossyKey || bossStillAlive || chestLooted, 5000);
            
            // Check results
            if (chestLooted) {
                keysUsed++;
                needsChestLoot = false;
                Microbot.log("Chest looted successfully! Keys used: " + keysUsed);
                
                // Wait for loot to appear on ground and pick it up
                sleep(500, 1000);
                
                // Keep looting items until no more valuable items are found in 5-tile range
                Microbot.log("Picking up valuable items from chest");
                int lootAttempts = 0;
                int maxLootAttempts = 10; // Prevent infinite loops
                
                while (lootAttempts < maxLootAttempts) {
                    boolean foundItems = Rs2GroundItem.lootItemBasedOnValue(1000, 5);
                    if (!foundItems) {
                        // No more valuable items found
                        break;
                    }
                    sleep(300, 500);
                    lootAttempts++;
                }
                
                Microbot.log("Chest looting completed after " + lootAttempts + " attempts");
                
                // Now go to loot any additional drops
                changeState(BryophytaState.LOOTING_DROPS);
            } else if (noMossyKey) {
                Microbot.log("No mossy key message received");
                needsChestLoot = false;
                changeState(BryophytaState.LOOTING_DROPS);
            } else if (bossStillAlive) {
                Microbot.log("Boss still alive message received");
                changeState(BryophytaState.FIGHTING_BOSS);
            } else {
                Microbot.log("Chest interaction timeout");
                // Try again next iteration
            }
        } else {
            Microbot.log("Chest not found with any detection method, proceeding to loot drops");
            needsChestLoot = false;
            changeState(BryophytaState.LOOTING_DROPS);
        }
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
