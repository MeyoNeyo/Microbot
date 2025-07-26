package net.runelite.client.plugins.microbot.apexfighter;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class ApexFighterInfoOverlay extends OverlayPanel {
        private final ApexFighterConfig config;
        private final ApexFighterPlugin plugin;

        @Inject
        ApexFighterInfoOverlay(ApexFighterPlugin plugin, ApexFighterConfig config) {
                super(plugin);
                this.plugin = plugin;
                this.config = config;
                setPosition(OverlayPosition.TOP_LEFT);
                setNaughty();
        }

        @Override
        public Dimension render(Graphics2D graphics) {
                try {
                        panelComponent.setPreferredSize(new Dimension(250, 400));
                        panelComponent.getChildren().add(TitleComponent.builder()
                                        .text("\uD83E\uDD86 ApexFighter \uD83E\uDD86")
                                        .color(Color.ORANGE)
                                        .build());

                        panelComponent.getChildren().add(LineComponent.builder()
                                        .left("Play Style: " + config.playStyle() + "("
                                                        + config.playStyle().getPrimaryTickInterval() + ","
                                                        + config.playStyle().getSecondaryTickInterval() + ")")
                                        .right("Attack cooldown: " + ApexFighterPlugin.getCooldown())
                                        .build());

                        // --- Runtime Overlay ---
                        panelComponent.getChildren().add(LineComponent.builder()
                                        .left("Runtime:")
                                        .right(plugin.getTimeRunning())
                                        .build());
                        panelComponent.getChildren().add(LineComponent.builder().build());
                        panelComponent.getChildren().add(LineComponent.builder()
                                        .left(Microbot.status)
                                        .right("Version:" + ApexFighterPlugin.version)
                                        .build());

                        // --- Custom Food Display ---
                        String customFoodList = config.customFoodPriority();
                        if (customFoodList != null && !customFoodList.trim().isEmpty()) {
                                panelComponent.getChildren().add(LineComponent.builder()
                                                .left("Custom Foods:")
                                                .right(customFoodList.replace(",", ", "))
                                                .build());
                                
                                // Show count of each custom food type in inventory
                                java.util.List<net.runelite.client.plugins.microbot.util.misc.Rs2Food> customFoods = 
                                    net.runelite.client.plugins.microbot.apexfighter.bank.BankerScript.parseCustomFoodPriority(customFoodList);
                                
                                int totalCustomFoodCount = 0;
                                for (net.runelite.client.plugins.microbot.util.misc.Rs2Food food : customFoods) {
                                    int count = net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory.count(food.getId());
                                    totalCustomFoodCount += count;
                                    if (count > 0) {
                                        panelComponent.getChildren().add(LineComponent.builder()
                                                        .left("  " + food.getName() + ":")
                                                        .right("x" + count)
                                                        .build());
                                    }
                                }
                                
                                panelComponent.getChildren().add(LineComponent.builder()
                                                .left("Total Custom Food:")
                                                .right("x" + totalCustomFoodCount)
                                                .build());
                        } else {
                                // Show automatic food count
                                int totalFoodCount = net.runelite.client.plugins.microbot.util.misc.Rs2Food.getIds().stream()
                                    .mapToInt(net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory::count)
                                    .sum();
                                panelComponent.getChildren().add(LineComponent.builder()
                                                .left("Food Count (Auto):")
                                                .right("x" + totalFoodCount)
                                                .build());
                        }

                        // --- Loot/Cost/Profit Tracking Overlay ---
                        long totalValue = ApexFighterPlugin.getTotalLootValue();
                        panelComponent.getChildren().add(LineComponent.builder()
                                        .left("Total Loot Value:")
                                        .right(ApexFighterPlugin.formatGp(totalValue) + " gp")
                                        .build());
                        /*
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Total Cost:")
                         * .right(ApexFighterPlugin.formatGp(totalCost) + " gp")
                         * .build());
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Profit:")
                         * .right(ApexFighterPlugin.formatGp(profit) + " gp")
                         * .build());
                         */

                        // Show resource usage breakdown
                        /*
                         * panelComponent.getChildren().add(LineComponent.builder().
                         * left("Resource Usage:").build());
                         * for (Map.Entry<Integer, Integer> entry :
                         * net.runelite.client.plugins.microbot.apexfighter.CostTracker.getInstance().
                         * getResourceUsage().entrySet()) {
                         * int itemId = entry.getKey();
                         * int qty = entry.getValue();
                         * String itemName = "";
                         * try {
                         * itemName = Microbot.getItemManager().getItemComposition(itemId).getName();
                         * } catch (Exception ignored) {}
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left(itemName.isEmpty() ? ("ID: " + itemId) : itemName)
                         * .right("x" + qty)
                         * .build());
                         * }
                         */

                        // Show looted items as before
                        if (!ApexFighterPlugin.sessionLoot.isEmpty()) {
                                panelComponent.getChildren().add(
                                                LineComponent.builder().left("Looted Items This Session:").build());
                                for (LootEntry entry : ApexFighterPlugin.sessionLoot.values()) {
                                        panelComponent.getChildren().add(LineComponent.builder()
                                                        .left(entry.getName())
                                                        .right("x" + entry.getQuantity())
                                                        .build());
                                }
                        }

                        /*
                         * // Debug Banking Status
                         * panelComponent.getChildren().add(LineComponent.builder().build());
                         * panelComponent.getChildren().add(LineComponent.builder().
                         * left("=== BANKING DEBUG ===").build());
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Banking Enabled:")
                         * .right(String.valueOf(config.bank()))
                         * .build());
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Use Food:")
                         * .right(String.valueOf(config.useFood()))
                         * .build());
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Food Value:")
                         * .right(String.valueOf(config.foodValue()))
                         * .build());
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Min Free Slots:")
                         * .right(String.valueOf(config.minFreeSlots()))
                         * .build());
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Current Empty Slots:")
                         * .right(String.valueOf(Rs2Inventory.emptySlotCount()))
                         * .build());
                         * 
                         * // Count food items using Rs2Food
                         * int foodCount =
                         * net.runelite.client.plugins.microbot.util.misc.Rs2Food.getIds().stream()
                         * .mapToInt(Rs2Inventory::count).sum();
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Food Count (Rs2Food):")
                         * .right(String.valueOf(foodCount))
                         * .build());
                         * 
                         * // Count food items using inventory food method
                         * int inventoryFoodCount = Rs2Inventory.getInventoryFood().size();
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Food Count (Inventory):")
                         * .right(String.valueOf(inventoryFoodCount))
                         * .build());
                         * 
                         * // Show actual banking needed result
                         * boolean isBankingRequired =
                         * net.runelite.client.plugins.microbot.apexfighter.bank.BankerScript.
                         * isBankingNeeded(config);
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Banking Needed:")
                         * .right(String.valueOf(isBankingRequired))
                         * .build());
                         * 
                         * // Show upkeep items status
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Upkeep items:")
                         * .right(net.runelite.client.plugins.microbot.apexfighter.bank.BankerScript.
                         * getUpkeepItemsDebugInfo(config))
                         * .build());
                         * 
                         * // Show specific shark count
                         * int sharkCount = Rs2Inventory.count(385); // Shark ID
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Shark Count (ID 385):")
                         * .right(String.valueOf(sharkCount))
                         * .build());
                         * 
                         * // Show if shark is in Rs2Food IDs
                         * boolean sharkInRs2Food =
                         * net.runelite.client.plugins.microbot.util.misc.Rs2Food.getIds().contains(385)
                         * ;
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Shark in Rs2Food IDs:")
                         * .right(String.valueOf(sharkInRs2Food))
                         * .build());
                         * 
                         * // Show banking needed status
                         * boolean bankingNeeded =
                         * net.runelite.client.plugins.microbot.apexfighter.bank.BankerScript.
                         * isBankingNeeded(config);
                         * panelComponent.getChildren().add(LineComponent.builder()
                         * .left("Banking Needed:")
                         * .right(String.valueOf(bankingNeeded))
                         * .build());
                         */
                } catch (Exception ex) {
                        Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
                }
                return super.render(graphics);
        }
}
