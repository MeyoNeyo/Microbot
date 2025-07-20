package net.runelite.client.plugins.microbot.apexfighter.combat;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment.get;

public class FoodScript extends Script {

    String weaponname = "";
    String bodyName = "";
    String legsName = "";
    String helmName = "";

    String shieldName = "";

    public boolean run(ApexFighterConfig config) {
        weaponname = "";
        bodyName = "";
        legsName = "";
        helmName = "";
        shieldName = "";
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                if (!config.toggleFood()) return;
                if (Rs2Inventory.hasItem("empty vial"))
                    Rs2Inventory.drop("empty vial");
                double treshHold = (double) (Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) * 100) / Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
                if (Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_WARSPEAR) && Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_PLATEBODY) && Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_CHAINSKIRT) && Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_HELM)) {
                    if (treshHold > 80)
                        unEquipGuthans();
                    return;
                } else {
                    if (treshHold > 51)
                        return;
                }

                List<Rs2ItemModel> foods = Rs2Inventory.getInventoryFood();

                if (foods == null || foods.isEmpty()) {
                    if (!equipFullGuthans()) {
                        Microbot.showMessage("No more food left & no guthans available. Please teleport");
                        sleep(5000);
                    }
                    return;
                }
                for (Rs2ItemModel food : foods) {
                    Rs2Inventory.interact(food, "eat");
                    // Track food usage in CostTracker
                    net.runelite.client.plugins.microbot.apexfighter.CostTracker.getInstance().addUsage(food.getId(), 1);
                    sleep(1200, 2000);
                    break;
                }
            } catch(Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private void unEquipGuthans() {
        if (Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_WARSPEAR) && !weaponname.isEmpty()) {
            Rs2Inventory.wield(weaponname);
            if (shieldName != null)
                Rs2Inventory.wield(shieldName);
        }
        if (Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_PLATEBODY) && !bodyName.isEmpty()) {
            Rs2Inventory.wield(bodyName);
        }
        if (Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_CHAINSKIRT) && !legsName.isEmpty()) {
            Rs2Inventory.wield(legsName);
        }
        if (Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_HELM) && !helmName.isEmpty()) {
            Rs2Inventory.wield(helmName);
        }
    }

    private boolean equipFullGuthans() {
        Rs2ItemModel shield = get(EquipmentInventorySlot.SHIELD);
        if (shield != null)
            shieldName = shield.getName();

        if (!Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_WARSPEAR)) {
            Rs2ItemModel spearWidget = Microbot.getClientThread().runOnClientThreadOptional(() ->
                    Rs2Inventory.get("guthan's warspear")).orElse(null);
            if (spearWidget == null) return false;
            Rs2ItemModel weapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON);
            weaponname = weapon != null ? weapon.getName() : "";
            Rs2Inventory.wield(spearWidget.getName());
        }
        if (!Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_PLATEBODY)) {
            Rs2ItemModel bodyWidget = Microbot.getClientThread().runOnClientThreadOptional(() -> Rs2Inventory.get("guthan's platebody")).orElse(null);
            if (bodyWidget == null) return false;
            Rs2ItemModel body = Rs2Equipment.get(EquipmentInventorySlot.BODY);
            bodyName = body != null ? body.getName() : "";
            Rs2Inventory.wield(bodyWidget.getName());
        }
        if (!Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_CHAINSKIRT)) {
            Rs2ItemModel legsWidget = Microbot.getClientThread().runOnClientThreadOptional(() -> Rs2Inventory.get("guthan's chainskirt")).orElse(null);
            if (legsWidget == null) return false;
            Rs2ItemModel legs = Rs2Equipment.get(EquipmentInventorySlot.LEGS);
            legsName = legs != null ? legs.getName() : "";
            Rs2Inventory.wield(legsWidget.getName());
        }
        if (!Rs2Equipment.isWearing(net.runelite.api.ItemID.GUTHANS_HELM)) {
            Rs2ItemModel helmWidget = Microbot.getClientThread().runOnClientThreadOptional(() -> Rs2Inventory.get("guthan's helm")).orElse(null);
            if (helmWidget == null) return false;
            Rs2ItemModel helm = Rs2Equipment.get(EquipmentInventorySlot.HEAD);
            helmName = helm != null ? helm.getName() : "";
            Rs2Inventory.wield(helmWidget.getName());
        }
        return true;
    }
}
