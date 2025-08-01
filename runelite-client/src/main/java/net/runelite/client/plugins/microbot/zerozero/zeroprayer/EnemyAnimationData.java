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
 * Database of enemy attack animations and their corresponding attack types.
 * This is used to determine what defensive prayer to activate based on
 * enemy animations.
 */
@Getter
@RequiredArgsConstructor
public enum EnemyAnimationData
{
    // === MELEE ATTACK ANIMATIONS ===
    
    // Human Combat Animations - Melee
    HUMAN_DDAGGER_LUNGE(376, EnemyAttackType.MELEE),
    HUMAN_DDAGGER_HACK(377, EnemyAttackType.MELEE),
    HUMAN_DSPEAR_SLASH(380, EnemyAttackType.MELEE),
    HUMAN_DSPEAR_STAB(381, EnemyAttackType.MELEE),
    HUMAN_DSPEAR_LUNGE(382, EnemyAttackType.MELEE),
    HUMAN_SWORD_STAB(386, EnemyAttackType.MELEE),
    HUMAN_SWORD_SLASH(390, EnemyAttackType.MELEE),
    HUMAN_SWORD_LUNGE(392, EnemyAttackType.MELEE),
    HUMAN_AXE_CHOP(393, EnemyAttackType.MELEE),
    HUMAN_TRANS_AXE_CHOP(394, EnemyAttackType.MELEE),
    HUMAN_AXE_HACK(395, EnemyAttackType.MELEE),
    HUMAN_AXE_SMASH(396, EnemyAttackType.MELEE),
    HUMAN_BLUNT_SPIKE(400, EnemyAttackType.MELEE),
    HUMAN_BLUNT_POUND(401, EnemyAttackType.MELEE),
    HUMAN_BLUNT_PUMMEL(402, EnemyAttackType.MELEE),
    HUMAN_DHSWORD_STAB(405, EnemyAttackType.MELEE),
    HUMAN_DHSWORD_CHOP(406, EnemyAttackType.MELEE),
    HUMAN_DHSWORD_SLASH(407, EnemyAttackType.MELEE),
    HUMAN_DHSWORD_LUNGE(408, EnemyAttackType.MELEE),
    HUMAN_DHSWORD_SPIN(409, EnemyAttackType.MELEE),
    HUMAN_STAFF_SPIKE(412, EnemyAttackType.MELEE),
    HUMAN_STAFF_POUND(413, EnemyAttackType.MELEE),
    HUMAN_STAFF_PUMMEL(414, EnemyAttackType.MELEE),
    HUMAN_STAFFORB_SPIKE(417, EnemyAttackType.MELEE),
    HUMAN_STAFFORB_POUND(418, EnemyAttackType.MELEE),
    HUMAN_STAFFORB_PUMMEL(419, EnemyAttackType.MELEE),
    HUMAN_UNARMEDPUNCH(422, EnemyAttackType.MELEE),
    HUMAN_UNARMEDKICK(423, EnemyAttackType.MELEE),
    HUMAN_SPEAR_SPIKE(428, EnemyAttackType.MELEE),
    HUMAN_SPEAR_LUNGE(429, EnemyAttackType.MELEE),
    HUMAN_FARMERSFORK_STAB(433, EnemyAttackType.MELEE),
    HUMAN_SCYTHE_SLASH(437, EnemyAttackType.MELEE),
    HUMAN_SCYTHE_LUNGE(438, EnemyAttackType.MELEE),
    HUMAN_SCYTHE_SPIN(439, EnemyAttackType.MELEE),
    HUMAN_SCYTHE_SWEEP(440, EnemyAttackType.MELEE),
    
    // Generic Melee Animations (extended range)
    GENERIC_MELEE_1(60, EnemyAttackType.MELEE),
    GENERIC_MELEE_2(61, EnemyAttackType.MELEE),
    GENERIC_MELEE_3(62, EnemyAttackType.MELEE),
    GENERIC_MELEE_4(63, EnemyAttackType.MELEE),
    GENERIC_MELEE_5(64, EnemyAttackType.MELEE),
    GENERIC_MELEE_6(65, EnemyAttackType.MELEE),
    
    // === RANGED ATTACK ANIMATIONS ===
    
    // Human Ranged Animations
    HUMAN_BOW(426, EnemyAttackType.RANGED),
    HUMAN_CROSSBOW(427, EnemyAttackType.RANGED),
    HUMAN_THROW(385, EnemyAttackType.RANGED),
    HUMAN_SPEAR_THROW(432, EnemyAttackType.RANGED),
    
