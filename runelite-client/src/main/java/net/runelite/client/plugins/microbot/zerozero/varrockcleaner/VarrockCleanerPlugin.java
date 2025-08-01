package net.runelite.client.plugins.microbot.zerozero.varrockcleaner;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.ui.util.ConditionConfigPanelUtil;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

@PluginDescriptor(
        name = PluginDescriptor.zerozero + "Museum Cleaner",
        description = "Varrock Museum Cleaner",
        tags = {"varrock", "museum", "cleaner"},
        enabledByDefault = false
)
public class VarrockCleanerPlugin extends Plugin {
    static final String CONFIG = "varrockmuseum";

    @Inject
    private VarrockCleanerScript script;

    @Inject
    private VarrockCleanerConfig config;

    @Override
    protected void startUp() {
        // Validate skill level if lamp usage is enabled
        if (config.useAntiqueLamps()) {
            SwingUtilities.invokeLater(this::validateSkillLevelOnStartup);
        }
        
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.stop();
    }

    /**
     * Validates the selected skill level on plugin startup and shows warning if level is too low
     */
    private void validateSkillLevelOnStartup() {
        if (!Microbot.isLoggedIn()) {
            return; // Can't check skill levels if not logged in
        }
        
        LampSkill selectedSkill = config.lampSkillSelection();
        int skillLevel = Rs2Player.getRealSkillLevel(selectedSkill.getSkill());
        
        if (skillLevel < 10) {
            String message = String.format(
                "Warning: Cannot use antique lamps on %s (level %d).\n\n" +
                "Antique lamps require the skill to be level 10 or higher.\n" +
                "Please select a different skill in the plugin configuration or level up %s to at least level 10.\n\n" +
                "The lamp usage feature will be disabled until a valid skill is selected.",
                selectedSkill.getDisplayName(), 
                skillLevel,
                selectedSkill.getDisplayName()
            );
            
            ConditionConfigPanelUtil.showWarningDialog(
                null,
                message,
                "Antique Lamp - Skill Level Too Low"
            );
        }
    }

    @Provides
    VarrockCleanerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(VarrockCleanerConfig.class);
    }
}
