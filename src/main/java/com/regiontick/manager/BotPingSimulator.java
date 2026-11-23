
package com.regiontick.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class BotPingSimulator {

    private final JavaPlugin plugin;

    private final Map<UUID, Integer> fixedDelays =
            new ConcurrentHashMap<>();

    private final Map<UUID, int[]> randomDelays =
            new ConcurrentHashMap<>();

    public BotPingSimulator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setDelay(Player player, int milliseconds) {
        if (milliseconds < 0 || milliseconds > 1000) {
            throw new IllegalArgumentException(
                    "Delay must be between 0 and 1000 ms"
            );
        }

        fixedDelays.put(player.getUniqueId(), milliseconds);
        randomDelays.remove(player.getUniqueId());
    }

    public void setRandomDelay(Player player, int min, int max) {
        if (min < 0 || max < min || max > 1000) {
            throw new IllegalArgumentException("Invalid delay range");
        }

        randomDelays.put(player.getUniqueId(), new int[]{min, max});
        fixedDelays.remove(player.getUniqueId());
    }

    public void disable(Player player) {
        fixedDelays.remove(player.getUniqueId());
        randomDelays.remove(player.getUniqueId());
    }

    public int getSimulatedDelay(Player player) {
        UUID uuid = player.getUniqueId();

        if (randomDelays.containsKey(uuid)) {
            int[] range = randomDelays.get(uuid);

            return ThreadLocalRandom.current()
                    .nextInt(range[0], range[1] + 1);
        }

        return fixedDelays.getOrDefault(uuid, 0);
    }

    public void executeDelayed(Player player, Runnable action) {
        int delay = getSimulatedDelay(player);

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (player.isOnline()) {
                        action.run();
                    }
                },
                Math.max(0, (long) Math.ceil(delay / 50.0))
        );
    }

    public void clear() {
        fixedDelays.clear();
        randomDelays.clear();
    }
}