    // Specific Enemy Ranged Attacks
    DEMONIC_GORILLA_RANGED_ATTACK(7227, EnemyAttackType.RANGED),
    TZTOK_JAD_RANGE_ATTACK(2652, EnemyAttackType.RANGED),
    KALPHITE_QUEEN_RANGED_ATTACK(1250, EnemyAttackType.RANGED),
    KALPHITE_RANGED_ATTACK(1251, EnemyAttackType.RANGED),
    HORROR_DAGANNOTH_RANGEATTACK(1343, EnemyAttackType.RANGED),
    M_MONKEY_ATTACK_BOW(1394, EnemyAttackType.RANGED),
    DRAGON_RANGED_ATTACKS(1990, EnemyAttackType.RANGED),
    BARROWS_REPEATING_CROSSBOW_FIRE(2075, EnemyAttackType.RANGED),
    OGRE_LONGBOW(1025, EnemyAttackType.RANGED),
    GNOME_THROW(201, EnemyAttackType.RANGED),
    DWARF_STONE_THROW_LAUNCH(922, EnemyAttackType.RANGED),
    TROLL_ROCK_THROW(1142, EnemyAttackType.RANGED),
    TROLL_ROCK_THROW_LEFTHAND(1488, EnemyAttackType.RANGED),
    EADGAR_TROLL_AXETHROW(1259, EnemyAttackType.RANGED),
    WAA_WEREWOLF_STICK_THROW(1619, EnemyAttackType.RANGED),
    
    // Generic Ranged Animations (extended range)
    GENERIC_RANGED_1(66, EnemyAttackType.RANGED),
    GENERIC_RANGED_2(67, EnemyAttackType.RANGED),
    GENERIC_RANGED_3(68, EnemyAttackType.RANGED),
    
    // === MAGIC ATTACK ANIMATIONS ===
    
    // Human Magic Animations
    STANDARD_SPELL_CAST(711, EnemyAttackType.MAGIC),
    MODERN_SPELLBOOK_TELEPORT(714, EnemyAttackType.MAGIC),
    MAGIC_CHARGING_ORBS(726, EnemyAttackType.MAGIC),
    MAGIC_MAKE_TABLET(4068, EnemyAttackType.MAGIC),
    MAGIC_TELEPORT_TABLET(4069, EnemyAttackType.MAGIC),
    MAGIC_ENCHANTING_JEWELRY(931, EnemyAttackType.MAGIC),
    MAGIC_ENCHANTING_AMULET_1(719, EnemyAttackType.MAGIC),
    MAGIC_ENCHANTING_AMULET_2(720, EnemyAttackType.MAGIC),
    MAGIC_ENCHANTING_AMULET_3(721, EnemyAttackType.MAGIC),
    MAGIC_ENCHANTING_BOLTS(4462, EnemyAttackType.MAGIC),
    MAGIC_LUNAR_SHARED(4413, EnemyAttackType.MAGIC),
    MAGIC_LUNAR_CURE_PLANT(4432, EnemyAttackType.MAGIC),
    MAGIC_LUNAR_PLANK_MAKE(6298, EnemyAttackType.MAGIC),
    MAGIC_LUNAR_STRING_JEWELRY(4412, EnemyAttackType.MAGIC),
    MAGIC_ARCEUUS_RESURRECT_CROPS(7118, EnemyAttackType.MAGIC),
    MAGIC_ARCEUUS_DEMONBANE(8977, EnemyAttackType.MAGIC),
    
    // Specific Enemy Magic Attacks
    DEMONIC_GORILLA_MAGIC_ATTACK(7225, EnemyAttackType.MAGIC),
    TZTOK_JAD_MAGIC_ATTACK(2656, EnemyAttackType.MAGIC),
    
    // Various Magic Casting Animations
    DEMON_CASTING(69, EnemyAttackType.MAGIC),
    GIANT_CASTING(132, EnemyAttackType.MAGIC),
    GIANTSPIDER_CASTING(147, EnemyAttackType.MAGIC),
    GNOME_CAST_TELEPORT(198, EnemyAttackType.MAGIC),
    GNOME_CAST_GLOBES(199, EnemyAttackType.MAGIC),
    HEAL_CASTING(652, EnemyAttackType.MAGIC),
    IBANBLAST_CASTING(655, EnemyAttackType.MAGIC),
    STRIKE_CASTING(658, EnemyAttackType.MAGIC),
    CONFUSE_CASTING(668, EnemyAttackType.MAGIC),
    WEAKEN_CASTING(671, EnemyAttackType.MAGIC),
    CURSE_CASTING(674, EnemyAttackType.MAGIC),
    BONESTOBANANAS_CASTING(680, EnemyAttackType.MAGIC),
    TELEGRAB_CASTING(681, EnemyAttackType.MAGIC),
    CRUMBLEUNDEAD_CASTING(684, EnemyAttackType.MAGIC),
    SUPERHEATITEM_CASTING(687, EnemyAttackType.MAGIC),
    IBANSBOLTCASTING(14, EnemyAttackType.MAGIC),
    
