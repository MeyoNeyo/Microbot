package net.runelite.client.plugins.microbot.apexfighter;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks resource usage and calculates total cost for the current session.
 */
public class CostTracker {
    private static final CostTracker INSTANCE = new CostTracker();
    private final Map<Integer, Integer> resourceUsage = new HashMap<>(); // itemId -> quantity used
    private final Map<Integer, Integer> gePrices = new HashMap<>(); // itemId -> GE price

    private CostTracker() {}

    public static CostTracker getInstance() {
        return INSTANCE;
    }

    public void addUsage(int itemId, int amount) {
        resourceUsage.put(itemId, resourceUsage.getOrDefault(itemId, 0) + amount);
    }

    public void setGEPrice(int itemId, int price) {
        gePrices.put(itemId, price);
    }

    public int getGEPrice(int itemId) {
        return gePrices.getOrDefault(itemId, 0);
    }

    public int getUsage(int itemId) {
        return resourceUsage.getOrDefault(itemId, 0);
    }

    public int getTotalCost() {
        int total = 0;
        for (Map.Entry<Integer, Integer> entry : resourceUsage.entrySet()) {
            int itemId = entry.getKey();
            int qty = entry.getValue();
            int price = getGEPrice(itemId);
            total += qty * price;
        }
        return total;
    }

    public Map<Integer, Integer> getResourceUsage() {
        return resourceUsage;
    }

    public void reset() {
        resourceUsage.clear();
        gePrices.clear();
    }
}
