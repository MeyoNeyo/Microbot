package net.runelite.client.plugins.microbot.apexfighter;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;
import net.runelite.api.World;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.Item;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.MenuEntry;
import net.runelite.api.MenuAction;
import net.runelite.api.KeyCode;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.apexfighter.bank.BankerScript;
import net.runelite.client.plugins.microbot.apexfighter.cannon.CannonScript;
import net.runelite.client.plugins.microbot.apexfighter.combat.AttackNpcScript;
import net.runelite.client.plugins.microbot.apexfighter.combat.BuryScatterScript;
import net.runelite.client.plugins.microbot.apexfighter.combat.FlickerScript;
import net.runelite.client.plugins.microbot.apexfighter.combat.FoodScript;
import net.runelite.client.plugins.microbot.apexfighter.combat.HighAlchScript;
import net.runelite.client.plugins.microbot.apexfighter.combat.PotionManagerScript;
import net.runelite.client.plugins.microbot.apexfighter.combat.PrayerScript;
import net.runelite.client.plugins.microbot.apexfighter.combat.SafeSpot;
import net.runelite.client.plugins.microbot.apexfighter.combat.UseSpecialAttackScript;
import net.runelite.client.plugins.microbot.apexfighter.enums.PrayerStyle;
import net.runelite.client.plugins.microbot.apexfighter.enums.State;
import net.runelite.client.plugins.microbot.apexfighter.LootEntry;
import net.runelite.client.plugins.microbot.apexfighter.loot.LootScript;
import net.runelite.client.plugins.microbot.apexfighter.safety.SafetyScript;
import net.runelite.client.plugins.microbot.apexfighter.skill.AttackStyleScript;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.grounditems.GroundItem;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;


@PluginDescriptor(
        name = "777 ApexFighter",
        description = "Microbot ApexFighter plugin",
        tags = {"fight", "microbot", "misc", "combat", "playerassistant"},
        enabledByDefault = false
)
@Slf4j

public class ApexFighterPlugin extends Plugin {
    // Camera configuration flag
    private boolean cameraConfigured = false;

    /**
     * Sets the camera to a top-down view, similar to WildyRuniteMiner.
     */
    private void setTopDownCameraView() {
        if (Microbot.getClient() == null || cameraConfigured) return;
        Microbot.getClient().setCameraPitchTarget(383);
        Microbot.getClient().setCameraYawTarget(0);
        Microbot.getClient().setCameraShakeDisabled(true);
        cameraConfigured = true;
        Microbot.status = "Camera set to top-down view.";
    }
    // Track seconds without monsters for hop logic
    private int secondsWithoutMonsters = 0;

    // Helper to count players in the area
    private int getPlayerCountInArea() {
        WorldPoint center = config.toggleCenterTile() ? config.centerLocation() : Rs2Player.getWorldLocation();
        return (int) Microbot.getClient().getTopLevelWorldView().players().stream()
            .filter(p -> p.getWorldLocation().distanceTo(center) <= config.attackRadius())
            .count();
    }
    /**
     * Returns true if the player is inside the configured target area (center + attack radius).
     */
    public boolean isPlayerInTargetArea() {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        WorldPoint center = config.toggleCenterTile() ? config.centerLocation() : Rs2Player.getWorldLocation();
        return playerLoc.distanceTo(center) <= config.attackRadius();
    }
    // Helper to format GP values for overlay
    public static String formatGp(long gp) {
        if (gp >= 1_000_000) return String.format("%.2fM", gp / 1_000_000.0);
        if (gp >= 1_000) return String.format("%.1fk", gp / 1_000.0);
        return String.valueOf(gp);
    }

