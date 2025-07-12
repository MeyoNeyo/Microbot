package net.runelite.client.plugins.microbot.wineofzamorak;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("wineofzamorak")
public interface WineOfZamorakConfig extends Config {
    
    @ConfigSection(
            name = "General Settings",
            description = "General configuration options",
            position = 10
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "enableDebugLogging",
            name = "Enable Debug Logging",
            description = "Enable detailed logging for debugging purposes",
            position = 11,
            section = generalSection
    )
    default boolean enableDebugLogging() {
        return false;
    }

    @ConfigSection(
            name = "World Hopping",
            description = "World Hopping Settings",
            position = 20
    )
    String worldHoppingSection = "World Hopping";

    @ConfigItem(
            keyName = "enableWorldHopping",
            name = "Enable World Hopping",
            description = "Enable automatic world hopping when wine is not available",
            position = 21,
            section = worldHoppingSection
    )
    default boolean enableWorldHopping() {
        return true;
    }

    @ConfigItem(
            keyName = "worldHopDelay",
            name = "World Hop Delay (seconds)",
            description = "Delay between world hops in seconds",
            position = 22,
            section = worldHoppingSection
    )
    default int worldHopDelay() {
        return 5;
    }

    @ConfigItem(
            keyName = "maxWorldsToTry",
            name = "Max Worlds to Try",
            description = "Maximum number of worlds to try before stopping",
            position = 23,
            section = worldHoppingSection
    )
    default int maxWorldsToTry() {
        return 20;
    }

    @ConfigItem(
            keyName = "avoidPvpWorlds",
            name = "Avoid PvP Worlds",
            description = "Skip PvP and high-risk worlds when hopping",
            position = 24,
            section = worldHoppingSection
    )
    default boolean avoidPvpWorlds() {
        return true;
    }
}
