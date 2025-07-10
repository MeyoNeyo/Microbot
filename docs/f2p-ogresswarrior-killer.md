# F2P Ogress Warrior Killer Plugin

## Overview
This document describes the full requirements and logic for an automated Old School RuneScape (OSRS) plugin that kills Ogress Warriors in F2P areas, specifically in the Corsair Cove Dungeon. The plugin is designed to maximize efficiency, safety, and profit by automating combat, looting, banking, and inventory management. This specification is intended for use by an AI coding assistant or developer to implement the plugin.

---

## Core Workflow

1. **Inventory & Banking Preparation**
   - On script start or after banking, check if the player has:
     - The required amount and type of food (user-configurable).
     - Sufficient free inventory slots (user-configurable minimum).
     - Required runes for High Alchemy (if High Alch is enabled).
   - If any requirement is not met:
     - Walk to Corsair Cove bank.
     - Withdraw the required food and runes (if High Alch is enabled).
     - If there are no runes for High Alch, log a warning but continue.
     - If there is no food in bank or inventory, stop the script at the bank and log a message.

2. **Walking to Safespot**
   - If inventory is correctly set up, walk to the predefined safespot in Corsair Cove Dungeon.

3. **Aggro Timer Handling**
   - If "Wait for Aggro Timer" is enabled:
     - Wait at the safespot for the user-defined aggro timer duration (default: 10 minutes).
     - This wait is only required once after each banking trip.
   - If not enabled, proceed immediately to combat.

4. **Combat Loop**
   - Remain within the defined Ogress Warrior area to avoid re-aggro.
   - Only attack Ogress Warriors (never Ogress Shamans or other monsters).
   - After each kill:
     - Loot all high alchable drops from the Ogress Warrior (if high alchemy is enabled, and then also use the spell high alchemy on the items to turn them into coins).
     - If toggled, loot runes and/or coins (only coins dropped by the player’s kills, not world-spawned or other players’ drops).
     - If toggled, loot bread (max 4) from the ground and eat it if needed to extend time before banking.
     - If toggled, loot all gems or only high-value gems (ruby/diamond) or no gem looting if not toggled.
   - Repeat fighting and looting until:
     - The player runs out of food in inventory, **or**
     - The number of free inventory slots is less than the user-defined minimum.

5. **Return to Bank**
   - If out of food or not enough free inventory slots, walk to Corsair Cove bank.
   - Deposit loot, withdraw food and runes as needed, and repeat the process.
   - If no food is available in bank or inventory, stop the script and log a message.

6. **Health & Safety Monitoring**
   - While in combat, continuously monitor the player’s health in a separate thread or loop.
   - If health drops below the user-defined percentage threshold, eat food.
   - If no food is available, immediately walk to the bank.

7. **Combat Target Validation**
   - Ensure the player is always fighting an Ogress Warrior.
   - If the player is in combat with another monster:
     - Walk to the safespot and wait for a short duration (e.g., 10 seconds).
     - Attempt to attack the nearest Ogress Warrior again.

---

## User-Configurable Options
- **Food Type & Amount:** Name and quantity of food to withdraw from bank.
- **Minimum Free Inventory Slots:** Number of free slots required before banking.
- **Aggro Timer:** Enable/disable and set duration for aggro wait.
- **High Alchemy:** Enable/disable, and set which items to alch.
- **Loot Bread:** Enable/disable looting and eating bread from ground.
- **Loot Gems:** Option to loot all gems or only ruby/diamond.
- **Loot Coins:** Enable/disable, only loot coins from player’s own kills.
- **Health Threshold:** Percentage of health at which to eat food.

---

## Overlay & Status Display
- **Current Status:** Shows what the script is doing (e.g., "Banking", "Waiting for Aggro", "Fighting", "Looting").
- **Aggro Timer:** Countdown or timer for aggression status.
- **Loot Tracker:** List of items picked up and their quantities (bread, coins, gems, etc.).
- **Warnings:** Display/log messages for missing food, runes, or other issues.

---

## Additional Notes
- The plugin must never attack Ogress Shamans or any monster other than Ogress Warriors.
- Looting coins must be restricted to coins dropped by the player’s own kills.
- The plugin should always remain within the defined Ogress Warrior area to avoid unnecessary aggro resets.
- All user-configurable options should be accessible via the plugin’s settings panel.
- The script must stop safely at the bank if food is depleted.

---

## Summary
This document provides a complete, step-by-step specification for an F2P Ogress Warrior Killer plugin for OSRS. It covers all logic, user options, and safety checks required for a robust, efficient, and user-friendly automation script. No code is included; this is a requirements and behavior specification for implementation by an AI or developer.