    // Calculate total loot value (GE price)
    public static long getTotalLootValue() {
        long total = 0;
        for (LootEntry entry : sessionLoot.values()) {
            int price = 0;
            try {
                price = Microbot.getItemManager().getItemPrice(entry.getItemId());
            } catch (Exception ignored) {}
            total += (long) price * entry.getQuantity();
        }
        return total;
    }
    /**
     * Hops to a random safe world of the same type (members/free) as the current world.
     * Filters out dangerous, full, and current worlds. Runs on the client thread.
     */
    public static void hopWorlds() {
        Microbot.getClientThread().invokeLater(() -> {
            net.runelite.http.api.worlds.WorldResult worldResult = Microbot.getWorldService().getWorlds();
            if (worldResult == null) return;

            int currentWorld = Microbot.getClient().getWorld();
            boolean isMember = Microbot.getClient().getWorldType().contains(net.runelite.api.WorldType.MEMBERS);

            List<net.runelite.http.api.worlds.World> worlds = worldResult.getWorlds().stream()
                .filter(w -> w.getId() != currentWorld)
                .filter(w -> w.getPlayers() < 2000) // 2000 is the typical world cap
                .filter(w -> w.getTypes().stream().noneMatch(t ->
                    t.toString().equals("PVP") ||
                    t.toString().equals("DEADMAN") ||
                    t.toString().equals("HIGH_RISK") ||
                    t.toString().equals("SKILL_TOTAL") ||
                    t.toString().equals("QUEST_SPEEDRUNNING") ||
                    t.toString().equals("PVP_ARENA") ||
                    t.toString().equals("SEASONAL") ||
                    t.toString().equals("BETA_WORLD") ||
                    t.toString().equals("NOSAVE_MODE") ||
                    t.toString().equals("FRESH_START_WORLD")
                ))
                .filter(w -> isMember == w.getTypes().stream().anyMatch(t -> t.toString().equals("MEMBERS")))
                .collect(java.util.stream.Collectors.toList());

            if (worlds.isEmpty()) return;

            net.runelite.http.api.worlds.World targetWorld = worlds.get(new java.util.Random().nextInt(worlds.size()));

            // Convert HTTP world to API world
            net.runelite.api.World rsWorld = Microbot.getClient().createWorld();
            rsWorld.setActivity(targetWorld.getActivity());
            rsWorld.setAddress(targetWorld.getAddress());
            rsWorld.setId(targetWorld.getId());
            rsWorld.setPlayerCount(targetWorld.getPlayers());
            rsWorld.setLocation(targetWorld.getLocation());
            rsWorld.setTypes(net.runelite.client.util.WorldUtil.toWorldTypes(targetWorld.getTypes()));

            Microbot.getClient().changeWorld(rsWorld);
        });
    }
    // ...existing code...
    // Utility: Check if player is on safespot
    public static boolean isOnSafeSpot() {
        WorldPoint safeSpot = Microbot.getConfigManager().getConfiguration(
                "PlayerAssistant",
                "safeSpotLocation",
                WorldPoint.class
        );
        return safeSpot != null && !safeSpot.equals(new WorldPoint(0, 0, 0)) &&
                net.runelite.client.plugins.microbot.util.player.Rs2Player.getWorldLocation().equals(safeSpot);
    }
    // Track previous state to filter out banking/food
    private State previousState = null;
    // For event-based loot tracking
    private final java.util.List<DespawnedGroundItem> recentDespawnedItems = new java.util.ArrayList<>();
    private final Map<Integer, Integer> previousInventory = new HashMap<>();
    public static final String version = "1.3.1";
    private static final String SET = "Set";
    private static final String CENTER_TILE = ColorUtil.wrapWithColorTag("Center Tile", JagexColors.MENU_TARGET);
    private static final String SAFE_SPOT = ColorUtil.wrapWithColorTag("Safe Spot", JagexColors.CHAT_PRIVATE_MESSAGE_TEXT_TRANSPARENT_BACKGROUND);
    private static final String ADD_TO = "Start Fighting:";
    private static final String REMOVE_FROM = "Stop Fighting:";
    private static final String WALK_HERE = "Walk here";
    private static final String ATTACK = "Attack";
    private static final int WORLD_MAP_MAPVIEW_ID = 595; // Example value, update as needed
    @Getter
    @Setter
    public static int cooldown = 0;
    // Loot tracking: itemId -> LootEntry (stores name and quantity)
    public static final Map<Integer, LootEntry> sessionLoot = new ConcurrentHashMap<>();