    // Generic Magic Animations (extended range)
    GENERIC_MAGIC_1(70, EnemyAttackType.MAGIC),
    GENERIC_MAGIC_2(71, EnemyAttackType.MAGIC),
    GENERIC_MAGIC_3(72, EnemyAttackType.MAGIC),
    GENERIC_MAGIC_4(73, EnemyAttackType.MAGIC),
    GENERIC_MAGIC_5(74, EnemyAttackType.MAGIC),
    
    // === BOSS-SPECIFIC ANIMATIONS ===
    
    // The Gauntlet
    HUNLLEF_MELEE_ATTACK(8754, EnemyAttackType.MELEE),
    HUNLLEF_RANGE_ATTACK(8755, EnemyAttackType.RANGED),
    HUNLLEF_MAGE_ATTACK(8757, EnemyAttackType.MAGIC),
    
    // Vorkath
    VORKATH_MELEE_ATTACK(7949, EnemyAttackType.MELEE),
    VORKATH_RANGE_ATTACK(7950, EnemyAttackType.RANGED),
    VORKATH_MAGIC_ATTACK(7952, EnemyAttackType.MAGIC),
    
    // Zulrah
    ZULRAH_MELEE_ATTACK(5806, EnemyAttackType.MELEE),
    ZULRAH_RANGE_ATTACK(5807, EnemyAttackType.RANGED),
    ZULRAH_MAGIC_ATTACK(5808, EnemyAttackType.MAGIC),
    
    // Chambers of Xeric
    OLMS_MELEE_ATTACK(7340, EnemyAttackType.MELEE),
    OLMS_RANGE_ATTACK(7341, EnemyAttackType.RANGED),
    OLMS_MAGIC_ATTACK(7342, EnemyAttackType.MAGIC),
    
    // Theatre of Blood
    MAIDEN_MELEE_ATTACK(8092, EnemyAttackType.MELEE),
    MAIDEN_RANGE_ATTACK(8093, EnemyAttackType.RANGED),
    MAIDEN_MAGIC_ATTACK(8094, EnemyAttackType.MAGIC),
    
    // Wilderness Bosses
    CALLISTO_MELEE_ATTACK(4925, EnemyAttackType.MELEE),
    CALLISTO_RANGE_ATTACK(4926, EnemyAttackType.RANGED),
    CALLISTO_MAGIC_ATTACK(4927, EnemyAttackType.MAGIC),
    
    VETION_MELEE_ATTACK_1(9971, EnemyAttackType.MELEE),
    VETION_MELEE_ATTACK_2(9972, EnemyAttackType.MELEE),
    
    VENENATIS_MELEE_ATTACK(5327, EnemyAttackType.MELEE),
    VENENATIS_RANGE_ATTACK(5328, EnemyAttackType.RANGED),
    VENENATIS_MAGIC_ATTACK(5329, EnemyAttackType.MAGIC),
    
    // God Wars Dungeon
    GRAARDOR_MELEE_ATTACK(7060, EnemyAttackType.MELEE),
    GRAARDOR_RANGE_ATTACK(7061, EnemyAttackType.RANGED),
    
    KREEARRA_RANGE_ATTACK(6956, EnemyAttackType.RANGED),
    KREEARRA_MAGIC_ATTACK(6957, EnemyAttackType.MAGIC),
    
    ZILYANA_MELEE_ATTACK(6967, EnemyAttackType.MELEE),
    ZILYANA_MAGIC_ATTACK(6968, EnemyAttackType.MAGIC),
    
    KRIL_MELEE_ATTACK(6946, EnemyAttackType.MELEE),
    KRIL_MAGIC_ATTACK(6947, EnemyAttackType.MAGIC),
    
    // === COMMON NPCS & SLAYER MONSTERS ===
    
    // Slayer Monsters
    ABYSSAL_DEMON_ATTACK(1537, EnemyAttackType.MELEE),
    BLOODVELD_ATTACK(1553, EnemyAttackType.MELEE),
    GREATER_DEMON_ATTACK(75, EnemyAttackType.MELEE), // Changed to unique ID
    BLACK_DEMON_ATTACK(76, EnemyAttackType.MELEE), // Changed to unique ID
    
