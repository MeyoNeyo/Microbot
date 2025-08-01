package net.runelite.client.plugins.microbot.apexfighter.bank;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterPlugin;
import net.runelite.client.plugins.microbot.apexfighter.constants.Constants;
import net.runelite.client.plugins.microbot.apexfighter.enums.State;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

enum ItemToKeep {
    TELEPORT(Constants.TELEPORT_IDS, ApexFighterConfig::ignoreTeleport, ApexFighterConfig::staminaValue),
    STAMINA(Constants.STAMINA_POTION_IDS, ApexFighterConfig::useStamina, ApexFighterConfig::staminaValue),
    PRAYER(Constants.PRAYER_RESTORE_POTION_IDS, ApexFighterConfig::usePrayer, ApexFighterConfig::prayerValue),
    FOOD(Rs2Food.getIds(), ApexFighterConfig::useFood, ApexFighterConfig::foodValue),
    ANTIPOISON(Constants.ANTI_POISON_POTION_IDS, ApexFighterConfig::useAntipoison, ApexFighterConfig::antipoisonValue),
    ANTIFIRE(Constants.ANTI_FIRE_POTION_IDS, ApexFighterConfig::useAntifire, ApexFighterConfig::antifireValue),
    COMBAT(Constants.STRENGTH_POTION_IDS, ApexFighterConfig::useCombat, ApexFighterConfig::combatValue),
    RESTORE(Constants.RESTORE_POTION_IDS, ApexFighterConfig::useRestore, ApexFighterConfig::restoreValue);

    @Getter
    private final List<Integer> ids;
    private final Function<ApexFighterConfig, Boolean> useConfig;
    private final Function<ApexFighterConfig, Integer> valueConfig;

    ItemToKeep(Set<Integer> ids, Function<ApexFighterConfig, Boolean> useConfig, Function<ApexFighterConfig, Integer> valueConfig) {
        this.ids = new ArrayList<>(ids);
        this.useConfig = useConfig;
        this.valueConfig = valueConfig;
    }

    public boolean isEnabled(ApexFighterConfig config) {
        return useConfig.apply(config);
    }

    public int getValue(ApexFighterConfig config) {
        return valueConfig.apply(config);
    }
}

@Slf4j
public class BankerScript extends Script {
    ApexFighterConfig config;
    boolean initialized = false;
    private int bankingRetryCount = 0;
    private static final int MAX_BANKING_RETRIES = 5;

