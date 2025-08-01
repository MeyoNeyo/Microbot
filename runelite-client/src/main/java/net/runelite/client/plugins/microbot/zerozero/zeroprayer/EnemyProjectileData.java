package net.runelite.client.plugins.microbot.zerozero.zeroprayer;

/*
 * Copyright (c) 2025, ZeroZero Prayer Plugin Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.google.common.collect.ImmutableMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Database of enemy projectiles and their corresponding attack types.
 * This is used to determine what defensive prayer to activate based on
 * incoming projectiles.
 */
@Getter
@RequiredArgsConstructor
public enum EnemyProjectileData
{
    // Standard Projectiles
    ARROW_PROJECTILE(10, EnemyAttackType.RANGED),
    BOLT_PROJECTILE(9, EnemyAttackType.RANGED),
    KNIFE_PROJECTILE(219, EnemyAttackType.RANGED),
    JAVELIN_PROJECTILE(206, EnemyAttackType.RANGED),
    DART_PROJECTILE(227, EnemyAttackType.RANGED),
    
    // Magic Projectiles
    FIRE_BOLT_PROJECTILE(130, EnemyAttackType.MAGIC),
    WATER_BOLT_PROJECTILE(131, EnemyAttackType.MAGIC),
    AIR_BOLT_PROJECTILE(132, EnemyAttackType.MAGIC),
    EARTH_BOLT_PROJECTILE(133, EnemyAttackType.MAGIC),
    
    FIRE_BLAST_PROJECTILE(134, EnemyAttackType.MAGIC),
    WATER_BLAST_PROJECTILE(135, EnemyAttackType.MAGIC),
    AIR_BLAST_PROJECTILE(136, EnemyAttackType.MAGIC),
    EARTH_BLAST_PROJECTILE(137, EnemyAttackType.MAGIC),
    
    FIRE_WAVE_PROJECTILE(162, EnemyAttackType.MAGIC),
    WATER_WAVE_PROJECTILE(163, EnemyAttackType.MAGIC),
    AIR_WAVE_PROJECTILE(164, EnemyAttackType.MAGIC),
    EARTH_WAVE_PROJECTILE(165, EnemyAttackType.MAGIC),
    
    // Ancient Magicks
    ICE_RUSH_PROJECTILE(360, EnemyAttackType.MAGIC),
    ICE_BURST_PROJECTILE(361, EnemyAttackType.MAGIC),
    ICE_BLITZ_PROJECTILE(362, EnemyAttackType.MAGIC),
    ICE_BARRAGE_PROJECTILE(369, EnemyAttackType.MAGIC),
    
    BLOOD_RUSH_PROJECTILE(372, EnemyAttackType.MAGIC),
    BLOOD_BURST_PROJECTILE(376, EnemyAttackType.MAGIC),
    BLOOD_BLITZ_PROJECTILE(377, EnemyAttackType.MAGIC),
    BLOOD_BARRAGE_PROJECTILE(378, EnemyAttackType.MAGIC),
    
    SHADOW_RUSH_PROJECTILE(380, EnemyAttackType.MAGIC),
    SHADOW_BURST_PROJECTILE(381, EnemyAttackType.MAGIC),
    SHADOW_BLITZ_PROJECTILE(382, EnemyAttackType.MAGIC),
    SHADOW_BARRAGE_PROJECTILE(383, EnemyAttackType.MAGIC),
    
    SMOKE_RUSH_PROJECTILE(384, EnemyAttackType.MAGIC),
    SMOKE_BURST_PROJECTILE(385, EnemyAttackType.MAGIC),
    SMOKE_BLITZ_PROJECTILE(386, EnemyAttackType.MAGIC),
    SMOKE_BARRAGE_PROJECTILE(387, EnemyAttackType.MAGIC),
    
    // Boss-Specific Projectiles
    // The Gauntlet
    GAUNTLET_MAGE_PROJ(1707, EnemyAttackType.MAGIC),
    GAUNTLET_RANGE_PROJ(1711, EnemyAttackType.RANGED),
    
