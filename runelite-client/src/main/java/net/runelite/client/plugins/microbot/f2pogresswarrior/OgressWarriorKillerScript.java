package net.runelite.client.plugins.microbot.f2pogresswarrior;

import lombok.Getter;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OgressWarriorKillerScript extends Script {
    public static double version = 1.0;

    public static OgressWarriorKillerState currentState = OgressWarriorKillerState.IDLE;
    public static Instant aggroTimerStart = null;
    public static Map<String, Integer> lootTracker = new HashMap<>();

    // Combat area boundaries (based on coordinates from MD)
    private static final int LEFT_BOUNDARY_X = 2027; // Approximate left boundary
    private static final int RIGHT_BOUNDARY_X = 2040; // Approximate right boundary
    private static final WorldPoint SAFE_SPOT = new WorldPoint(2043, 8975, 1); // From coordinates in MD

    // Item tracking
    private static final String[] HIGH_VALUE_GEMS = { "Ruby", "Diamond" };
    private static final String[] ALL_GEMS = { "Sapphire", "Emerald", "Ruby", "Diamond" };

    private OgressWarriorKillerConfig config;
    private String lastChatMessage = "";
    private Thread autoEatThread;
    private volatile boolean autoEatRunning = false;

    // Retry limits
    private static final int MAX_LOOT_ATTEMPTS = 5;
    private static final int MAX_BANK_ATTEMPTS = 5;
    private Map<String, Integer> failedLootAttempts = new HashMap<>();
    private int bankAttempts = 0;
    private boolean aggroTimerDoneSinceBank = false;


    private static final String[] RUNES_TO_LOOT = {
        "Fire rune", "Law rune", "Water rune", "Earth rune", "Air rune",
        "Mind rune", "Body rune", "Chaos rune", "Nature rune"
    };

    public boolean run(OgressWarriorKillerConfig config) {
        // Cancel any previous scheduled task to ensure a clean start
        if (mainScheduledFuture != null && !mainScheduledFuture.isDone()) {
            mainScheduledFuture.cancel(true);
        }
        this.config = config;
        currentState = OgressWarriorKillerState.IDLE;
        aggroTimerDoneSinceBank = false;

        // Configure antiban settings
        setupAntiban();
        startAutoEatThread();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run())
                    return;

                // 1. Inventory check: food, runes (if high alch), free slots, health
                boolean hasRequiredFood = false;
                int foodNeeded = config.foodAmount();
                String[] foods = config.foodName().split(",");
                int foodInInv = 0;
                for (String food : foods) {
                    food = food.trim();
                    if (!food.isEmpty()) {
                        foodInInv += Rs2Inventory.count(food);
                    }
                }
                if (foodInInv >= foodNeeded) {
                    hasRequiredFood = true;
                }
                boolean hasRequiredRunes = true;
                if (config.useHighAlch()) {
                    hasRequiredRunes = Rs2Inventory.count("Law rune") > 0 && Rs2Inventory.count("Fire rune") > 0;
                }
                boolean needsBank = !hasRequiredFood || !hasRequiredRunes ||
                    (Rs2Inventory.emptySlotCount() <= config.minFreeSlots()) ||
                    (Microbot.getClient().getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS) * 100 /
                        Math.max(1, Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.HITPOINTS)) <= 20);

                // If banking is needed, do it and reset aggro timer state
                if (needsBank) {
                    aggroTimerDoneSinceBank = false;
                    aggroTimerStart = null;
                    currentState = OgressWarriorKillerState.BANKING;
                    handleBanking(config);
                    return;
                }

                // If aggro timer is enabled and not done since last bank, walk to safespot and start timer
                if (config.waitForAggroTimer() && !aggroTimerDoneSinceBank) {
                    if (Rs2Player.getWorldLocation().distanceTo(SAFE_SPOT) > 2) {
                        currentState = OgressWarriorKillerState.WALKING_TO_SAFE_SPOT;
                        Rs2Walker.walkTo(SAFE_SPOT);
                        return;
                    }
                    if (aggroTimerStart == null) {
                        startAggroTimer(config);
                        return;
                    }
                    if (!isAggroTimerComplete(config)) {
                        currentState = OgressWarriorKillerState.WAITING_AGGRO_TIMER;
                        Microbot.status = "Waiting for aggro timer (" + config.aggroTimerSeconds() + " seconds)";
                        return;
                    }
                    // Timer complete, mark as done for this banking trip
                    aggroTimerDoneSinceBank = true;
                    aggroTimerStart = null; // Reset timer for next banking trip
                }

                // If not inside combat area (X bounds), walk inside
                if (!isWithinCombatArea(Rs2Player.getWorldLocation())) {
                    currentState = OgressWarriorKillerState.WALKING_TO_SAFE_SPOT;
                    // Walk to center of combat area (no longer safespot)
                    WorldPoint center = new WorldPoint((LEFT_BOUNDARY_X + RIGHT_BOUNDARY_X) / 2, SAFE_SPOT.getY(), SAFE_SPOT.getPlane());
                    Rs2Walker.walkTo(center);
                    return;
                }

                // Main loop: loot/attack until out of food or inv space
                while (true) {
                    // Check food/inv again in case it changed during loop
                    foodInInv = 0;
                    for (String food : foods) {
                        food = food.trim();
                        if (!food.isEmpty()) {
                            foodInInv += Rs2Inventory.count(food);
                        }
                    }
                    if (foodInInv < foodNeeded || Rs2Inventory.emptySlotCount() <= config.minFreeSlots()) {
                        break;
                    }
                    boolean stillLooting = handleLooting(config);
                    if (stillLooting) continue;
                    // After looting, always try to attack an Ogress Warrior in the area
                    currentState = OgressWarriorKillerState.COMBAT;
                    boolean attacked = handleCombatLooped(config);
                    if (!attacked) break; // No valid targets, exit loop
                    // Optionally sleep a bit between attacks
                    sleep(600, 900);
                }
                // 4. High alch if enabled
                if (config.useHighAlch()) {
                    handleHighAlchemy(config);
                }
            } catch (Exception ex) {
                logOnceToChat("Error in Ogress Warrior Killer: " + ex.getMessage(), false, config);
                Microbot.log("Stack trace: " + ex.getStackTrace());
            }
        }, 0L, 600L, TimeUnit.MILLISECONDS);

        return true;
    }

    private void setupAntiban() {
        Rs2Antiban.resetAntibanSettings();
        Rs2AntibanSettings.antibanEnabled = true;
        Rs2AntibanSettings.usePlayStyle = true;
        Rs2AntibanSettings.randomIntervals = false;
        Rs2AntibanSettings.simulateFatigue = false;
        Rs2AntibanSettings.simulateAttentionSpan = false;
        Rs2AntibanSettings.behavioralVariability = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.takeMicroBreaks = false;
        Rs2AntibanSettings.microBreakChance = 0.01;
        Rs2AntibanSettings.actionCooldownChance = 0.1;

        Rs2Antiban.setActivity(Activity.GENERAL_COMBAT);
        Rs2Antiban.setActivityIntensity(ActivityIntensity.EXTREME);
    }

    private boolean shouldHopWorlds(OgressWarriorKillerConfig config) {
        return config.hopWorlds() &&
                config.maxPlayers() > 0 &&
                Rs2Player.getPlayers(p -> true).count() > config.maxPlayers();
    }

    private void handleWorldHopping(OgressWarriorKillerConfig config) {
        currentState = OgressWarriorKillerState.WORLD_HOPPING;
        Microbot.status = "World too crowded - hopping...";
        Rs2Random.waitEx(3200, 800); // Delay to avoid UI locking
        int world = net.runelite.client.plugins.microbot.util.security.Login.getRandomWorld(Rs2Player.isMember());
        boolean hopped = Microbot.hopToWorld(world);
        if (hopped) {
            Microbot.status = "Hopped to world: " + world;
        } else {
            Microbot.status = "World hop failed.";
        }
        currentState = OgressWarriorKillerState.IDLE;
    }

    private void handleIdleState(OgressWarriorKillerConfig config) {
        if (needsBanking(config)) {
            // Only allow banking if aggro timer is complete
            if (!config.waitForAggroTimer() || isAggroTimerComplete(config)) {
                currentState = OgressWarriorKillerState.BANKING;
            }
            return;
        }

        if (config.waitForAggroTimer() && Rs2Player.getWorldLocation().distanceTo(SAFE_SPOT) > 2) {
            currentState = OgressWarriorKillerState.WALKING_TO_SAFE_SPOT;
            return;
        }

        if (config.waitForAggroTimer() && aggroTimerStart == null) {
            startAggroTimer(config);
            return;
        }

        // Only allow transition to COMBAT if aggro timer is complete
        if (config.waitForAggroTimer() && !isAggroTimerComplete(config)) {
            currentState = OgressWarriorKillerState.WAITING_AGGRO_TIMER;
            return;
        }

        if (isAggroTimerComplete(config)) {
            currentState = OgressWarriorKillerState.COMBAT;
        }
    }

    private void handleWalkingToSafeSpot(OgressWarriorKillerConfig config) {
        Microbot.status = "Walking to safe spot";
        boolean walked = Rs2Walker.walkTo(SAFE_SPOT);
        if (!walked) {
            Microbot.log("Failed to walk to safespot: " + SAFE_SPOT + ". Check pathfinder/web.");
        }
        if (Rs2Player.getWorldLocation().distanceTo(SAFE_SPOT) <= 2) {
            currentState = OgressWarriorKillerState.WAITING_AGGRO_TIMER;
            startAggroTimer(config);
            Microbot.log("Arrived at safespot. Starting aggro timer.");
        }
    }

    private void startAggroTimer(OgressWarriorKillerConfig config) {
        aggroTimerStart = Instant.now();
        currentState = OgressWarriorKillerState.WAITING_AGGRO_TIMER;
        Microbot.status = "Waiting for aggro timer (" + config.aggroTimerSeconds() + " seconds)";
    }

    private void handleWaitingAggroTimer(OgressWarriorKillerConfig config) {
        if (isAggroTimerComplete(config)) {
            currentState = OgressWarriorKillerState.COMBAT;
            Microbot.status = "Aggro timer complete - ready to fight";
        } 
    }

    private boolean isAggroTimerComplete(OgressWarriorKillerConfig config) {
        if (aggroTimerStart == null)
            return false;
        Duration elapsed = Duration.between(aggroTimerStart, Instant.now());
        return elapsed.getSeconds() >= config.aggroTimerSeconds();
    }

    // Update isWithinCombatArea to only check X bounds
    private boolean isWithinCombatArea(WorldPoint location) {
        return location.getX() >= LEFT_BOUNDARY_X && location.getX() <= RIGHT_BOUNDARY_X;
    }

    // Add a new method for the looped combat logic
    private boolean handleCombatLooped(OgressWarriorKillerConfig config) {
        // Find and attack Ogress Warriors within the area that are not in combat
        var target = Rs2Npc.getAttackableNpcs("Ogress Warrior")
            .filter(npc -> isWithinCombatArea(npc.getWorldLocation()))
            .findFirst().orElse(null);
        if (target != null) {
            Microbot.status = "Attacking " + target.getName();
            Rs2Npc.interact(target, "Attack");
            sleep(1500, 2500);
            return true;
        } else {
            Microbot.status = "Looking for Ogress Warriors";
            return false;
        }
    }

    // Update handleCombat to only attack Ogress Warriors within the area
    private void handleCombat(OgressWarriorKillerConfig config) {
        if (needsBanking(config)) {
            currentState = OgressWarriorKillerState.BANKING;
            return;
        }
        // Find and attack Ogress Warriors within the area
        var target = Rs2Npc.getNpcs("Ogress Warrior")
            .filter(npc -> isWithinCombatArea(npc.getWorldLocation()))
            .filter(npc -> npc.getInteracting() == null)
            .findFirst().orElse(null);
        if (target != null) {
            Microbot.status = "Attacking " + target.getName();
            Rs2Npc.interact(target, "Attack");
            sleep(1500, 2500);
        } else {
            Microbot.status = "Looking for Ogress Warriors";
        }
    }

    // Refactor handleLooting to return boolean: true if still looting, false if done
    private boolean handleLooting(OgressWarriorKillerConfig config) {
        Microbot.status = "Looting items";
        boolean looted = false;

        // Loot coins from kills
        if (config.lootCoins()) {
            looted |= lootCoinsFromKills();
        }

        // Loot runes if enabled
        if (config.lootRunes()) {
            looted |= lootRunes();
        }

        // Loot gems
        looted |= lootGems(config);

        // Loot and eat bread if enabled and health is below threshold
        if (config.lootBread()) {
            int breadCount = Rs2Inventory.all().stream()
                .filter(item -> item.getName().equalsIgnoreCase("Bread"))
                .mapToInt(item -> item.getQuantity() > 0 ? item.getQuantity() : 1)
                .sum();
            if (breadCount < 4) {
                if (lootBreadIfNeeded(config)) {
                    looted = true;
                }
            }
        }

        // If we looted something, or if there are still items to loot, return true to keep looting
        boolean moreLoot = checkForMoreLoot(config);
        if (looted || moreLoot) {
            return true;
        }
        // Otherwise, done looting, return false to proceed to combat
        failedLootAttempts.clear();
        Microbot.log("Transitioning to COMBAT from LOOTING (no loot or too many failed attempts)");
        return false;
    }

    // Helper to check if there are still lootable items on the ground
    private boolean checkForMoreLoot(OgressWarriorKillerConfig config) {
        var groundItems = Rs2GroundItem.getAll(10);
        for (var item : groundItems) {
            String name = item.getItem().getName();
            if (config.lootCoins() && name.equalsIgnoreCase("Coins")) return true;
            if (config.lootBread() && name.equalsIgnoreCase("Bread")) return true;
            if (config.gemLootMode() != null) {
                switch (config.gemLootMode()) {
                    case HIGH_VALUE_ONLY:
                        if (name.equalsIgnoreCase("Ruby") || name.equalsIgnoreCase("Diamond")) return true;
                        break;
                    case ALL_GEMS:
                        if (name.equalsIgnoreCase("Sapphire") || name.equalsIgnoreCase("Emerald") || name.equalsIgnoreCase("Ruby") || name.equalsIgnoreCase("Diamond")) return true;
                        break;
                    default:
                        break;
                }
            }
        }
        return false;
    }

    // Loot coins from kills
    private boolean lootCoinsFromKills() {
        net.runelite.client.plugins.microbot.util.grounditem.LootingParameters params =
            new net.runelite.client.plugins.microbot.util.grounditem.LootingParameters(
                10, // range (tiles)
                1,  // minItems
                1,  // minQuantity
                0,  // minInvSlots
                false, // delayedLooting
                true  // antiLureProtection (only loot my items)
            );
        boolean result = Rs2GroundItem.lootCoins(params);
        if (result) updateLootTracker("Coins", 1);
        return result;
    }

    // Loot gems
    private boolean lootGems(OgressWarriorKillerConfig config) {
        boolean looted = false;
        String[] gemsToLoot;
        switch (config.gemLootMode()) {
            case HIGH_VALUE_ONLY:
                gemsToLoot = new String[] { "Ruby", "Diamond" };
                break;
            case ALL_GEMS:
                gemsToLoot = new String[] { "Sapphire", "Emerald", "Ruby", "Diamond" };
                break;
            default:
                gemsToLoot = new String[0];
                break;
        }
        for (String gem : gemsToLoot) {
            if (Rs2GroundItem.loot(gem, 10)) {
                updateLootTracker(gem, 1);
                sleep(500, 800);
                looted = true;
            }
        }
        return looted;
    }

    // Loot bread
    private boolean lootBreadIfNeeded(OgressWarriorKillerConfig config) {
        boolean didLoot = false;
        var groundItems = Rs2GroundItem.getAll(10);
        for (var item : groundItems) {
            String name = item.getItem().getName();
            if (name != null && name.equalsIgnoreCase("Bread")) {
                if (Rs2GroundItem.loot("Bread", 10)) {
                    updateLootTracker("Bread", 1);
                    sleep(500, 800);
                    didLoot = true;
                }
            }
        }
        return didLoot;
    }

    private boolean lootRunes() {
        boolean looted = false;
        for (String rune : RUNES_TO_LOOT) {
            if (Rs2GroundItem.loot(rune, 10)) {
                updateLootTracker(rune, 1);
                sleep(500, 800);
                looted = true;
            }
        }
        return looted;
    }

    private void handleBanking(OgressWarriorKillerConfig config) {
        Microbot.status = "Banking for supplies";

        if (bankAttempts >= MAX_BANK_ATTEMPTS) {
            Microbot.log("Banking failed too many times, returning to COMBAT");
            bankAttempts = 0;
            currentState = OgressWarriorKillerState.COMBAT;
            return;
        }

        // Walk to nearest bank
        if (!Rs2Bank.isNearBank(10)) {
            BankLocation nearestBank = Rs2Bank.getNearestBank();
            if (nearestBank != null) {
                Rs2Walker.walkTo(nearestBank.getWorldPoint());
            }
            bankAttempts++;
            return;
        }

        // Open bank
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            sleep(1000, 1500);
            bankAttempts++;
            return;
        }

        // Deposit all inventory
        Rs2Bank.depositAll();
        sleep(500, 800);

        // Withdraw food (support multiple foods, comma-separated)
        if (config.bankForFood()) {
            int foodNeeded = config.foodAmount();
            String[] foods = config.foodName().split(",");
            for (String food : foods) {
                food = food.trim();
                if (food.isEmpty() || foodNeeded <= 0) continue;
                int available = Rs2Bank.count(food);
                if (available > 0) {
                    int toWithdraw = Math.min(foodNeeded, available);
                    Rs2Bank.withdrawX(food, toWithdraw);
                    sleep(500, 800);
                    foodNeeded -= toWithdraw;
                }
            }
        }

        // Withdraw high alch runes if enabled
        if (config.useHighAlch()) {
            if (Rs2Bank.count("Law rune") > 0) {
                Rs2Bank.withdrawAll("Law rune");
                sleep(300, 500);
            }
            if (Rs2Bank.count("Fire rune") > 0) {
                Rs2Bank.withdrawAll("Fire rune");
                sleep(300, 500);
            }
        }

        // Close bank and return to combat
        Rs2Bank.closeBank();
        bankAttempts = 0;
        currentState = OgressWarriorKillerState.COMBAT;
        Microbot.log("Banking complete, returning to COMBAT");
    }

