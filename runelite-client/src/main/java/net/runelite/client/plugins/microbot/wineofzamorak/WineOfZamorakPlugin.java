package net.runelite.client.plugins.microbot.wineofzamorak;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = "777 Wine of Zamorak",
        description = "Automates collecting Wine of Zamorak using telekinetic grab",
        tags = {"microbot", "wine", "zamorak", "magic", "telekinetic grab"},
        enabledByDefault = false
)
@Slf4j
public class WineOfZamorakPlugin extends Plugin {
    @Inject
    private WineOfZamorakConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private WineOfZamorakOverlay wineOfZamorakOverlay;

    @Inject
    WineOfZamorakScript wineOfZamorakScript;

    @Provides
    WineOfZamorakConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WineOfZamorakConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(wineOfZamorakOverlay);
        }
        wineOfZamorakScript.run(config);
    }

    @Override
    protected void shutDown() {
        wineOfZamorakScript.shutdown();
        if (overlayManager != null) {
            overlayManager.remove(wineOfZamorakOverlay);
        }
    }

    public WineOfZamorakScript getWineOfZamorakScript() {
        return wineOfZamorakScript;
    }
}
