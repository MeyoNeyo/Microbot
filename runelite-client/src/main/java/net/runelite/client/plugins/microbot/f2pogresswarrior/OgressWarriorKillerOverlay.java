package net.runelite.client.plugins.microbot.f2pogresswarrior;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.Map;

public class OgressWarriorKillerOverlay extends OverlayPanel {
    private OgressWarriorKillerScript script;
    private OgressWarriorKillerConfig config;

    @Inject
    public OgressWarriorKillerOverlay(OgressWarriorKillerPlugin plugin) {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    public void setScript(OgressWarriorKillerScript script) {
        this.script = script;
    }
    
    public void setConfig(OgressWarriorKillerConfig config) {
        this.config = config;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (script == null || !script.isRunning() || config == null) {
            return null;
        }

        panelComponent.setPreferredSize(new Dimension(280, 300));
        
        // Title
        panelComponent.getChildren().add(
            TitleComponent.builder()
                .text("⚔️ F2P Ogress Warrior Killer ⚔️")
                .color(new Color(255, 165, 0))
                .build()
        );

        // Current Status
        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Status:")
                .right(script.getCurrentStatus())
                .rightColor(getStatusColor())
                .build()
        );

        // Current State
        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("State:")
                .right(OgressWarriorKillerScript.currentState.toString())
                .rightColor(Color.CYAN)
                .build()
        );

        // Aggro Timer
        if (config.waitForAggroTimer()) {
            panelComponent.getChildren().add(
                LineComponent.builder()
                    .left("Aggro Timer:")
                    .right(script.getAggroTimerString())
                    .rightColor(Color.CYAN)
                    .build()
            );
        }

        // Divider
        panelComponent.getChildren().add(LineComponent.builder().build());

        // Loot Tracker
        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Loot Tracker:")
                .leftColor(new Color(255, 215, 0))
                .build()
        );

        Map<String, Integer> lootTracker = script.getLootTracker();
        if (lootTracker.isEmpty()) {
            panelComponent.getChildren().add(
                LineComponent.builder()
                    .left("  No items looted yet")
                    .leftColor(Color.GRAY)
                    .build()
            );
        } else {
            for (Map.Entry<String, Integer> entry : lootTracker.entrySet()) {
                panelComponent.getChildren().add(
                    LineComponent.builder()
                        .left("  " + entry.getKey() + ":")
                        .right(String.valueOf(entry.getValue()))
                        .rightColor(getLootColor(entry.getKey()))
                        .build()
                );
            }
        }

        return super.render(graphics);
    }

    private Color getStatusColor() {
        String status = script.getCurrentStatus();
        if (status.contains("Attacking")) return Color.RED;
        if (status.contains("Banking")) return Color.YELLOW;
        if (status.contains("Looting")) return Color.GREEN;
        if (status.contains("Waiting")) return Color.CYAN;
        return Color.WHITE;
    }

    private Color getLootColor(String itemName) {
        String name = itemName.toLowerCase();
        if (name.equals("coins")) return Color.YELLOW;
        if (name.equals("ruby") || name.equals("diamond")) return Color.MAGENTA;
        if (name.equals("emerald")) return Color.GREEN;
        if (name.equals("sapphire")) return Color.BLUE;
        if (name.equals("bread")) return new Color(139, 69, 19); // Brown
        return Color.WHITE;
    }
}
