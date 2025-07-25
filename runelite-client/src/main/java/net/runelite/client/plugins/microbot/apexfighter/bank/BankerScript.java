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
     * PRIORITY LOGIC:
     * 1. FOOD FIRST - Only bank if food is completely depleted (count = 0)
     * 2. Keep fighting as long as there's ANY food available
     * 3. Only check other items when food is depleted
     */
    public static boolean isBankingNeeded(ApexFighterConfig config) {
        if (!config.bank()) return false;
        
        // 1. Check if food is completely depleted - HIGHEST PRIORITY
        if (config.useFood()) {
            int foodCount = Rs2Food.getIds().stream().mapToInt(Rs2Inventory::count).sum();
            if (foodCount == 0) {
                log.info("[isBankingNeeded] CRITICAL - Food completely depleted (count: 0) - banking needed immediately");
                return true;
            } else {
                log.debug("[isBankingNeeded] Food available (count: {}) - continue fighting, no banking needed", foodCount);
                // As long as there's food, DON'T bank - keep fighting!
                // This means we ignore other item requirements if we have food
                return false;
            }
        }
        
        // 2. Only if food is disabled, check inventory slots
        boolean slotDepleted = Rs2Inventory.emptySlotCount() <= config.minFreeSlots();
        if (slotDepleted) {
            log.info("[isBankingNeeded] Not enough free slots (current: {}, required: {}) - banking needed", 
                Rs2Inventory.emptySlotCount(), config.minFreeSlots());
            return true;
        }
        
        // 3. Only if food is disabled, check other critical upkeep items
        boolean hasCriticalDepletion = false;
        for (ItemToKeep item : ItemToKeep.values()) {
            if (item == ItemToKeep.TELEPORT || item == ItemToKeep.FOOD || !item.isEnabled(config)) continue;
            
            int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
            
            log.debug("[isBankingNeeded] {} check - enabled: {}, count: {}", 
                item.name(), item.isEnabled(config), count);
                
            // Critical depletion: item is completely out (count = 0)
            if (count == 0) {
                log.info("[isBankingNeeded] Critical item '{}' depleted (count: 0) - banking needed", item.name());
                hasCriticalDepletion = true;
                break;
            }
        }
        
        if (hasCriticalDepletion) {
            return true;
        }
        
        // 4. Only if food is disabled, check custom banking items for critical depletion (count = 0)
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
                    
                    ArrayList<Integer> ids = new ArrayList<>(item.getIds());
                    Collections.reverse(ids);
                    boolean withdrawn = false;
                    
                    for (int id : ids) {
                        if (Rs2Bank.hasBankItem(id, needed)) {
                            log.info("[withdrawUpkeepItems] Withdrawing {} x{} (ID: {})", item.name(), needed, id);
                            Rs2Bank.withdrawX(true, id, needed);
                            
                            // Wait and verify withdrawal
                            if (waitForWithdrawal(id, count, required, 3000)) {
                                withdrawn = true;
                                break;
                            } else {
                                log.warn("[withdrawUpkeepItems] Failed to withdraw correct amount of {} (ID: {})", item.name(), id);
                            }
                        }
                    }
                    
                    if (!withdrawn) {
                        log.warn("[withdrawUpkeepItems] Could not withdraw required {} items from bank", item.name());
                    }
                }
            }
        }

        // Withdraw food upkeep item last
        for (ItemToKeep item : ItemToKeep.values()) {
            if (item.name().equals("FOOD") && item.isEnabled(config)) {
                int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
                int required = item.getValue(config);
                int needed = required - count;
                
                if (needed > 0) {
                    log.info("[withdrawUpkeepItems] Need {} more food items (current: {}, required: {})", 
                        needed, count, required);
                    
                    boolean withdrawn = false;
                    // Try to withdraw food in order of healing value (highest first)
                    for (Rs2Food food : Arrays.stream(Rs2Food.values())
                            .sorted(Comparator.comparingInt(Rs2Food::getHeal).reversed())
                            .collect(Collectors.toList())) {
                        
                        if (Rs2Bank.hasBankItem(food.getId(), needed)) {
                            log.info("[withdrawUpkeepItems] Withdrawing {} x{} (ID: {})", food.getName(), needed, food.getId());
                            Rs2Bank.withdrawX(true, food.getId(), needed);
                            
                            // Wait and verify withdrawal
                            if (waitForWithdrawal(food.getId(), count, required, 3000)) {
                                withdrawn = true;
                                break;
                            } else {
                                log.warn("[withdrawUpkeepItems] Failed to withdraw correct amount of {} (ID: {})", food.getName(), food.getId());
                            }
                        }
                    }
                    
                    if (!withdrawn) {
                        log.warn("[withdrawUpkeepItems] Could not withdraw required food items from bank");
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
}