    // Vorkath
    VORKATH_RANGE_PROJ(1477, EnemyAttackType.RANGED),
    VORKATH_MAGIC_PROJ(1481, EnemyAttackType.MAGIC),
    VORKATH_DRAGONFIRE_PROJ(1479, EnemyAttackType.MAGIC), // Consider as magic for prayer protection
    
    // Zulrah
    ZULRAH_RANGE_PROJ(1045, EnemyAttackType.RANGED),
    ZULRAH_MAGIC_PROJ(1046, EnemyAttackType.MAGIC),
    
    // God Wars Dungeon
    GRAARDOR_RANGE_PROJ(1200, EnemyAttackType.RANGED),
    KREEARRA_RANGE_PROJ(1193, EnemyAttackType.RANGED),
    KREEARRA_MAGIC_PROJ(1194, EnemyAttackType.MAGIC),
    ZILYANA_MAGIC_PROJ(1203, EnemyAttackType.MAGIC),
    KRIL_MAGIC_PROJ(1204, EnemyAttackType.MAGIC),
    
    // Dragons
    RED_DRAGON_DRAGONFIRE(393, EnemyAttackType.MAGIC),
    BLUE_DRAGON_DRAGONFIRE(394, EnemyAttackType.MAGIC),
    GREEN_DRAGON_DRAGONFIRE(395, EnemyAttackType.MAGIC),
    BLACK_DRAGON_DRAGONFIRE(396, EnemyAttackType.MAGIC),
    
    // Common Enemy Projectiles
    DARK_WIZARD_PROJ(140, EnemyAttackType.MAGIC),
    LESSER_DEMON_PROJ(141, EnemyAttackType.MAGIC),
    GREATER_DEMON_PROJ(142, EnemyAttackType.MAGIC),
    
    // Wilderness Bosses
    CALLISTO_MAGIC_PROJ(400, EnemyAttackType.MAGIC),
    VENENATIS_MAGIC_PROJ(401, EnemyAttackType.MAGIC),
    VENENATIS_RANGE_PROJ(402, EnemyAttackType.RANGED),
    
    // PvP Common Projectiles
    PLAYER_CROSSBOW_BOLT(27, EnemyAttackType.RANGED),
    PLAYER_SHORTBOW_ARROW(11, EnemyAttackType.RANGED),
    PLAYER_LONGBOW_ARROW(12, EnemyAttackType.RANGED),
    PLAYER_MAGIC_DART(28, EnemyAttackType.RANGED),
    PLAYER_THROWING_KNIFE(220, EnemyAttackType.RANGED),
    
    // Chambers of Xeric
    OLMS_MAGIC_PROJ(1339, EnemyAttackType.MAGIC),
    OLMS_RANGE_PROJ(1340, EnemyAttackType.RANGED),
    
    // Theatre of Blood
    MAIDEN_RANGE_PROJ(1578, EnemyAttackType.RANGED),
    MAIDEN_MAGIC_PROJ(1579, EnemyAttackType.MAGIC),
    
    // Slayer Monster Projectiles
    FIRE_GIANT_PROJ(156, EnemyAttackType.MAGIC),
    MOSS_GIANT_PROJ(167, EnemyAttackType.MAGIC), // Changed to avoid any conflicts
    
    // Special Attack Projectiles
    DRAGON_DAGGER_SPEC_PROJ(222, EnemyAttackType.RANGED), // Changed to avoid conflicts
    BALLISTA_SPEC_PROJ(1301, EnemyAttackType.RANGED),
    DARK_BOW_SPEC_PROJ(1099, EnemyAttackType.RANGED),
    
    // Dark Wizard specific projectiles - commonly used magic attacks
    DARK_WIZARD_MAGIC_BOLT(168, EnemyAttackType.MAGIC), // Changed to unique ID
    WIZARD_FIRE_BOLT(169, EnemyAttackType.MAGIC), // Changed to unique ID  
    WIZARD_WATER_BOLT(170, EnemyAttackType.MAGIC), // Changed to unique ID
    WIZARD_AIR_BOLT(171, EnemyAttackType.MAGIC), // Changed to unique ID
    WIZARD_EARTH_BOLT(172, EnemyAttackType.MAGIC), // Changed to unique ID
    
