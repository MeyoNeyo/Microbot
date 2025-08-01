package net.runelite.client.plugins.microbot.mining.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

@Getter
@RequiredArgsConstructor
public enum Rocks {
    CLAY("clay rocks", 1, 1200),        // 1.2 seconds
    RUNE_ESSENCE("rune essence", 1, 0), // Instant
    TIN("tin rocks", 1, 2400),          // 2.4 seconds
    COPPER("copper rocks", 1, 2400),    // 2.4 seconds
    LIMESTONE("limestone rocks", 1, 5400), // 5.4 seconds (varies by depletion)
    BLURITE("blurite rocks", 10, 25000), // 25 seconds
    DAEYALT("daeyalt rocks", 20, 28000), // 28 seconds
    IRON("iron rocks", 15, 5400),       // 5.4 seconds
    SILVER("silver rocks", 20, 60000),  // 1 minute
    COAL("coal rocks", 30, 30000),      // 30 seconds
    SANDSTONE("sandstone rocks", 35, 5000), // 5 seconds
    GRANITE("granite rocks", 45, 5000), // 5 seconds
    GOLD("gold rocks", 40, 60000),      // 1 minute
    GEM("gem rocks", 40, 90000),        // 1.5 minutes
    MITHRIL("mithril rocks", 55, 120000), // 2 minutes
    ADAMANTITE("adamantite rocks", 70, 240000), // 4 minutes
    BASALT("Basalt rocks", 72, 180000), // 3 minutes
    URT_SALT("Urt salt rocks", 72, 180000), // 3 minutes
    EFH_SALT("Efh salt rocks", 72, 180000), // 3 minutes
    TE_SALT("Te salt rocks", 72, 180000), // 3 minutes
    RUNITE("runite rocks", 85, 720000); // 12 minutes (6 minutes in mining guild)

    private final String name;
    private final int miningLevel;
    private final long respawnTimeMs; // Respawn time in milliseconds

    @Override
    public String toString() {
        return name;
    }
    
    public boolean hasRequiredLevel() {
        return Rs2Player.getSkillRequirement(Skill.MINING, this.miningLevel);
    }
    
    /**
     * Get the actual respawn time considering mining guild bonus
     * @param inMiningGuild whether the player is in the mining guild
     * @return respawn time in milliseconds
     */
    public long getActualRespawnTimeMs(boolean inMiningGuild) {
        if (this == RUNITE && inMiningGuild) {
            return 360000; // 6 minutes for runite in mining guild
        }
        return respawnTimeMs;
    }
}
