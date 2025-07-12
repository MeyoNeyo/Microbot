# 🍷 Wine of Zamorak Plugin

## Overview
**✅ 2nd Floor Only – With World Hopping Optimization**

This plugin automates the collection of Wine of Zamorak from the Chaos Temple in Asgarnia. The plugin focuses on the 2nd floor wine spawn with intelligent world hopping to maximize efficiency while avoiding detection.

## 🧠 Final Step-by-Step Logic

### 1. Initial Precondition Checks
The plugin will only run if **ALL** of the following conditions are met:

- ✅ Player is logged in and idle
- ✅ Not in combat
- ✅ Total level ≥ 500
- ✅ Wearing Zamorak monk robes (top & bottom)
- ✅ Inventory contains:
  - At least 1 Law rune
  - At least 1 Air rune (or Air staff equipped)

### 2. Travel to Wine Spot
If not already at the 2nd floor wine table:

1. Walk or teleport to Chaos Temple (Asgarnia)
2. Enter the temple
3. Climb ladder to 2nd floor
4. Walk to wine spawn tile

### 3. Telegrab + World Hop Loop
Repeat until inventory is full:

1. **Wait for Wine to spawn**
2. **Cast Telekinetic Grab on wine**
3. **Wait for item to appear in inventory**
4. **Hop to next world:**
   - Skip PvP worlds, high-risk worlds, deadman worlds, etc.
   - Prioritize worlds with lowest ping (optional but preferred)
   - Avoid recently visited worlds (RS anti-hop cooldown: 30s per world)
   - After hop completes, wait for the new world to fully load
   - Check that you're still at wine table (recover if path was interrupted)

### 4. Banking Logic
If inventory is full:

1. **Exit the Chaos Temple**
2. **Teleport or Walk to Bank:**
   - If Falador teleport runes exist: Cast Teleport to Falador
   - Else: Walk to Falador West Bank
3. **Bank all Wines of Zamorak**
4. **Confirm inventory still has:**
   - Required runes
   - Sufficient space
5. **Repeat from Step 2**

## 🔄 World Hopping Details

| Requirement | Rule |
|-------------|------|
| **Hop method** | Use Microbot API or RuneLite API's World Hopper |
| **Avoid worlds** | PvP, Deadman, High Risk, Skill Total restrictions |
| **Cooldown-aware** | Keep a recent world history to avoid re-hopping too fast |
| **Sort worlds** | Prefer worlds with lowest ping if accessible |
| **World loading** | Wait for "fully logged in and idle" before resuming actions |

## 🟡 Optional Improvements for Later

- Add anti-ban logic (small idle times, camera wiggle)
- Track number of wines collected per hour
- Add simple on-screen overlay (Inventory Count, World Count, etc.)

## ⚠️ Fail Safes

| Situation | Action |
|-----------|--------|
| **Ran out of runes** | Stop plugin + notify user |
| **Not wearing Zamorak robes** | Stop plugin + notify user |
| **Total level dropped < 500** | Stop plugin + notify user |
| **World hop failed** | Retry or hop to fallback world |
| **Player moved** | Walk back to wine tile or reset script |

## 📋 Requirements Summary

### Equipment Required:
- Zamorak monk top
- Zamorak monk bottom
- Air staff (optional, saves inventory space)

### Inventory Required:
- Law runes (for Telekinetic Grab)
- Air runes (if not using Air staff)
- Empty inventory slots for wines

### Account Requirements:
- Total level ≥ 500
- Magic level sufficient for Telekinetic Grab spell
- Access to Chaos Temple

## 🎯 Expected Behavior

1. **Startup**: Plugin validates all preconditions before starting
2. **Collection**: Efficiently collects wines with minimal downtime
3. **World Management**: Smart world hopping to avoid crowded areas
4. **Banking**: Automated banking when inventory is full
5. **Safety**: Stops immediately if any requirement is not met

## 🔧 Configuration Options

- **Max wines per trip**: Default 28 (full inventory)
- **World hop delay**: Default 2-5 seconds between hops
- **Banking method**: Falador teleport (preferred) or walking
- **Anti-ban features**: Configurable random delays and camera movements

---

*This plugin is designed for educational purposes and should be used responsibly in accordance with game rules and regulations.*
