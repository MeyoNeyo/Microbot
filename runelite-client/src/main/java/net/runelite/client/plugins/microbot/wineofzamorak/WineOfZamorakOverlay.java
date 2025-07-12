package net.runelite.client.plugins.microbot.wineofzamorak;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class WineOfZamorakOverlay extends OverlayPanel {
    
    private final WineOfZamorakConfig config;
    private final WineOfZamorakPlugin plugin;

    @Inject
    WineOfZamorakOverlay(WineOfZamorakConfig config, WineOfZamorakPlugin plugin) {
        super();
        this.config = config;
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Wine of Zamorak " + WineOfZamorakScript.version)
                    .color(Color.GREEN)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Script Status:")
                    .right(Microbot.isLoggedIn() ? "RUNNING" : "LOGGED OUT")
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("State:")
                    .right(WineOfZamorakScript.state.toString())
                    .build());

            WineOfZamorakScript script = plugin.wineOfZamorakScript;
            if (script != null) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Wines Collected:")
                        .right(String.valueOf(script.getWinesCollected()))
                        .build());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Worlds Hopped:")
                        .right(String.valueOf(script.getWorldsHopped()))
                        .build());

                // Show detailed status if debug mode is enabled
                if (config.enableDebugLogging()) {
                    panelComponent.getChildren().add(LineComponent.builder()
                            .left("Player Level:")
                            .right(String.valueOf(Microbot.getClient().getTotalLevel()))
                            .build());

                    panelComponent.getChildren().add(LineComponent.builder()
                            .left("Current World:")
                            .right(String.valueOf(Microbot.getClient().getWorld()))
                            .build());
                }
            }

        } catch (Exception ex) {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }
}