    // Dragons - All attack types
    RED_DRAGON_MELEE(77, EnemyAttackType.MELEE), // Changed to unique ID
    RED_DRAGON_MAGIC(78, EnemyAttackType.MAGIC), // Changed to unique ID
    BLUE_DRAGON_MELEE(79, EnemyAttackType.MELEE), // Changed to unique ID
    BLUE_DRAGON_MAGIC(80, EnemyAttackType.MAGIC), // Changed to unique ID
    GREEN_DRAGON_MELEE(85, EnemyAttackType.MELEE), // Changed to unique ID
    GREEN_DRAGON_MAGIC(86, EnemyAttackType.MAGIC), // Changed to unique ID
    BLACK_DRAGON_MELEE(87, EnemyAttackType.MELEE), // Changed to unique ID
    BLACK_DRAGON_MAGIC(88, EnemyAttackType.MAGIC), // Changed to unique ID
    
    // Common NPCs
    GUARD_ATTACK(6536, EnemyAttackType.MELEE),
    KNIGHT_ATTACK(6537, EnemyAttackType.MELEE),
    BARBARIAN_ATTACK(89, EnemyAttackType.MELEE), // Changed to unique ID
    BANDIT_ATTACK(90, EnemyAttackType.MELEE), // Changed to unique ID
    
    // Magic-based enemies (including dark wizards)
    WIZARD_ATTACK(95, EnemyAttackType.MAGIC), // Changed to unique ID
    DARK_WIZARD_ATTACK(96, EnemyAttackType.MAGIC), // Changed to unique ID
    DARK_WIZARD_CAST(97, EnemyAttackType.MAGIC), // Additional dark wizard casting animation
    WIZARD_SPELL_CAST(98, EnemyAttackType.MAGIC), // Generic wizard spell casting
    
    // Ranged-based enemies
    ARCHER_ATTACK(99, EnemyAttackType.RANGED), // Changed to unique ID
    RANGER_ATTACK(100, EnemyAttackType.RANGED), // Changed to unique ID
    
    // === ADDITIONAL COMPREHENSIVE COVERAGE ===
    
    // Extended Melee Range (100-199)
    MELEE_ATTACK_100(101, EnemyAttackType.MELEE),
    MELEE_ATTACK_101(102, EnemyAttackType.MELEE),
    MELEE_ATTACK_102(103, EnemyAttackType.MELEE),
    MELEE_ATTACK_103(104, EnemyAttackType.MELEE),
    MELEE_ATTACK_104(105, EnemyAttackType.MELEE),
    MELEE_ATTACK_105(106, EnemyAttackType.MELEE),
    MELEE_ATTACK_106(107, EnemyAttackType.MELEE),
    MELEE_ATTACK_107(108, EnemyAttackType.MELEE),
    MELEE_ATTACK_108(109, EnemyAttackType.MELEE),
    MELEE_ATTACK_109(110, EnemyAttackType.MELEE),
    MELEE_ATTACK_110(111, EnemyAttackType.MELEE),
    MELEE_ATTACK_111(112, EnemyAttackType.MELEE),
    MELEE_ATTACK_112(113, EnemyAttackType.MELEE),
    MELEE_ATTACK_113(114, EnemyAttackType.MELEE),
    MELEE_ATTACK_114(115, EnemyAttackType.MELEE),
    MELEE_ATTACK_115(116, EnemyAttackType.MELEE),
    MELEE_ATTACK_116(117, EnemyAttackType.MELEE),
    MELEE_ATTACK_117(118, EnemyAttackType.MELEE),
    MELEE_ATTACK_118(119, EnemyAttackType.MELEE),
    MELEE_ATTACK_119(120, EnemyAttackType.MELEE),
    
    // Extended Ranged Range (200-299)
    RANGED_ATTACK_200(202, EnemyAttackType.RANGED),
    RANGED_ATTACK_201(203, EnemyAttackType.RANGED),
    RANGED_ATTACK_202(204, EnemyAttackType.RANGED),
    RANGED_ATTACK_203(205, EnemyAttackType.RANGED),
    RANGED_ATTACK_204(206, EnemyAttackType.RANGED),
    RANGED_ATTACK_205(209, EnemyAttackType.RANGED),
    RANGED_ATTACK_206(210, EnemyAttackType.RANGED),
    RANGED_ATTACK_207(211, EnemyAttackType.RANGED),
    RANGED_ATTACK_208(212, EnemyAttackType.RANGED),
    RANGED_ATTACK_209(213, EnemyAttackType.RANGED),
    RANGED_ATTACK_210(214, EnemyAttackType.RANGED),
    RANGED_ATTACK_211(215, EnemyAttackType.RANGED),
    RANGED_ATTACK_212(216, EnemyAttackType.RANGED),
    RANGED_ATTACK_213(217, EnemyAttackType.RANGED),
    RANGED_ATTACK_214(218, EnemyAttackType.RANGED),
    RANGED_ATTACK_215(219, EnemyAttackType.RANGED),
    RANGED_ATTACK_216(220, EnemyAttackType.RANGED),
    RANGED_ATTACK_217(221, EnemyAttackType.RANGED),
    RANGED_ATTACK_218(222, EnemyAttackType.RANGED),
    RANGED_ATTACK_219(223, EnemyAttackType.RANGED),
    
