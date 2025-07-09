# F2P Ogress Warrior Killer Plugin

## Description

This plugin automates the process of killing Ogress Warriors in F2P areas. Its main features include:

- Walking to a user-defined location and waiting for the aggression timer to expire (default: 10 minutes, toggleable).
- After the aggression timer, the player will only attack Ogress Warriors within a specified area, avoiding Ogress Shamans.
- Option to use High Alchemy on profitable items (toggleable).
- Option to bank for food when needed. The type of food and the amount to withdraw are user-configurable (textbox for food name, number box for amount).
- Option to set the minimum number of free inventory slots before banking.
- **NEW:** Toggle option to loot bread that spawns on the ground and eat it if needed, to extend time before banking.
- **NEW:** Option to loot all gems or only high-value gems (ruby/diamond).
- **NEW:** Toggle option to loot coins, but only those dropped by Ogress Warriors you kill (not naturally spawned coins).

## Features

- **Aggro Timer Wait:** Walk to location and wait for aggro timer (toggleable, default 10 minutes).
- **Target Selection:** Only attack Ogress Warriors, avoid Ogress Shamans.
- **High Alch:** Optionally cast High Alchemy on profitable items.
- **Banking & Food:** Option to bank for food; user sets food type and amount.
- **Inventory Management:** Option to set minimum free inventory slots before banking.
- **Loot Bread:** Toggle to loot and eat bread from ground drops to stay longer without banking.
- **Loot Gems:** Option to loot all gems or only ruby/diamond for profit.
- **Loot Coins:** Toggle to loot coins dropped by killed Ogress Warriors only.
- **Overlay:** Displays current status, aggression timer, and a loot tracker of items picked up and their quantities.

## Overlay Design

The plugin will include an on-screen overlay with the following elements:

- **Current Status:** Shows what the script is currently doing (e.g., "Waiting for aggro timer", "Fighting Ogress Warriors", "Banking", "Looting").
- **Aggression Timer:** Displays a countdown or timer for when Ogress Warriors will stop being aggressive.
- **Loot Tracker:** Lists items picked up during the session and their quantities (e.g., bread, coins, gems, etc.).

### Example Overlay Code Snippet
```java
@Override
public void render(Graphics2D graphics) {
    int y = 20;
    graphics.drawString("Status: " + plugin.getCurrentStatus(), 10, y);
    y += 15;
    graphics.drawString("Aggro Timer: " + plugin.getAggroTimerString(), 10, y);
    y += 15;
    graphics.drawString("Loot Tracker:", 10, y);
    y += 15;
    for (Map.Entry<String, Integer> entry : plugin.getLootTracker().entrySet()) {
        graphics.drawString(entry.getKey() + ": " + entry.getValue(), 20, y);
        y += 15;
    }
}
```

## Code Examples

### Looting and Eating Bread
```java
if (config.lootBread() && Rs2GroundItem.hasItem("Bread")) {
    Rs2GroundItem.pickup("Bread");
    if (Rs2Inventory.hasItem("Bread") && Rs2Player.needsHealing()) {
        Rs2Inventory.interact("Bread", "Eat");
    }
}
```

### Looting Gems (All or Only High-Value)
```java
String[] highValueGems = {"Ruby", "Diamond"};
if (config.lootAllGems()) {
    for (String gem : allGemNames) {
        if (Rs2GroundItem.hasItem(gem)) Rs2GroundItem.pickup(gem);
    }
} else if (config.lootHighValueGems()) {
    for (String gem : highValueGems) {
        if (Rs2GroundItem.hasItem(gem)) Rs2GroundItem.pickup(gem);
    }
}
```

### Looting Coins Dropped by Killed Ogress Warriors Only
```java
if (config.lootCoins()) {
    for (GroundItem coin : Rs2GroundItem.getAll("Coins")) {
        if (coin.getOwner().equals(Microbot.getClient().getLocalPlayer().getName())) {
            Rs2GroundItem.pickup(coin);
        }
    }
}
```
// Only loot coins where the owner matches the player (i.e., coins dropped by your kills, not world spawns).

## Antiban Settings (from WildernessRuniteMiningScript)

The following antiban configuration is recommended for human-like behavior and is adapted directly from the `WildernessRuniteMiningScript`:

```java
// Antiban configuration (same as WildernessRuniteMiningScript)
Rs2Antiban.resetAntibanSettings();
Rs2AntibanSettings.antibanEnabled = true;
Rs2AntibanSettings.usePlayStyle = true;
Rs2AntibanSettings.randomIntervals = false;
Rs2AntibanSettings.simulateFatigue = false;
Rs2AntibanSettings.simulateAttentionSpan = false;
Rs2AntibanSettings.behavioralVariability = true;
Rs2AntibanSettings.naturalMouse = true;
Rs2AntibanSettings.takeMicroBreaks = false;
Rs2AntibanSettings.microBreakChance = 0.01;
Rs2AntibanSettings.actionCooldownChance = 0.1;

// Set activity and intensity (adjust as needed for combat)
Rs2Antiban.setActivity(Activity.GENERAL_COMBAT);
Rs2Antiban.setActivityIntensity(ActivityIntensity.EXTREME);
```

- Place this snippet in your plugin's initialization or setup logic.
- Adjust `Activity` and `ActivityIntensity` as appropriate for Ogress Warrior combat.
- This setup ensures consistent, human-like antiban behavior matching the mining script.

## Research & References

- Use toggle options in the config for bread, gem, and coin looting.
- Use Rs2GroundItem for ground item detection and pickup.
- Use Rs2Inventory and Rs2Player for eating logic and health checks.
- See `BlueDragonsScript`, `PVirewatchKillerPlugin`, and `PAlcher` for similar implementations.

*To be expanded with more code and research as needed.*
