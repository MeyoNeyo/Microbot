package net.runelite.client.plugins.microbot.allinonemetalworker;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.allinonemetalworker.enums.ProcessPhase;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.ColorUtil;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;

/**
 * Overlay panel for the AIO Metal Worker plugin.
 * Displays real-time information about the current state, progress, and statistics.
 */
public class AIOMetalWorkerOverlay extends OverlayPanel {

    private final AIOMetalWorkerConfig config;

    @Inject
    AIOMetalWorkerOverlay(AIOMetalWorkerPlugin plugin, AIOMetalWorkerConfig config) {
        super(plugin);
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(250, 350));
            
            // Plugin title
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("AIO Metal Worker")
                    .color(ColorUtil.fromHex("#FFD700"))
                    .build());
            
            // Version info
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Version:")
                    .right("1.0.0")
                    .build());
            
            addEmptyLine();
            
            // Current phase status
            ProcessPhase currentPhase = AIOMetalWorkerScript.getCurrentPhase();
            Color phaseColor = getPhaseColor(currentPhase);
            
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Current Phase:")
                    .right(currentPhase.toString())
                    .rightColor(phaseColor)
                    .build());
            
            // Current status
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(Microbot.status)
                    .rightColor(Color.WHITE)
                    .build());
            
            addEmptyLine();
            
            // Configuration info
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Metal Type:")
                    .right(config.metalType().getDisplayName())
                    .rightColor(Color.CYAN)
                    .build());
            
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Target Quantity:")
                    .right(String.valueOf(config.targetQuantity()))
                    .rightColor(Color.CYAN)
                    .build());
            
            addEmptyLine();
            
            // Progress information
            AIOMetalWorkerScript.ProgressTracker progress = AIOMetalWorkerScript.getProgress();
            if (progress != null) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Ores Mined:")
                        .right(progress.getOresMined() + "/" + config.targetQuantity())
                        .rightColor(getProgressColor(progress.getOresMined(), config.targetQuantity()))
                        .build());
                
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Bars Smelted:")
                        .right(String.valueOf(progress.getBarsSmelted()))
                        .rightColor(Color.ORANGE)
                        .build());
                
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Items Smithed:")
                        .right(String.valueOf(progress.getItemsSmithed()))
                        .rightColor(Color.YELLOW)
                        .build());
                
                addEmptyLine();
                
                // XP tracking
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Mining XP:")
                        .right("+" + progress.getMiningXpGained())
                        .rightColor(Color.GREEN)
                        .build());
                
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Smithing XP:")
                        .right("+" + progress.getSmithingXpGained())
                        .rightColor(Color.GREEN)
                        .build());
                
                addEmptyLine();
                
                // Runtime information
                if (progress.getStartTime() != null) {
                    Duration runtime = Duration.between(progress.getStartTime(), Instant.now());
                    String runtimeText = String.format("%02d:%02d:%02d", 
                            runtime.toHours(), 
                            runtime.toMinutesPart(), 
                            runtime.toSecondsPart());
                    
                    panelComponent.getChildren().add(LineComponent.builder()
                            .left("Runtime:")
                            .right(runtimeText)
                            .rightColor(Color.WHITE)
                            .build());
                }
            }
            
            // Detailed overlay information if enabled
            if (config.showDetailedOverlay()) {
                addEmptyLine();
                addDetailedInfo();
            }
            
            // Anti-ban status
            if (config.enableAntiban()) {
                addEmptyLine();
                addAntibanInfo();
            }
            
        } catch (Exception ex) {
            /*
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Error:")
                    .right("Overlay rendering failed")
                    .rightColor(Color.RED)
                    .build());
            */
        }
        
        return super.render(graphics);
    }

    /**
     * Adds an empty line to the overlay for spacing
     */
    private void addEmptyLine() {
        panelComponent.getChildren().add(LineComponent.builder()
                .left("")
                .right("")
                .build());
    }

    /**
     * Gets color based on current process phase
     */
    private Color getPhaseColor(ProcessPhase phase) {
        switch (phase) {
            case MINING:
                return Color.CYAN;
            case SMELTING:
                return Color.ORANGE;
            case SMITHING:
                return Color.YELLOW;
            case BANKING:
                return Color.MAGENTA;
            case WALKING:
                return Color.LIGHT_GRAY;
            case COMPLETE:
                return Color.GREEN;
            case ERROR:
                return Color.RED;
            default:
                return Color.WHITE;
        }
    }

    /**
     * Gets color based on progress percentage
     */
    private Color getProgressColor(int current, int target) {
        double percentage = (double) current / target;
        if (percentage >= 1.0) return Color.GREEN;
        if (percentage >= 0.75) return Color.YELLOW;
        if (percentage >= 0.5) return Color.ORANGE;
        return Color.RED;
    }

    /**
     * Adds detailed information when enabled
     */
    private void addDetailedInfo() {
        // World information
        panelComponent.getChildren().add(LineComponent.builder()
                .left("World:")
                .right(String.valueOf(Microbot.getClient().getWorld()))
                .rightColor(Color.LIGHT_GRAY)
                .build());
        
        // Anti-ban status
        if (Rs2AntibanSettings.actionCooldownActive) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Cooldown:")
                    .right("Active")
                    .rightColor(Color.YELLOW)
                    .build());
        }
    }

    /**
     * Adds anti-ban information when enabled
     */
    private void addAntibanInfo() {
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Activity:")
                .right(Rs2Antiban.getActivity().toString())
                .rightColor(Color.LIGHT_GRAY)
                .build());
        
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Intensity:")
                .right(Rs2Antiban.getActivityIntensity().toString())
                .rightColor(Color.LIGHT_GRAY)
                .build());
    }
}
