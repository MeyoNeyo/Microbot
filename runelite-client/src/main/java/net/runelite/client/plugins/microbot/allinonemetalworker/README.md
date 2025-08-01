# All-in-One Mining, Smelting & Smithing Plugin

## Plugin Name: **"AIO Metal Worker"**

## Overview
This plugin automates the complete metalworking process from ore to finished products in RuneScape. It handles mining ores, smelting them into bars, and smithing bars into items in a continuous loop until the desired quantities are achieved.

## Key Features

### 🔨 **Three-Phase Automation**
1. **Mining Phase**: Mines specified ores until target quantity is reached
2. **Smelting Phase**: Smelts all ores into bars at Al Kharid furnace
3. **Smithing Phase**: Smiths all bars into the highest available items at anvil

### ⚙️ **Smart Ore Management**
- **Balanced Mining**: For alloy bars (bronze, steel, etc.), mines equal quantities of required ores
- **Example**: For 560 bronze bars → mines 280 copper + 280 tin (14 copper + 14 tin per inventory)
- **Progressive Unlocking**: Automatically uses higher-tier ores as levels increase

### 🎯 **Intelligent Item Selection**
- **Dynamic Product Selection**: Automatically chooses the best smithable item based on current smithing level
- **XP Optimization**: Prioritizes items that give maximum XP per bar used
- **Level Progression**: 
  - Low levels: Daggers (1 bar each)
  - Mid levels: Scimitars, Longswords (2 bars each) 
  - High levels: Platebodies (5 bars each)

### 🏃‍♂️ **Efficient Pathing**
- **Smart Banking**: Uses Al Kharid bank for optimal furnace proximity
- **Anvil Selection**: Chooses Varrock anvil as this is closest to the bank
- **Return Navigation**: Always returns to original mining location after banking/smelting

## Configuration Options

### **Ore Selection**
- Dropdown menu with available ores:
  - Bronze (Copper + Tin) - Level 1
  - Iron - Level 15  
  - Steel (Iron + Coal) - Level 30
  - Mithril (Mithril + Coal) - Level 50
  - Adamantite (Adamantite + Coal) - Level 70
  - Runite (Runite + Coal) - Level 85

### **Quantity Settings**
- **Total Ore Target**: How many total ores to mine before smelting (e.g., 560 for bronze = 280 copper + 280 tin)
- **Batch Size**: Inventory capacity consideration (28 slots minus pickaxe or if the player cant wield the pickaxe it can be set to 26 slots so its still equal ore for bronze)

### **Location Settings**
- **Mining Location**: Auto-detect or manual selection(for now use varrock south-east mine or south-west mine or Al Kharid mine)
- **Use Banking**: always enabled so that it can do the smelting and smithing phases(not optional)
- **Return to Origin**: Always return to starting mining spot
- **mining range**: Set a range for mining distance from the player (e.g., 5 tiles)

//these are future features that will be added so dont include them in the initial release
### **Advanced Options**
- **Coal Bag Support**: Automatically use coal bag if available
- **Special Equipment**: Auto-equip Ring of Forging, Varrock Armour, etc.
- **World Hopping**: Hop if rocks are depleted or too crowded
- **Break Management**: Automatic breaks between phases

## Workflow Logic

### **Phase 1: Mining**
1. Start at chosen mining location with the correct pickaxe equipped.
   - If no pickaxe is equipped, the plugin will use the best pickaxe in the inventory or go to the nearest bank to get best one the player can wield/use based on mining and attack levels.
2. Mine ores maintaining proper ratios (e.g., 1:1 copper:tin for bronze,and 1:2 iron:coal for steel and such)
3. Bank when inventory full
4. Repeat until target ore quantity reached
5. Proceed to Phase 2

### **Phase 2: Smelting** 
1. Walk to Al Kharid bank
2. Deposit all from inventory
3. Withdraw ores in correct ratios for selected bar type
4. Use furnace to smelt all ores into bars
5. Bank bars and repeat until all ores are processed(all ores meaning the amount of ores set in the configuration)
   - Example: For 560 bronze bars, smelt 280 copper + 280 tin
6. Proceed to Phase 3

