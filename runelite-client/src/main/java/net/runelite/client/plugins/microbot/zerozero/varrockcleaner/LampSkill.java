package net.runelite.client.plugins.microbot.zerozero.varrockcleaner;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.globval.WidgetIndices;

/**
 * Enum representing skills that can be used with antique lamps
 * Each skill maps to its corresponding widget child ID in the lamp interface
 */
public enum LampSkill {
    ATTACK("Attack", Skill.ATTACK, WidgetIndices.GenieLampWindow.ATTACK_DYNAMIC_CONTAINER),
    STRENGTH("Strength", Skill.STRENGTH, WidgetIndices.GenieLampWindow.STRENGHT_DYNAMIC_CONTAINER),
    DEFENCE("Defence", Skill.DEFENCE, WidgetIndices.GenieLampWindow.DEFENSE_DYNAMIC_CONTAINER),
    RANGED("Ranged", Skill.RANGED, WidgetIndices.GenieLampWindow.RANGED_DYNAMIC_CONTAINER),
    PRAYER("Prayer", Skill.PRAYER, WidgetIndices.GenieLampWindow.PRAYER_DYNAMIC_CONTAINER),
    MAGIC("Magic", Skill.MAGIC, WidgetIndices.GenieLampWindow.MAGIC_DYNAMIC_CONTAINER),
    RUNECRAFT("Runecraft", Skill.RUNECRAFT, WidgetIndices.GenieLampWindow.RUNECRAFTING_DYNAMIC_CONTAINER),
    CONSTRUCTION("Construction", Skill.CONSTRUCTION, WidgetIndices.GenieLampWindow.CONSTRUCTION_DYNAMIC_CONTAINER),
    HITPOINTS("Hitpoints", Skill.HITPOINTS, WidgetIndices.GenieLampWindow.HITPOINTS_DYNAMIC_CONTAINER),
    AGILITY("Agility", Skill.AGILITY, WidgetIndices.GenieLampWindow.AGILITY_DYNAMIC_CONTAINER),
    HERBLORE("Herblore", Skill.HERBLORE, WidgetIndices.GenieLampWindow.HERBOLORE_DYNAMIC_CONTAINER),
    THIEVING("Thieving", Skill.THIEVING, WidgetIndices.GenieLampWindow.THIEVING_DYNAMIC_CONTAINER),
    CRAFTING("Crafting", Skill.CRAFTING, WidgetIndices.GenieLampWindow.CRAFTING_DYNAMIC_CONTAINER),
    FLETCHING("Fletching", Skill.FLETCHING, WidgetIndices.GenieLampWindow.FLETCHING_DYNAMIC_CONTAINER),
    SLAYER("Slayer", Skill.SLAYER, WidgetIndices.GenieLampWindow.SLAYER_DYNAMIC_CONTAINER),
    HUNTER("Hunter", Skill.HUNTER, WidgetIndices.GenieLampWindow.HUNTER_DYNAMIC_CONTAINER),
    MINING("Mining", Skill.MINING, WidgetIndices.GenieLampWindow.MINING_DYNAMIC_CONTAINER),
    SMITHING("Smithing", Skill.SMITHING, WidgetIndices.GenieLampWindow.SMITHING_DYNAMIC_CONTAINER),
    FISHING("Fishing", Skill.FISHING, WidgetIndices.GenieLampWindow.FISHING_DYNAMIC_CONTAINER),
    COOKING("Cooking", Skill.COOKING, WidgetIndices.GenieLampWindow.COOKING_DYNAMIC_CONTAINER),
    FIREMAKING("Firemaking", Skill.FIREMAKING, WidgetIndices.GenieLampWindow.FIREMAKING_DYNAMIC_CONTAINER),
    WOODCUTTING("Woodcutting", Skill.WOODCUTTING, WidgetIndices.GenieLampWindow.WOODCUTTING_DYNAMIC_CONTAINER),
    FARMING("Farming", Skill.FARMING, WidgetIndices.GenieLampWindow.FARMING_DYNAMIC_CONTAINER);

    private final String displayName;
    private final Skill skill;
    private final int widgetChildId;

    LampSkill(String displayName, Skill skill, int widgetChildId) {
        this.displayName = displayName;
        this.skill = skill;
        this.widgetChildId = widgetChildId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Skill getSkill() {
        return skill;
    }

    public int getWidgetChildId() {
        return widgetChildId;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
