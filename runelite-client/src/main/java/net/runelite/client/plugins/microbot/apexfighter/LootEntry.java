package net.runelite.client.plugins.microbot.apexfighter;

public class LootEntry {
    private final int itemId;
    private final String name;
    private int quantity;

    public LootEntry(int itemId, String name, int quantity) {
        this.itemId = itemId;
        this.name = name;
        this.quantity = quantity;
    }

    public int getItemId() { return itemId; }
    public String getName() { return name; }
    public int getQuantity() { return quantity; }
    public void addQuantity(int amount) { this.quantity += amount; }
    public void subtractQuantity(int amount) { 
        this.quantity = Math.max(0, this.quantity - amount); 
    }
}
