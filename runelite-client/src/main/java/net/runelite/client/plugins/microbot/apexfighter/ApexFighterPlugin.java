package net.runelite.client.plugins.microbot.apexfighter;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
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
import net.runelite.client.plugins.microbot.apexfighter.combat.*;
import net.runelite.client.plugins.microbot.apexfighter.enums.PrayerStyle;
import net.runelite.client.plugins.microbot.apexfighter.enums.State;
import net.runelite.client.plugins.microbot.apexfighter.loot.LootScript;
import net.runelite.client.plugins.microbot.apexfighter.safety.SafetyScript;
import net.runelite.client.plugins.microbot.apexfighter.skill.AttackStyleScript;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.awt.*;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    // Track previous state to filter out banking/food
    private State previousState = null;
    // For event-based loot tracking
    private volatile PendingLoot pendingLoot = null;
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

    // Helper class to track pending loot pickup
    private static class PendingLoot {
        final int itemId;
        final String itemName;
        final int quantity;
        final WorldPoint location;
        final long timestamp;
        PendingLoot(int itemId, String itemName, int quantity, WorldPoint location) {
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
        pendingLoot = null;
        previousState = null;
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
        if (overlayManager != null) {
            overlayManager.add(playerAssistOverlay);
            overlayManager.add(playerAssistInfoOverlay);
        }
        if (!config.toggleCenterTile() && Microbot.isLoggedIn())
            setCenter(Rs2Player.getWorldLocation());
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
     * Listen for inventory changes and update loot tracking if a new item is added after looting.
     * Only count as loot if the item was picked up from the ground within the configured radius.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // Use 93 (InventoryID.INVENTORY.getId()) directly to avoid deprecated field
        if (event.getContainerId() != 93) return;
        if (!Microbot.isLoggedIn()) return;
        if (pendingLoot == null) return;

        // Check if the item is now in inventory
        int count = 0;
        for (Rs2ItemModel item : net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory.all()) {
            if (item.getId() == pendingLoot.itemId) {
                count += item.getQuantity();
            }
        }
        if (count >= pendingLoot.quantity) {
            // Check if the pickup was within the allowed radius
            WorldPoint center = config.toggleCenterTile() ? config.centerLocation() : Rs2Player.getWorldLocation();
            int radius = config.attackRadius();
            if (pendingLoot.location != null && center != null && pendingLoot.location.distanceTo(center) <= radius) {
                sessionLoot.compute(pendingLoot.itemId, (id, lootEntry) -> {
                    String itemName = pendingLoot.itemName;
                    if (lootEntry == null) return new LootEntry(id, itemName, pendingLoot.quantity);
                    lootEntry.addQuantity(pendingLoot.quantity);
                    return lootEntry;
                });
            }
            pendingLoot = null;
        }
    }

    /**
     * Listen for menu option clicks to detect when the player attempts to pick up a ground item.
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!Microbot.isLoggedIn()) return;
        String option = event.getMenuOption();
        if (option == null) return;
        if (!option.equalsIgnoreCase("Take") && !option.equalsIgnoreCase("Pick up")) return;

        // Get item id and name
        int itemId = event.getId();
        String itemName = event.getMenuTarget();
        if (itemName != null) itemName = net.runelite.client.util.Text.removeTags(itemName);
        else itemName = String.valueOf(itemId);

        // Get world location of the ground item
        int x = event.getParam0();
        int y = event.getParam1();
        int z = Microbot.getClient().getPlane();
        WorldPoint location = new WorldPoint(x, y, z);

        // Track this as a pending loot pickup
        pendingLoot = new PendingLoot(itemId, itemName, 1, location);
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
        if (Microbot.getClient().isKeyPressed(KeyCode.KC_SHIFT) && event.getOption().equals(WALK_HERE) && event.getTarget().isEmpty() && config.toggleCenterTile()) {
            addMenuEntry(event, SET, CENTER_TILE, 1);
        }
        if (Microbot.getClient().isKeyPressed(KeyCode.KC_SHIFT) && event.getOption().equals(WALK_HERE) && event.getTarget().isEmpty()) {
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
                if (Microbot.getClient().isInInstancedRegion()) {
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
        List<MenuEntry> entries = new LinkedList<>(Arrays.asList(Microbot.getClient().getMenuEntries()));
        if (entries.stream().anyMatch(e -> e.getOption().equals(option) && e.getTarget().equals(target))) {
            return;
        }
        Microbot.getClient().createMenuEntry(position)
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