    // Helper class to track recently despawned ground items
    private static class DespawnedGroundItem {
        final int itemId;
        final String itemName;
        final int quantity;
        final WorldPoint location;
        final long timestamp;
        DespawnedGroundItem(int itemId, String itemName, int quantity, WorldPoint location) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.quantity = quantity;
            this.location = location;
            this.timestamp = System.currentTimeMillis();
        }
    }
    private final CannonScript cannonScript = new CannonScript();
    private final AttackNpcScript attackNpc = new AttackNpcScript();
    private final FoodScript foodScript = new FoodScript();
    private final LootScript lootScript = new LootScript();
    private final SafeSpot safeSpotScript = new SafeSpot();
    private final FlickerScript flickerScript = new FlickerScript();
    private final UseSpecialAttackScript useSpecialAttackScript = new UseSpecialAttackScript();
    private final BuryScatterScript buryScatterScript = new BuryScatterScript();
    private final AttackStyleScript attackStyleScript = new net.runelite.client.plugins.microbot.apexfighter.skill.AttackStyleScript();
    private final BankerScript bankerScript = new BankerScript();
    private final PrayerScript prayerScript = new PrayerScript();
    private final HighAlchScript highAlchScript = new HighAlchScript();
    private final PotionManagerScript potionManagerScript = new PotionManagerScript();
    private final SafetyScript safetyScript = new SafetyScript();
    //private final SlayerScript slayerScript = new SlayerScript();
    @Inject
    private net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig config;
    @Inject
    private ConfigManager configManager;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private net.runelite.client.plugins.microbot.apexfighter.ApexFighterOverlay playerAssistOverlay;
    @Inject
    private net.runelite.client.plugins.microbot.apexfighter.ApexFighterInfoOverlay playerAssistInfoOverlay;
    private Point lastMenuOpenedPoint;
    // (removed duplicate sessionLoot declaration)
    protected ScheduledExecutorService initializerExecutor = Executors.newSingleThreadScheduledExecutor();
    @Provides
    net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(net.runelite.client.plugins.microbot.apexfighter.ApexFighterConfig.class);
    }
    @Override
    protected void startUp() throws AWTException {
        sessionLoot.clear();
        previousState = null;
        previousInventory.clear();
        for (Rs2ItemModel item : net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory.all()) {
            if (item.getId() > 0) {
                previousInventory.put(item.getId(), item.getQuantity());
            }
        }
        recentDespawnedItems.clear();
        Microbot.pauseAllScripts.compareAndSet(true, false);
        cooldown = 0;
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        executor.scheduleWithFixedDelay(() -> {
            if (Microbot.getConfigManager() == null) {
                return;
            }
            setState(State.IDLE);
            ScheduledFuture<?> scheduledFuture = futureRef.get();
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
            executor.shutdown();
        }, 0, 1, TimeUnit.SECONDS);
        // Set camera to top-down view at script start, once per session
        setTopDownCameraView();
        if (overlayManager != null) {
            overlayManager.add(playerAssistOverlay);
            overlayManager.add(playerAssistInfoOverlay);
        }
        // Ensure centerLocation is set from manualCenterTileCoords if needed
        if (config.toggleCenterTile() && Microbot.isLoggedIn()) {
            String coords = config.manualCenterTileCoords();
            if (coords != null && !coords.isEmpty()) {
                String[] parts = coords.split(",");
                if (parts.length == 3) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int z = Integer.parseInt(parts[2].trim());
                        setCenter(new WorldPoint(x, y, z));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } else if (!config.toggleCenterTile() && Microbot.isLoggedIn()) {
            setCenter(Rs2Player.getWorldLocation());
        }
        lootScript.run(config);
        cannonScript.run(config);
        attackNpc.run(config);
        foodScript.run(config);
        safeSpotScript.run(config);
        flickerScript.run(config);
        useSpecialAttackScript.run(config);
        buryScatterScript.run(config);
        attackStyleScript.run(config);
        bankerScript.run(config);
        prayerScript.run(config);
        highAlchScript.run(config);
        potionManagerScript.run(config);
        safetyScript.run(config);
        //slayerScript.run(config);
        Microbot.getSpecialAttackConfigs().setSpecialAttack(true);
    }
    @Override
    protected void shutDown() {
        sessionLoot.clear();
        lootScript.shutdown();
        cannonScript.shutdown();
        attackNpc.shutdown();
        foodScript.shutdown();
        safeSpotScript.shutdown();
        flickerScript.shutdown();
        useSpecialAttackScript.shutdown();
        buryScatterScript.shutdown();
        attackStyleScript.shutdown();
        bankerScript.shutdown();
        prayerScript.shutdown();
        highAlchScript.shutdown();
        potionManagerScript.shutdown();
        safetyScript.shutdown();
        //slayerScript.shutdown();
        resetLocation();
        overlayManager.remove(playerAssistOverlay);
        overlayManager.remove(playerAssistInfoOverlay);
    }

    /**
     * Listen for ground item despawned events and track them for loot correlation.
     */
    @Subscribe
    public void onItemDespawned(ItemDespawned event) {
        net.runelite.api.TileItem item = event.getItem();
        if (item == null) return;
        WorldPoint location = event.getTile().getWorldLocation();
        // Only track items within loot radius
        WorldPoint center = config.toggleCenterTile() ? config.centerLocation() : net.runelite.client.plugins.microbot.util.player.Rs2Player.getWorldLocation();
        if (location.distanceTo(center) > config.attackRadius()) return;
        // Lookup item name using ItemManager
        String itemName;
        try {
            itemName = Microbot.getItemManager().getItemComposition(item.getId()).getName();
        } catch (Exception e) {
            itemName = String.valueOf(item.getId());
        }
        this.recentDespawnedItems.add(new DespawnedGroundItem(item.getId(), itemName, item.getQuantity(), location));
        // Clean up old entries (older than 3 seconds)
        long now = System.currentTimeMillis();
        this.recentDespawnedItems.removeIf(i -> now - i.timestamp > 3000);
    }

    /**
     * Listen for inventory changes and update loot tracking if a new item is added after looting.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != 93) return; // Inventory
        if (!Microbot.isLoggedIn()) return;

        Map<Integer, Integer> currentInventory = new HashMap<>();
        for (net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel item : net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory.all()) {
            if (item.getId() > 0) {
                currentInventory.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }

        // Compare to previous inventory
        for (Map.Entry<Integer, Integer> entry : currentInventory.entrySet()) {
            int itemId = entry.getKey();
            int newQty = entry.getValue();
            int oldQty = previousInventory.getOrDefault(itemId, 0);
            int diff = newQty - oldQty;
            if (diff > 0) {
                // See if this matches a recent despawned ground item
                DespawnedGroundItem match = this.recentDespawnedItems.stream()
                    .filter(i -> i.itemId == itemId && i.quantity == diff)
                    .findFirst().orElse(null);
                if (match != null) {
                    sessionLoot.compute(itemId, (id, lootEntry) -> {
                        String itemName = match.itemName;
                        if (lootEntry == null) return new LootEntry(id, itemName, diff);
                        lootEntry.addQuantity(diff);
                        return lootEntry;
                    });
                    this.recentDespawnedItems.remove(match);
                }
            }
        }

        previousInventory.clear();
        previousInventory.putAll(currentInventory);
    }
    public static void resetLocation() {
        setCenter(new WorldPoint(0, 0, 0));
        setSafeSpot(new WorldPoint(0, 0, 0));
    }
    public static void setCenter(WorldPoint worldPoint)
    {
        Microbot.getConfigManager().setConfiguration(
                "PlayerAssistant",
                "centerLocation",
                worldPoint
        );
    }
    public static void setSafeSpot(WorldPoint worldPoint)
    {
        Microbot.getConfigManager().setConfiguration(
                "PlayerAssistant",
                "safeSpotLocation",
                worldPoint
        );
    }
    public static State getState() {
        return Microbot.getConfigManager().getConfiguration(
                "PlayerAssistant",
                "state",
                State.class
        );
    }
    public static void setState(State state) {
        Microbot.getConfigManager().setConfiguration(
                "PlayerAssistant",
                "state",
                state
        );
    }
    private void addNpcToList(String npcName) {
        configManager.setConfiguration(
                "PlayerAssistant",
                "monster",
                config.attackableNpcs() + npcName + ","
        );
    }
    private void removeNpcFromList(String npcName) {
        configManager.setConfiguration(
                "PlayerAssistant",
                "monster",
                Arrays.stream(config.attackableNpcs().split(","))
                        .filter(n -> !n.equalsIgnoreCase(npcName))
                        .collect(Collectors.joining(","))
        );
    }
    public static void setAttackableNpcs(String npcNames) {
        Microbot.getConfigManager().setConfiguration(
                "PlayerAssistant",
                "monster",
                npcNames
        );
    }
    private String getNpcNameFromMenuEntry(String menuTarget) {
        return menuTarget.replaceAll("<[^>]*>|\\(.*\\)", "").trim();
    }
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getMessage().contains("reach that")) {
            AttackNpcScript.skipNpc();
        }
    }
    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getKey().equals("Safe Spot")) {
            if (!config.toggleSafeSpot()) {
                setSafeSpot(new WorldPoint(0, 0, 0));
            }
        }
        if(event.getKey().equals("Combat")) {
            if (!config.toggleCombat() && config.toggleCenterTile()) {
                setCenter(new WorldPoint(0, 0, 0));
            }
            if (config.toggleCombat() && !config.toggleCenterTile()) {
                setCenter(Rs2Player.getWorldLocation());
            }
        }
        if (event.getKey().equals("manualCenterTileCoords")) {
            String coords = config.manualCenterTileCoords();
            if (coords != null && !coords.isEmpty()) {
                String[] parts = coords.split(",");
                if (parts.length == 3) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int z = Integer.parseInt(parts[2].trim());
                        setCenter(new WorldPoint(x, y, z));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if(event.getKey().equals("Center Tile")) {
            // If manual center tile is turned off, do NOT update the textbox or set a new center tile
            // Just leave the current center tile as is
            if (config.toggleCenterTile()) {
                String coords = config.manualCenterTileCoords();
                if (coords != null && !coords.isEmpty()) {
                    String[] parts = coords.split(",");
                    if (parts.length == 3) {
                        try {
                            int x = Integer.parseInt(parts[0].trim());
                            int y = Integer.parseInt(parts[1].trim());
                            int z = Integer.parseInt(parts[2].trim());
                            setCenter(new WorldPoint(x, y, z));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
    }
    @Subscribe
    public void onGameTick(GameTick gameTick) {
        if (cooldown > 0 && !Rs2Combat.inCombat())
            cooldown--;
        if(config.togglePrayer())
            flickerScript.onGameTick();

        // --- Robust hop logic ---
        if (isPlayerInTargetArea()) {
            // Count monsters in area
            int monstersInArea = (int) Microbot.getClient().getTopLevelWorldView().npcs().stream()
                .filter(npc -> config.attackableNpcs().contains(npc.getName()))
                .filter(npc -> npc.getWorldLocation().distanceTo(
                    config.toggleCenterTile() ? config.centerLocation() : net.runelite.client.plugins.microbot.util.player.Rs2Player.getWorldLocation()
                ) <= config.attackRadius())
                .count();

            if (monstersInArea == 0) {
                secondsWithoutMonsters++;
            } else {
                secondsWithoutMonsters = 0;
            }

            if (config.maxPlayersBeforeHop() > 0 && getPlayerCountInArea() >= config.maxPlayersBeforeHop()) {
                ApexFighterPlugin.hopWorlds();
            }
            if (config.maxSecondsWithoutMonstersBeforeHop() > 0 && secondsWithoutMonsters >= config.maxSecondsWithoutMonstersBeforeHop()) {
                ApexFighterPlugin.hopWorlds();
                secondsWithoutMonsters = 0;
            }
        } else {
            secondsWithoutMonsters = 0;
        }
    }
    @Subscribe
    public void onNpcDespawned(NpcDespawned npcDespawned) {
        if(config.togglePrayer())
            flickerScript.onNpcDespawned(npcDespawned);
    }
    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event){
        if (event.getActor() != Microbot.getClient().getLocalPlayer()) return;
        final Hitsplat hitsplat = event.getHitsplat();
        if ((hitsplat.isMine()) && event.getActor().getInteracting() instanceof NPC && config.togglePrayer() && (config.prayerStyle() == PrayerStyle.LAZY_FLICK) || (config.prayerStyle() == PrayerStyle.PERFECT_LAZY_FLICK)) {
            flickerScript.resetLastAttack(true);
            Rs2Prayer.disableAllPrayers();
            if (config.toggleQuickPray())
                Rs2Prayer.toggleQuickPrayer(false);
        }
    }
    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        lastMenuOpenedPoint = Microbot.getClient().getMouseCanvasPosition();
    }
    @Subscribe
    private void onMenuEntryAdded(MenuEntryAdded event) {
        if (Microbot.getClient().isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT) && event.getOption().equals(WALK_HERE) && event.getTarget().isEmpty() && config.toggleCenterTile()) {
            addMenuEntry(event, SET, CENTER_TILE, 1);
        }
        if (Microbot.getClient().isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT) && event.getOption().equals(WALK_HERE) && event.getTarget().isEmpty()) {
            addMenuEntry(event, SET, SAFE_SPOT, 1);
        }
        if (event.getOption().equals(ATTACK) && config.attackableNpcs().contains(getNpcNameFromMenuEntry(Text.removeTags(event.getTarget())))) {
            addMenuEntry(event, REMOVE_FROM, event.getTarget(), 1);
        }
        if (event.getOption().equals(ATTACK) && !config.attackableNpcs().contains(getNpcNameFromMenuEntry(Text.removeTags(event.getTarget())))) {
            addMenuEntry(event, ADD_TO, event.getTarget(), 1);
        }
        if (event.getOption().equals(SET) && event.getTarget().equals(CENTER_TILE) && config.toggleCenterTile()) {
            // When Set Center Tile is clicked and manual center tile is enabled, update the textbox with the clicked tile's coordinates
            WorldPoint selected = getSelectedWorldPoint();
            if (selected != null) {
                String coords = selected.getX() + "," + selected.getY() + "," + selected.getPlane();
                Microbot.getConfigManager().setConfiguration(
                    "PlayerAssistant",
                    "manualCenterTileCoords",
                    coords
                );
            }
        }
    }
    private WorldPoint getSelectedWorldPoint() {
        if (Microbot.getClient().getWidget(WORLD_MAP_MAPVIEW_ID) == null) {
            WorldPoint result = null;
            if (Microbot.getClient().getSelectedSceneTile() != null) {
                if (Microbot.getClient().getTopLevelWorldView().isInstance()) {
                    result = WorldPoint.fromLocalInstance(Microbot.getClient(), Microbot.getClient().getSelectedSceneTile().getLocalLocation());
                } else {
                    result = Microbot.getClient().getSelectedSceneTile().getWorldLocation();
                }
            }
            return result;
        } else {
            return calculateMapPoint(Microbot.getClient().isMenuOpen() ? lastMenuOpenedPoint : Microbot.getClient().getMouseCanvasPosition());
        }
    }
    public WorldPoint calculateMapPoint(Point point) {
        WorldMap worldMap = Microbot.getClient().getWorldMap();
        float zoom = worldMap.getWorldMapZoom();
        final WorldPoint mapPoint = new WorldPoint(worldMap.getWorldMapPosition().getX(), worldMap.getWorldMapPosition().getY(), 0);
        final Point middle = mapWorldPointToGraphicsPoint(mapPoint);
        if (point == null || middle == null) {
            return null;
        }
        final int dx = (int) ((point.getX() - middle.getX()) / zoom);
        final int dy = (int) ((-(point.getY() - middle.getY())) / zoom);
        return mapPoint.dx(dx).dy(dy);
    }
    public Point mapWorldPointToGraphicsPoint(WorldPoint worldPoint) {
        WorldMap worldMap = Microbot.getClient().getWorldMap();
        float pixelsPerTile = worldMap.getWorldMapZoom();
        Widget map = Microbot.getClient().getWidget(WORLD_MAP_MAPVIEW_ID);
        if (map != null) {
            Rectangle worldMapRect = map.getBounds();
            int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
            int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);
            Point worldMapPosition = worldMap.getWorldMapPosition();
            int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
            int yTileOffset = (yTileMax - worldPoint.getY() - 1) * -1;
            int xTileOffset = worldPoint.getX() + widthInTiles / 2 - worldMapPosition.getX();
            int xGraphDiff = ((int) (xTileOffset * pixelsPerTile));
            int yGraphDiff = (int) (yTileOffset * pixelsPerTile);
            return new Point(map.getBounds().x + xGraphDiff, map.getBounds().y + yGraphDiff);
        }
        return null;
    }
    private void addMenuEntry(MenuEntryAdded event, String option, String target, int position) {
        java.util.List<MenuEntry> entries = new java.util.LinkedList<>(java.util.Arrays.asList(Microbot.getClient().getMenu().getMenuEntries()));
        if (entries.stream().anyMatch(e -> e.getOption().equals(option) && e.getTarget().equals(target))) {
            return;
        }
        Microbot.getClient().getMenu().createMenuEntry(position)
            .setOption(option)
            .setTarget(target)
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setIdentifier(event.getIdentifier())
            .setType(MenuAction.RUNELITE)
            .onClick(this::onMenuOptionClicked);
    }
    private void onMenuOptionClicked(MenuEntry entry) {
        // Handle Set Center Tile click
        if (entry.getOption().equals(SET) && entry.getTarget().equals(CENTER_TILE) && config.toggleCenterTile()) {
            WorldPoint selected = getSelectedWorldPoint();
            if (selected != null) {
                setCenter(selected);
                String coords = selected.getX() + "," + selected.getY() + "," + selected.getPlane();
                Microbot.getConfigManager().setConfiguration(
                    "PlayerAssistant",
                    "manualCenterTileCoords",
                    coords
                );
            }
        }
        // Handle Set Safe Spot click
        if (entry.getOption().equals(SET) && entry.getTarget().equals(SAFE_SPOT)) {
            WorldPoint selected = getSelectedWorldPoint();
            if (selected != null) {
                setSafeSpot(selected);
            }
        }
    }
}
