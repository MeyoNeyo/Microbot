package net.runelite.client.plugins.microbot.apexfighter.combat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.item.Rs2ExplorersRing;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import net.runelite.client.plugins.microbot.apexfighter.CostTracker;

public class HighAlchScript extends Script
{

	private static final int MIN_TICKS = (int) Math.ceil(30.0 / 0.6);
	// Example: Track rune usage for High Alch
	private static final int NATURE_RUNE_ID = 561;
	private static final int FIRE_RUNE_ID = 554;
	private static final int MAX_TICKS = (int) Math.floor(45.0 / 0.6);
	private int lastAlchCheckTick = -1;
	private int nextAlchIntervalTicks = 0;


	public boolean run(ApexFighterConfig config)
	{
		mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
			try
			{
				if (!Microbot.isLoggedIn() || !super.run() || !config.toggleHighAlchProfitable())
				{
					return;
				}
				List<Rs2ItemModel> items = Rs2Inventory.getList(Rs2ItemModel::isHaProfitable);

				if (items.isEmpty())
				{
					if (Rs2Tab.getCurrentTab() != InterfaceTab.INVENTORY)
					{
					Rs2Tab.switchTo(InterfaceTab.INVENTORY);
					}
					return;
				}

				int currentTick = Microbot.getClient().getTickCount();

				if (lastAlchCheckTick != -1 && currentTick - lastAlchCheckTick < nextAlchIntervalTicks)
				{
					return;
				}

				lastAlchCheckTick = currentTick;
				nextAlchIntervalTicks = Rs2Random.nextInt(MIN_TICKS, MAX_TICKS, 1.5, true);

				if (Rs2ExplorersRing.hasRing() && Rs2ExplorersRing.hasCharges())
				{
					for (Rs2ItemModel item : items)
					{
						if (!isRunning())
						{
							break;
						}
						Rs2ExplorersRing.highAlch(item);
						trackAlchRunes();
					}
					Rs2ExplorersRing.closeInterface();
				}
				else if (Rs2Player.getSkillRequirement(Skill.MAGIC, Rs2Spells.HIGH_LEVEL_ALCHEMY.getRequiredLevel()) && Rs2Magic.hasRequiredRunes(Rs2Spells.HIGH_LEVEL_ALCHEMY))
				{
					for (Rs2ItemModel item : items)
					{
						if (!isRunning())
						{
							break;
						}
						Rs2Magic.alch(item);
						trackAlchRunes();
						if (item.getHaPrice() > Rs2Settings.getMinimumItemValueAlchemyWarning())
						{
							sleepUntil(() -> Rs2Widget.hasWidget("Proceed to cast High Alchemy on it"));
							if (Rs2Widget.hasWidget("Proceed to cast High Alchemy on it"))
							{
								Rs2Keyboard.keyPress('1');
								Rs2Player.waitForAnimation();
							}
						}
					}
				}
			}
			catch (Exception ex)
			{
				Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
			}
		}, 0, 600, TimeUnit.MILLISECONDS);
		return true;
	}


	public void shutdown()
	{
		super.shutdown();
	}

	// Call this method when a rune is consumed for alchemy
	private void trackAlchRunes() {
		net.runelite.client.plugins.microbot.apexfighter.CostTracker.getInstance().addUsage(NATURE_RUNE_ID, 1);
		// If not using a fire staff, track fire runes as well
		if (!isWearingFireStaff()) {
			net.runelite.client.plugins.microbot.apexfighter.CostTracker.getInstance().addUsage(FIRE_RUNE_ID, 5);
		}
	}

	// Checks if the player is wearing any fire staff variant
	private boolean isWearingFireStaff() {
		// Common fire staves in OSRS
		int[] fireStaffIds = {
			1387, // Staff of fire
			1393, // Mystic fire staff
			3053, // Lava battlestaff
			3054, // Mystic lava staff
			11738, // Smoke battlestaff
			11739, // Mystic smoke staff
			21003, // Steam battlestaff
			21004, // Mystic steam staff
			22294, // Staff of the dead (fire)
			22296, // Toxic staff of the dead (fire)
			22323, // Kodai wand (acts as all elemental)
			12795, // Staff of fire (or)
			12000  // Staff of fire (uncharged)
		};
		net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel weapon =
			net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment.get(net.runelite.api.EquipmentInventorySlot.WEAPON);
		if (weapon == null) return false;
		int weaponId = weapon.getId();
		for (int id : fireStaffIds) {
			if (weaponId == id) return true;
		}
		return false;
	}
}
