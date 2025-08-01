package net.runelite.client.plugins.microbot.zerozero.zeroprayer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("zprayerhelper")
public interface AttackTimerMetronomeConfig extends Config
{
	@Getter
	@RequiredArgsConstructor
	enum PrayerMode {
		NONE("None"),           // New option
		LAZY("Lazy Flick"),
		NORMAL("Normal");

		private final String description;
	}


	@ConfigSection(
			name = "Prayer Settings",
			description = "Settings",
			position = 1
	)
	String TickNumberSettings = "Attack Cooldown Tick Settings";

	@ConfigItem(
			position = 1,
			keyName = "enableLazyFlicking",
			name = "Enable Offensive Prayers",
			description = "Toggle the lazy flicking of offensive prayers based on attack style",
			section = TickNumberSettings

	)
	default PrayerMode enableLazyFlicking()
	{
		return PrayerMode.LAZY;
	}

	@ConfigItem(
			position = 2,
			keyName = "showTick",
			name = "Show Attack Cooldown Ticks",
			description = "Shows number of ticks until next attack",
			section = TickNumberSettings
	)
	default boolean showTick()
	{
		return true;
	}

	@ConfigSection(
			name = "Defensive Prayer Settings",
			description = "Settings for automatic defensive prayers",
			position = 2
	)
	String DefensivePrayerSettings = "Defensive Prayer Settings";

	@ConfigItem(
			position = 3,
			keyName = "enableDefensivePrayers",
			name = "Enable Defensive Prayers",
			description = "Automatically activate defensive prayers based on enemy attacks",
			section = DefensivePrayerSettings
	)
	default boolean enableDefensivePrayers()
	{
		return false;
	}

	@ConfigItem(
			position = 4,
			keyName = "enablePvpMode",
			name = "Enable PvP Mode",
			description = "Enhanced defensive prayer switching for PvP combat",
			section = DefensivePrayerSettings
	)
	default boolean enablePvpMode()
	{
		return false;
	}

	@ConfigItem(
			position = 5,
			keyName = "enablePredictiveDefense",
			name = "Enable Predictive Defense",
			description = "Use pattern analysis to predict enemy attacks (experimental)",
			section = DefensivePrayerSettings
	)
	default boolean enablePredictiveDefense()
	{
		return false;
	}

	@ConfigItem(
			position = 6,
			keyName = "defensivePrayerDelay",
			name = "Defensive Prayer Delay (ticks)",
			description = "Number of ticks to keep defensive prayer active after attack",
			section = DefensivePrayerSettings
	)
	default int defensivePrayerDelay()
	{
		return 3;
	}

}
