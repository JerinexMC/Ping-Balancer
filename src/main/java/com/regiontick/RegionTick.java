
package com.regiontick;

import com.regiontick.manager.BotPingSimulator;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class RegionTick extends JavaPlugin
        implements CommandExecutor, TabCompleter {

    private final Map<UUID, String> activeUses = new HashMap<>();
    private final Map<UUID, Integer> reductions = new HashMap<>();

    private BukkitTask playerTask;
    private BukkitTask refreshTask;

    private BotPingSimulator botPingSimulator;

    private static final List<String> VALID_ACTIONS = List.of(
            "food",
            "golden-apples",
            "potions",
            "bow",
            "crossbow",
            "trident",
            "shield"
    );

    // ==========================================
    // ENABLE / DISABLE
    // ==========================================

    @Override
    public void onEnable() {

        saveDefaultConfig();

        botPingSimulator = new BotPingSimulator(this);

        if (getCommand("region") == null) {
            getLogger().severe("Command 'region' is missing from plugin.yml!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getCommand("region").setExecutor(this);
        getCommand("region").setTabCompleter(this);

        long interval = Math.max(
                1L,
                getConfig().getLong("settings.refresh-interval-ticks", 20L)
        );

        refreshReductions();

        playerTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::tickPlayers,
                1L,
                1L
        );

        refreshTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::refreshReductions,
                interval,
                interval
        );

        getLogger().info("--------------------------------");
        getLogger().info("RegionTick 1.0.0 enabled!");
        getLogger().info("Ping compensation: " +
                getConfig().getBoolean("ping.enabled", true));
        getLogger().info("TPS compensation: " +
                getConfig().getBoolean("tps.enabled", true));
        getLogger().info("Utility adjustments: " +
                getConfig().getBoolean("settings.utilities-enabled", true));
        getLogger().info("BotPingSimulator initialized!");
        getLogger().info("Use /region tick help");
        getLogger().info("--------------------------------");
    }

    @Override
    public void onDisable() {

        if (playerTask != null) {
            playerTask.cancel();
        }

        if (refreshTask != null) {
            refreshTask.cancel();
        }

        activeUses.clear();
        reductions.clear();

        if (botPingSimulator != null) {
            botPingSimulator.clear();
        }

        getLogger().info("RegionTick disabled.");
    }

    // ==========================================
    // BOT PING SIMULATOR ACCESS
    // ==========================================

    public BotPingSimulator getBotPingSimulator() {
        return botPingSimulator;
    }

    // ==========================================
    // PING AND TPS COMPENSATION
    // ==========================================

    private void refreshReductions() {

        boolean globallyEnabled =
                getConfig().getBoolean("settings.enabled", true);

        boolean pingEnabled =
                getConfig().getBoolean("ping.enabled", true);

        boolean tpsEnabled =
                getConfig().getBoolean("tps.enabled", true);

        double[] tps = Bukkit.getTPS();

        int sample = Math.max(
                0,
                Math.min(2, getConfig().getInt("tps.sample", 0))
        );

        double currentTps =
                tps.length > sample ? tps[sample] : 20.0;

        boolean lowTps = tpsEnabled
                && currentTps < getConfig().getDouble("tps.below", 19.0);

        int tpsReduction = lowTps
                ? Math.max(0, getConfig().getInt("tps.reduce-ticks", 4))
                : 0;

        for (Player player : Bukkit.getOnlinePlayers()) {

            UUID uuid = player.getUniqueId();

            int pingReduction = 0;

            if (globallyEnabled && pingEnabled) {

                int ping = Math.max(0, player.getPing());

                for (Map<?, ?> tier : getConfig().getMapList("ping.tiers")) {

                    int min = number(tier.get("min"), 0);

                    int max = number(
                            tier.get("max"),
                            Integer.MAX_VALUE
                    );

                    if (ping >= min && ping <= max) {

                        pingReduction = Math.max(
                                0,
                                number(tier.get("reduce-ticks"), 0)
                        );
                    }
                }
            }

            int totalReduction = globallyEnabled
                    ? pingReduction + tpsReduction
                    : 0;

            reductions.put(uuid, totalReduction);
        }
    }

    private int number(Object value, int fallback) {

        return value instanceof Number n
                ? n.intValue()
                : fallback;
    }

    // ==========================================
    // ITEM USE SPEED ADJUSTMENTS
    // ==========================================

    private void tickPlayers() {

        if (!getConfig().getBoolean("settings.enabled", true)
                || !getConfig().getBoolean("settings.utilities-enabled", true)) {

            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {

            UUID uuid = player.getUniqueId();

            if (!player.hasActiveItem()) {
                activeUses.remove(uuid);
                continue;
            }

            ItemStack item = player.getActiveItem();

            if (item == null || item.getType().isAir()) {
                continue;
            }

            String action = actionFor(item.getType());

            if (action == null) {
                continue;
            }

            if (!getConfig().getBoolean(
                    "actions." + action + ".enabled",
                    true
            )) {
                continue;
            }

            int used = player.getActiveItemUsedTime();

            String signature =
                    player.getActiveItemHand().name()
                            + ":" + item.getType().name();

            String previous = activeUses.get(uuid);

            // Only apply once per item-use session.
            if (used <= 1 && !signature.equals(previous)) {

                int configuredReduction = Math.max(
                        0,
                        getConfig().getInt(
                                "actions." + action + ".reduce-ticks",
                                0
                        )
                );

                int compensation = reductions.getOrDefault(uuid, 0);

                int totalReduction =
                        configuredReduction + compensation;

                int remaining =
                        player.getActiveItemRemainingTime();

                int minimumDuration = Math.max(
                        0,
                        getConfig().getInt(
                                "settings.minimum-duration-ticks",
                                1
                        )
                );

                if (remaining > 0 && totalReduction > 0) {

                    int newRemaining = Math.max(
                            minimumDuration,
                            remaining - totalReduction
                    );

                    player.setActiveItemRemainingTime(newRemaining);
                }

                activeUses.put(uuid, signature);
            }
        }
    }

    // ==========================================
    // ACTION DETECTION
    // ==========================================

    private String actionFor(Material material) {

        if (material == Material.BOW) {
            return "bow";
        }

        if (material == Material.CROSSBOW) {
            return "crossbow";
        }

        if (material == Material.TRIDENT) {
            return "trident";
        }

        if (material == Material.SHIELD) {
            return "shield";
        }

        if (material == Material.POTION
                || material == Material.HONEY_BOTTLE
                || material == Material.MILK_BUCKET) {

            return "potions";
        }

        if (material == Material.GOLDEN_APPLE
                || material == Material.ENCHANTED_GOLDEN_APPLE) {

            return "golden-apples";
        }

        if (material.isEdible()) {
            return "food";
        }

        return null;
    }

    // ==========================================
    // COMMAND HANDLER
    // ==========================================

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!sender.hasPermission("regiontick.admin")) {

            sender.sendMessage(
                    ChatColor.RED + "You don't have permission."
            );

            return true;
        }

        if (args.length == 0
                || !args[0].equalsIgnoreCase("tick")) {

            help(sender);
            return true;
        }

        if (args.length == 1) {

            sender.sendMessage(
                    ChatColor.GOLD + "RegionTick"
                            + ChatColor.GRAY
                            + " | Enabled: "
                            + getConfig().getBoolean("settings.enabled")
                            + " | Utilities: "
                            + getConfig().getBoolean("settings.utilities-enabled")
            );

            sender.sendMessage(
                    ChatColor.YELLOW
                            + "Use /region tick help for commands."
            );

            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);

        switch (sub) {

            case "help" -> help(sender);

            case "all" -> {

                getConfig().set("settings.enabled", true);
                getConfig().set("settings.utilities-enabled", true);

                saveConfig();
                refreshReductions();

                sender.sendMessage(
                        ChatColor.GREEN
                                + "All RegionTick adjustments enabled."
                );
            }

            case "utilities" -> {

                boolean next = !getConfig().getBoolean(
                        "settings.utilities-enabled",
                        true
                );

                getConfig().set("settings.utilities-enabled", next);

                if (!next) {
                    activeUses.clear();
                }

                saveConfig();

                sender.sendMessage(
                        ChatColor.GREEN
                                + "Utilities adjustments: "
                                + next
                );
            }

            case "reload" -> {

                reloadConfig();

                activeUses.clear();
                refreshReductions();

                sender.sendMessage(
                        ChatColor.GREEN
                                + "RegionTick configuration reloaded."
                );
            }

            case "ping" -> {

                sender.sendMessage(
                        ChatColor.GOLD + "Ping compensation tiers:"
                );

                for (Map<?, ?> tier :
                        getConfig().getMapList("ping.tiers")) {

                    sender.sendMessage(
                            ChatColor.YELLOW
                                    + number(tier.get("min"), 0)
                                    + "-"
                                    + number(tier.get("max"), 999999)
                                    + "ms: -"
                                    + number(tier.get("reduce-ticks"), 0)
                                    + " ticks"
                    );
                }
            }

            case "tps" -> {

                double[] values = Bukkit.getTPS();

                sender.sendMessage(
                        ChatColor.GOLD + "Server TPS:"
                );

                sender.sendMessage(
                        ChatColor.YELLOW
                                + String.format(
                                        Locale.US,
                                        "%.2f / %.2f / %.2f",
                                        values[0],
                                        values[1],
                                        values[2]
                                )
                );

                sender.sendMessage(
                        ChatColor.GRAY
                                + "Threshold: "
                                + getConfig().getDouble("tps.below", 19.0)
                                + " | Reduction: "
                                + getConfig().getInt("tps.reduce-ticks", 4)
                                + " ticks"
                );
            }

            case "set" -> setAction(sender, args);

            default -> help(sender);
        }

        return true;
    }

    // ==========================================
    // SET ACTION REDUCTION
    // ==========================================

    private void setAction(
            CommandSender sender,
            String[] args
    ) {

        if (args.length != 4) {

            sender.sendMessage(
                    ChatColor.RED
                            + "Usage: /region tick set <action> <ticks>"
            );

            return;
        }

        String action = args[2].toLowerCase(Locale.ROOT);

        if (!VALID_ACTIONS.contains(action)) {

            sender.sendMessage(
                    ChatColor.RED
                            + "Unknown action. Valid actions: "
                            + String.join(", ", VALID_ACTIONS)
            );

            return;
        }

        try {

            int ticks = Integer.parseInt(args[3]);

            if (ticks < 0 || ticks > 100) {
                throw new NumberFormatException();
            }

            getConfig().set(
                    "actions." + action + ".reduce-ticks",
                    ticks
            );

            saveConfig();

            sender.sendMessage(
                    ChatColor.GREEN
                            + action
                            + " reduction set to -"
                            + ticks
                            + " ticks."
            );

        } catch (NumberFormatException ex) {

            sender.sendMessage(
                    ChatColor.RED
                            + "Ticks must be a number from 0 to 100."
            );
        }
    }

    // ==========================================
    // HELP
    // ==========================================

    private void help(CommandSender sender) {

        sender.sendMessage(
                ChatColor.GOLD + "====== RegionTick ======"
        );

        sender.sendMessage(
                ChatColor.YELLOW
                        + "/region tick"
                        + ChatColor.GRAY
                        + " - Show plugin status"
        );

        sender.sendMessage(
                ChatColor.YELLOW
                        + "/region tick all"
                        + ChatColor.GRAY
                        + " - Enable all adjustments"
        );

        sender.sendMessage(
                ChatColor.YELLOW
                        + "/region tick utilities"
                        + ChatColor.GRAY
                        + " - Toggle utility adjustments"
        );

        sender.sendMessage(
                ChatColor.YELLOW
                        + "/region tick set <action> <ticks>"
                        + ChatColor.GRAY
                        + " - Change action reduction"
        );

        sender.sendMessage(
                ChatColor.YELLOW
                        + "/region tick ping"
                        + ChatColor.GRAY
                        + " - View ping tiers"
        );

        sender.sendMessage(
                ChatColor.YELLOW
                        + "/region tick tps"
                        + ChatColor.GRAY
                        + " - View TPS compensation"
        );

        sender.sendMessage(
                ChatColor.YELLOW
                        + "/region tick reload"
                        + ChatColor.GRAY
                        + " - Reload config"
        );

        sender.sendMessage(
                ChatColor.YELLOW
                        + "/region tick help"
        );
    }

    // ==========================================
    // TAB COMPLETION
    // ==========================================

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {

        if (args.length == 1) {

            return filter(
                    List.of("tick"),
                    args[0]
            );
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("tick")) {

            return filter(
                    List.of(
                            "all",
                            "utilities",
                            "set",
                            "ping",
                            "tps",
                            "reload",
                            "help"
                    ),
                    args[1]
            );
        }

        if (args.length == 3
                && args[1].equalsIgnoreCase("set")) {

            return filter(
                    VALID_ACTIONS,
                    args[2]
            );
        }

        if (args.length == 4
                && args[1].equalsIgnoreCase("set")) {

            return filter(
                    List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "10"),
                    args[3]
            );
        }

        return Collections.emptyList();
    }

    private List<String> filter(
            List<String> values,
            String prefix
    ) {

        String lower = prefix.toLowerCase(Locale.ROOT);

        return values.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
