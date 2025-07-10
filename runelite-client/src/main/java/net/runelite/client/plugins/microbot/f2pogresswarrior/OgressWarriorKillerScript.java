package net.runelite.client.plugins.microbot.f2pogresswarrior;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class OgressWarriorKillerScript extends Script {
    public static double version = 1.0;

    public static OgressWarriorKillerState currentState = OgressWarriorKillerState.IDLE;
    public static Instant aggroTimerStart = null;
    public static Map<String, Integer> lootTracker = new HashMap<>();

    // Combat area boundaries (based on coordinates from MD)
    private static final int LEFT_BOUNDARY_X = 2027; // Approximate left boundary
    private static final int RIGHT_BOUNDARY_X = 2040; // Approximate right boundary
    private static final WorldPoint SAFE_SPOT = new WorldPoint(2043, 8975, 1); // From coordinates in MD

    private OgressWarriorKillerConfig config;
    private String lastChatMessage = "";
    private final AtomicBoolean scriptRunning = new AtomicBoolean(false);
    private Thread autoEatThread;
    private Thread combatMonitorThread;

    // Retry limits and tracking
    private static final int MAX_BANK_ATTEMPTS = 5;
    private int bankAttempts = 0;
    private boolean aggroTimerDoneSinceBank = false;

    private static final String[] RUNES_TO_LOOT = {
            "Fire rune", "Law rune", "Water rune", "Earth rune", "Air rune",
            "Mind rune", "Body rune", "Chaos rune", "Nature rune"
    };

    // Profitable high alch drops from Ogress Warriors (always loot these)
    private static final String[] PROFITABLE_HA_DROPS = {
            "Rune battleaxe",
            "Rune full helm",
            "Rune med helm",
            "Mithril kiteshield",
            "Adamant platebody",
            "Adamant kiteshield",
            "Adamant platelegs",
            "Adamant plateskirt",
            "Adamant med helm"
    };

    private boolean cameraConfigured = false;

    private void setTopDownCameraView() {
        if (Microbot.getClient() == null || cameraConfigured)
            return;
        Microbot.getClient().setCameraPitchTarget(383);
        Microbot.getClient().setCameraYawTarget(0);
        Microbot.getClient().setCameraShakeDisabled(true);
        cameraConfigured = true;
        Microbot.status = "Camera set to top-down view.";
    }

    private void stopAllThreads() {
        if (autoEatThread != null && autoEatThread.isAlive()) {
            autoEatThread.interrupt();
            try {
                autoEatThread.join(500);
            } catch (InterruptedException ignored) {}
        }
        if (combatMonitorThread != null && combatMonitorThread.isAlive()) {
            combatMonitorThread.interrupt();
            try {
                combatMonitorThread.join(500);
            } catch (InterruptedException ignored) {}
        }
    }

    private void startAutoEatThread() {
        if (autoEatThread != null && autoEatThread.isAlive()) return;
        autoEatThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && scriptRunning.get()) {
                try {
                    if (config != null && config.enableEatAtPercent() && config.eatAtPercent() > 0
                            && Microbot.isLoggedIn()) {
                        int currentHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
                        int maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
                        if (currentHp > 0 && maxHp > 0 && (currentHp * 100 / maxHp) <= config.eatAtPercent()) {
                            // Support multiple foods, first in list is highest priority
                            String[] foods = config.foodName().split(",");
                            for (String food : foods) {
                                food = food.trim();
                                if (!food.isEmpty() && Rs2Inventory.hasItem(food)) {
                                    Rs2Inventory.interact(food, "Eat");
                                    sleep(100, 200);
                                    break; // Only eat one food per check
                                }
                            }
                        }
                    }
                    sleep(100, 200);
                } catch (Exception ignored) {}
            }
        });
        autoEatThread.setName("OgressWarriorKiller-AutoEat");
        autoEatThread.setDaemon(true);
        autoEatThread.start();
    }

    private synchronized void startCombatMonitorThread() {
        if (combatMonitorThread != null && combatMonitorThread.isAlive()) return;
        combatMonitorThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && scriptRunning.get()) {
                try {
                    // Only monitor when in COMBAT state
                    if (currentState == OgressWarriorKillerState.COMBAT && Microbot.isLoggedIn()) {
                        var localPlayer = Microbot.getClient().getLocalPlayer();
                        var interacting = localPlayer != null ? localPlayer.getInteracting() : null;
                        if (interacting != null) {
                            String name = interacting.getName();
                            if (name == null || !name.equalsIgnoreCase("Ogress Warrior")) {
                                Microbot.status = "Not fighting Ogress Warrior, running to safespot";
                                Rs2Walker.walkTo(SAFE_SPOT);
                                waitUntilAtSafeSpot(SAFE_SPOT, 10000);
                                var target = Rs2Npc.getAttackableNpcs("Ogress Warrior")
                                    .filter(npc -> isWithinCombatArea(npc.getWorldLocation()))
                                    .findFirst().orElse(null);
                                if (target != null) {
                                    Rs2Npc.interact(target, "Attack");
                                }
                            }
                        }
                    }
                    sleep(100, 200);
                } catch (Exception ignored) {}
            }
        });
        combatMonitorThread.setName("OgressWarriorKiller-CombatMonitor");
        combatMonitorThread.setDaemon(true);
        combatMonitorThread.start();
    }

    public boolean run(OgressWarriorKillerConfig config) {
        // Cancel any previous scheduled task to ensure a clean start
        if (mainScheduledFuture != null && !mainScheduledFuture.isDone()) {
            mainScheduledFuture.cancel(true);
        }
        this.config = config;
        currentState = OgressWarriorKillerState.IDLE;
        aggroTimerDoneSinceBank = false;
        scriptRunning.set(true);

        // Set camera position and zoom at startup
        setTopDownCameraView();
        net.runelite.client.plugins.microbot.util.camera.Rs2Camera.resetZoom();

        // Configure antiban settings
        setupAntiban();
        startAutoEatThread();
        startCombatMonitorThread();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run())
                    return;

                // Handle world hopping if enabled
                if (shouldHopWorlds(config)) {
                    handleWorldHopping(config);
                    return;
                }

                // Execute state machine
                switch (currentState) {
                    case IDLE:
                        handleIdleState(config);
                        break;
                    case WALKING_TO_SAFE_SPOT:
                        handleWalkingToSafeSpot(config);
                        break;
                    case WAITING_AGGRO_TIMER:
                        handleWaitingAggroTimer(config);
                        break;
                    case BANKING:
                        handleBanking(config);
                        break;
                    case LOOTING:
                        handleLooting(config);
                        break;
                    case COMBAT:
                        handleCombat(config);
                        break;
                    case WORLD_HOPPING:
                        // World hopping is handled above
                        break;
                }

                // Always try to do high alchemy if enabled and we have items/runes
                if (config.useHighAlch() && currentState != OgressWarriorKillerState.BANKING) {
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
        sleep(3200, 800); // Delay to avoid UI locking
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
        // Check if we need banking first
        if (needsBanking(config)) {
            currentState = OgressWarriorKillerState.BANKING;
            return;
        }

        // If aggro timer is enabled and not done since last bank
        if (config.waitForAggroTimer() && !aggroTimerDoneSinceBank) {
            // Check if we're at the safe spot
            if (Rs2Player.getWorldLocation().distanceTo(SAFE_SPOT) > 2) {
                currentState = OgressWarriorKillerState.WALKING_TO_SAFE_SPOT;
                return;
            }

            // Start aggro timer if not started
            if (aggroTimerStart == null) {
                startAggroTimer(config);
                return;
            }

            // Wait for timer to complete
            if (!isAggroTimerComplete(config)) {
                currentState = OgressWarriorKillerState.WAITING_AGGRO_TIMER;
                return;
            }

            // Timer complete, mark as done for this banking trip
            aggroTimerDoneSinceBank = true;
            aggroTimerStart = null;
        }

        // Check if we're in combat area
        if (!isWithinCombatArea(Rs2Player.getWorldLocation())) {
            currentState = OgressWarriorKillerState.WALKING_TO_SAFE_SPOT;
            return;
        }

        // Start looting phase
        currentState = OgressWarriorKillerState.LOOTING;
    }

    private void handleWalkingToSafeSpot(OgressWarriorKillerConfig config) {
        WorldPoint destination;

        // If aggro timer is enabled and not done, go to safe spot
        if (config.waitForAggroTimer() && !aggroTimerDoneSinceBank) {
            destination = SAFE_SPOT;
            Microbot.status = "Walking to safe spot for aggro timer";
        } else {
            // Go to center of combat area
            destination = new WorldPoint((LEFT_BOUNDARY_X + RIGHT_BOUNDARY_X) / 2, SAFE_SPOT.getY(),
                    SAFE_SPOT.getPlane());
            Microbot.status = "Walking to combat area";
        }

        boolean walked = Rs2Walker.walkTo(destination);
        if (!walked) {
            Microbot.log("Failed to walk to destination: " + destination);
        }

        // Check if we arrived
        if (Rs2Player.getWorldLocation().distanceTo(destination) <= 2) {
            if (config.waitForAggroTimer() && !aggroTimerDoneSinceBank) {
                startAggroTimer(config);
            } else {
                currentState = OgressWarriorKillerState.IDLE;
            }
        }
    }

    private void startAggroTimer(OgressWarriorKillerConfig config) {
        aggroTimerStart = Instant.now();
        currentState = OgressWarriorKillerState.WAITING_AGGRO_TIMER;
        Microbot.status = "Waiting for aggro timer (" + config.aggroTimerSeconds() + " seconds)";
    }

    private void handleWaitingAggroTimer(OgressWarriorKillerConfig config) {
        if (isAggroTimerComplete(config)) {
            aggroTimerDoneSinceBank = true;
            aggroTimerStart = null;
            currentState = OgressWarriorKillerState.IDLE;
            Microbot.status = "Aggro timer complete - ready to fight";
        } else {
            Microbot.status = "Waiting for aggro timer...";
        }
    }

    private boolean isAggroTimerComplete(OgressWarriorKillerConfig config) {
        if (aggroTimerStart == null)
            return true; // If no timer started, consider it complete
        Duration elapsed = Duration.between(aggroTimerStart, Instant.now());
        return elapsed.getSeconds() >= config.aggroTimerSeconds();
    }

    private boolean isWithinCombatArea(WorldPoint location) {
        return location.getX() >= LEFT_BOUNDARY_X && location.getX() <= RIGHT_BOUNDARY_X;
    }

    private void handleCombat(OgressWarriorKillerConfig config) {
        // Check if we need banking
        if (needsBanking(config)) {
            aggroTimerDoneSinceBank = false;
            currentState = OgressWarriorKillerState.BANKING;
            return;
        }

        var localPlayer = Microbot.getClient().getLocalPlayer();
        var interacting = localPlayer != null ? localPlayer.getInteracting() : null;

        // If attacking a monster, check if it's still alive
        if (interacting != null && interacting.getHealthRatio() > 0) {
            Microbot.status = "In combat with " + interacting.getName();
            // Stay in COMBAT state, do not transition
            return;
        }

        // If not attacking or target is dead, try to find and attack a new Ogress Warrior
        var target = Rs2Npc.getAttackableNpcs("Ogress Warrior")
            .filter(npc -> isWithinCombatArea(npc.getWorldLocation()))
            .findFirst().orElse(null);

        if (target != null) {
            Microbot.status = "Attacking " + target.getName();
            Microbot.getClientThread().invokeLater(() -> {
                Rs2Npc.interact(target, "Attack");
            });
            sleep(100, 200);
            // Stay in COMBAT state to wait for the attack to start
            return;
        }

        // If no targets found, transition to LOOTING
        currentState = OgressWarriorKillerState.LOOTING;
    }

    // Refactor handleLooting to work with state machine
    private void handleLooting(OgressWarriorKillerConfig config) {
        Microbot.status = "Looting items";
        boolean looted = false;

        // Always loot profitable high alch drops first
        for (String haItem : PROFITABLE_HA_DROPS) {
            if (Rs2GroundItem.loot(haItem, 10)) {
                updateLootTracker(haItem, 1);
                looted = true;
            }
        }

        // Check if we need banking first
        if (needsBanking(config)) {
            aggroTimerDoneSinceBank = false; // Reset aggro timer for next trip
            currentState = OgressWarriorKillerState.BANKING;
            return;
        }

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
            int breadCount = Rs2Inventory.count("Bread");
            if (breadCount < 4) {
                looted |= lootBreadIfNeeded(config);
            }
        }

        // If we looted something, give a small delay then go to combat
        if (looted) {
            currentState = OgressWarriorKillerState.COMBAT;
            return;
        }

        // No loot found, go directly to combat
        currentState = OgressWarriorKillerState.COMBAT;
    }

    // Loot coins from kills
    private boolean lootCoinsFromKills() {
        net.runelite.client.plugins.microbot.util.grounditem.LootingParameters params = new net.runelite.client.plugins.microbot.util.grounditem.LootingParameters(
                10, // range (tiles)
                1, // minItems
                1, // minQuantity
                0, // minInvSlots
                false, // delayedLooting
                true // antiLureProtection (only loot my items)
        );
        boolean result = Rs2GroundItem.lootCoins(params);
        if (result)
            updateLootTracker("Coins", 1);
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
                sleep(100, 200);
                looted = true;
            }
        }
        return looted;
    }

    // Loot bread
    @SuppressWarnings("deprecation")
    private boolean lootBreadIfNeeded(OgressWarriorKillerConfig config) {
        boolean didLoot = false;
        var groundItems = Rs2GroundItem.getAll(10);
        for (var item : groundItems) {
            String name = item.getItem().getName();
            if (name != null && name.equalsIgnoreCase("Bread")) {
                if (Rs2GroundItem.loot("Bread", 10)) {
                    updateLootTracker("Bread", 1);
                    sleep(100, 200);
                    didLoot = true;

                    // Eat bread if health is low
                    int currentHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
                    int maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
                    if (currentHp > 0 && maxHp > 0 && (currentHp * 100 / maxHp) <= config.eatAtPercent()) {
                        if (Rs2Inventory.hasItem("Bread")) {
                            Rs2Inventory.interact("Bread", "Eat");
                            sleep(100, 200);
                        }
                    }
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
                sleep(100, 200);
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

        // Walk to Corsair Cove bank only
        WorldPoint corsairCoveBank = new WorldPoint(2566, 2858, 0);
        if (Rs2Player.getWorldLocation().distanceTo(corsairCoveBank) > 10) {
            Rs2Walker.walkTo(corsairCoveBank);
            bankAttempts++;
            return;
        }

        // Open bank
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            sleep(100, 200);
            bankAttempts++;
            return;
        }

        // Deposit all inventory
        Rs2Bank.depositAll();
        sleep(100, 200);

        // Withdraw food (support multiple foods, comma-separated)
        if (config.bankForFood()) {
            int foodNeeded = config.foodAmount();
            String[] foods = config.foodName().split(",");
            for (String food : foods) {
                food = food.trim();
                if (food.isEmpty() || foodNeeded <= 0)
                    continue;
                int available = Rs2Bank.count(food);
                if (available > 0) {
                    int toWithdraw = Math.min(foodNeeded, available);
                    Rs2Bank.withdrawX(food, toWithdraw);
                    sleep(100, 200);
                    foodNeeded -= toWithdraw;
                }
            }
        }

        // Withdraw high alch runes if enabled
        if (config.useHighAlch()) {
            if (Rs2Bank.count("Nature rune") > 0) {
                Rs2Bank.withdrawAll("Nature rune");
                sleep(100, 200);
            }
            if (Rs2Bank.count("Fire rune") > 0) {
                Rs2Bank.withdrawAll("Fire rune");
                sleep(100, 200);
            }
        }

        // Close bank and return to combat
        Rs2Bank.closeBank();
        // Walk to safespot after banking
        Rs2Walker.walkTo(SAFE_SPOT);
        waitUntilAtSafeSpot(SAFE_SPOT, 10000); // waits up to 10 seconds
        bankAttempts = 0;
        currentState = OgressWarriorKillerState.COMBAT;
        Microbot.log("Banking complete, returning to COMBAT");
    }

    private void waitUntilAtSafeSpot(WorldPoint safeSpot, int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (Rs2Player.getWorldLocation().equals(safeSpot)) {
                break;
            }
            sleep(100, 200);
        }
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
            if (!isRunning())
                break;
            Rs2Magic.alch(item);
            // If item is valuable, handle confirmation widget
            if (item.getHaPrice() > Rs2Settings.getMinimumItemValueAlchemyWarning()) {
                net.runelite.client.plugins.microbot.util.Global
                        .sleepUntil(() -> Rs2Widget.hasWidget("Proceed to cast High Alchemy on it"), 2000);
                if (Rs2Widget.hasWidget("Proceed to cast High Alchemy on it")) {
                    Rs2Keyboard.keyPress('1');
                    net.runelite.client.plugins.microbot.util.Global.sleep(100, 200);
                }
            }
            net.runelite.client.plugins.microbot.util.Global.sleep(100, 200);
        }
    }

    private boolean needsBanking(OgressWarriorKillerConfig config) {
        // Check health percentage - bank if below 20%
        int currentHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
        if (currentHp > 0 && maxHp > 0 && (currentHp * 100 / maxHp) <= 20) {
            return true;
        }

        // Check if inventory has enough free slots
        if (Rs2Inventory.emptySlotCount() <= config.minFreeSlots()) {
            return true;
        }

        // Check if we have enough food
        if (config.bankForFood()) {
            String[] foods = config.foodName().split(",");
            int foodInInv = 0;
            for (String food : foods) {
                food = food.trim();
                if (!food.isEmpty()) {
                    foodInInv += Rs2Inventory.count(food);
                }
            }
            if (foodInInv < config.foodAmount()) {
                return true;
            }
        }

        // Check if we have required runes for high alchemy
        if (config.useHighAlch()) {
            if (Rs2Inventory.count("Nature rune") == 0 || Rs2Inventory.count("Fire rune") == 0) {
                return true;
            }
        }

        return false;
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

    @Override
    public void shutdown() {
        super.shutdown();
        scriptRunning.set(false);
        stopAllThreads();
        currentState = OgressWarriorKillerState.IDLE;
        aggroTimerStart = null;
    }
}
