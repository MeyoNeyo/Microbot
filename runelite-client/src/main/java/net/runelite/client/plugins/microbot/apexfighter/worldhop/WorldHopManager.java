package net.runelite.client.plugins.microbot.apexfighter.worldhop;

import net.runelite.client.plugins.microbot.Microbot;

public class WorldHopManager {

    /**
     * Hops to a random safe world of the same type (members/free) as the current world.
     * Filters out dangerous, full, and current worlds. Runs on the client thread.
     */
    /**
     * Checks if scripts are paused due to world hopping and resumes them if the hop is complete.
     * Should be called regularly by scripts to ensure scripts resume after hop.
     */
    public static void processWorldHop() {
        // If scripts are paused and player is logged in and not hopping, resume scripts
        if (Microbot.pauseAllScripts.get()) {
            if (Microbot.isLoggedIn() && !Microbot.isHopping()) {
                Microbot.pauseAllScripts.compareAndSet(true, false);
                Microbot.log("[ApexFighter] World hop complete, resuming scripts.");
            }
        }
    }
    // Removed duplicate/broken hopWorlds method. Use safeHopWorlds or processWorldHop instead.

    /**
     * Safely hops worlds by pausing all scripts, performing the hop, and resuming scripts when safe.
     * This should be called by scripts that need to trigger a world hop due to area conditions.
     *
     * @param reason Optional log message for why the hop is occurring.
     */
    public static void safeHopWorlds(String reason) {
        if (reason != null && !reason.isEmpty()) {
            Microbot.log("[ApexFighter] Hopping worlds: " + reason);
        } else {
            Microbot.log("[ApexFighter] Hopping worlds due to area conditions.");
        }

        // Pause all scripts before hopping
        Microbot.pauseAllScripts.compareAndSet(false, true);

        // Perform the hop on the client thread
        Microbot.getClientThread().invokeLater(() -> {
            net.runelite.http.api.worlds.WorldResult worldResult = Microbot.getWorldService().getWorlds();
            if (worldResult == null) {
                Microbot.pauseAllScripts.compareAndSet(true, false); // Resume if failed
                return;
            }

            int currentWorld = Microbot.getClient().getWorld();
            boolean isMember = Microbot.getClient().getWorldType().contains(net.runelite.api.WorldType.MEMBERS);

            // Filter for safe, not-full, not-current, correct type worlds
            java.util.List<net.runelite.http.api.worlds.World> worlds = worldResult.getWorlds().stream()
                .filter(w -> w.getId() != currentWorld)
                .filter(w -> w.getPlayers() < 2000)
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

            if (worlds.isEmpty()) {
                Microbot.log("[ApexFighter] No suitable world found to hop.");
                Microbot.pauseAllScripts.compareAndSet(true, false);
                return;
            }

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

            // Resume all scripts after a short delay to allow the hop to complete
            new java.util.Timer().schedule(
                new java.util.TimerTask() {
                    @Override
                    public void run() {
                        Microbot.pauseAllScripts.compareAndSet(true, false);
                    }
                },
                5000 // 5 seconds, adjust as needed
            );
        });
    }


    // The following logic is now handled by safeHopWorlds and should not be duplicated here.
}
