# ApexFighter Future Feature Ideas

## 1. Loot Tracking Overlay
- Add inside overlay display all items picked up/looted during the session.
- Show the amount of each looted item.

## 2. Display Total Profit from Loot (GE Value)
- Display a total profit number above the loot list in the overlay.
- Total profit is calculated as the sum of the Grand Exchange (GE) value of all looted items.
- Reference: Check the logic in `wildyruniteminer` for fetching GE prices for items.

## 3. Stop Plugin After Death (Config Toggle)
- Add a toggle option in the config to stop the plugin after the player has died one time.
- When enabled, the plugin will automatically stop all scripts if a death is detected.
- Useful for safety and account protection.

## 4. Anti-PK Escape (Player Attacked by Another Player)
- If the player is attacked by another player (PVP), immediately run/walk to a safe location (e.g., Lumbridge).
- While running, spam the logout button until successfully logged out or until the player dies.
- If the player survives, resume normal operation; if dead, handle according to the death logic.
- Useful for avoiding PKers in the wilderness or other PVP areas.

## 5. (Add more ideas below as you think of them)
- ...
