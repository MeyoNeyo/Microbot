package net.runelite.client.plugins.microbot.f2pogresswarrior;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("ogressWarriorKiller")
public interface OgressWarriorKillerConfig extends Config {
    // Aggro Timer Settings
    @ConfigSection(
        name = "Aggro Timer",
        description = "Settings for aggression timer management",
        position = 0
    )
    String aggroSection = "aggroSection";

    @ConfigItem(
        keyName = "waitForAggroTimer",
        name = "Wait for aggro timer",
        description = "Wait at safe spot for 10 minutes before fighting",
        section = aggroSection
    )
    default boolean waitForAggroTimer() { return true; }

    @ConfigItem(
        keyName = "aggroTimerSeconds",
        name = "Aggro timer duration (seconds)",
        description = "How long to wait for aggression timer in seconds (default: 600)",
        section = aggroSection
    )
    default int aggroTimerSeconds() { return 600; }

    // Combat Settings
    @ConfigSection(
        name = "Combat",
        description = "Combat-related settings",
        position = 1
    )
    String combatSection = "combatSection";

    @ConfigItem(
        keyName = "useHighAlch",
        name = "Use High Alchemy",
        description = "Cast High Alchemy on profitable items",
        section = combatSection
    )
    default boolean useHighAlch() { return false; }

    @ConfigItem(
        keyName = "enableEatAtPercent",
        name = "Enable Auto-Eat",
        description = "Automatically eat food when HP falls below a set percent",
        section = combatSection
    )
    default boolean enableEatAtPercent() { return true; }

    @ConfigItem(
        keyName = "eatAtPercent",
        name = "Eat at HP %",
        description = "Eat food when HP falls below this percent (0 to disable)",
        section = combatSection
    )
    default int eatAtPercent() { return 50; }

    // Banking Settings
    @ConfigSection(
        name = "Banking",
        description = "Banking and food settings",
        position = 2
    )
    String bankingSection = "bankingSection";

    @ConfigItem(
        keyName = "bankForFood",
        name = "Bank for food",
        description = "Automatically bank when low on food",
        section = bankingSection
    )
    default boolean bankForFood() { return true; }

    @ConfigItem(
        keyName = "foodName",
        name = "Food type",
        description = "Name of food to withdraw (e.g., 'Lobster')",
        section = bankingSection
    )
    default String foodName() { return "Lobster"; }

    @ConfigItem(
        keyName = "foodAmount",
        name = "Food amount",
        description = "How much food to withdraw from bank",
        section = bankingSection
    )
    default int foodAmount() { return 20; }

    @ConfigItem(
        keyName = "minFreeSlots",
        name = "Minimum free inventory slots",
        description = "Bank when inventory has this many or fewer free slots",
        section = bankingSection
    )
    default int minFreeSlots() { return 5; }

    // Looting Settings
    @ConfigSection(
        name = "Looting",
        description = "Item looting preferences",
        position = 3
    )
    String lootingSection = "lootingSection";

    @ConfigItem(
        keyName = "lootBread",
        name = "Loot and eat bread",
        description = "Pick up bread from ground and eat when needed",
        section = lootingSection
    )
    default boolean lootBread() { return true; }

    @ConfigItem(
        keyName = "lootCoins",
        name = "Loot coins from kills",
        description = "Loot coins dropped by Ogress Warriors you kill",
        section = lootingSection
    )
    default boolean lootCoins() { return true; }

    @ConfigItem(
        keyName = "gemLootMode",
        name = "Gem looting",
        description = "Which gems to loot",
        section = lootingSection
    )
    default GemLootMode gemLootMode() { return GemLootMode.HIGH_VALUE_ONLY; }

    @ConfigItem(
        keyName = "lootRunes",
        name = "Loot Runes",
        description = "Loot runes dropped by monsters",
        section = lootingSection
    )
    default boolean lootRunes() { return true; }

    // World Hopping Settings
    @ConfigSection(
        name = "World Hopping",
        description = "World hopping settings",
        position = 4
    )
    String hoppingSection = "hoppingSection";

    @ConfigItem(
        keyName = "hopWorlds",
        name = "Hop worlds if crowded",
        description = "Automatically hop worlds if too many players nearby",
        section = hoppingSection
    )
    default boolean hopWorlds() { return true; }

    @ConfigItem(
        keyName = "maxPlayers",
        name = "Maximum players nearby",
        description = "Hop worlds if more than this many players are nearby (set to 0 to disable)",
        section = hoppingSection
    )
    default int maxPlayers() { return 3; }

    enum GemLootMode {
        NONE("Don't loot gems"),
        HIGH_VALUE_ONLY("Ruby/Diamond only"),
        ALL_GEMS("All gems");

        private final String name;
        GemLootMode(String name) { this.name = name; }
        @Override
        public String toString() { return name; }
    }
}
