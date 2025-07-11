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

    @Inject
    ApexFighterInfoOverlay(ApexFighterPlugin plugin, ApexFighterConfig config) {
        super(plugin);
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
            panelComponent.getChildren().add(LineComponent.builder().build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left(Microbot.status)
                    .right("Version:" + ApexFighterPlugin.version)
                    .build());

            // --- Loot Tracking Overlay ---
            if (!ApexFighterPlugin.sessionLoot.isEmpty()) {
                panelComponent.getChildren().add(LineComponent.builder().left("Looted Items This Session:").build());
                for (Map.Entry<Integer, Integer> entry : ApexFighterPlugin.sessionLoot.entrySet()) {
                    int itemId = entry.getKey();
                    int amount = entry.getValue();
                    String itemName = "";
                    try {
                        Rs2ItemModel itemModel = Rs2Inventory.get(itemId);
                        if (itemModel != null && itemModel.getName() != null && !itemModel.getName().isEmpty()) {
                            itemName = itemModel.getName();
                        } else {
                            itemName = "Item " + itemId;
                        }
                    } catch (Exception ignored) {
                        itemName = "Item " + itemId;
                    }
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left(itemName)
                        .right("x" + amount)
                        .build());
                }
            }
        } catch (Exception ex) {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }
        return super.render(graphics);
    }
}
