package net.runelite.client.plugins.microbot.bee.chaosaltar;

import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;

import java.awt.*;

public class ChaosAltarOverlay extends OverlayPanel {

    private final PanelComponent panelComponent = new PanelComponent();


    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();

        // Header
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Chaos Altar Bot")
                .build());

        // Wilderness Warning
        if (Rs2Pvp.isInWilderness()) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("WARNING:")
                    .right("In Wilderness! Keep LiteMode of Player Monitor ON")
                    .build());
        }

        // World hopping status
        if (ChaosAltarWorldHopManager.isCurrentlyHopping()) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right("Hopping worlds...")
                    .rightColor(Color.YELLOW)
                    .build());
        } else {
            // Show player detection info
            int trackedPlayers = ChaosAltarWorldHopManager.getTrackedPlayerCount();
            if (trackedPlayers > 0) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Players detected:")
                        .right(String.valueOf(trackedPlayers))
                        .rightColor(Color.RED)
                        .build());
            }

            // Show time since last hop
            long timeSinceHop = ChaosAltarWorldHopManager.getTimeSinceLastHop();
            if (timeSinceHop < 30000) { // Show for 30 seconds after hop
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Last hop:")
                        .right(String.format("%.1fs ago", timeSinceHop / 1000.0))
                        .rightColor(Color.GREEN)
                        .build());
            }
        }

        return panelComponent.render(graphics);
    }
}
