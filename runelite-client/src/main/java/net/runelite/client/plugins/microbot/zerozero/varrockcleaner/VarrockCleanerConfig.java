package net.runelite.client.plugins.microbot.zerozero.varrockcleaner;

import net.runelite.client.config.*;

@ConfigInformation("Start this inside the museum near the finds. You must have the Gloves and Boots equipped, with the Trowel, brush and pick in your inventory.")
@ConfigGroup(VarrockCleanerPlugin.CONFIG)
public interface VarrockCleanerConfig extends Config {

    @ConfigSection(
            name = "Antique Lamp Settings",
            description = "Configure antique lamp usage",
            position = 0,
            closedByDefault = false
    )
    String lampSection = "lampSettings";

    @ConfigItem(
            keyName = "useAntiqueLamps",
            name = "Use Antique Lamps",
            description = "Enable automatic use of antique lamps on the selected skill",
            position = 1,
            section = lampSection
    )
    default boolean useAntiqueLamps() {
        return false;
    }

    @ConfigItem(
            keyName = "lampSkillSelection",
            name = "Lamp Skill",
            description = "Select which skill to use antique lamps on (requires level 10+)",
            position = 2,
            section = lampSection
    )
    default LampSkill lampSkillSelection() {
        return LampSkill.ATTACK;
    }
}
