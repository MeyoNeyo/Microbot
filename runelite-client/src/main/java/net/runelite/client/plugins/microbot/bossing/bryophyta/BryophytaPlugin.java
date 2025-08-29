package net.runelite.client.plugins.microbot.bossing.bryophyta;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.See1Duck + "Bryophyta Fighter",
        description = "Automated Bryophyta (Moss Giantess) boss fighting plugin with chest looting",
        tags = {"bryophyta", "boss", "combat", "moss giantess", "microbot"},
        enabledByDefault = false
)
@Slf4j
public class BryophytaPlugin extends Plugin {
    
    @Inject
    private BryophytaConfig config;
    
    @Inject
    private OverlayManager overlayManager;
    
    @Inject
    private BryophytaOverlay bryophytaOverlay;
    
    @Inject
    private BryophytaScript bryophytaScript;

    @Provides
    BryophytaConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BryophytaConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(bryophytaOverlay);
        }
        bryophytaScript.run(config);
    }

    @Override
    protected void shutDown() {
        bryophytaScript.shutdown();
        overlayManager.remove(bryophytaOverlay);
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        // Game tick events can be handled here if needed
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE) {
            return;
        }

        String message = event.getMessage();
        
        // Handle important game messages
        if (message.contains("You hear the leaves stir...")) {
            BryophytaScript.setChestClicked(true);
        }
        
        if (message.contains("You need a Mossy key to open this chest.")) {
            BryophytaScript.setNoMossyKey(true);
        }
        
        if (message.contains("You can't loot the chest whilst Bryophyta is still attacking you!")) {
            BryophytaScript.setBossStillAlive(true);
        }
        
        if (message.contains("The loot spills out as you open the chest.")) {
            BryophytaScript.setChestLooted(true);
        }
    }
}
