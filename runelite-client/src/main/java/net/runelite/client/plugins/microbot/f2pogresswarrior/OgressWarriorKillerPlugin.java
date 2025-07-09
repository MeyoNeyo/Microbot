package net.runelite.client.plugins.microbot.f2pogresswarrior;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.zerozero + "F2P Ogress Warrior Killer",
        description = "Automated F2P Ogress Warrior killer with advanced features",
        tags = {"f2p", "ogress", "combat", "warrior", "zerozero"},
        enabledByDefault = false
)
public class OgressWarriorKillerPlugin extends Plugin {
    static final String CONFIG = "ogressWarriorKiller";

    @Inject
    private OgressWarriorKillerScript script;

    @Inject
    private OgressWarriorKillerConfig config;
    
    @Inject
    private OgressWarriorKillerOverlay overlay;
    
    @Inject
    private OverlayManager overlayManager;

    @Override
    protected void startUp() {
        overlay.setScript(script);
        overlay.setConfig(config);
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.logOnceToChat("Stopping F2P Ogress Warrior Killer plugin...", false, config);
        overlayManager.remove(overlay);
        script.shutdown();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals(CONFIG)) return;

        // Only update config, do not start/stop script here
        script.logOnceToChat("Configuration changed. Updating script settings.", true, config);
        script.updateConfig(config);
    }

    @Provides
    OgressWarriorKillerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(OgressWarriorKillerConfig.class);
    }
}