### **Phase 3: Smithing**
1. Withdraw bars and hammer from bank at varrock east bank
2. Find nearest anvil across the street
3. Smith bars into highest available item:
   - Check smithing level requirements
   - Choose item with best bar-to-XP ratio
   - Smith until no bars remain
   as you smith the lvl increases so each time you are at the anvil it will check the smithing level and if you can smith a better item it will switch to that item
4. Bank finished items
5. Return to Phase 1

## Technical Implementation

### **File Structure**
```
/allinone/
├── AllInOneMetalPlugin.java
├── AllInOneMetalConfig.java  
├── AllInOneMetalScript.java
├── AllInOneMetalOverlay.java
├── enums/
│   ├── MetalType.java
│   ├── ProcessPhase.java
│   └── SmithingProduct.java
└── README.md
```

### **Key Components**

#### **MetalType Enum**
- Defines available metals with ore requirements
- Includes level requirements and ore ratios
- Maps to corresponding bar types

#### **ProcessPhase Enum** 
- MINING: Active mining phase
- SMELTING: Processing ores to bars
- SMITHING: Converting bars to items
- COMPLETE: All phases finished

#### **SmithingProduct Enum**
- Available smithing items per metal type
- Level requirements and bar costs
- XP values for optimization

### **State Management**
- Track current phase and progress
- Store ore counts and targets
- Monitor inventory and bank states
- Handle phase transitions smoothly

### **Error Handling**
- Insufficient materials detection
- Level requirement validation  
- Equipment requirement checking
- Graceful failure recovery

## User Experience

### **Setup Process**
1. Select desired metal type (Bronze, Iron, Steel, etc.)
2. Set target quantity (total ores to process)
3. Position character near mining location
4. Start plugin

### **Progress Tracking**
- Real-time overlay showing:
  - Current phase (Mining/Smelting/Smithing)
  - Ore counts vs targets
  - Bars produced
  - Items smithed
  - total XP gained for each skill(Mining, Smithing/Smelting)
  - total time running the plugin
  - for future add the total profit made from the items smithed(do not calculate profit for ores or bars)


## Benefits Over Individual Plugins

### **Seamless Integration**
- No manual intervention between phases
- Optimized transitions and pathing
- Unified progress tracking

### **Intelligent Resource Management**
- Maintains proper ore ratios automatically
- Optimizes inventory usage across all phases
- Smart equipment and item management

### **XP Efficiency**
- Maximizes XP/hour across skills
- Reduces idle time between activities
- Optimizes smithing product selection

### **User Convenience**
- Single plugin for entire metalworking chain
- Minimal configuration required
- Set-and-forget operation

## Future Enhancements

### **Advanced Features**
- **Special Locations**: Support for Motherlode Mine, Blast Furnace
- **Profitable Items**: Focus on valuable smithing products
- **Achievement Integration**: Track towards smithing achievements
- **Multi-Metal Support**: Process multiple metal types in sequence

### **Quality of Life**
- **Visual Indicators**: Highlight current targets and objectives
- **Sound Notifications**: Audio alerts for phase changes
- **Statistics Tracking**: Long-term progress and efficiency metrics
- **Preset Configurations**: Save/load common setups

This plugin will provide a comprehensive, efficient, and user-friendly solution for players wanting to train all three metalworking skills simultaneously with minimal effort and maximum efficiency.

### **Missing Features Based on Existing Plugin Analysis**

#### **Mining Phase Enhancement Features**
- **Anti-ban System Integration**: Complex anti-ban settings with activity intensity, behavioral variability, natural mouse movements, and micro-breaks
- **World Hopping Logic**: Rate-limited world hopping with protection against consecutive hops (max 10 hops with forced breaks)
- **Rock Tracking System**: Intelligent rock tracking to predict respawn times and optimize world hopping decisions  
- **Depleted Rock Detection**: Automatically detect when currently mining a depleted rock and stop animation
- **Player Competition Management**: Monitor nearby players and hop worlds if too many miners in the area
- **Dragon Pickaxe Special Attack**: Auto-activate special attack when equipped with dragon pickaxe
- **Equipment Validation**: Check for best available pickaxe and auto-equip or retrieve from bank
- **Mining Guild Support**: Special handling for Mining Guild with reduced respawn times
- **Gem Bag Integration**: Support for gem bag usage during mining (future enhancement)