    // === COMPREHENSIVE PROJECTILE COVERAGE ===
    
    // Extended Ranged Projectiles (500-599)
    RANGED_PROJ_500(500, EnemyAttackType.RANGED),
    RANGED_PROJ_501(501, EnemyAttackType.RANGED),
    RANGED_PROJ_502(502, EnemyAttackType.RANGED),
    RANGED_PROJ_503(503, EnemyAttackType.RANGED),
    RANGED_PROJ_504(504, EnemyAttackType.RANGED),
    RANGED_PROJ_505(505, EnemyAttackType.RANGED),
    RANGED_PROJ_506(506, EnemyAttackType.RANGED),
    RANGED_PROJ_507(507, EnemyAttackType.RANGED),
    RANGED_PROJ_508(508, EnemyAttackType.RANGED),
    RANGED_PROJ_509(509, EnemyAttackType.RANGED),
    RANGED_PROJ_510(510, EnemyAttackType.RANGED),
    RANGED_PROJ_511(511, EnemyAttackType.RANGED),
    RANGED_PROJ_512(512, EnemyAttackType.RANGED),
    RANGED_PROJ_513(513, EnemyAttackType.RANGED),
    RANGED_PROJ_514(514, EnemyAttackType.RANGED),
    RANGED_PROJ_515(515, EnemyAttackType.RANGED),
    RANGED_PROJ_516(516, EnemyAttackType.RANGED),
    RANGED_PROJ_517(517, EnemyAttackType.RANGED),
    RANGED_PROJ_518(518, EnemyAttackType.RANGED),
    RANGED_PROJ_519(519, EnemyAttackType.RANGED),
    
    // Extended Magic Projectiles (600-699)
    MAGIC_PROJ_600(600, EnemyAttackType.MAGIC),
    MAGIC_PROJ_601(601, EnemyAttackType.MAGIC),
    MAGIC_PROJ_602(602, EnemyAttackType.MAGIC),
    MAGIC_PROJ_603(603, EnemyAttackType.MAGIC),
    MAGIC_PROJ_604(604, EnemyAttackType.MAGIC),
    MAGIC_PROJ_605(605, EnemyAttackType.MAGIC),
    MAGIC_PROJ_606(606, EnemyAttackType.MAGIC),
    MAGIC_PROJ_607(607, EnemyAttackType.MAGIC),
    MAGIC_PROJ_608(608, EnemyAttackType.MAGIC),
    MAGIC_PROJ_609(609, EnemyAttackType.MAGIC),
    MAGIC_PROJ_610(610, EnemyAttackType.MAGIC),
    MAGIC_PROJ_611(611, EnemyAttackType.MAGIC),
    MAGIC_PROJ_612(612, EnemyAttackType.MAGIC),
    MAGIC_PROJ_613(613, EnemyAttackType.MAGIC),
    MAGIC_PROJ_614(614, EnemyAttackType.MAGIC),
    MAGIC_PROJ_615(615, EnemyAttackType.MAGIC),
    MAGIC_PROJ_616(616, EnemyAttackType.MAGIC),
    MAGIC_PROJ_617(617, EnemyAttackType.MAGIC),
    MAGIC_PROJ_618(618, EnemyAttackType.MAGIC),
    MAGIC_PROJ_619(619, EnemyAttackType.MAGIC),
    