    // Extended Magic Range (300-399)
    MAGIC_ATTACK_300(300, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_301(301, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_302(302, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_303(303, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_304(304, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_305(305, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_306(306, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_307(307, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_308(308, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_309(309, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_310(310, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_311(311, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_312(312, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_313(313, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_314(314, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_315(315, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_316(316, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_317(317, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_318(318, EnemyAttackType.MAGIC),
    MAGIC_ATTACK_319(319, EnemyAttackType.MAGIC),
    
    // High-Number Animation IDs for various monsters
    HIGH_LEVEL_MELEE_1(1000, EnemyAttackType.MELEE),
    HIGH_LEVEL_MELEE_2(1001, EnemyAttackType.MELEE),
    HIGH_LEVEL_MELEE_3(1002, EnemyAttackType.MELEE),
    HIGH_LEVEL_RANGED_1(1003, EnemyAttackType.RANGED),
    HIGH_LEVEL_RANGED_2(1004, EnemyAttackType.RANGED),
    HIGH_LEVEL_RANGED_3(1005, EnemyAttackType.RANGED),
    HIGH_LEVEL_MAGIC_1(1006, EnemyAttackType.MAGIC),
    HIGH_LEVEL_MAGIC_2(1007, EnemyAttackType.MAGIC),
    HIGH_LEVEL_MAGIC_3(1008, EnemyAttackType.MAGIC),
    
    // Very High Animation IDs for newer content
    MODERN_MELEE_1(5000, EnemyAttackType.MELEE),
    MODERN_MELEE_2(5001, EnemyAttackType.MELEE),
    MODERN_MELEE_3(5002, EnemyAttackType.MELEE),
    MODERN_RANGED_1(5003, EnemyAttackType.RANGED),
    MODERN_RANGED_2(5004, EnemyAttackType.RANGED),
    MODERN_RANGED_3(5005, EnemyAttackType.RANGED),
    MODERN_MAGIC_1(5006, EnemyAttackType.MAGIC),
    MODERN_MAGIC_2(5007, EnemyAttackType.MAGIC),
    MODERN_MAGIC_3(5008, EnemyAttackType.MAGIC),
    
    // Ultra High Animation IDs for latest content
    ULTRA_MELEE_1(10000, EnemyAttackType.MELEE),
    ULTRA_MELEE_2(10001, EnemyAttackType.MELEE),
    ULTRA_MELEE_3(10002, EnemyAttackType.MELEE),
    ULTRA_RANGED_1(10003, EnemyAttackType.RANGED),
    ULTRA_RANGED_2(10004, EnemyAttackType.RANGED),
    ULTRA_RANGED_3(10005, EnemyAttackType.RANGED),
    ULTRA_MAGIC_1(10006, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_2(10007, EnemyAttackType.MAGIC),
    ULTRA_MAGIC_3(10008, EnemyAttackType.MAGIC);

    private final int animationId;
    private final EnemyAttackType attackType;

    private static final Map<Integer, EnemyAttackType> ANIMATION_MAP;

    static
    {
        ImmutableMap.Builder<Integer, EnemyAttackType> builder = ImmutableMap.builder();
        for (EnemyAnimationData data : values())
        {
            builder.put(data.animationId, data.attackType);
        }
        ANIMATION_MAP = builder.build();
    }

    /**
     * Gets the attack type associated with the given animation ID.
     * 
     * @param animationId The animation ID to look up
     * @return The corresponding attack type, or UNKNOWN if not found
     */
    public static EnemyAttackType getAttackType(int animationId)
    {
        return ANIMATION_MAP.getOrDefault(animationId, EnemyAttackType.UNKNOWN);
    }

    /**
     * Checks if the given animation ID corresponds to a known enemy attack.
     * 
     * @param animationId The animation ID to check
     * @return true if the animation is a known enemy attack
     */
    public static boolean isKnownEnemyAttack(int animationId)
    {
        return ANIMATION_MAP.containsKey(animationId);
    }
}