#### **Smelting Phase Enhancement Features**  
- **Coal Bag Management**: Automatic coal bag filling/emptying for steel+ bars in member worlds
- **Special Equipment Auto-Equip**:
  - Ring of Forging for iron bars (100% success rate)
  - Gauntlets of Goldsmithing for gold bars  
  - Smithing Uniform Gloves for XP bonuses
- **Furnace Interface Handling**: Proper widget interaction for smelting selection dialog
- **Anti-ban During Smelting**: Cooldowns and micro-breaks during smelting operations
- **Inventory Optimization**: Smart withdrawal calculations based on coal bag availability
- **Material Validation**: Verify sufficient materials in bank before attempting to smelt
- **Member/F2P Logic**: Different handling for member vs F2P worlds (coal bag, equipment)

#### **Smithing Phase Enhancement Features**
- **Anvil Interface Management**: Proper widget handling for smithing selection (widget 312)
- **Dynamic Item Selection**: Real-time smithing level checking to upgrade to better items mid-session
- **XP Drop Monitoring**: Wait for smithing XP drops to confirm successful smithing
- **Hammer Validation**: Auto-withdraw hammer from bank if not in inventory
- **Bar Count Optimization**: Calculate optimal number of bars to withdraw based on selected item
- **Smithing Varbit Tracking**: Use varbit 2224 to track anvil make amount and set to "All"
- **Location Awareness**: Precise world point navigation between Varrock bank (3185, 3438, 0) and anvil (3188, 3426, 0)

#### **Cross-Phase State Management**
- **Robust State Machine**: Enum-based states (MINING, SMELTING, SMITHING, BANKING, WALKING, COMPLETE)
- **Error Recovery**: Graceful handling when materials run out or equipment is missing
- **Progress Persistence**: Track progress across logout/login sessions
- **Resource Counting**: Accurate ore/bar/item counting with target validation
- **Phase Transition Logic**: Smart detection when to move between phases
- **Initial Location Tracking**: Remember starting location and return after banking/smelting

#### **Banking and Inventory Management**
- **Smart Deposit Logic**: Deposit all except essential items (pickaxe, hammer, coal bag)
- **Withdrawal Optimization**: Calculate exact amounts needed based on inventory space and ratios
- **Bank Validation**: Check bank contents before starting operations
- **Equipment Banking**: Handle equipment swapping (pickaxe types, gloves, rings)
- **Inventory Space Calculation**: Account for tools when calculating ore ratios (26-28 slots)

#### **Anti-Detection and Safety Features**
- **Dynamic Activity Settings**: Adjust intensity and behavior based on current phase
- **Action Cooldowns**: Randomized delays between actions
- **Mouse Movement Patterns**: Natural mouse movements and click patterns
- **Break Scheduling**: Micro-breaks and longer breaks to simulate human behavior
- **Logout on Completion**: Option to logout when all tasks completed
- **Failure Detection**: Stop script if critical errors occur (no materials, equipment missing)

#### **Advanced Configuration Options**
- **Ore Ratio Customization**: Fine-tune ore ratios for different bar types
- **Banking Preferences**: Choose between different bank locations
- **Performance Monitoring**: Track XP/hour, items created, profit calculations
- **Debug Mode**: Detailed logging for troubleshooting
- **World Selection**: Preferred worlds or world type (member/F2P)
- **Time Limits**: Optional time-based stopping conditions

#### **User Interface Enhancements**
- **Real-time Overlay**: Show current state, progress, XP gains, time elapsed
- **Configuration Validation**: Prevent invalid setups (impossible ore combinations)
- **Status Messages**: Clear feedback on current actions and any issues
- **Progress Indicators**: Visual progress bars for each phase
- **Statistics Dashboard**: Session stats including items made, XP gained, estimated profit

#### **Integration Features**
- **Walker Integration**: Use Rs2Walker for pathfinding between locations
- **Widget Management**: Proper handling of all game interfaces (smelting, smithing, banking)
- **Chat Message Monitoring**: Listen for relevant game messages (coal bag status, completion messages)
- **Item Container Tracking**: Monitor inventory changes for state management
- **Skill Level Monitoring**: Track skill levels for dynamic progression decisions