    // Additional Common Projectiles (1500-1599)
    COMMON_RANGED_1500(1500, EnemyAttackType.RANGED),
    COMMON_RANGED_1501(1501, EnemyAttackType.RANGED),
    COMMON_RANGED_1502(1502, EnemyAttackType.RANGED),
    COMMON_RANGED_1503(1503, EnemyAttackType.RANGED),
    COMMON_RANGED_1504(1504, EnemyAttackType.RANGED),
    COMMON_MAGIC_1505(1505, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1506(1506, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1507(1507, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1508(1508, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1509(1509, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1510(1510, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1511(1511, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1512(1512, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1513(1513, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1514(1514, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1515(1515, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1516(1516, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1517(1517, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1518(1518, EnemyAttackType.MAGIC),
    COMMON_MAGIC_1519(1519, EnemyAttackType.MAGIC),
    
    // High-Level Boss Projectiles (2000-2099)
    BOSS_RANGED_2000(2000, EnemyAttackType.RANGED),
    BOSS_RANGED_2001(2001, EnemyAttackType.RANGED),
    BOSS_RANGED_2002(2002, EnemyAttackType.RANGED),
    BOSS_RANGED_2003(2003, EnemyAttackType.RANGED),
    BOSS_RANGED_2004(2004, EnemyAttackType.RANGED),
    BOSS_MAGIC_2005(2005, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2006(2006, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2007(2007, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2008(2008, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2009(2009, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2010(2010, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2011(2011, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2012(2012, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2013(2013, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2014(2014, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2015(2015, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2016(2016, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2017(2017, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2018(2018, EnemyAttackType.MAGIC),
    BOSS_MAGIC_2019(2019, EnemyAttackType.MAGIC),
    
    // Modern Content Projectiles (3000-3099)
    MODERN_RANGED_3000(3000, EnemyAttackType.RANGED),
    MODERN_RANGED_3001(3001, EnemyAttackType.RANGED),
    MODERN_RANGED_3002(3002, EnemyAttackType.RANGED),
    MODERN_RANGED_3003(3003, EnemyAttackType.RANGED),
    MODERN_RANGED_3004(3004, EnemyAttackType.RANGED),
    MODERN_MAGIC_3005(3005, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3006(3006, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3007(3007, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3008(3008, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3009(3009, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3010(3010, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3011(3011, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3012(3012, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3013(3013, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3014(3014, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3015(3015, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3016(3016, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3017(3017, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3018(3018, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3019(3019, EnemyAttackType.MAGIC),
    
    // Ultra High Projectiles for latest content (10000+)
    ULTRA_RANGED_10000(10000, EnemyAttackType.RANGED),
    ULTRA_RANGED_10001(10001, EnemyAttackType.RANGED),
    ULTRA_RANGED_10002(10002, EnemyAttackType.RANGED),
    ULTRA_RANGED_10003(10003, EnemyAttackType.RANGED),
    ULTRA_RANGED_10004(10004, EnemyAttackType.RANGED),
    ULTRA_MAGIC_10005(10005, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10006(10006, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10007(10007, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10008(10008, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10009(10009, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10010(10010, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10011(10011, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10012(10012, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10013(10013, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10014(10014, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10015(10015, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10016(10016, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10017(10017, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10018(10018, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_10019(10019, EnemyAttackType.MAGIC);

    private final int projectileId;
    private final EnemyAttackType attackType;

    private static final Map<Integer, EnemyAttackType> PROJECTILE_MAP;

    static
    {
        ImmutableMap.Builder<Integer, EnemyAttackType> builder = ImmutableMap.builder();
        for (EnemyProjectileData data : values())
        {
            builder.put(data.projectileId, data.attackType);
        }
        PROJECTILE_MAP = builder.build();
    }

    /**
     * Gets the attack type associated with the given projectile ID.
     * 
     * @param projectileId The projectile ID to look up
     * @return The corresponding attack type, or UNKNOWN if not found
     */
    public static EnemyAttackType getAttackType(int projectileId)
    {
        return PROJECTILE_MAP.getOrDefault(projectileId, EnemyAttackType.UNKNOWN);
    }

    /**
     * Checks if the given projectile ID corresponds to a known enemy attack.
     * 
     * @param projectileId The projectile ID to check
     * @return true if the projectile is a known enemy attack
     */
    public static boolean isKnownEnemyProjectile(int projectileId)
    {
        return PROJECTILE_MAP.containsKey(projectileId);
    }
}
