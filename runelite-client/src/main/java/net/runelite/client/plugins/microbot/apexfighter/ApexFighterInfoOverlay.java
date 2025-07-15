package net.runelite.client.plugins.microbot.apexfighter;


import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;


import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import java.util.Map;

public class ApexFighterInfoOverlay extends OverlayPanel {
    private final ApexFighterConfig config;
    private final ApexFighterPlugin plugin;

    @Inject
    ApexFighterInfoOverlay(ApexFighterPlugin plugin, ApexFighterConfig config) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(250, 400));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("\uD83E\uDD86 ApexFighter \uD83E\uDD86")
                    .color(Color.ORANGE)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Play Style: " + config.playStyle() + "(" + config.playStyle().getPrimaryTickInterval() + "," + config.playStyle().getSecondaryTickInterval() + ")")
                    .right("Attack cooldown: " + ApexFighterPlugin.getCooldown())
                    .build());

            // --- Runtime Overlay ---
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Runtime:")
                    .right(plugin.getTimeRunning())
                    .build());
            panelComponent.getChildren().add(LineComponent.builder().build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left(Microbot.status)
                    .right("Version:" + ApexFighterPlugin.version)
                    .build());

            // --- Loot Tracking Overlay ---
            if (!ApexFighterPlugin.sessionLoot.isEmpty()) {
                long totalValue = ApexFighterPlugin.getTotalLootValue();
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Total Loot Value:")
                    .right(ApexFighterPlugin.formatGp(totalValue) + " gp")
                    .build());
                panelComponent.getChildren().add(LineComponent.builder().left("Looted Items This Session:").build());
                for (LootEntry entry : ApexFighterPlugin.sessionLoot.values()) {
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left(entry.getName())
                        .right("x" + entry.getQuantity())
                        .build());
                }
            }
        } catch (Exception ex) {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }
        return super.render(graphics);
    }
}