    public boolean run(ApexFighterConfig config) {
        this.config = config;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (config.bank() && needsBanking()) {
                    log.info("[run] Banking needed, attempting to bank (retry count: {})", bankingRetryCount);
                    
                    // PRIORITY: If world hop is in progress and banking is urgent, interrupt it
                    if (Microbot.pauseAllScripts.get()) {
                        // Check if we have food (most critical)
                        boolean hasFoodDepleted = false;
                        for (ItemToKeep item : ItemToKeep.values()) {
                            if (item.name().equals("FOOD") && item.isEnabled(config)) {
                                int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
                                if (count == 0) {
                                    hasFoodDepleted = true;
                                    break;
                                }
                            }
                        }
                        
                        if (hasFoodDepleted) {
                            net.runelite.client.plugins.microbot.apexfighter.worldhop.WorldHopManager.interruptWorldHopForBanking("No food remaining - banking is critical!");
                        }
                    }
                    
                    // Reset retry count if we've been away from banking for a while
                    if (ApexFighterPlugin.getState() != State.BANKING) {
                        bankingRetryCount = 0;
                    }
                    
                    // Check if we've exceeded max retries
                    if (bankingRetryCount >= MAX_BANKING_RETRIES) {
                        log.error("[run] Max banking retries ({}) exceeded, resetting retry count and continuing", MAX_BANKING_RETRIES);
                        bankingRetryCount = 0;
                        ApexFighterPlugin.setState(State.IDLE);
                        return; // Skip banking this cycle
                    }
                    
                    // Removed eatFoodForSpace logic; FoodScript handles eating
                    if (handleBanking()) {
                        log.info("[run] Banking successful, returning to combat area");
                        bankingRetryCount = 0; // Reset on success
                        // After banking, walk to center if not already there
                        if (config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) > config.attackRadius()) {
                            ApexFighterPlugin.setState(State.WALKING);
                            log.info("[run] Walking back to combat area from bank");
                            
                            // Always disable teleports for banking returns to prevent getting stuck
                            boolean originalDisableTeleports = Rs2Walker.disableTeleports;
                            try {
                                Rs2Walker.disableTeleports = true;
                                log.info("[run] Disabled teleports for safe walking back to combat area");
                                
                                // Use timeout for walking to prevent infinite waiting
                                boolean walkSuccess = Rs2Walker.walkTo(config.centerLocation(), 10);
                                if (!walkSuccess) {
                                    log.warn("[run] Walking back to combat area timed out, continuing anyway");
                                }
                                
                                // Wait for arrival with timeout
                                Rs2Player.waitForWalking(5000);
                                boolean arrived = config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) <= config.attackRadius();
                                if (arrived) {
                                    log.info("[run] Successfully returned to combat area");
                                    ApexFighterPlugin.setState(State.IDLE);
                                } else {
                                    log.warn("[run] Failed to reach combat area, will retry next cycle");
                                    ApexFighterPlugin.setState(State.IDLE); // Reset state anyway
                                }
                                
                            } finally {
                                // Always restore the original teleport setting
                                Rs2Walker.disableTeleports = originalDisableTeleports;
                                log.info("[run] Restored teleport setting to: {}", originalDisableTeleports);
                            }
                        } else {
                            log.info("[run] Already in combat area after banking");
                            ApexFighterPlugin.setState(State.IDLE);
                        }
                    } else {
                        bankingRetryCount++;
                        log.warn("[run] Banking failed, will retry next cycle (attempt {}/{})", bankingRetryCount, MAX_BANKING_RETRIES);
                        // State is already reset to IDLE in handleBanking if it failed
                    }
                } else if (!needsBanking() && config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) > config.attackRadius() && !Objects.equals(config.centerLocation(), new WorldPoint(0, 0, 0))) {
                    ApexFighterPlugin.setState(State.WALKING);
                    log.info("[run] Walking to combat area (not banking)");
                    
                    // Always disable teleports to prevent getting stuck on teleport dialogues
                    boolean originalDisableTeleports = Rs2Walker.disableTeleports;
                    try {
                        Rs2Walker.disableTeleports = true;
                        log.info("[run] Disabled teleports for safe walking to combat area");
                        
                        boolean walkSuccess = Rs2Walker.walkTo(config.centerLocation(), 10);
                        if (walkSuccess) {
                            Rs2Player.waitForWalking(5000);
                            if (config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) <= config.attackRadius()) {
                                log.info("[run] Successfully reached combat area");
                                ApexFighterPlugin.setState(State.IDLE);
                            } else {
                                log.warn("[run] Walking to combat area incomplete, will retry");
                                ApexFighterPlugin.setState(State.IDLE); // Reset state anyway
                            }
                        } else {
                            log.warn("[run] Failed to start walking to combat area");
                            ApexFighterPlugin.setState(State.IDLE);
                        }
                    } finally {
                        // Always restore the original teleport setting
                        Rs2Walker.disableTeleports = originalDisableTeleports;
                        log.info("[run] Restored teleport setting to: {}", originalDisableTeleports);
                    }
                }
            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    public boolean needsBanking() {
        // Use the static method to ensure consistent logic
        return isBankingNeeded(config);
    }

    /**
     * Static method to check if banking is needed without requiring a BankerScript instance.
     * This should be used by other scripts to check banking priority.
     * 
     * NEW PRIORITY LOGIC (Fixed):
     * 1. INVENTORY SPACE FIRST - Bank if free slots <= minFreeSlots (regardless of food status)
     * 2. FOOD DEPLETION - Bank if food is completely depleted (count = 0) 
     * 3. CRITICAL ITEMS - Bank only if other upkeep items are completely depleted (count = 0)
     * 4. CUSTOM ITEMS - Bank only if custom items are completely depleted (count = 0)
     * 
     * This ensures:
     * - Banking happens when inventory is full of loot items
     * - Food is prioritized but not exclusively (will still bank for inventory space)
     * - Teleports like "Falador teleport" only trigger banking if completely depleted (4/5 is fine)
     */
    public static boolean isBankingNeeded(ApexFighterConfig config) {
        if (!config.bank()) return false;
        
        // 1. ALWAYS check inventory slots first - HIGHEST PRIORITY (regardless of food status)
        int currentFreeSlots = Rs2Inventory.emptySlotCount();
        int minRequiredSlots = config.minFreeSlots();
        boolean slotDepleted = currentFreeSlots <= minRequiredSlots;
        if (slotDepleted) {
            log.info("[isBankingNeeded] INVENTORY SPACE - Not enough free slots (current: {}, required: {}) - banking needed", 
                currentFreeSlots, minRequiredSlots);
            return true;
        }
        
        // 2. Check if food is completely depleted - SECOND PRIORITY
        if (config.useFood()) {
            // Check if custom food list is specified
            List<Rs2Food> customFoods = parseCustomFoodPriority(config.customFoodPriority());
            
            int foodCount;
            if (!customFoods.isEmpty()) {
                // Count only custom foods if list is specified
                foodCount = customFoods.stream()
                    .mapToInt(food -> Rs2Inventory.count(food.getId()))
                    .sum();
                log.debug("[isBankingNeeded] Using custom food list for food count: {} total from {} food types", 
                    foodCount, customFoods.size());
            } else {
                // Count all food if no custom list
                foodCount = Rs2Food.getIds().stream().mapToInt(Rs2Inventory::count).sum();
                log.debug("[isBankingNeeded] Using automatic food detection for food count: {}", foodCount);
            }
            
            if (foodCount == 0) {
                log.info("[isBankingNeeded] CRITICAL - Food completely depleted (count: 0) - banking needed immediately");
                return true;
            } else {
                log.debug("[isBankingNeeded] Food available (count: {}) - continue fighting, no need to bank for food", foodCount);
                // Don't return false here - we still need to check other critical items
            }
        }
        
        // 3. Check other critical upkeep items (if food available, only for critical depletion)
        boolean hasCriticalDepletion = false;
        for (ItemToKeep item : ItemToKeep.values()) {
            if (item == ItemToKeep.TELEPORT || item == ItemToKeep.FOOD || !item.isEnabled(config)) continue;
            
            int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
            
            log.debug("[isBankingNeeded] {} check - enabled: {}, count: {}", 
                item.name(), item.isEnabled(config), count);
                
            // Only bank for critical depletion: item is completely out (count = 0)
            if (count == 0) {
                log.info("[isBankingNeeded] Critical item '{}' depleted (count: 0) - banking needed", item.name());
                hasCriticalDepletion = true;
                break;
            }
        }
        
        if (hasCriticalDepletion) {
            return true;
        }
        
        // 4. Check custom banking items for critical depletion (count = 0)
        Map<String, Integer> keepItems = BankerScript.parseBankingInventoryKeep(config.bankingInventoryKeep());
        for (Map.Entry<String, Integer> entry : keepItems.entrySet()) {
            String itemName = entry.getKey();
            Integer requiredAmount = entry.getValue();
            int currentAmount = Rs2Inventory.all().stream()
                .filter(i -> i.getName().equalsIgnoreCase(itemName))
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();
            
            // ONLY bank for custom items if they are COMPLETELY DEPLETED (count = 0)
            if (currentAmount == 0) {
                log.info("[isBankingNeeded] Custom item '{}' completely depleted (count: 0) - banking needed", itemName);
                return true;
            } else {
                log.debug("[isBankingNeeded] Custom item '{}' available (current: {}, target: {}) - no banking needed", 
                    itemName, currentAmount, requiredAmount);
            }
        }
        
        // Debug log when banking is NOT needed
        log.debug("[isBankingNeeded] Banking not needed - food available or all critical items present");
        return false;
    }

    public boolean withdrawUpkeepItems(ApexFighterConfig config) {
        if (config.useInventorySetup()) {
            Rs2InventorySetup inventorySetup = new Rs2InventorySetup(config.inventorySetup().getName(), mainScheduledFuture);
            if (!inventorySetup.doesEquipmentMatch()) {
                inventorySetup.loadEquipment();
            }
            inventorySetup.loadInventory();
            return true;
        }

        // Withdraw all non-food upkeep items first
        for (ItemToKeep item : ItemToKeep.values()) {
            if (!item.name().equals("FOOD") && item.isEnabled(config)) {
                int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
                int required = item.getValue(config);
                int needed = required - count;
                
                if (needed > 0) {
                    log.info("[withdrawUpkeepItems] Need {} more {} items (current: {}, required: {})", 
                        needed, item.name(), count, required);
                    
                    // Sort IDs by dose priority: 1-dose first, then 2-dose, 3-dose, 4-dose
                    List<Integer> sortedIds = item.getIds().stream()
                        .sorted((id1, id2) -> {
                            int dose1 = getPotionDose(id1);
                            int dose2 = getPotionDose(id2);
                            log.debug("[withdrawUpkeepItems] Comparing potions - ID1: {} (dose: {}), ID2: {} (dose: {})", 
                                id1, dose1, id2, dose2);
                            return Integer.compare(dose1, dose2); // Sort ascending (1-dose first)
                        })
                        .collect(Collectors.toList());
                    
                    log.info("[withdrawUpkeepItems] {} sorted by dose: {}", item.name(), 
                        sortedIds.stream()
                            .map(id -> id + "(dose:" + getPotionDose(id) + ")")
                            .collect(Collectors.joining(", ")));
                    
                    boolean withdrawn = false;
                    int remaining = needed;
                    
                    // Try to withdraw from lowest dose potions first
                    for (int id : sortedIds) {
                        if (remaining <= 0) break;
                        
                        if (Rs2Bank.hasBankItem(id, 1)) { // Check if at least 1 is available
                            int availableInBank = Rs2Bank.count(id);
                            int toWithdraw = Math.min(remaining, availableInBank);
                            int currentCount = Rs2Inventory.count(id); // Get count before withdrawal
                            
                            log.info("[withdrawUpkeepItems] Withdrawing {} x{} (ID: {}, dose: {}, available: {})", 
                                item.name(), toWithdraw, id, getPotionDose(id), availableInBank);
                            Rs2Bank.withdrawX(true, id, toWithdraw);
                            
                            // Wait and verify withdrawal with correct parameters
                            if (waitForWithdrawal(id, currentCount, currentCount + toWithdraw, 3000)) {
                                remaining -= toWithdraw;
                                withdrawn = true;
                                log.info("[withdrawUpkeepItems] Successfully withdrew {} {}, {} remaining needed", 
                                    toWithdraw, item.name(), remaining);
                            } else {
                                log.warn("[withdrawUpkeepItems] Failed to withdraw correct amount of {} (ID: {})", item.name(), id);
                            }
                        } else {
                            log.debug("[withdrawUpkeepItems] Item ID {} not available in bank or insufficient quantity", id);
                        }
                    }
                    
                    if (!withdrawn) {
                        log.warn("[withdrawUpkeepItems] Could not withdraw required {} items from bank", item.name());
                    }
                }
            }
        }

        // Withdraw food upkeep item last - use custom priority list or automatic selection
        for (ItemToKeep item : ItemToKeep.values()) {
            if (item.name().equals("FOOD") && item.isEnabled(config)) {
                int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
                int required = item.getValue(config);
                int needed = required - count;
                
                if (needed > 0) {
                    log.info("[withdrawUpkeepItems] Need {} more food items (current: {}, required: {})", 
                        needed, count, required);
                    
                    boolean withdrawn = false;
                    
                    // Get food list based on config (custom priority or automatic)
                    List<Rs2Food> foodSelectionList = getFoodSelectionList(config);
                    boolean usingCustomList = !parseCustomFoodPriority(config.customFoodPriority()).isEmpty();
                    
                    if (usingCustomList) {
                        log.info("[withdrawUpkeepItems] Using CUSTOM food priority list: {}", 
                            foodSelectionList.stream().map(Rs2Food::getName).collect(Collectors.toList()));
                    } else {
                        log.debug("[withdrawUpkeepItems] Using AUTOMATIC food selection (highest healing first)");
                    }
                    
                    // Try to withdraw food in priority order - withdraw as much as possible from each food type
                    int remainingNeeded = needed;
                    for (Rs2Food food : foodSelectionList) {
                        if (remainingNeeded <= 0) break; // All needed food has been withdrawn
                        
                        int availableInBank = Rs2Bank.count(food.getId());
                        if (availableInBank > 0) {
                            int toWithdraw = Math.min(remainingNeeded, availableInBank);
                            log.info("[withdrawUpkeepItems] Found {} x{} (heal: {}) in bank, withdrawing {} pieces", 
                                food.getName(), availableInBank, food.getHeal(), toWithdraw);
                            Rs2Bank.withdrawX(true, food.getId(), toWithdraw);
                            
                            // Wait and verify withdrawal with correct parameters
                            int currentFoodCount = Rs2Inventory.count(food.getId());
                            if (waitForWithdrawal(food.getId(), currentFoodCount, 
                                                currentFoodCount + toWithdraw, 3000)) {
                                log.info("[withdrawUpkeepItems] Successfully withdrew {} {} (heal: {} each), {} still needed", 
                                    toWithdraw, food.getName(), food.getHeal(), remainingNeeded - toWithdraw);
                                remainingNeeded -= toWithdraw;
                                withdrawn = true;
                            } else {
                                log.warn("[withdrawUpkeepItems] Failed to withdraw correct amount of {} (ID: {})", 
                                    food.getName(), food.getId());
                            }
                        } else {
                            log.debug("[withdrawUpkeepItems] {} not available in bank, trying next food type", 
                                food.getName());
                        }
                    }
                    
                    if (!withdrawn) {
                        if (usingCustomList) {
                            // With custom list, STOP the script if none of the specified foods are available
                            log.error("[withdrawUpkeepItems] CRITICAL: None of the custom food types are available in bank!");
                            log.error("[withdrawUpkeepItems] Custom food list: {}", 
                                foodSelectionList.stream().map(Rs2Food::getName).collect(Collectors.toList()));
                            log.error("[withdrawUpkeepItems] Script will stop to prevent using unwanted food types.");
                            Microbot.pauseAllScripts.set(true);
                            return false; // Stop banking process
                        } else {
                            // With automatic selection, just warn but continue
                            log.warn("[withdrawUpkeepItems] Could not withdraw any food items from bank");
                        }
                    } else if (remainingNeeded > 0) {
                        if (usingCustomList) {
                            log.warn("[withdrawUpkeepItems] Only partially fulfilled custom food requirement - {} still needed", remainingNeeded);
                        } else {
                            log.warn("[withdrawUpkeepItems] Only partially fulfilled food requirement - {} still needed", remainingNeeded);
                        }
                    }
                }
            }
        }
        
        // Withdraw custom keep items
        Map<String, Integer> keepItems = parseBankingInventoryKeep(config.bankingInventoryKeep());
        for (Map.Entry<String, Integer> entry : keepItems.entrySet()) {
            String itemName = entry.getKey();
            Integer requiredAmount = entry.getValue();
            int currentAmount = Rs2Inventory.count(itemName);
            
            if (requiredAmount != null && requiredAmount == Integer.MAX_VALUE) {
                // Withdraw all of this item if not already in inventory
                int inBank = Rs2Bank.count(itemName);
                if (inBank > 0 && currentAmount < inBank) {
                    log.info("[withdrawUpkeepItems] Withdrawing all {} from bank", itemName);
                    Rs2Bank.withdrawAll(true, itemName);
                    
                    // Wait for withdrawal to complete
                    waitForCustomItemWithdrawal(itemName, currentAmount, 3000);
                }
            } else if (requiredAmount != null && currentAmount < requiredAmount) {
                int needed = requiredAmount - currentAmount;
                log.info("[withdrawUpkeepItems] Withdrawing {} x{} (current: {}, required: {})", 
                    itemName, needed, currentAmount, requiredAmount);
                Rs2Bank.withdrawX(true, itemName, needed);
                
                // Wait and verify withdrawal with correction support
                waitForCustomItemWithdrawalWithTarget(itemName, currentAmount, requiredAmount, 3000);
            }
        }
        return !isUpkeepItemDepleted(config);
    }
    
    /**
     * Waits for a specific item withdrawal to complete and verifies the correct amount was withdrawn.
     * If the withdrawal is incorrect, it will attempt to correct it.
     * @param itemId The ID of the item being withdrawn
     * @param initialCount The count before withdrawal
     * @param targetCount The desired total count after withdrawal
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if the correct amount was withdrawn, false otherwise
     */
    private boolean waitForWithdrawal(int itemId, int initialCount, int targetCount, long timeoutMs) {
        log.debug("[waitForWithdrawal] Starting withdrawal verification for item ID {} (initial: {}, target: {})", 
            itemId, initialCount, targetCount);
        
        // Wait for the withdrawal to complete
        boolean withdrawalSuccessful = sleepUntil(() -> {
            int currentCount = Rs2Inventory.count(itemId);
            return currentCount >= targetCount;
        }, (int) timeoutMs);
        
        int finalCount = Rs2Inventory.count(itemId);
        
        if (withdrawalSuccessful && finalCount >= targetCount) {
            log.debug("[waitForWithdrawal] Successfully withdrew item ID {} (final count: {})", itemId, finalCount);
            return true;
        }
        
        // If withdrawal was incomplete, try to correct it
        if (finalCount > initialCount && finalCount < targetCount) {
            int stillNeeded = targetCount - finalCount;
            log.info("[waitForWithdrawal] Partial withdrawal detected for item ID {} (got: {}, need: {} more)", 
                itemId, finalCount, stillNeeded);
                
            if (Rs2Bank.isOpen() && Rs2Bank.hasBankItem(itemId, stillNeeded)) {
                log.info("[waitForWithdrawal] Attempting to withdraw remaining {} of item ID {}", stillNeeded, itemId);
                Rs2Bank.withdrawX(true, itemId, stillNeeded);
                
                // Wait for the correction
                boolean correctionSuccessful = sleepUntil(() -> {
                    int currentCount = Rs2Inventory.count(itemId);
                    return currentCount >= targetCount;
                }, 2000);
                
                finalCount = Rs2Inventory.count(itemId);
                if (correctionSuccessful && finalCount >= targetCount) {
                    log.info("[waitForWithdrawal] Successfully corrected withdrawal for item ID {} (final count: {})", 
                        itemId, finalCount);
                    return true;
                }
            }
        }
        
        log.warn("[waitForWithdrawal] Failed to withdraw correct amount of item ID {} (initial: {}, target: {}, final: {})", 
            itemId, initialCount, targetCount, finalCount);
        return false;
    }
    
    /**
     * Waits for a custom item withdrawal to complete and attempts correction if needed.
     * @param itemName The name of the item being withdrawn
     * @param initialCount The count before withdrawal
     * @param timeoutMs Maximum time to wait in milliseconds
     */
    private void waitForCustomItemWithdrawal(String itemName, int initialCount, long timeoutMs) {
        log.debug("[waitForCustomItemWithdrawal] Starting withdrawal verification for '{}' (initial: {})", 
            itemName, initialCount);
        
        // Wait for any change in inventory count
        boolean withdrawalDetected = sleepUntil(() -> {
            int currentCount = Rs2Inventory.count(itemName);
            return currentCount > initialCount;
        }, (int) timeoutMs);
        
        int finalCount = Rs2Inventory.count(itemName);
        
        if (withdrawalDetected && finalCount > initialCount) {
            log.debug("[waitForCustomItemWithdrawal] Successfully withdrew '{}' (final count: {})", itemName, finalCount);
        } else {
            log.warn("[waitForCustomItemWithdrawal] No change detected for '{}' (initial: {}, final: {})", 
                itemName, initialCount, finalCount);
        }
    }
    
    /**
     * Waits for a custom item withdrawal to complete with a specific target and attempts correction if needed.
     * @param itemName The name of the item being withdrawn
     * @param initialCount The count before withdrawal  
     * @param targetCount The desired total count after withdrawal
     * @param timeoutMs Maximum time to wait in milliseconds
     */
    private void waitForCustomItemWithdrawalWithTarget(String itemName, int initialCount, int targetCount, long timeoutMs) {
        log.debug("[waitForCustomItemWithdrawalWithTarget] Starting withdrawal verification for '{}' (initial: {}, target: {})", 
            itemName, initialCount, targetCount);
        
        // Wait for the withdrawal to complete
        boolean withdrawalSuccessful = sleepUntil(() -> {
            int currentCount = Rs2Inventory.count(itemName);
            return currentCount >= targetCount;
        }, (int) timeoutMs);
        
        int finalCount = Rs2Inventory.count(itemName);
        
        if (withdrawalSuccessful && finalCount >= targetCount) {
            log.debug("[waitForCustomItemWithdrawalWithTarget] Successfully withdrew '{}' (final count: {})", itemName, finalCount);
            return;
        }
        
        // If withdrawal was incomplete, try to correct it
        if (finalCount > initialCount && finalCount < targetCount) {
            int stillNeeded = targetCount - finalCount;
            log.info("[waitForCustomItemWithdrawalWithTarget] Partial withdrawal detected for '{}' (got: {}, need: {} more)", 
                itemName, finalCount, stillNeeded);
                
            if (Rs2Bank.isOpen() && Rs2Bank.hasBankItem(itemName, stillNeeded)) {
                log.info("[waitForCustomItemWithdrawalWithTarget] Attempting to withdraw remaining {} of '{}'", stillNeeded, itemName);
                Rs2Bank.withdrawX(true, itemName, stillNeeded);
                
                // Wait for the correction
                boolean correctionSuccessful = sleepUntil(() -> {
                    int currentCount = Rs2Inventory.count(itemName);
                    return currentCount >= targetCount;
                }, 2000);
                
                finalCount = Rs2Inventory.count(itemName);
                if (correctionSuccessful && finalCount >= targetCount) {
                    log.info("[waitForCustomItemWithdrawalWithTarget] Successfully corrected withdrawal for '{}' (final count: {})", 
                        itemName, finalCount);
                    return;
                }
            }
        }
        
        log.warn("[waitForCustomItemWithdrawalWithTarget] Failed to withdraw correct amount of '{}' (initial: {}, target: {}, final: {})", 
            itemName, initialCount, targetCount, finalCount);
    }

    public boolean depositAllExcept(ApexFighterConfig config) {
        // Get item names and amounts to keep from config
        Map<String, Integer> keepItems = parseBankingInventoryKeep(config.bankingInventoryKeep());
        // Convert item names to IDs using Rs2Inventory and Rs2Bank helpers
        List<Integer> idsToKeep = new ArrayList<>();
        Set<Integer> upkeepIds = Arrays.stream(ItemToKeep.values())
                .filter(item -> item.isEnabled(config))
                .flatMap(item -> item.getIds().stream())
                .collect(Collectors.toSet());

        // First, deposit excess of keep items
        for (Map.Entry<String, Integer> entry : keepItems.entrySet()) {
            String itemName = entry.getKey();
            Integer keepAmount = entry.getValue();
            List<Rs2ItemModel> inventoryItems = Rs2Inventory.all().stream()
                    .filter(i -> i.getName().equalsIgnoreCase(itemName))
                    .collect(Collectors.toList());
            int totalInInventory = inventoryItems.stream().mapToInt(Rs2ItemModel::getQuantity).sum();
            if (keepAmount == null) keepAmount = 1;
            // If keepAmount is Integer.MAX_VALUE, keep all (deposit none)
            int toDeposit = (keepAmount != null && keepAmount == Integer.MAX_VALUE) ? 0 : totalInInventory - keepAmount;
            if (toDeposit > 0 && !inventoryItems.isEmpty()) {
                Rs2Bank.depositX(inventoryItems.get(0).getId(), toDeposit);
            }
            // Always keep the item ID in the keep list
            if (!inventoryItems.isEmpty()) {
                idsToKeep.add(inventoryItems.get(0).getId());
            }
        }
        // Add upkeep item IDs to keep list
        idsToKeep.addAll(upkeepIds);
        // Deposit all except the keep list
        Rs2Bank.depositAllExcept(idsToKeep.toArray(new Integer[0]));
        return Rs2Bank.isOpen();
    }

    public boolean isUpkeepItemDepleted(ApexFighterConfig config) {
        return Arrays.stream(ItemToKeep.values())
                .filter(item -> item != ItemToKeep.TELEPORT && item.isEnabled(config))
                .anyMatch(item -> item.getIds().stream().mapToInt(Rs2Inventory::count).sum() == 0);
    }

    /**
     * Static version of isUpkeepItemDepleted for use in static contexts
     */
    public static boolean isUpkeepItemDepletedStatic(ApexFighterConfig config) {
        return Arrays.stream(ItemToKeep.values())
                .filter(item -> item != ItemToKeep.TELEPORT && item.isEnabled(config))
                .anyMatch(item -> item.getIds().stream().mapToInt(Rs2Inventory::count).sum() == 0);
    }

    /**
     * Get a debugging string showing upkeep item status for overlay display
     */
    public static String getUpkeepItemsDebugInfo(ApexFighterConfig config) {
        StringBuilder sb = new StringBuilder();
        for (ItemToKeep item : ItemToKeep.values()) {
            if (item == ItemToKeep.TELEPORT || !item.isEnabled(config)) continue;
            
            int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
            int required = item.getValue(config);
            sb.append(item.name()).append(": ").append(count).append("/").append(required).append(" ");
        }
        return sb.toString().trim();
    }

    /**
     * Extract the dose number from a potion ID based on its name pattern.
     * Most potions follow the pattern: POTION_NAME1, POTION_NAME2, etc.
     * Returns 5 for unknown potions to sort them last.
     */
    private static int getPotionDose(int itemId) {
        // Create a mapping using the actual ItemID constants
        // This is the most reliable way to determine potion doses
        
        // Use a simple lookup approach with known potion ID patterns
        // Most potions follow the pattern where lower dose = higher ID number
        
        // For antifire potions: 2458, 2456, 2454, 2452 = doses 1, 2, 3, 4
        if (itemId == 2458) return 1; // ANTIFIRE_POTION1
        if (itemId == 2456) return 2; // ANTIFIRE_POTION2 
        if (itemId == 2454) return 3; // ANTIFIRE_POTION3
        if (itemId == 2452) return 4; // ANTIFIRE_POTION4
        
        // Extended antifire: 11951, 11949, 11947, 11945 = doses 1, 2, 3, 4
        if (itemId == 11951) return 1; // EXTENDED_ANTIFIRE1
        if (itemId == 11949) return 2; // EXTENDED_ANTIFIRE2
        if (itemId == 11947) return 3; // EXTENDED_ANTIFIRE3
        if (itemId == 11945) return 4; // EXTENDED_ANTIFIRE4
        
        // Super antifire: 21987, 21984, 21981, 21978 = doses 1, 2, 3, 4
        if (itemId == 21987) return 1; // SUPER_ANTIFIRE_POTION1
        if (itemId == 21984) return 2; // SUPER_ANTIFIRE_POTION2
        if (itemId == 21981) return 3; // SUPER_ANTIFIRE_POTION3
        if (itemId == 21978) return 4; // SUPER_ANTIFIRE_POTION4
        
        // Extended super antifire: 22209, 22212, 22215, 22218 = doses 1, 2, 3, 4
        if (itemId == 22209) return 1; // EXTENDED_SUPER_ANTIFIRE1
        if (itemId == 22212) return 2; // EXTENDED_SUPER_ANTIFIRE2
        if (itemId == 22215) return 3; // EXTENDED_SUPER_ANTIFIRE3
        if (itemId == 22218) return 4; // EXTENDED_SUPER_ANTIFIRE4
        
        // Combat potions: 9739, 9741, 9743, 9745 = doses 1, 2, 3, 4
        if (itemId == 9739) return 1; // COMBAT_POTION1
        if (itemId == 9741) return 2; // COMBAT_POTION2
        if (itemId == 9743) return 3; // COMBAT_POTION3
        if (itemId == 9745) return 4; // COMBAT_POTION4
        
        // Super combat: 12695, 12697, 12699, 12701 = doses 1, 2, 3, 4
        if (itemId == 12695) return 1; // SUPER_COMBAT_POTION1
        if (itemId == 12697) return 2; // SUPER_COMBAT_POTION2
        if (itemId == 12699) return 3; // SUPER_COMBAT_POTION3
        if (itemId == 12701) return 4; // SUPER_COMBAT_POTION4
        
        // Divine super combat: 23685, 23688, 23691, 23694 = doses 1, 2, 3, 4
        if (itemId == 23685) return 1; // DIVINE_SUPER_COMBAT_POTION1
        if (itemId == 23688) return 2; // DIVINE_SUPER_COMBAT_POTION2
        if (itemId == 23691) return 3; // DIVINE_SUPER_COMBAT_POTION3
        if (itemId == 23694) return 4; // DIVINE_SUPER_COMBAT_POTION4
        
        // Strength potions: 119, 117, 115, 113 = doses 1, 2, 3, 4
        if (itemId == 119) return 1; // STRENGTH_POTION1
        if (itemId == 117) return 2; // STRENGTH_POTION2
        if (itemId == 115) return 3; // STRENGTH_POTION3
        if (itemId == 113) return 4; // STRENGTH_POTION4
        
        // Super strength: 149, 159, 157, 2440 = doses 1, 2, 3, 4
        if (itemId == 149) return 1; // SUPER_STRENGTH1
        if (itemId == 159) return 2; // SUPER_STRENGTH2
        if (itemId == 157) return 3; // SUPER_STRENGTH3
        if (itemId == 2440) return 4; // SUPER_STRENGTH4
        
        // Divine super strength: 23718, 23715, 23712, 23709 = doses 1, 2, 3, 4
        if (itemId == 23718) return 1; // DIVINE_SUPER_STRENGTH_POTION1
        if (itemId == 23715) return 2; // DIVINE_SUPER_STRENGTH_POTION2
        if (itemId == 23712) return 3; // DIVINE_SUPER_STRENGTH_POTION3
        if (itemId == 23709) return 4; // DIVINE_SUPER_STRENGTH_POTION4
        
        // Prayer potions: 143, 141, 139, 2434 = doses 1, 2, 3, 4
        if (itemId == 143) return 1; // PRAYER_POTION1
        if (itemId == 141) return 2; // PRAYER_POTION2
        if (itemId == 139) return 3; // PRAYER_POTION3
        if (itemId == 2434) return 4; // PRAYER_POTION4
        
        // Super restore: 3030, 3028, 3026, 3024 = doses 1, 2, 3, 4
        if (itemId == 3030) return 1; // SUPER_RESTORE1
        if (itemId == 3028) return 2; // SUPER_RESTORE2
        if (itemId == 3026) return 3; // SUPER_RESTORE3
        if (itemId == 3024) return 4; // SUPER_RESTORE4
        
        // Restore potions: 131, 129, 127, 2430 = doses 1, 2, 3, 4
        if (itemId == 131) return 1; // RESTORE_POTION1
        if (itemId == 129) return 2; // RESTORE_POTION2
        if (itemId == 127) return 3; // RESTORE_POTION3
        if (itemId == 2430) return 4; // RESTORE_POTION4
        
        // Antipoison: 179, 177, 175, 2446 = doses 1, 2, 3, 4
        if (itemId == 179) return 1; // ANTIPOISON1
        if (itemId == 177) return 2; // ANTIPOISON2
        if (itemId == 175) return 3; // ANTIPOISON3
        if (itemId == 2446) return 4; // ANTIPOISON4
        
        // Super antipoison: 185, 183, 181, 2448 = doses 1, 2, 3, 4
        if (itemId == 185) return 1; // SUPERANTIPOISON1
        if (itemId == 183) return 2; // SUPERANTIPOISON2
        if (itemId == 181) return 3; // SUPERANTIPOISON3
        if (itemId == 2448) return 4; // SUPERANTIPOISON4
        
        // Stamina potions: 12631, 12629, 12627, 12625 = doses 1, 2, 3, 4
        if (itemId == 12631) return 1; // STAMINA_POTION1
        if (itemId == 12629) return 2; // STAMINA_POTION2
        if (itemId == 12627) return 3; // STAMINA_POTION3
        if (itemId == 12625) return 4; // STAMINA_POTION4
        
        // Try to get item name as fallback for unknown potions
        String itemName = "";
        
        // Try to get from bank interface if open
        if (Rs2Bank.isOpen()) {
            try {
                itemName = Rs2Bank.getBankItem(itemId) != null ? Rs2Bank.getBankItem(itemId).getName() : "";
            } catch (Exception e) {
                // Fallback to inventory check
            }
        }
        
        // If still empty, try inventory
        if (itemName.isEmpty()) {
            itemName = Rs2Inventory.get(itemId) != null ? Rs2Inventory.get(itemId).getName() : "";
        }
        
        // If we have a name, extract dose from it
        if (!itemName.isEmpty()) {
            // Extract dose from name like "Antifire potion(1)", "Super strength(3)", etc.
            if (itemName.contains("(1)")) return 1;
            if (itemName.contains("(2)")) return 2;
            if (itemName.contains("(3)")) return 3;
            if (itemName.contains("(4)")) return 4;
        }
        
        // If we can't determine dose, assume it's unknown (sort last)
        log.debug("[getPotionDose] Unknown potion ID: {}, treating as dose 5 (sort last)", itemId);
        return 5; // Unknown dose, sort last
    }

    public boolean goToBank() {
        return Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 8);
    }

    public boolean handleBanking() {
        ApexFighterPlugin.setState(State.BANKING);
        Rs2Prayer.disableAllPrayers();
        
        log.info("[handleBanking] Starting banking process");
        
        // Try to walk to bank and open it
        if (Rs2Bank.walkToBankAndUseBank()) {
            log.info("[handleBanking] Successfully reached bank, performing transactions");
            depositAllExcept(config);
            withdrawUpkeepItems(config);
            Rs2Bank.closeBank();
            log.info("[handleBanking] Banking completed successfully");
            return !needsBanking();
        } else {
            log.warn("[handleBanking] Failed to reach bank or open bank interface");
            // Reset state if banking failed
            ApexFighterPlugin.setState(State.IDLE);
            return false;
        }
    }


    public void shutdown() {
        super.shutdown();
        // reset the initialized flag
        initialized = false;

    }

    /**
     * Parses the bankingInventoryKeep config string into a map of item name -> amount (null if not specified)
     */
    public static Map<String, Integer> parseBankingInventoryKeep(String input) {
        Map<String, Integer> result = new HashMap<>();
        if (input == null || input.trim().isEmpty()) return result;
        String[] items = input.split(",");
        for (String item : items) {
            item = item.trim();
            if (item.isEmpty()) continue;
            if (item.contains("-")) {
                String[] parts = item.split("-");
                String name = parts[0].trim();
                String amountStr = parts[1].trim();
                if (amountStr.equalsIgnoreCase("all")) {
                    result.put(name, Integer.MAX_VALUE); // Integer.MAX_VALUE means 'all'
                } else {
                    try {
                        int amount = Integer.parseInt(amountStr);
                        result.put(name, amount);
                    } catch (NumberFormatException e) {
                        result.put(name, 1); // fallback if amount is invalid
                    }
                }
            } else {
                result.put(item, 1);
            }
        }
        return result;
    }

    /**
     * Attempts to withdraw partial food amounts from the provided food list
     * @param foodList List of foods to try (in priority order)
     * @param needed Amount of food needed
     * @return true if any food was withdrawn, false otherwise
     */
    /**
     * Parses the custom food priority list from config
     * @param foodListString Comma-separated food names like "Shark,Lobster,Trout"
     * @return List of Rs2Food in priority order (first item = highest priority)
     */
    public static List<Rs2Food> parseCustomFoodPriority(String foodListString) {
        List<Rs2Food> priorityFoods = new ArrayList<>();
        
        if (foodListString == null || foodListString.trim().isEmpty()) {
            log.debug("[parseCustomFoodPriority] No custom food list provided, will use automatic selection");
            return priorityFoods; // Empty list means use automatic selection
        }
        
        String[] foodNames = foodListString.split(",");
        for (String foodName : foodNames) {
            String cleanName = foodName.trim();
            if (cleanName.isEmpty()) continue;
            
            // Find matching Rs2Food by name (case-insensitive)
            Rs2Food matchedFood = findFoodByName(cleanName);
            if (matchedFood != null) {
                priorityFoods.add(matchedFood);
                log.debug("[parseCustomFoodPriority] Added food '{}' (heal: {}) to priority list", 
                    matchedFood.getName(), matchedFood.getHeal());
            } else {
                log.warn("[parseCustomFoodPriority] Could not find food matching name '{}' - skipping", cleanName);
            }
        }
        
        log.info("[parseCustomFoodPriority] Parsed {} valid foods from custom list: {}", 
            priorityFoods.size(), priorityFoods.stream().map(Rs2Food::getName).collect(Collectors.toList()));
        return priorityFoods;
    }

    /**
     * Finds Rs2Food enum by name (case-insensitive)
     * @param foodName Name to search for
     * @return Matching Rs2Food or null if not found
     */
    private static Rs2Food findFoodByName(String foodName) {
        for (Rs2Food food : Rs2Food.values()) {
            if (food.getName().equalsIgnoreCase(foodName)) {
                return food;
            }
        }
        
        // Try partial matching for common food names
        String lowerFoodName = foodName.toLowerCase();
        for (Rs2Food food : Rs2Food.values()) {
            String lowerFoodEnumName = food.getName().toLowerCase();
            if (lowerFoodEnumName.contains(lowerFoodName) || lowerFoodName.contains(lowerFoodEnumName)) {
                log.debug("[findFoodByName] Found partial match '{}' for search term '{}'", 
                    food.getName(), foodName);
                return food;
            }
        }
        
        return null;
    }

    /**
     * Gets the appropriate food list based on config - either custom priority or automatic selection
     * @param config ApexFighterConfig to check for custom food settings
     * @return List of Rs2Food in order of priority
     */
    public static List<Rs2Food> getFoodSelectionList(ApexFighterConfig config) {
        List<Rs2Food> customFoods = parseCustomFoodPriority(config.customFoodPriority());
        
        if (!customFoods.isEmpty()) {
            log.info("[getFoodSelectionList] Using custom food priority list with {} foods", customFoods.size());
            return customFoods;
        } else {
            // Fallback to automatic selection (highest healing first)
            List<Rs2Food> automaticList = Arrays.stream(Rs2Food.values())
                .sorted(Comparator.comparingInt(Rs2Food::getHeal).reversed())
                .collect(Collectors.toList());
            log.debug("[getFoodSelectionList] Using automatic food selection (highest healing first)");
            return automaticList;
        }
    }
}
