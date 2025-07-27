package net.runelite.client.plugins.microbot.zerozero.varrockcleaner;


import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.globval.WidgetIndices;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.concurrent.TimeUnit;

public class VarrockCleanerScript extends Script {

    private enum State {
        TAKE_UNCLEANED,
        CLEAN_FIND,
        STORAGE_CRATE,
        USE_ANTIQUE_LAMP,
        DROP_ITEMS
    }

    private State currentState = State.TAKE_UNCLEANED;
    private VarrockCleanerConfig config;
    private boolean skillLevelValidated = false;

    public boolean run(VarrockCleanerConfig config) {
        shutdown();
        this.config = config;
        currentState = State.TAKE_UNCLEANED;
        skillLevelValidated = false;

        // Validate skill level if lamp usage is enabled
        if (config.useAntiqueLamps()) {
            validateSkillLevel(config);
        }

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) return;

                switch (currentState) {
                    case TAKE_UNCLEANED:
                        takeUncleanedFinds();
                        break;
                    case CLEAN_FIND:
                        cleanFinds();
                        break;
                    case STORAGE_CRATE:
                        storeFindsInCrate();
                        break;
                    case USE_ANTIQUE_LAMP:
                        useAntiqueLamp();
                        break;
                    case DROP_ITEMS:
                        dropUnwantedItems();
                        break;
                }

            } catch (Exception ex) {
                Microbot.log(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        return true;
    }

    private void takeUncleanedFinds() {
        if (Rs2Inventory.isFull()) {
            currentState = State.CLEAN_FIND;
            return;
        }
        if (Rs2GameObject.interact(net.runelite.api.gameval.ObjectID.VM_DIGSITE_WHEELBARROW_ROCK_PILE, "Take")) {
            sleepUntil(() -> !Rs2Inventory.isFull(), 5000);
        }
        if (Rs2Inventory.isFull()) {
            currentState = State.CLEAN_FIND;
        }
    }

    private void cleanFinds() {
        if (Rs2Inventory.contains("Uncleaned find") && Rs2GameObject.interact(net.runelite.api.gameval.ObjectID.VM_SPECIMEN_TABLE2, "Clean")) {
            sleepUntil(() -> !Rs2Inventory.contains("Uncleaned find"), 30000);
            if (!Rs2Inventory.contains("Uncleaned find")) {
                currentState = State.STORAGE_CRATE;
            }
        }
    }

    private void storeFindsInCrate() {
        String[] rareItems = {
                "Old symbol",
                "Ancient symbol",
                "Old coin",
                "Ancient coin",
                "Clean necklace",
                "Pottery",
                "Jewellery",
                "Old chipped vase",
                "Arrowheads"
        };

        if (!Rs2Inventory.contains("Uncleaned find") && Rs2GameObject.interact(net.runelite.api.gameval.ObjectID.VM_CRATE, "Add finds")) {
            System.out.println("Successfully interacted with storage crate");
            Rs2Keyboard.keyPress('2');
            sleep(1000);

            boolean hasRareItems = false;
            for (String rareItem : rareItems) {
                if (Rs2Inventory.contains(rareItem)) {
                    hasRareItems = true;
                    break;
                }
            }

            // Check for antique lamp usage before dropping items
            if (config.useAntiqueLamps() && Rs2Inventory.contains("Antique lamp")) {
                currentState = State.USE_ANTIQUE_LAMP;
                return;
            }

            if (!hasRareItems) {
                currentState = State.DROP_ITEMS;
            }
        } else {
            // Debug: Log why we couldn't interact with storage crate
            if (Rs2Inventory.contains("Uncleaned find")) {
                System.out.println("Still have uncleaned finds, cannot use storage crate yet");
            } else {
                System.out.println("Failed to interact with storage crate - checking if it exists nearby");
            }
        }
    }

    private void dropUnwantedItems() {
        String[] itemsToKeep = {"Antique lamp", "Trowel", "Rock pick", "Specimen brush"};
        if (Rs2Inventory.dropAllExcept(itemsToKeep)) {
            currentState = State.TAKE_UNCLEANED;
        }
    }

    /**
     * Validates that the selected skill has level 10 or higher for lamp usage
     */
    private void validateSkillLevel(VarrockCleanerConfig config) {
        if (!skillLevelValidated && config.useAntiqueLamps()) {
            LampSkill selectedSkill = config.lampSkillSelection();
            int skillLevel = Rs2Player.getRealSkillLevel(selectedSkill.getSkill());
            
            if (skillLevel < 10) {
                Microbot.log("Warning: Cannot use antique lamp on " + selectedSkill.getDisplayName() + 
                           " (level " + skillLevel + "). Skill must be level 10 or higher. Lamp usage disabled.");
                skillLevelValidated = true;
                return;
            }
            
            Microbot.log("Antique lamp usage enabled for " + selectedSkill.getDisplayName() + " (level " + skillLevel + ")");
            skillLevelValidated = true;
        }
    }

    /**
     * Uses antique lamp on the configured skill
     */
    private void useAntiqueLamp() {
        if (!config.useAntiqueLamps()) {
            currentState = State.DROP_ITEMS;
            return;
        }

        LampSkill selectedSkill = config.lampSkillSelection();
        int skillLevel = Rs2Player.getRealSkillLevel(selectedSkill.getSkill());
        
        // Recheck skill level requirement
        if (skillLevel < 10) {
            Microbot.log("Cannot use antique lamp on " + selectedSkill.getDisplayName() + 
                       " (level " + skillLevel + "). Skipping lamp usage.");
            currentState = State.DROP_ITEMS;
            return;
        }

        // Check if lamp is in inventory
        if (!Rs2Inventory.contains("Antique lamp")) {
            currentState = State.DROP_ITEMS;
            return;
        }

        // Rub the lamp to open the interface
        if (Rs2Inventory.interact("Antique lamp", "Rub")) {
            Microbot.log("Rubbing antique lamp...");
            
            // Wait for the lamp interface to open
            sleepUntil(() -> Rs2Widget.getWidget(WidgetIndices.GenieLampWindow.GROUP_INDEX, 0) != null, 5000);
            
            // Click on the selected skill
            if (Rs2Widget.clickWidget(WidgetIndices.GenieLampWindow.GROUP_INDEX, selectedSkill.getWidgetChildId())) {
                Microbot.log("Selected " + selectedSkill.getDisplayName() + " skill for lamp experience");
                sleep(1000);
                
                // Click confirm button if it exists
                if (Rs2Widget.getWidget(WidgetIndices.GenieLampWindow.GROUP_INDEX, WidgetIndices.GenieLampWindow.CONFIRM_DYNAMIC_CONTAINER) != null) {
                    Rs2Widget.clickWidget(WidgetIndices.GenieLampWindow.GROUP_INDEX, WidgetIndices.GenieLampWindow.CONFIRM_DYNAMIC_CONTAINER);
                    sleep(1000);
                }
                
                Microbot.log("Used antique lamp on " + selectedSkill.getDisplayName());
            } else {
                Microbot.log("Failed to select skill for antique lamp");
            }
        } else {
            Microbot.log("Failed to rub antique lamp");
        }

        // Wait for lamp interface to close and lamp to be consumed
        sleepUntil(() -> !Rs2Inventory.contains("Antique lamp") || 
                        Rs2Widget.getWidget(WidgetIndices.GenieLampWindow.GROUP_INDEX, 0) == null, 5000);
        
        currentState = State.DROP_ITEMS;
    }

    public void stop() {
        Microbot.log("Varrock Cleaner plugin stopped.");
        currentState = VarrockCleanerScript.State.TAKE_UNCLEANED;
        super.shutdown();
    }
}
