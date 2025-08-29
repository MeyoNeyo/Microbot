package net.runelite.client.plugins.microbot.bossing.bryophyta;


import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.bossing.bryophyta.enums.BryophytaState;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class BryophytaOverlay extends OverlayPanel {
    
    @Inject
    BryophytaOverlay() {
        super();
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(200, 300));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Bryophyta Fighter")
                    .color(Color.GREEN)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder().build());

            String botStatus = BryophytaScript.getBotStatus();
            Color statusColor;
            switch (botStatus) {
                case "OFF":
                    statusColor = Color.RED;
                    break;
                case "WAITING FOR LOGIN":
                    statusColor = Color.YELLOW;
                    break;
                case "INITIALIZING":
                    statusColor = Color.ORANGE;
                    break;
                case "RUNNING":
                    statusColor = Color.GREEN;
                    break;
                default:
                    statusColor = Color.WHITE;
                    break;
            }

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(botStatus)
                    .rightColor(statusColor)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("State:")
                    .right(BryophytaScript.getCurrentState().toString())
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Kills:")
                    .right(String.valueOf(BryophytaScript.getKillCount()))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Keys Used:")
                    .right(String.valueOf(BryophytaScript.getKeysUsed()))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Runtime:")
                    .right(BryophytaScript.getRuntime())
                    .build());

            if (BryophytaScript.getCurrentState() == BryophytaState.FIGHTING_BOSS ||
                BryophytaScript.getCurrentState() == BryophytaState.FIGHTING_GROWTHLINGS) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Combat Target:")
                        .right(BryophytaScript.getCurrentTarget())
                        .build());
            }

            // Display current inventory status
            panelComponent.getChildren().add(LineComponent.builder().build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Food Count:")
                    .right(String.valueOf(BryophytaScript.getFoodCount()))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Keys in Inv:")
                    .right(String.valueOf(BryophytaScript.getKeysInInventory()))
                    .build());

            // Display prayer status
            panelComponent.getChildren().add(LineComponent.builder().build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Prayer Points:")
                    .right(String.valueOf(Microbot.getClient().getBoostedSkillLevel(net.runelite.api.Skill.PRAYER)))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Health:")
                    .right(String.valueOf(Microbot.getClient().getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS)))
                    .build());

        } catch (Exception ex) {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }
}
