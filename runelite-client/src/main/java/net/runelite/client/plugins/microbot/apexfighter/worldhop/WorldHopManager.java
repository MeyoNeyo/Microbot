package net.runelite.client.plugins.microbot.apexfighter.worldhop;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.apexfighter.ApexFighterPlugin;
import net.runelite.client.plugins.microbot.apexfighter.enums.State;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;

public class WorldHopManager {

    /**
     * Hops to a random safe world of the same type (members/free) as the current world.
     * Filters out dangerous, full, and current worlds. Runs on the client thread.
     */
    public static void hopWorlds() {
        Microbot.log("[ApexFighter] Hopping worlds due to area conditions.");
        Microbot.getClientThread().invokeLater(() -> {
            net.runelite.http.api.worlds.WorldResult worldResult = Microbot.getWorldService().getWorlds();
            if (worldResult == null) return;

            int currentWorld = Microbot.getClient().getWorld();
            boolean isMember = Microbot.getClient().getWorldType().contains(net.runelite.api.WorldType.MEMBERS);

            java.util.List<net.runelite.http.api.worlds.World> worlds = worldResult.getWorlds().stream()
                .filter(w -> w.getId() != currentWorld)
                .filter(w -> w.getPlayers() < 2000) // 2000 is the typical world cap
                .filter(w -> w.getTypes().stream().noneMatch(t ->
                    t.toString().equals("PVP") ||
                    t.toString().equals("DEADMAN") ||
                    t.toString().equals("HIGH_RISK") ||
                    t.toString().equals("SKILL_TOTAL") ||
                    t.toString().equals("QUEST_SPEEDRUNNING") ||
                    t.toString().equals("PVP_ARENA") ||
                    t.toString().equals("SEASONAL") ||
                    t.toString().equals("BETA_WORLD") ||
                    t.toString().equals("NOSAVE_MODE") ||
                    t.toString().equals("FRESH_START_WORLD")
                ))
                .filter(w -> isMember == w.getTypes().stream().anyMatch(t -> t.toString().equals("MEMBERS")))
                .collect(java.util.stream.Collectors.toList());

            if (worlds.isEmpty()) return;

            net.runelite.http.api.worlds.World targetWorld = worlds.get(new java.util.Random().nextInt(worlds.size()));

            // Convert HTTP world to API world
            net.runelite.api.World rsWorld = Microbot.getClient().createWorld();
            rsWorld.setActivity(targetWorld.getActivity());
            rsWorld.setAddress(targetWorld.getAddress());
            rsWorld.setId(targetWorld.getId());
            rsWorld.setPlayerCount(targetWorld.getPlayers());
            rsWorld.setLocation(targetWorld.getLocation());
            rsWorld.setTypes(net.runelite.client.util.WorldUtil.toWorldTypes(targetWorld.getTypes()));
            
            Microbot.log(rsWorld.toString());
            
            Microbot.getClient().changeWorld(rsWorld);
        });
    }
    private static int lastWorld = -1;
    private static boolean isHopping = false;

    public static void handleWorldHopIfNeeded(int maxPlayers, int maxSecondsWithoutMonsters, int secondsWithoutMonsters, int playersInArea) {
        if (maxPlayers > 0 && playersInArea >= maxPlayers) {
            Microbot.log("[ApexFighter] Hopping worlds: too many players in area (" + playersInArea + " >= " + maxPlayers + ")");
            ApexFighterPlugin.setState(State.HOPPING_WORLDS);
            return;
        }
        if (maxSecondsWithoutMonsters > 0 && secondsWithoutMonsters >= maxSecondsWithoutMonsters) {
            Microbot.log("[ApexFighter] Hopping worlds: no monsters in area for " + secondsWithoutMonsters + " seconds");
            ApexFighterPlugin.setState(State.HOPPING_WORLDS);
            return;
        }
    }

    public static void processWorldHop() {
        if (ApexFighterPlugin.getState() != State.HOPPING_WORLDS) return;
        if (Rs2Combat.inCombat()) {
            Microbot.status = "Waiting to leave combat before hopping worlds...";
            return;
        }
        if (!isHopping) {
            lastWorld = Microbot.getClient().getWorld();
            isHopping = true;
            Microbot.log("[ApexFighter] Hopping worlds due to area conditions.");
            hopWorlds();
            return;
        }
        if (Microbot.getClient().getWorld() != lastWorld) {
            isHopping = false;
            ApexFighterPlugin.setState(State.IDLE);
            Microbot.status = "World hopped, resuming.";
        }
    }
}
