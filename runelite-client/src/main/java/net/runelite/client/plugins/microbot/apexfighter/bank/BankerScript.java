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

    public boolean run(ApexFighterConfig config) {
        this.config = config;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (config.bank() && needsBanking()) {
                    // Removed eatFoodForSpace logic; FoodScript handles eating
                    if (handleBanking()) {
                        // After banking, walk to center if not already there
                        if (config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) > config.attackRadius()) {
                            ApexFighterPlugin.setState(State.WALKING);
                            Rs2Walker.walkTo(config.centerLocation());
                        } else {
                            ApexFighterPlugin.setState(State.IDLE);
                        }
                    }
                } else if (!needsBanking() && config.centerLocation().distanceTo(Rs2Player.getWorldLocation()) > config.attackRadius() && !Objects.equals(config.centerLocation(), new WorldPoint(0, 0, 0))) {
                    ApexFighterPlugin.setState(State.WALKING);
                    if (Rs2Walker.walkTo(config.centerLocation())) {
                        ApexFighterPlugin.setState(State.IDLE);
                    }
                }
            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    public boolean needsBanking() {
        // 1. Food priority: if food is missing, bank immediately
        boolean foodDepleted = false;
        for (ItemToKeep item : ItemToKeep.values()) {
            if (item.name().equals("FOOD") && item.isEnabled(config)) {
                int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
                if (count == 0) {
                    foodDepleted = true;
                    log.info("[needsBanking] Food depleted");
                    break;
                }
            }
        }
        if (foodDepleted) return true;

        // 2. Then check other upkeep/custom items as before
        boolean defaultDepleted = isUpkeepItemDepleted(config);
        boolean slotDepleted = Rs2Inventory.emptySlotCount() <= config.minFreeSlots();
        if (defaultDepleted) {
            log.info("[needsBanking] Default upkeep item depleted");
        }
        if (slotDepleted) {
            log.info("[needsBanking] Not enough empty slots (empty: {}, minFree: {})", Rs2Inventory.emptySlotCount(), config.minFreeSlots());
        }
        boolean customDepleted = false;
        if ((defaultDepleted || slotDepleted) && config.bank()) {
            Map<String, Integer> keepItems = parseBankingInventoryKeep(config.bankingInventoryKeep());
            for (Map.Entry<String, Integer> entry : keepItems.entrySet()) {
                String itemName = entry.getKey();
                Integer requiredAmount = entry.getValue();
                int currentAmount = Rs2Inventory.all().stream()
                    .filter(i -> i.getName().equalsIgnoreCase(itemName))
                    .mapToInt(Rs2ItemModel::getQuantity)
                    .sum();
                if (requiredAmount != null) {
                    if (currentAmount < requiredAmount) {
                        log.info("[needsBanking] Custom item depleted: {} (have: {}, need: {})", itemName, currentAmount, requiredAmount);
                        customDepleted = true;
                    }
                } else {
                    if (currentAmount < 1) {
                        log.info("[needsBanking] Custom item depleted: {} (have: {}, need: 1)", itemName, currentAmount);
                        customDepleted = true;
                    }
                }
            }
        }
        return ((defaultDepleted || customDepleted) && config.bank()) || (slotDepleted && config.bank());
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

        // Withdraw default upkeep items
        for (ItemToKeep item : ItemToKeep.values()) {
            if (item.isEnabled(config)) {
                int count = item.getIds().stream().mapToInt(Rs2Inventory::count).sum();
                if (count < item.getValue(config)) {
                    if (item.name().equals("FOOD")) {
                        for (Rs2Food food : Arrays.stream(Rs2Food.values()).sorted(Comparator.comparingInt(Rs2Food::getHeal).reversed()).collect(Collectors.toList())) {
                            if (Rs2Bank.hasBankItem(food.getId(), item.getValue(config) - count)) {
                                Rs2Bank.withdrawX(true, food.getId(), item.getValue(config) - count);
                                break;
                            }
                        }
                    } else {
                        ArrayList<Integer> ids = new ArrayList<>(item.getIds());
                        Collections.reverse(ids);
                        for (int id : ids) {
                            if (Rs2Bank.hasBankItem(id, item.getValue(config) - count)) {
                                Rs2Bank.withdrawX(true, id, item.getValue(config) - count);
                                break;
                            }
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
                    Rs2Bank.withdrawAll(true, itemName);
                }
            } else if (requiredAmount != null && currentAmount < requiredAmount) {
                Rs2Bank.withdrawX(true, itemName, requiredAmount - currentAmount);
            }
        }
        return !isUpkeepItemDepleted(config);
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

    public boolean goToBank() {
        return Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 8);
    }

    public boolean handleBanking() {
        ApexFighterPlugin.setState(State.BANKING);
        Rs2Prayer.disableAllPrayers();
        if (Rs2Bank.walkToBankAndUseBank()) {
            depositAllExcept(config);
            withdrawUpkeepItems(config);
            Rs2Bank.closeBank();
        }
        return !needsBanking();
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
