package com.regiontick;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class RegionTick extends JavaPlugin implements CommandExecutor, TabCompleter {
    private final Map<UUID, String> activeUses = new HashMap<>();
    private final Map<UUID, Integer> reductions = new HashMap<>();
    private BukkitTask task;

    @Override public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("region")).setExecutor(this);
        Objects.requireNonNull(getCommand("region")).setTabCompleter(this);
        long interval = Math.max(1, getConfig().getLong("settings.refresh-interval-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(this, this::tickPlayers, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshReductions, 1L, interval);
        getLogger().info("RegionTick enabled. Use /region tick.");
    }

    @Override public void onDisable() {
        if (task != null) task.cancel();
        activeUses.clear(); reductions.clear();
    }

    private void refreshReductions() {
        boolean globallyEnabled = getConfig().getBoolean("settings.enabled", true);
        boolean pingEnabled = getConfig().getBoolean("ping.enabled", true);
        boolean tpsEnabled = getConfig().getBoolean("tps.enabled", true);
        double[] tps = Bukkit.getTPS();
        int sample = Math.max(0, Math.min(2, getConfig().getInt("tps.sample", 0)));
        double currentTps = tps.length > sample ? tps[sample] : 20.0;
        boolean lowTps = tpsEnabled && currentTps < getConfig().getDouble("tps.below", 19.0);
        int tpsReduction = lowTps ? Math.max(0, getConfig().getInt("tps.reduce-ticks", 4)) : 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            int pingReduction = 0;
            if (globallyEnabled && pingEnabled) {
                int ping = Math.max(0, player.getPing());
                for (Map<?, ?> tier : getConfig().getMapList("ping.tiers")) {
                    int min = number(tier.get("min"), 0), max = number(tier.get("max"), Integer.MAX_VALUE);
                    if (ping >= min && ping <= max) pingReduction = Math.max(0, number(tier.get("reduce-ticks"), 0));
                }
            }
            reductions.put(player.getUniqueId(), globallyEnabled ? pingReduction + tpsReduction : 0);
        }
    }

    private int number(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }

    private void tickPlayers() {
        if (!getConfig().getBoolean("settings.enabled", true) || !getConfig().getBoolean("settings.utilities-enabled", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!player.hasActiveItem()) { activeUses.remove(id); continue; }
            ItemStack item = player.getActiveItem();
            if (item == null || item.getType().isAir()) continue;
            String action = actionFor(item.getType());
            if (action == null || !getConfig().getBoolean("actions." + action + ".enabled", true)) continue;
            int used = player.getActiveItemUsedTime();
            String signature = player.getActiveItemHand().name() + ":" + item.getType().name();
            String previous = activeUses.get(id);
            // Apply once at the beginning of each item-use session, not every server tick.
            if (used <= 1 && !signature.equals(previous)) {
                int configured = Math.max(0, getConfig().getInt("actions." + action + ".reduce-ticks", 0));
                int total = Math.max(0, reductions.getOrDefault(id, 0) + configured);
                int remaining = player.getActiveItemRemainingTime();
                int minimum = Math.max(0, getConfig().getInt("settings.minimum-duration-ticks", 1));
                if (remaining > 0 && total > 0) {
                    player.setActiveItemRemainingTime(Math.max(minimum, remaining - total));
                }
                activeUses.put(id, signature);
            } else if (used > 1 && previous == null) {
                activeUses.put(id, signature);
            }
            if (used > 1 && previous != null && !previous.equals(signature)) activeUses.put(id, signature);
        }
    }

    private String actionFor(Material material) {
        if (material == Material.BOW) return "bow";
        if (material == Material.CROSSBOW) return "crossbow";
        if (material == Material.TRIDENT) return "trident";
        if (material == Material.SHIELD) return "shield";
        if (material == Material.POTION || material == Material.HONEY_BOTTLE || material == Material.MILK_BUCKET) return "potions";
        if (material == Material.GOLDEN_APPLE || material == Material.ENCHANTED_GOLDEN_APPLE) return "golden-apples";
        if (material.isEdible()) return "food";
        return null;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("regiontick.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
        if (args.length == 0 || !args[0].equalsIgnoreCase("tick")) { help(sender); return true; }
        if (args.length == 1) {
            sender.sendMessage(ChatColor.GOLD + "RegionTick " + ChatColor.GRAY + "| enabled=" + getConfig().getBoolean("settings.enabled") + ", utilities=" + getConfig().getBoolean("settings.utilities-enabled"));
            sender.sendMessage(ChatColor.YELLOW + "Ping tiers and TPS compensation are configured in config.yml. Use /region tick help."); return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "all" -> { getConfig().set("settings.enabled", true); getConfig().set("settings.utilities-enabled", true); saveConfig(); sender.sendMessage(ChatColor.GREEN + "RegionTick and all supported utility adjustments enabled."); }
            case "utilities" -> { boolean next = !getConfig().getBoolean("settings.utilities-enabled", true); getConfig().set("settings.utilities-enabled", next); saveConfig(); sender.sendMessage(ChatColor.GREEN + "Utilities adjustments: " + next); }
            case "reload" -> { reloadConfig(); activeUses.clear(); refreshReductions(); sender.sendMessage(ChatColor.GREEN + "RegionTick config reloaded."); }
            case "ping" -> sender.sendMessage(ChatColor.YELLOW + "Ping tiers: " + getConfig().getMapList("ping.tiers"));
            case "tps" -> { double[] values = Bukkit.getTPS(); sender.sendMessage(ChatColor.YELLOW + "TPS: " + String.format(Locale.US, "%.2f / %.2f / %.2f", values[0], values[1], values[2]) + " | below=" + getConfig().getDouble("tps.below") + " | reduction=" + getConfig().getInt("tps.reduce-ticks")); }
            case "set" -> setAction(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void setAction(CommandSender sender, String[] args) {
        if (args.length != 4) { sender.sendMessage(ChatColor.RED + "Usage: /region tick set <action> <ticks>"); return; }
        String action = args[2].toLowerCase(Locale.ROOT);
        List<String> valid = List.of("food", "golden-apples", "potions", "bow", "crossbow", "trident", "shield");
        if (!valid.contains(action)) { sender.sendMessage(ChatColor.RED + "Unknown action. Valid: " + String.join(", ", valid)); return; }
        try {
            int ticks = Integer.parseInt(args[3]);
            if (ticks < 0 || ticks > 100) throw new NumberFormatException();
            getConfig().set("actions." + action + ".reduce-ticks", ticks); saveConfig();
            sender.sendMessage(ChatColor.GREEN + action + " reduction set to " + ticks + " ticks.");
        } catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "Ticks must be a number from 0 to 100."); }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "RegionTick commands:");
        sender.sendMessage(ChatColor.YELLOW + "/region tick all " + ChatColor.GRAY + "- enable all supported adjustments");
        sender.sendMessage(ChatColor.YELLOW + "/region tick utilities " + ChatColor.GRAY + "- toggle utility adjustments");
        sender.sendMessage(ChatColor.YELLOW + "/region tick set <action> <ticks> " + ChatColor.GRAY + "- set extra reduction");
        sender.sendMessage(ChatColor.YELLOW + "/region tick ping | tps | reload | help");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("tick"), args[0]);
        if (args.length == 2) return filter(List.of("all", "utilities", "set", "ping", "tps", "reload", "help"), args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("set")) return filter(List.of("food", "golden-apples", "potions", "bow", "crossbow", "trident", "shield"), args[2]);
        return Collections.emptyList();
    }
    private List<String> filter(List<String> values, String prefix) { return values.stream().filter(s -> s.startsWith(prefix.toLowerCase(Locale.ROOT))).toList(); }
}
