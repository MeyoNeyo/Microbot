package net.runelite.client.plugins.microbot.bee.chaosaltar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.equipment.JewelleryLocationEnum;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.walker.Rs2Walker.walkTo;

@Slf4j
public class ChaosAltarScript extends Script {

    public static final WorldArea CHAOS_ALTAR_AREA = new WorldArea(2947, 3818, 11, 6, 0);
    public static final WorldPoint CHAOS_ALTAR_POINT = new WorldPoint(2949, 3820,0);
    public static final WorldPoint CHAOS_ALTAR_POINT_SOUTH = new WorldPoint(2972, 3810,0);

    private static final int CHAOS_ALTAR = 411;
    public static final WorldPoint lumbridgeBank = new WorldPoint(3209, 3220, 2);
        
        

    private ChaosAltarConfig config;
    private boolean autoRetaliate = false;

    private State currentState = State.UNKNOWN;

    public static boolean didWeDie = false;

    public boolean run(ChaosAltarConfig config, ChaosAltarPlugin plugin) {
        this.config = config; // Store config for use in other methods
        Microbot.enableAutoRunOn = false;
        
        // Start threaded monitoring system immediately when script starts
        ChaosAltarWorldHopManager.startThreadedMonitoring(config);
        
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                long startTime = System.currentTimeMillis();

                // Check if we're currently hopping worlds - if so, wait
                if (ChaosAltarWorldHopManager.processWorldHop()) {
                    Microbot.status = "Hopping worlds...";
                    return;
                }

                // The threaded monitoring system now handles all player detection and world hopping
                // No need for manual checks in the main loop anymore

                if (!autoRetaliate) {
                    Rs2Combat.setAutoRetaliate(false);
                    autoRetaliate = true;
                }

                // Determine current state
                currentState = determineState();
                Microbot.log("Current state: " + currentState);

                // Execute state action
                switch (currentState) {
                    case BANK:
                        plugin.lockCondition.lock();
                        handleBanking();
                        break;
                    case WALK_TO_BANK:
                        walkToLumbridgeBank();
                        break;
                    case TELEPORT_TO_WILDERNESS:
                        teleportToWilderness();
                        break;
                    case OFFER_BONES:
                        if (config.giveBonesFast()) offerBonesFast();
                        else offerBones();
                        break;
                    case WALK_TO_ALTAR:
                        // Use Rs2Walker to handle doors and obstacles properly
                        if (!isAtChaosAltar()) {
                            Microbot.log("Walking to Chaos Altar using Rs2Walker (handles doors)");
                            Rs2Walker.walkTo(CHAOS_ALTAR_POINT);
                            sleepUntil(() -> isAtChaosAltar() || Rs2Player.getWorldLocation().distanceTo(CHAOS_ALTAR_POINT) <= 1, 10000);
                        }
                        // Once at altar, offer bones
                        if (isAtChaosAltar()) {
                            if (config.giveBonesFast()) offerBonesFast();
                            else offerBones();
                        }
                        break;
                    case DIE_TO_NPC:
                        dieToNpc();
                        plugin.lockCondition.unlock();
                        handleBanking();
                        break;
                    default:
                        System.out.println("Unknown state. Resetting...");
                        break;
                }

                long endTime = System.currentTimeMillis();
                long totalTime = endTime - startTime;
                System.out.println("Total time for loop " + totalTime);

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    private GameObject getChaosAltar() {
        return (GameObject) Rs2GameObject
                .getAll(obj -> obj.getId() == CHAOS_ALTAR && obj instanceof GameObject)
                .stream().findFirst().orElse(null);
    }

    public boolean isAtChaosAltar() {
        final GameObject gameObject = getChaosAltar();
        if (gameObject == null) return false;

        boolean reachable = Rs2GameObject.isReachable(gameObject);
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        int distanceToAltar = gameObject.getWorldLocation().distanceTo(playerLocation);
        
        // More strict check: must be reachable AND within 1 tile for accurate altar access
        if (reachable && distanceToAltar <= 3) {
            reachable = true;
        } else {
            reachable = false;
        }
        
        log.info("Found Chaos Altar GameObject at: {}. Distance: {}, Reachable={}", 
                gameObject.getWorldLocation(), distanceToAltar, reachable);
        return reachable;
    }
    
    /**
     * Check if player is near altar but potentially blocked by door or other obstacles
     */
    public boolean isNearChaosAltarArea() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        return CHAOS_ALTAR_AREA.contains(playerLocation) || 
               playerLocation.distanceTo(CHAOS_ALTAR_POINT) <= 1;
    }


