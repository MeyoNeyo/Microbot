package net.runelite.client.plugins.microbot.wineofzamorak.enums;

public enum WineOfZamorakState {
    IDLE("Idle"),
    CHECKING_PRECONDITIONS("Checking Preconditions"),
    TRAVELING_TO_WINE_SPOT("Traveling to Wine Spot"),
    WAITING_FOR_WINE("Waiting for Wine"),
    CASTING_TELEKINETIC_GRAB("Casting Telekinetic Grab"),
    WORLD_HOPPING("World Hopping"),
    BANKING("Banking"),
    STOPPING("Stopping");

    private final String description;

    WineOfZamorakState(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return description;
    }
}
