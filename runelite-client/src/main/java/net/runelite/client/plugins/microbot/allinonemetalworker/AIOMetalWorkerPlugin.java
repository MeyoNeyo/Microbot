package net.runelite.client.plugins.microbot.allinonemetalworker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

/**
 * All-in-One Metal Worker Plugin
 * 
 * Automates the complete metalworking process from mining ores to finished smithed items.
 * Handles mining with proper ore ratios, smelting at Al Kharid furnace, and smithing at Varrock anvil.
 * 
 * Features:
 * - Intelligent ore ratio management for alloy bars
 * - Dynamic smithing item selection based on level progression
 * - Anti-ban measures and human-like behavior simulation
 * - World hopping when areas are crowded
 * - Comprehensive error handling and recovery
 * 
 * @author AIO Metal Worker Plugin
 * @version 1.0.0
 */
@PluginDescriptor(
        name = "777" + " AIO Metal Worker",
        description = "All-in-one mining, smelting, and smithing automation with intelligent progression",
        tags = {"mining", "smelting", "smithing", "metalworking", "automation", "skilling"},
        enabledByDefault = false
)
@Slf4j
public class AIOMetalWorkerPlugin extends Plugin {

    @Inject
    private AIOMetalWorkerConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private AIOMetalWorkerOverlay overlay;

    @Inject
    private AIOMetalWorkerScript script;

    /**
     * Provides the configuration instance for dependency injection
     */
    @Provides
    AIOMetalWorkerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AIOMetalWorkerConfig.class);
    }

    /**
     * Called when the plugin is started
     */
    @Override
    protected void startUp() throws AWTException {
        log.info("AIO Metal Worker Plugin starting up...");
        
        try {
            if (overlayManager != null) {
                overlayManager.add(overlay);
            }
            
            // Ensure script is initialized properly
            if (script != null) {
                log.info("Starting AIO Metal Worker script...");
                script.run(config);
            } else {
                log.error("Script injection failed - plugin cannot start");
                return;
            }
            
            log.info("AIO Metal Worker Plugin started successfully");
            
        } catch (Exception e) {
            log.error("Failed to start AIO Metal Worker Plugin: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Called when the plugin is stopped
     */
    @Override
    protected void shutDown() {
        log.info("AIO Metal Worker Plugin shutting down...");
        
        try {
            // Stop the script first with proper cleanup
            if (script != null) {
                log.info("Stopping AIO Metal Worker script...");
                script.shutdown();
                
                // Wait for script to stop gracefully, check multiple times
                int maxWaitAttempts = 15; // Increased from 10 to 15 for more time
                int waitAttempts = 0;
                while (script.isRunning() && waitAttempts < maxWaitAttempts) {
                    try {
                        Thread.sleep(200);
                        waitAttempts++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
                if (script.isRunning()) {
                    log.warn("Script did not stop gracefully after 3 seconds, forcing additional cleanup");
                    
                    // Force additional walker cleanup if script is still running
                    try {
                        net.runelite.client.plugins.microbot.util.walker.Rs2Walker.setTarget(null);
                        net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin.exit();
                        log.info("Forced walker cleanup completed");
                    } catch (Exception walkerEx) {
                        log.warn("Error during forced walker cleanup: " + walkerEx.getMessage());
                    }
                } else {
                    log.info("Script stopped successfully");
                }
            }
            
            // Additional cleanup - ensure walker is completely stopped
            try {
                net.runelite.client.plugins.microbot.util.walker.Rs2Walker.setTarget(null);
                log.info("Final walker cleanup completed");
            } catch (Exception finalWalkerEx) {
                log.warn("Error during final walker cleanup: " + finalWalkerEx.getMessage());
            }
            
            // Remove overlay
            if (overlayManager != null) {
                overlayManager.remove(overlay);
            }
            
            log.info("AIO Metal Worker Plugin stopped successfully");
            
        } catch (Exception e) {
            log.error("Error during plugin shutdown: " + e.getMessage(), e);
        }
    }

    /**
     * Handles chat messages for coal bag status and other relevant game messages
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() == ChatMessageType.GAMEMESSAGE) {
            String message = event.getMessage();
            
            // Coal bag status monitoring
            if (message.contains("The coal bag is now empty.")) {
                script.onCoalBagEmptied();
            } else if (message.contains("The coal bag contains")) {
                script.onCoalBagFilled();
            }
            
            // Smelting completion messages
            if (message.startsWith("You retrieve a bar of") || 
                message.startsWith("The Varrock platebody enabled you to smelt") ||
                message.contains("You smelt")) {
                script.onSmeltingSuccess();
            }
            
            // Mining success messages
            if (message.contains("You manage to mine") || 
                message.contains("You get some") ||
                message.contains("You swing your pick")) {
                script.onMiningSuccess();
            }
            
            // Error conditions
            if (message.contains("You need") && message.contains("to use this")) {
                script.onLevelRequirementError(message);
            }
            
            // Additional safety checks
            if (message.contains("You are already holding") || 
                message.contains("Your inventory is too full")) {
                log.warn("Inventory management issue detected: " + message);
            }
        }
    }

    /**
     * Monitors inventory changes for state management
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // Only process player inventory changes
        if (event.getItemContainer().getId() == 93) {
            script.onInventoryChanged(event);
        }
    }
    
    /**
     * Handles script shutdown with cleanup
     */
    public void shutdownScript() {
        if (script != null) {
            script.shutdown();
        }
        log.info("AIO Metal Worker script shutdown completed");
    }
}
