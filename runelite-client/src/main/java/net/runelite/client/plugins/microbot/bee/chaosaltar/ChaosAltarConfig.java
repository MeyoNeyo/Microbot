package net.runelite.client.plugins.microbot.bee.chaosaltar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("chaosaltar")
public interface ChaosAltarConfig extends Config {

    @ConfigItem(
            keyName = "pluginDescription",
            name = "How to Use",
            description = "Best practices for using Chaos Altar plugin",
            position = 1
    )
    default String pluginDescription() {
        return "For best results:\n"
                + "- Activate Player Monitor in LITE_MODE\n"
                + "- Enable AutoLogin\n"
                + "- Keep Burning Amulets and dragon bones in the bank\n"
                + "- ONLY WORKS WITH Dragon Bones\n"
                + "- If you have an alt, consider teleporting to the Lava Maze spot first to distract PKers or bots who can auto-attack when you teleport\n"
                + "(Note: Microbot currently can't log out fast enough for when someone is waiting for you after teleport)";
    }

    @ConfigItem(
            keyName = "f2pHop",
            name = "Enable F2P Hop",
            description = "Hops to F2P worlds and runs to the altar instead of using Burning Amulet. (WIP - non-functional)"
    )
    default boolean f2pHop() {
        return false;
    }

    @ConfigItem(
            keyName = "Boneyard",
            name = "Enable Boneyard Mode",
            description = "Collects bones from boneyard and uses them on chaos altar(WIP - non-functional)"
    )
    default boolean boneYardMode() {
        return false;
    }

    @ConfigItem(
            keyName = "Fast Bones Offering",
            name = "Offer Bones Fast",
            description = "Uses the bones on the altar quickly (more apm)"
    )
    default boolean giveBonesFast() {
        return false;
    }

    @ConfigItem(
            keyName = "enableWorldHopping",
            name = "Enable World Hopping",
            description = "Automatically hop worlds when other players are detected"
    )
    default boolean enableWorldHopping() {
        return true;
    }

    @ConfigItem(
            keyName = "playerDetectionRadius",
            name = "Player Detection Radius",
            description = "Radius in tiles to detect other players (recommended: 15-25 for chaos altar)"
    )
    default int playerDetectionRadius() {
        return 20;
    }

    @ConfigItem(
            keyName = "maxPlayersBeforeHop",
            name = "Max Players Before Hop",
            description = "Maximum number of players allowed before hopping (0 = hop on any player)"
    )
    default int maxPlayersBeforeHop() {
        return 0;
    }

    @ConfigItem(
            keyName = "instantHop",
            name = "Instant Hop on Player Detection",
            description = "Hop immediately when any attackable player is detected (recommended for wilderness)"
    )
    default boolean instantHop() {
        return true;
    }

    @ConfigItem(
            keyName = "hopCooldownSeconds",
            name = "Hop Cooldown (seconds)",
            description = "Minimum time between world hops to avoid spam hopping"
    )
    default int hopCooldownSeconds() {
        return 10;
    }

    @ConfigItem(
            keyName = "emergencyHopOnAttack",
            name = "Emergency Hop on Attack",
            description = "Immediately hop when being attacked by another player (bypasses cooldown)"
    )
    default boolean emergencyHopOnAttack() {
        return true;
    }

}