private void handleHighAlchemy(OgressWarriorKillerConfig config) {
    // Get all profitable items for high alch
    java.util.List<Rs2ItemModel> items = Rs2Inventory.getList(Rs2ItemModel::isHaProfitable);
    if (items.isEmpty()) {
        if (Rs2Tab.getCurrentTab() != net.runelite.client.plugins.microbot.globval.enums.InterfaceTab.INVENTORY) {
            Rs2Tab.switchToInventoryTab();
        }
        return;
    }
    // Check for runes and magic level
    if (!Rs2Magic.hasRequiredRunes(Rs2Spells.HIGH_LEVEL_ALCHEMY)) {
        Microbot.log("Not enough runes for High Alchemy");
        return;
    }
    if (!Rs2Player.getSkillRequirement(Skill.MAGIC, 55)) {
        Microbot.log("Not enough Magic level for High Alchemy");
        return;
    }
    for (Rs2ItemModel item : items) {
        if (!isRunning()) break;
        Rs2Magic.alch(item);
        // If item is valuable, handle confirmation widget
        if (item.getHaPrice() > Rs2Settings.getMinimumItemValueAlchemyWarning()) {
            net.runelite.client.plugins.microbot.util.Global.sleepUntil(() -> Rs2Widget.hasWidget("Proceed to cast High Alchemy on it"), 2000);
            if (Rs2Widget.hasWidget("Proceed to cast High Alchemy on it")) {
                Rs2Keyboard.keyPress('1');
                net.runelite.client.plugins.microbot.util.Global.sleep(300, 500);
            }
        }
        net.runelite.client.plugins.microbot.util.Global.sleep(1200, 1800);
    }
}

    private boolean needsBanking(OgressWarriorKillerConfig config) {
        if (!config.bankForFood()) return false;
        boolean needsFood = Rs2Inventory.emptySlotCount() <= config.minFreeSlots() ||
            !Rs2Inventory.hasItem(config.foodName());
        return needsFood;
    }

    private void updateLootTracker(String itemName, int quantity) {
        lootTracker.merge(itemName, quantity, Integer::sum);
    }

    public void logOnceToChat(String message, boolean debug, OgressWarriorKillerConfig config) {
        if (!lastChatMessage.equals(message)) {
            if (!debug) {
                Microbot.log(message);
            }
            lastChatMessage = message;
        }
    }

    public void updateConfig(OgressWarriorKillerConfig newConfig) {
        this.config = newConfig;
        logOnceToChat("Configuration updated", true, config);
    }

    // Getters for overlay
    public String getCurrentStatus() {
        return Microbot.status != null ? Microbot.status : "Idle";
    }

    public String getAggroTimerString() {
        if (aggroTimerStart == null)
            return "Not started";

        Duration elapsed = Duration.between(aggroTimerStart, Instant.now());
        long totalSeconds = config.aggroTimerSeconds();
        long remainingSeconds = Math.max(0, totalSeconds - elapsed.getSeconds());
        long minutes = remainingSeconds / 60;
        long seconds = remainingSeconds % 60;
        return String.format("%02d:%02d (%ds) remaining", minutes, seconds, remainingSeconds);
    }

    public Map<String, Integer> getLootTracker() {
        return new HashMap<>(lootTracker);
    }

    private void startAutoEatThread() {
        autoEatRunning = true;
        if (autoEatThread == null || !autoEatThread.isAlive()) {
            autoEatThread = new Thread(() -> {
                while (autoEatRunning) {
                    try {
                        if (config != null && config.enableEatAtPercent() && config.eatAtPercent() > 0 && Microbot.isLoggedIn()) {
                            int currentHp = Microbot.getClient().getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS);
                            int maxHp = Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.HITPOINTS);
                            if (currentHp > 0 && maxHp > 0 && (currentHp * 100 / maxHp) <= config.eatAtPercent()) {
                                // Support multiple foods, first in list is highest priority
                                String[] foods = config.foodName().split(",");
                                for (String food : foods) {
                                    food = food.trim();
                                    if (!food.isEmpty() && Rs2Inventory.hasItem(food)) {
                                        Rs2Inventory.interact(food, "Eat");
                                        net.runelite.client.plugins.microbot.util.Global.sleep(1200, 1800);
                                        break; // Only eat one food per check
                                    }
                                }
                            }
                        }
                        net.runelite.client.plugins.microbot.util.Global.sleep(500, 700);
                    } catch (Exception ignored) {
                        // Ignore errors in auto-eat thread
                    }
                }
            });
            autoEatThread.setName("OgressWarriorKiller-AutoEat");
            autoEatThread.setDaemon(true);
            autoEatThread.start();
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        currentState = OgressWarriorKillerState.IDLE;
        aggroTimerStart = null;
        autoEatRunning = false;
        if (autoEatThread != null && autoEatThread.isAlive()) {
            try {
                autoEatThread.join(500);
            } catch (InterruptedException ignored) {}
        }
    }

    private int getBreadCount() {
        return Rs2Inventory.all().stream()
                .filter(item -> item.getName().equalsIgnoreCase("Bread"))
                .mapToInt(item -> item.getQuantity() > 0 ? item.getQuantity() : 1)
                .sum();
    }
}
