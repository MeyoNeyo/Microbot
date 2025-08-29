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
    public static final WorldPoint VARROCK_CHURCH = new WorldPoint(3255, 3482, 0);
    public static final WorldPoint BRYOPHYTA_ENTRANCE = new WorldPoint(3174, 4900, 0);
    
    // NPC and Object names
    public static final String BRYOPHYTA_NAME = "Bryophyta";
    public static final String GROWTHLING_NAME = "Growthling";
    public static final int GATE_ID = 32534;
    public static final String CHEST_NAME = "Chest";
    
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
    
    // State tracking for timeout prevention
    private static BryophytaState lastState = BryophytaState.IDLE;
    private static Instant lastStateChange = Instant.now();
    private static final long STATE_TIMEOUT_SECONDS = 60; // 60 seconds timeout per state
    private static boolean initialized = false; // Track if plugin has been initialized
    
    private BryophytaConfig config;

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
                        currentState = BryophytaState.BANKING;
                        lastStateChange = Instant.now();
                    }
                }

                // Execute based on current state
                switch (currentState) {
                    case BANKING:
                        handleBanking();
                        break;
                    case CHECKING_PRAYER:
                        handlePrayerCheck();
                        break;
                    case WALKING_TO_ENTRANCE:
                        walkToEntrance();
                        break;
                    case ENTERING_LAIR:
                        enterLair();
                        break;
                    case FIGHTING_BOSS:
                        fightBoss();
                        break;
                    case FIGHTING_GROWTHLINGS:
                        fightGrowthlings();
                        break;
                    case LOOTING_CHEST:
                        lootChest();
                        break;
                    case LOOTING_DROPS:
                        lootDrops();
                        break;
                    case LEAVING_LAIR:
                        leaveLair();
                        break;
                    case TELEPORTING:
                        handleTeleport();
                        break;
                    case IDLE:
                        sleep(600, 1000);
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
            NPC growthling = Rs2Npc.getNpc(GROWTHLING_NAME);
            if (growthling != null && !growthling.isDead()) {
                currentState = BryophytaState.FIGHTING_GROWTHLINGS;
                currentTarget = GROWTHLING_NAME;
                return;
            }
            
            NPC bryophyta = Rs2Npc.getNpc(BRYOPHYTA_NAME);
            if (bryophyta != null && !bryophyta.isDead()) {
                currentState = BryophytaState.FIGHTING_BOSS;
                currentTarget = BRYOPHYTA_NAME;
                return;
            }
        }

        // Check if we're in Bryophyta's lair
        if (isInBryophytaLair()) {
            // Check if Bryophyta is dead and we can loot chest
            NPC bryophyta = Rs2Npc.getNpc(BRYOPHYTA_NAME);
            if (bryophyta == null || bryophyta.isDead()) {
                // Check if there are ground items to loot
                if (Rs2GroundItem.exists(3, 1)) {
                    currentState = BryophytaState.LOOTING_DROPS;
                    return;
                }
                
                // Check if chest is available and we have keys
                GameObject chest = Rs2GameObject.getGameObject("Chest", true, Rs2Player.getWorldLocation(), 10);
                if (chest != null && hasKeys()) {
                    currentState = BryophytaState.LOOTING_CHEST;
                    return;
                }
            }
            
            // Look for Bryophyta to fight
            if (bryophyta != null && !bryophyta.isDead()) {
                currentState = BryophytaState.FIGHTING_BOSS;
                currentTarget = BRYOPHYTA_NAME;
                return;
            }
            
            // Check if we should leave (no keys, low resources, etc.) - only when in lair
            if (isInBryophytaLair() && shouldLeave()) {
                currentState = BryophytaState.LEAVING_LAIR;
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
        if (hasRequiredSupplies()) {
            currentState = BryophytaState.WALKING_TO_ENTRANCE;
            return;
        }

        // Check if we're at the bank and need banking
        if (isAtBank() && needsBanking()) {
            currentState = BryophytaState.BANKING;
            return;
        }

        // Default: if we need supplies but not at bank, go banking
        if (needsBanking()) {
            currentState = BryophytaState.BANKING;
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
            sleep(600, 1000);
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
                    sleep(600, 1000);
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
        
        // After banking, verify supplies and force state transition
        if (hasRequiredSupplies()) {
            Microbot.log("Banking successful, has all required supplies. Forcing state to WALKING_TO_ENTRANCE");
            // Force the state change to avoid the updateState loop issue
            currentState = BryophytaState.WALKING_TO_ENTRANCE;
            lastState = BryophytaState.BANKING; // Ensure state change is tracked
            lastStateChange = Instant.now();
        } else {
            Microbot.log("Still missing supplies after banking:");
            Microbot.log("- Has keys: " + hasKeys());
            Microbot.log("- Has axe: " + hasAxe());
            Microbot.log("- Food count: " + getFoodCount() + " (min required: " + config.minFoodCount() + ")");
            Microbot.log("- Prayer points: " + Rs2Player.getBoostedSkillLevel(Skill.PRAYER) + " (min required: " + config.minPrayerPoints() + ")");
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
                    sleep(600, 1000);
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
        if (Rs2Bank.hasItem("Law rune")) {
            Microbot.log("Withdrawing " + teleportSets + " law rune(s)...");
            Rs2Bank.withdrawX("Law rune", teleportSets);
            sleep(600, 1000);
        }
        if (Rs2Bank.hasItem("Air rune")) {
            Microbot.log("Withdrawing " + (teleportSets * 3) + " air rune(s)...");
            Rs2Bank.withdrawX("Air rune", teleportSets * 3);
            sleep(600, 1000);
        }
        if (Rs2Bank.hasItem("Fire rune")) {
            Microbot.log("Withdrawing " + teleportSets + " fire rune(s)...");
            Rs2Bank.withdrawX("Fire rune", teleportSets);
            sleep(600, 1000);
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
                sleep(800, 1200);
                
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
        
        if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= Rs2Player.getRealSkillLevel(Skill.PRAYER)) {
            return; // Prayer is full
        }
        
        if (!isAtChurch()) {
            Rs2Walker.walkTo(VARROCK_CHURCH);
            return;
        }

        // Use altar to restore prayer
        GameObject altar = Rs2GameObject.getGameObject("Altar", true, Rs2Player.getWorldLocation(), 10);
        if (altar != null) {
            Rs2GameObject.interact(altar, "Pray-at");
            sleepUntil(() -> Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= Rs2Player.getRealSkillLevel(Skill.PRAYER), 3000);
        }
    }

    private void walkToEntrance() {
        currentTarget = "Walking to Entrance";
        
        Rs2Walker.walkTo(BRYOPHYTA_ENTRANCE);
        sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(BRYOPHYTA_ENTRANCE) <= 5, 10000);
    }

    private void enterLair() {
        currentTarget = "Entering Lair";
        
        try {
            Microbot.log("Attempting to enter Bryophyta lair...");
            
            // Find the gate by ID
            GameObject gate = Rs2GameObject.getGameObject(GATE_ID);
            if (gate == null) {
                Microbot.log("Gate not found - moving closer to entrance");
                Rs2Walker.walkTo(BRYOPHYTA_ENTRANCE);
                Rs2Player.waitForWalking();
                return;
            }
            
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
                                currentState = BryophytaState.FIGHTING_BOSS;
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
        
        // Enable protection prayer
        if (config.useProtectFromMagic()) {
            if (config.useQuickPrayer()) {
                Rs2Prayer.toggleQuickPrayer(true);
            } else {
                Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, true);
            }
        }

        // Check for growthlings first (they have priority)
        NPC growthling = Rs2Npc.getNpc(GROWTHLING_NAME);
        if (growthling != null && !growthling.isDead()) {
            currentState = BryophytaState.FIGHTING_GROWTHLINGS;
            return;
        }

        // Make sure we have main weapon equipped
        equipMainWeapon();

        // Eat food if health is low
        handleFood();

        // Attack Bryophyta
        Rs2NpcModel bryophyta = Rs2Npc.getNpc(BRYOPHYTA_NAME);
        if (bryophyta != null && !bryophyta.isDead()) {
            if (!Rs2Player.isInCombat()) {
                Rs2Npc.interact(bryophyta, "Attack");
                sleep(1000, 1500);
            }
        } else {
            // Bryophyta is dead, increment kill count
            killCount++;
        }
    }

    private void fightGrowthlings() {
        currentTarget = GROWTHLING_NAME;
        
        // Equip axe for fighting growthlings
        equipAxe();

        // Eat food if health is low
        handleFood();

        // Find and attack growthlings
        Rs2NpcModel growthling = Rs2Npc.getNpc(GROWTHLING_NAME);
        if (growthling != null && !growthling.isDead()) {
            if (!Rs2Player.isInCombat()) {
                Rs2Npc.interact(growthling, "attack");
                sleep(1000, 1500);
            }
        } else {
            // No more growthlings, go back to fighting boss
            currentState = BryophytaState.FIGHTING_BOSS;
        }
    }

    private void lootChest() {
        currentTarget = "Looting Chest";
        
        if (!hasKeys()) {
            return;
        }

        GameObject chest = Rs2GameObject.getGameObject(CHEST_NAME);
        if (chest != null) {
            chestClicked = false;
            chestLooted = false;
            noMossyKey = false;
            bossStillAlive = false;
            
            Rs2GameObject.interact(chest, "Open");
            
            // Wait for chest interaction response
            sleepUntil(() -> chestClicked || noMossyKey || bossStillAlive, 3000);
            
            if (chestLooted) {
                keysUsed++;
                // Wait for loot to appear on ground
                sleep(2000, 3000);
            }
        }
    }

    private void lootDrops() {
        currentTarget = "Looting Drops";
        
        // Loot valuable ground items
        Rs2GroundItem.lootItemBasedOnValue(1000, 3);
        
        // Wait a bit for looting to complete
        sleep(1000, 2000);
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
        if (!mainWeapon.isEmpty() && !hasAxeEquipped) {
            if (Rs2Inventory.hasItem(mainWeapon)) {
                Rs2Inventory.wield(mainWeapon);
                hasAxeEquipped = false;
                sleep(600, 1000);
            }
        }
    }

    private void equipAxe() {
        if (hasAxeEquipped) return;
        
        for (String axeName : AXE_NAMES) {
            if (Rs2Inventory.hasItem(axeName)) {
                Rs2Inventory.wield(axeName);
                hasAxeEquipped = true;
                sleep(600, 1000);
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
        return location.getRegionID() == 12952; // Bryophyta's lair region
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
               getFoodCount() < config.minFoodCount() ||
               Rs2Player.getBoostedSkillLevel(Skill.PRAYER) < config.minPrayerPoints();
    }

    private boolean needsPrayer() {
        return Rs2Player.getBoostedSkillLevel(Skill.PRAYER) < Rs2Player.getRealSkillLevel(Skill.PRAYER) && 
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