    private void dieToNpc() {
        Microbot.log("Walking to dangerous NPC to die");
        Rs2Walker.walkTo(2979, 3845,0);
        sleepUntil(() -> Rs2Npc.getNpc("Chaos Fanatic") != null, 4000);
        // Attack chaos fanatic to die
        Rs2Npc.attack("Chaos Fanatic");
        // Wait until player dies
        sleepUntil(() -> Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) == 0, 60000);
        sleepUntil(() -> !Rs2Pvp.isInWilderness(), 15000);
        // Wait until respawn is complete and player can move
        sleepUntil(() -> Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) > 0 && 
                         !Rs2Player.isMoving() && 
                         !Rs2Pvp.isInWilderness(), 5000);
        didWeDie = true;
        Microbot.log("Died to NPC, now walking to Lumbridge bank");
        
        // Walk to Lumbridge bank after death
        walkToLumbridgeBank();
        
        // Disable world hopping when not in wilderness
        if (config != null && config.enableWorldHopping()) {
            ChaosAltarWorldHopManager.setHoppingEnabled(false);
            Microbot.log("Left wilderness - world hopping disabled");
        }
    }
    
    private void walkToLumbridgeBank() {
        Microbot.log("Walking to Lumbridge bank after death");
        
        // Walk to Lumbridge bank (closest bank after respawning)
        if (!Rs2Bank.isNearBank(20)) {
            Rs2Walker.walkTo(ChaosAltarScript.lumbridgeBank);
            sleepUntil(() -> Rs2Bank.isNearBank(20), 30000);
        }
        
        // Disable world hopping when not in wilderness
        if (config != null && config.enableWorldHopping()) {
            ChaosAltarWorldHopManager.setHoppingEnabled(false);
            Microbot.log("Left wilderness - world hopping disabled");
        }
    }


    private void teleportToWilderness() {
        // Enable protect item if needed
        if (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_ITEM)) {
            System.out.println("Enabling Protect Item");
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_ITEM, true);
            // Optimized wait for prayer to activate - exits early when successful
            sleepUntil(() -> Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_ITEM), 1500);
        }

        if (hasBurningAmulet()) {
            // Use burning amulet to teleport to Lava Maze before walking to altar
            Microbot.log("Using Burning Amulet to teleport to Lava Maze before going to Chaos Altar");
            Rs2Equipment.interact("Burning amulet", "Lava Maze");
            
            // Wait for teleport to complete - check if we're in wilderness and near Lava Maze
            sleepUntil(() -> {
                return Rs2Pvp.isInWilderness() && 
                       Rs2Player.getWorldLocation().distanceTo(JewelleryLocationEnum.LAVA_MAZE.getLocation()) <= 10;
            }, 8000);
            
            // If teleport was successful, walk directly to the chaos altar from Lava Maze
            if (Rs2Pvp.isInWilderness()) {
                Microbot.log("Successfully teleported to Lava Maze, now walking directly to Chaos Altar");
                
                // Enable world hopping now that we're in wilderness
                if (config != null && config.enableWorldHopping()) {
                    ChaosAltarWorldHopManager.setHoppingEnabled(true);
                    Microbot.log("Entered wilderness - world hopping enabled");
                }
                
                // Walk directly to the chaos altar from Lava Maze using Rs2Walker to handle doors/obstacles
                Rs2Walker.walkTo(CHAOS_ALTAR_POINT);
                sleepUntil(() -> isAtChaosAltar() || Rs2Player.getWorldLocation().distanceTo(CHAOS_ALTAR_POINT) <= 3, 15000);
            } else {
                Microbot.log("Teleport failed, falling back to manual walk to altar");
                // Fallback to walking if teleport failed - walk directly to altar
                Rs2Walker.walkTo(CHAOS_ALTAR_POINT);
                sleepUntil(() -> Rs2Pvp.isInWilderness() || isAtChaosAltar(), 15000);
                
                // Enable world hopping when we finally enter wilderness
                if (Rs2Pvp.isInWilderness() && config != null && config.enableWorldHopping()) {
                    ChaosAltarWorldHopManager.setHoppingEnabled(true);
                    Microbot.log("Entered wilderness - world hopping enabled");
                }
            }
        } else {
            Microbot.log("No burning amulet found, walking manually to Chaos Altar");
            // Fallback if no burning amulet - walk directly to altar
            Rs2Walker.walkTo(CHAOS_ALTAR_POINT);
            sleepUntil(() -> Rs2Pvp.isInWilderness() || isAtChaosAltar(), 15000);
            
            // Enable world hopping when we enter wilderness
            if (Rs2Pvp.isInWilderness() && config != null && config.enableWorldHopping()) {
                ChaosAltarWorldHopManager.setHoppingEnabled(true);
                Microbot.log("Entered wilderness - world hopping enabled");
            }
        }
    }

    private Rs2ItemModel getLastBone() {
        return Rs2Inventory.getBones().stream()
                .max(Comparator.comparingInt(Rs2ItemModel::getSlot)).orElse(null);
    }
    
    /**
     * Bury all bones in inventory to save them when altar is unreachable during combat
     */
    private void buryAllBones() {
        Microbot.log("Burying all bones to save them from PKers!");
        
        Rs2ItemModel bone;
        while ((bone = getLastBone()) != null && isRunning()) {
            int boneCountBefore = Rs2Inventory.getBones().size();
            Rs2Inventory.interact(bone, "Bury");
            
            // Wait for bone to be buried
            sleepUntil(() -> Rs2Inventory.getBones().size() < boneCountBefore || 
                            Rs2Player.waitForXpDrop(Skill.PRAYER, 300), 1000);
            
            // Brief pause between burials
            sleep(Rs2Random.between(100, 300));
        }
        
        Microbot.log("Finished burying all bones");
    }

    private void offerBones() {
        System.out.println("Offering bones at altar- IN OFFERBONES1");

        //if player is not in radius of chaos altar, walk to it using rs2walker
        if (!CHAOS_ALTAR_AREA.contains(Rs2Player.getWorldLocation())) {
            //if radius of the object chaos altar is greater than 5 tiles from the player
            if (CHAOS_ALTAR_POINT.distanceTo(Rs2Player.getWorldLocation()) > 5) {
                walkTo(CHAOS_ALTAR_POINT);
            }
        }

        if (Rs2Player.isInCombat()) {
            offerBonesFast();
            return;
        }

        final Rs2ItemModel lastBones = getLastBone();
        if (lastBones != null && isRunning()) {
            int initialBoneCount = Rs2Inventory.getBones().size();
            Rs2Inventory.interact(lastBones, "use");
            // Wait for interaction to register
            sleepUntil(() -> Rs2Player.isAnimating() || Rs2Player.isMoving(), 600);
            Rs2GameObject.interact(CHAOS_ALTAR);
            // Wait for bone offering animation or inventory change
            sleepUntil(() -> Rs2Inventory.getBones().size() < initialBoneCount || 
                            Rs2Player.waitForXpDrop(Skill.PRAYER, 600), 1500);

            Rs2Inventory.waitForInventoryChanges(Rs2Random.between(500,2000));
        }
    }

    private void offerBonesFast() {
        Microbot.log("Offering bones at altar - IN OFFERBONES FAST");

        // If under attack and near altar, prioritize offering over positioning
        if (Rs2Player.isInCombat()) {
            if (!isAtChaosAltar() && isNearChaosAltarArea()) {
                // Try quick walk to altar
                Rs2Walker.walkTo(CHAOS_ALTAR_POINT);
                sleepUntil(() -> isAtChaosAltar(), 1500);
            }
            
            if (!isAtChaosAltar()) {
                // Can't reach altar while in combat, bury bones to save them
                buryAllBones();
                return;
            }
        }

        // Check if we're actually at the altar before offering
        if (!isAtChaosAltar()) {
            if (isNearChaosAltarArea()) {
                Rs2Walker.walkTo(CHAOS_ALTAR_POINT);
                sleepUntil(() -> isAtChaosAltar(), 3000);
            } else {
                walkTo(CHAOS_ALTAR_POINT);
                return;
            }
        }

        Rs2ItemModel lastBones;
        while ((lastBones = getLastBone()) != null
                && isRunning()
                && !Rs2Player.isInCombat()
                && Rs2GameObject.exists(CHAOS_ALTAR)
                && isAtChaosAltar()) { // Ensure we stay at altar
            int boneCountBefore = Rs2Inventory.getBones().size();
            Rs2Inventory.interact(lastBones, "use");
            // Very short wait for interaction to register
            sleepUntil(() -> Rs2Player.isAnimating(), 150);
            Rs2GameObject.interact(CHAOS_ALTAR);
            Rs2Player.waitForXpDrop(Skill.PRAYER);

            // Brief pause between offerings for natural timing
            sleepUntil(() -> Rs2Inventory.getBones().size() < boneCountBefore || 
                            !Rs2GameObject.exists(CHAOS_ALTAR), 200);
        }
        
        // If we have bones left and in combat, try to save them
        if (Rs2Player.isInCombat() && getLastBone() != null) {
            if (isAtChaosAltar()) {
                // Continue offering if still at altar
                Microbot.log("Still at altar during combat - continuing to offer bones");
                // Continue the loop by calling recursively (but limit recursion)
                offerBonesFast();
            } else {
                // No longer at altar and in combat, bury remaining bones
                buryAllBones();
            }
        }
    }

    private void handleBanking() {
        if(Rs2Inventory.contains(Rs2ItemModel.matches(false, "Burning amulet"))){
            Rs2Inventory.wear("Burning amulet");
        }

        if (!Rs2Bank.isOpen()) {
            log.info("Opening bank");
            Rs2Walker.walkTo(ChaosAltarScript.lumbridgeBank);
            sleepUntil(() -> Rs2Bank.isNearBank(20), 30000);
            if (!Rs2Bank.walkToBankAndUseBank()) {
                log.error("Failed to walk to or use bank");
                return;
            }
        }

        log.info("Depositing All");
        Rs2Bank.depositAll();

        if(!Rs2Bank.hasItem("Dragon bones")) {
            Microbot.log("NO BONES, SHUTTING DOWN");
            shutdown();
            return;
        }

        if(!Rs2Bank.hasBankItem("Burning Amulet")) {
            Microbot.log("NO BURNING AMULET, SHUTTING DOWN");
            shutdown();
            return;
        }

        // If amulet not equipped or in inventory
        if (!hasBurningAmulet()) {
            // Wait for bank interface to be ready
            sleepUntil(() -> Rs2Bank.isOpen(), 1000);
            Microbot.log("Withdrawing burning amulet");
            Rs2Bank.withdrawAndEquip("burning amulet");
            Rs2Inventory.waitForInventoryChanges(2000);
        }

        // If no bones in inventory, withdraw 28
        if (!Rs2Inventory.contains("Dragon bones")) {
            log.info("Withdrawing bones");
            Rs2Bank.withdrawAll("Dragon bones");
            Rs2Inventory.waitForInventoryChanges(2000);
        }

        log.info("Closing Bank. Ammy={}, Bones={}", hasBurningAmulet(), Rs2Inventory.getBones().size());
        if (!Rs2Bank.closeBank()) {
            log.error("Failed to close bank");
        }
    }

    public boolean hasBurningAmulet() {
        return Rs2Inventory.contains(x-> x != null && x.getName().contains("Burning amulet")) || Rs2Equipment.isWearing("Burning amulet", false);
    }

    private State determineState() {
        final int boneCount = Rs2Inventory.getBones().size();
        final boolean inWilderness = Rs2Pvp.isInWilderness();
        final boolean hasBones = boneCount >= 1;
        final boolean hasAnyBones = boneCount > 0;
        final boolean atAltar = isAtChaosAltar();

        if(didWeDie){
            didWeDie = false;
            Microbot.log("We died! Walking to Lumbridge bank...");
            return State.WALK_TO_BANK;
        }
        if (!inWilderness && !hasBones) {
            return State.BANK;
        }
        if (!inWilderness && hasBones) {
            return State.TELEPORT_TO_WILDERNESS;
        }
        if (inWilderness && hasAnyBones && atAltar) {
            // At altar and have bones - offer them
            return State.OFFER_BONES;
        }
        if (inWilderness && hasAnyBones && !atAltar) {
            // Have bones but not at altar - walk to altar
            return State.WALK_TO_ALTAR;
        }
        if (inWilderness && !hasAnyBones) {
            // Turn off world hopping when we have no bones in wilderness to allow other players to attack us for faster death
            if (config != null && config.enableWorldHopping()) {
                ChaosAltarWorldHopManager.setHoppingEnabled(false);
                Microbot.log("No bones in wilderness - turning off world hopping to allow PK attacks for faster death");
            }
            
            // Check if we're stuck after world hop - if player is not moving and far from Chaos Fanatic
            WorldPoint chaosFantasticLocation = new WorldPoint(2979, 3845, 0);
            WorldPoint currentLocation = Rs2Player.getWorldLocation();
            
            // If we're in wilderness with no bones but far from Chaos Fanatic and not moving, walk there
            if (currentLocation.distanceTo(chaosFantasticLocation) > 5 && !Rs2Player.isMoving()) {
                Microbot.log("No bones in wilderness and far from Chaos Fanatic after potential world hop - walking to die");
                Rs2Walker.walkTo(chaosFantasticLocation);
            }
            
            return State.DIE_TO_NPC;
        }

        return State.UNKNOWN;
    }

    @Override
    public void shutdown() {
        autoRetaliate = false;
        // Stop the threaded monitoring system
        ChaosAltarWorldHopManager.stopThreadedMonitoring();
        super.shutdown();
    }
}
