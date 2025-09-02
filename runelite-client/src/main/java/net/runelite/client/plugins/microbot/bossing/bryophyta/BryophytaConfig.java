package net.runelite.client.plugins.microbot.bossing.bryophyta;

import net.runelite.client.config.*;

@ConfigInformation("<h2>Bryophyta Boss Fighter</h2>\n" +
        "<p>Automated Bryophyta (Moss Giantess) boss fighting plugin with chest looting</p>\n" +
        "<p><strong>Requirements:</strong></p>\n" +
        "<ul>" +
        "<li>Start at Varrock East Bank with an empty inventory</li>" +
        "<li>Have Mossy Keys in bank</li>" +
        "<li>Have an axe in bank (best available for your Woodcutting level)</li>" +
        "<li>Have food in bank</li>" +
        "<li>Have at least 43 Prayer for Protect from Magic (recommended)</li>" +
        "</ul>\n" +
        "<p><strong>How it works:</strong></p>\n" +
        "<ol>" +
        "<li>Takes Mossy Keys, axe, potions, runes (optional), and food from bank</li>" +
        "<li>Checks and restores prayer at Varrock church if needed</li>" +
        "<li>Walks to Bryophyta's entrance in Varrock Sewers</li>" +
        "<li>Fights Bryophyta and prioritizes Growthlings when they spawn</li>" +
        "<li>Loots the chest after killing Bryophyta</li>" +
        "<li>Continues fighting until out of keys, food, or prayer</li>" +
        "<li>Returns to bank to restock</li>" +
        "</ol>\n"
)
@ConfigGroup("bryophyta")
public interface BryophytaConfig extends Config {

    @ConfigSection(
            name = "Combat Settings",
            description = "Combat related settings",
            position = 1
    )
    String combatSection = "combat";

    @ConfigSection(
            name = "Banking Settings", 
            description = "Banking and supplies settings",
            position = 2
    )
    String bankingSection = "banking";

    @ConfigSection(
        name = "Safety Settings",
        description = "Safety and emergency settings", 
        position = 3
    )
    String safetySection = "safety";

    @ConfigSection(
        name = "Looting Settings",
        description = "Looting and item collection settings",
        position = 4
    )
    String lootingSection = "looting";    // Combat Settings
    @ConfigItem(
            keyName = "useProtectFromMagic",
            name = "Use Protect from Magic",
            description = "Activate Protect from Magic prayer during combat",
            position = 1,
            section = combatSection
    )
    default boolean useProtectFromMagic() {
        return true;
    }

    @ConfigItem(
            keyName = "useQuickPrayer",
            name = "Use Quick Prayer",
            description = "Use quick prayer instead of individual prayers",
            position = 2,
            section = combatSection
    )
    default boolean useQuickPrayer() {
        return true;
    }

    @ConfigItem(
            keyName = "eatAtHealthPercent",
            name = "Eat at Health %",
            description = "Eat food when health drops below this percentage (0-100)",
            position = 3,
            section = combatSection
    )
    @Range(min = 1, max = 99)
    default int eatAtHealthPercent() {
        return 50;
    }

    // Banking Settings
    @ConfigItem(
            keyName = "potionsToTake",
            name = "Potions to Take",
            description = "Comma-separated list of potions to take from bank (e.g., 'strength potion(4), attack potion(4)')",
            position = 1,
            section = bankingSection
    )
    default String potionsToTake() {
        return "strength potion(4)";
    }

    @ConfigItem(
            keyName = "potionQuantity",
            name = "Potion Quantity",
            description = "How many of each potion type to take",
            position = 2,
            section = bankingSection
    )
    @Range(min = 0, max = 10)
    default int potionQuantity() {
        return 1;
    }

    @ConfigItem(
            keyName = "useVarrockTeleport",
            name = "Use Varrock Teleport",
            description = "Take Varrock teleport runes for quick banking/escaping",
            position = 3,
            section = bankingSection
    )
    default boolean useVarrockTeleport() {
        return true;
    }

    @ConfigItem(
            keyName = "varrockTeleportQuantity",
            name = "Varrock Teleport Quantity",
            description = "How many Varrock teleports to take (sets of runes)",
            position = 4,
            section = bankingSection
    )
    @Range(min = 1, max = 10)
    default int varrockTeleportQuantity() {
        return 3;
    }

    // Safety Settings
    @ConfigItem(
            keyName = "minPrayerPoints",
            name = "Minimum Prayer Points",
            description = "Return to bank when prayer points drop below this value",
            position = 1,
            section = safetySection
    )
    @Range(min = 0, max = 99)
    default int minPrayerPoints() {
        return 10;
    }

    @ConfigItem(
            keyName = "minFoodCount",
            name = "Minimum Food Count",
            description = "Return to bank when food count drops below this value",
            position = 2,
            section = safetySection
    )
    @Range(min = 1, max = 10)
    default int minFoodCount() {
        return 3;
    }

    @ConfigItem(
            keyName = "emergencyTeleportHP",
            name = "Emergency Teleport HP",
            description = "Emergency teleport when HP drops below this percentage (0 to disable)",
            position = 3,
            section = safetySection
    )
    @Range(min = 0, max = 50)
    default int emergencyTeleportHP() {
        return 15;
    }

    @ConfigItem(
            keyName = "stopOnNoKeys",
            name = "Stop on No Keys",
            description = "Stop the plugin when no more mossy keys are available",
            position = 4,
            section = safetySection
    )
    default boolean stopOnNoKeys() {
        return true;
    }

    // Looting Settings
    @ConfigItem(
            keyName = "minItemValueToLoot",
            name = "Minimum Item Value",
            description = "Minimum GE value of items to loot (in GP)",
            position = 1,
            section = lootingSection
    )
    @Range(min = 0, max = 1000000)
    default int minItemValueToLoot() {
        return 1000;
    }

    @ConfigItem(
            keyName = "maxItemValueToLoot",
            name = "Maximum Item Value",
            description = "Maximum GE value of items to loot (in GP, for safety)",
            position = 2,
            section = lootingSection
    )
    @Range(min = 1000, max = 10000000)
    default int maxItemValueToLoot() {
        return 10000000;
    }

    @ConfigItem(
            keyName = "lootRadius",
            name = "Loot Radius",
            description = "Radius in tiles to search for items to loot",
            position = 3,
            section = lootingSection
    )
    @Range(min = 5, max = 20)
    default int lootRadius() {
        return 15;
    }
}
