# To Be Implemented

- **Safespot Manual Coordinate Textbox:**
  - Add a textbox in the plugin config settings to allow users to manually enter safespot coordinates (similar to the manual center tile/area input).
  - This will let users specify the safespot location directly by entering X, Y, and plane values.
  - Not yet implemented as of July 2025.

- **Magic Rune Check:**
  - Before attacking with magic, the script should check if the player has enough runes to cast the selected spell (using `Rs2Magic.canCast` or similar).
  - If the player runs out of runes, the script should stop attacking with magic or switch to another combat style, or notify the user.
  - This feature is not yet implemented as of July 2025.

- **Configurable Auto-Eat Percentage:**
  - Add a setting in the plugin menu to let the user specify at what health percentage the player should eat food (e.g., 40%, 60%, etc.).
  - or add like scurrus plugin 2 boxes for min and max health percentage to eat food. or just 1 box for min health percentage. but this needs to be analyzed to see what is the best practice.
  - Implement logic so the script automatically eats food when the player's health drops below this percentage.
  - The setting should be an integer input box in the menu for easy adjustment.
  - Not yet implemented as of July 2025.

- **Grand Exchange Refill:**
  - If food, ammo, runes, or equipment are depleted (including after death), automatically buy required items from the Grand Exchange and restock the bank.

- **Total Costs of Current Run:**
  - Track and display the total costs incurred during the current session, including rune usage, arrows, and food consumed.
  - Calculate costs using GE prices for each resource type used.
  - Show total cost in the overlay alongside profit/loot value.

  **monster kill per hour:**
  - Track and display the number of monster kills per hour in the overlay.



# Implemented Features

- **Bury Bones Only Option:**
  - The script now only buries bones found on the ground, without picking them up first. The logic for picking up bones has been removed. (Implemented July 2025)

  - **Show Plugin Runtime & Reset on Shutdown:**
  - Display the elapsed time since the plugin started (e.g., 01:23:45) in the overlay or status panel.
  - Reset the timer if the plugin is turned off or shut down, so each session shows its own runtime.
  - Not yet implemented as of July 2025.

- **Show Plugin Runtime:**
  - Display how long the plugin has been running (e.g., 01:23:45) in the overlay or status panel.

# ApexFighter Plugin

## Overview
The ApexFighter plugin is a comprehensive automation tool for Old School RuneScape (OSRS) that enables automated combat, looting, banking, and safety management for fighting any NPCs specified by the user. It is highly configurable and supports a wide range of combat styles, looting options, and safety features, making it suitable for both general PvM and specialized tasks.

---

## Core Workflow

1. **Combat Preparation & Banking**
   - On script start or after banking, checks if the player has:
     - Required food (type and amount, user-configurable).
     - Potions (combat, prayer, anti-poison, anti-fire, stamina, restore, etc. as configured).
     - Teleports and other upkeep items.
     - Sufficient free inventory slots (user-configurable minimum).
   - If requirements are not met:
     - Walks to the nearest bank.
     - Withdraws required items using either a custom inventory setup or direct item withdrawal.
     - If food or other critical items are missing, stops the script and logs a warning.

2. **Walking to Combat Area**
   - Walks to a user-defined center tile (safespot or combat area) within a specified radius.
   - Ensures the player remains within the defined area to avoid unwanted aggro or wandering.

3. **Combat Loop**
   - Attacks only NPCs specified by the user (via settings panel, right-click add, or list).
   - Supports auto-attack, cannon usage, and special attack logic.
   - Monitors and flicks prayers as configured.
   - Handles food consumption, including Guthan's healing if equipped.
   - Monitors health and automatically eats food or teleports/banks if health is low.
   - Supports potion usage and re-equipping gear as needed.
   - If the player is in combat with an undesired NPC, attempts to retarget or move to safespot.

4. **Looting Logic**
   - Loots items based on user configuration:
     - By item name, GE price, or mixed mode.
     - Bones (with optional burying), ashes (with optional scattering), runes, coins, arrows, untradeables, and more.
     - Only loots items dropped by the player's own kills if configured.
     - Supports delayed looting and minimum free slot logic.
   - Tracks looted items and displays them in the overlay.

5. **Safety Monitoring**
   - Continuously checks for missing food, runes, arrows, or low health.
   - If any safety condition is triggered (e.g., missing food, low health), walks to bank and logs out or stops the script.
   - Can be configured to stop only for specific safety conditions.

6. **Banking & Inventory Management**
   - Deposits loot and withdraws required items when banking is needed.
   - Supports custom inventory setups for fast gear and item management.
   - Ensures upkeep items (food, potions, teleports) are always available.
   - If food or critical items are depleted in bank/inventory, stops script and logs a warning.

---

## User-Configurable Options
- **Combat NPC List:** Specify which NPCs to attack (by name, right-click, or list).
- **Food Type & Amount:** Name and quantity of food to withdraw and eat.
- **Potion Usage:** Enable/disable and configure types of potions to use.
- **Minimum Free Inventory Slots:** Number of free slots required before banking.
- **Looting Style:** By item name, GE price, or mixed mode.
- **Loot Bones/Ashes:** Enable/disable burying/scattering.
- **Loot Runes/Coins/Arrows:** Enable/disable and configure looting logic.
- **Loot Untradeables:** Enable/disable looting of untradeable items.
- **Safety Checks:** Enable/disable checks for missing food, runes, arrows, or low health.
- **Prayer Flicking:** Enable/disable and configure prayer styles.
- **Cannon Usage:** Enable/disable and configure cannon logic.
- **Special Attacks:** Enable/disable and configure special attack logic.
- **Inventory Setup:** Use custom inventory setups for gear and items.
- **Center Tile & Radius:** Set safespot or combat area and radius.

---

## Overlay & Status Display
- **Current Status:** Shows what the script is doing (e.g., "Banking", "Fighting", "Looting", "Walking").
- **Loot Tracker:** List of items picked up and their quantities.
- **Warnings:** Display/log messages for missing food, runes, arrows, or other issues.
- **Combat Info:** Shows current target, health, prayer status, and more.

---

## Additional Notes
- The plugin never attacks NPCs not specified by the user.
- Looting coins, runes, and other items can be restricted to player’s own kills.
- Always remains within the defined combat area to avoid unwanted aggro resets.
- All user-configurable options are accessible via the plugin’s settings panel.
- The script stops safely at the bank if food or critical items are depleted.

---

## Summary
This document provides a complete, step-by-step specification for the ApexFighter plugin for OSRS. It covers all logic, user options, and safety checks required for a robust, efficient, and user-friendly automation script. No code is included; this is a requirements and behavior specification for implementation by an AI or developer.
