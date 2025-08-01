package net.runelite.client.plugins.microbot.allinonemetalworker;

import net.runelite.client.config.*;
import net.runelite.client.plugins.microbot.allinonemetalworker.enums.MetalType;

/**
 * Configuration interface for the All-in-One Metal Worker plugin.
 * Provides all necessary settings for automated mining, smelting, and smithing operations.
 */
@ConfigGroup("AIOMetalWorker")
@ConfigInformation(
    "<h2>AIO Metal Worker Plugin</h2>" +
    "<h3>Version: 1.0.0</h3>" +
    "<p><strong>Setup Instructions:</strong></p>" +
    "<ol>" +
    "<li>Select your desired metal type (Bronze, Iron, Steel, etc.)</li>" +
    "<li>Set the total quantity of ores you want to process</li>" +
    "<li>Position your character near a mining location</li>" +
    "<li>Ensure you have the required pickaxe and hammer</li>" +
    "<li>Start the plugin and let it handle the rest!</li>" +
    "</ol>" +
    "<p><strong>Plugin Flow:</strong> Mining → Banking → Smelting → Banking → Smithing → Repeat</p>" +
    "<p><strong>Locations:</strong> Mines ores at current location, smelts at Al Kharid furnace, smiths at Varrock anvil</p>"
)
public interface AIOMetalWorkerConfig extends Config {

    // Main Configuration Section
    @ConfigSection(
            name = "General Settings",
            description = "Primary configuration options",
            position = 0
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "metalType",
            name = "Metal Type",
            description = "Select the type of metal to work with. Determines which ores to mine and bars to produce.",
            position = 0,
            section = generalSection
    )
    default MetalType metalType() {
        return MetalType.BRONZE;
    }

    @ConfigItem(
            keyName = "targetQuantity",
            name = "Target Ore Quantity",
            description = "Total number of ores to mine before proceeding to smelting. For Bronze: 560 = 280 copper + 280 tin.",
            position = 1,
            section = generalSection
    )
    @Range(min = 28, max = 10000)
    default int targetQuantity() {
        return 560;
    }

    @ConfigItem(
            keyName = "miningRange",
            name = "Mining Range (tiles)",
            description = "Maximum distance in tiles to search for ores from your starting position.",
            position = 2,
            section = generalSection
    )
    @Range(min = 5, max = 30)
    default int miningRange() {
        return 15;
    }

    // Advanced Settings Section
    @ConfigSection(
            name = "Advanced Options",
            description = "Advanced configuration options",
            position = 1
    )
    String advancedSection = "advanced";

    @ConfigItem(
            keyName = "useCoalBag",
            name = "Use Coal Bag",
            description = "Automatically use coal bag when available for steel+ bars (Members only).",
            position = 0,
            section = advancedSection
    )
    default boolean useCoalBag() {
        return true;
    }

    @ConfigItem(
            keyName = "useSpecialEquipment",
            name = "Auto-Equip Special Items",
            description = "Automatically equip Ring of Forging, Goldsmith Gauntlets, etc. when beneficial.",
            position = 1,
            section = advancedSection
    )
    default boolean useSpecialEquipment() {
        return true;
    }

    @ConfigItem(
            keyName = "maxPlayersInArea",
            name = "Max Players in Mining Area",
            description = "Maximum number of other players allowed in mining area before world hopping. 0 = disabled.",
            position = 2,
            section = advancedSection
    )
    @Range(min = 0, max = 10)
    default int maxPlayersInArea() {
        return 3;
    }

    @ConfigItem(
            keyName = "hopWorlds",
            name = "Enable World Hopping",
            description = "Hop worlds when mining area is too crowded or rocks are depleted.",
            position = 3,
            section = advancedSection
    )
    default boolean hopWorlds() {
        return true;
    }

    // Safety and Anti-ban Section
    @ConfigSection(
            name = "Safety & Anti-ban",
            description = "Safety and detection avoidance settings",
            position = 2
    )
    String safetySection = "safety";

    @ConfigItem(
            keyName = "enableAntiban",
            name = "Enable Anti-ban",
            description = "Enable anti-ban features including random delays and human-like behavior.",
            position = 0,
            section = safetySection
    )
    default boolean enableAntiban() {
        return true;
    }

    @ConfigItem(
            keyName = "takeMicroBreaks",
            name = "Take Micro Breaks",
            description = "Take short random breaks during operation to simulate human behavior.",
            position = 1,
            section = safetySection
    )
    default boolean takeMicroBreaks() {
        return true;
    }

    @ConfigItem(
            keyName = "logoutOnComplete",
            name = "Logout When Complete",
            description = "Automatically logout when all operations are finished.",
            position = 2,
            section = safetySection
    )
    default boolean logoutOnComplete() {
        return true;
    }

    @ConfigItem(
            keyName = "maxRunTimeMinutes",
            name = "Max Runtime (minutes)",
            description = "Maximum time to run before automatically stopping. 0 = unlimited.",
            position = 3,
            section = safetySection
    )
    @Range(min = 0, max = 1440)
    default int maxRunTimeMinutes() {
        return 180; // 3 hours default
    }

    // Debug Section
    @ConfigSection(
            name = "Debug Options",
            description = "Debugging and development options",
            position = 3
    )
    String debugSection = "debug";

    @ConfigItem(
            keyName = "enableDebugLogs",
            name = "Enable Debug Logging",
            description = "Enable detailed logging for troubleshooting. May impact performance.",
            position = 0,
            section = debugSection
    )
    default boolean enableDebugLogs() {
        return false;
    }

    @ConfigItem(
            keyName = "showDetailedOverlay",
            name = "Show Detailed Overlay",
            description = "Show additional information in the overlay panel.",
            position = 1,
            section = debugSection
    )
    default boolean showDetailedOverlay() {
        return true;
    }

    // Additional methods for script functionality
    @ConfigItem(
            keyName = "actionDelay",
            name = "Action Delay (ms)",
            description = "Delay between actions in milliseconds",
            position = 20,
            section = advancedSection
    )
    @Range(min = 50, max = 5000)
    default int actionDelay() {
        return 150;
    }

    @ConfigItem(
            keyName = "maxFailedActions",
            name = "Max Failed Actions",
            description = "Maximum number of failed actions before stopping",
            position = 4,
            section = safetySection
    )
    @Range(min = 3, max = 20)
    default int maxFailedActions() {
        return 10;
    }

    @ConfigItem(
            keyName = "smeltBars",
            name = "Smelt Bars",
            description = "Whether to smelt ores into bars",
            position = 10,
            section = generalSection
    )
    default boolean smeltBars() {
        return true;
    }

    @ConfigItem(
            keyName = "smithItems",
            name = "Smith Items", 
            description = "Whether to smith bars into items",
            position = 11,
            section = generalSection
    )
    default boolean smithItems() {
        return true;
    }

    @ConfigItem(
            keyName = "withdrawPickaxe",
            name = "Withdraw Pickaxe",
            description = "Automatically withdraw pickaxe from bank",
            position = 21,
            section = advancedSection
    )
    default boolean withdrawPickaxe() {
        return true;
    }
}
