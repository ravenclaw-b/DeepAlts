package net.ravenclaw.deepAlts;

import org.bukkit.Bukkit;
import org.bukkit.command.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DeepAltsConfigCommand implements CommandExecutor, TabCompleter {

    private final DeepAltsManager manager;

    public DeepAltsConfigCommand(DeepAltsManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("deepalts.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§6=== DeepAlts Config Commands ===");
            sender.sendMessage("§f/deepaltsconfig status §7- Show plugin status");
            sender.sendMessage("§f/deepaltsconfig rebuild §7- Rebuild graph (checks uncached IPs)");
            sender.sendMessage("§f/deepaltsconfig clearcache §7- Clear proxy cache");
            sender.sendMessage("§f/deepaltsconfig save §7- Manually save all data");
            sender.sendMessage("§f/deepaltsconfig reload §7- Reload all data from files");
            sender.sendMessage("§f/deepaltsconfig info §7- Show detailed statistics");
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "status":
                handleStatus(sender);
                break;
            case "rebuild":
                handleRebuild(sender);
                break;
            case "clearcache":
                handleClearCache(sender);
                break;
            case "save":
                handleSave(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "info":
                handleInfo(sender);
                break;
            default:
                sender.sendMessage("§cUnknown command. Use §f/deepaltsconfig§c for help.");
                break;
        }

        return true;
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage("§6=== DeepAlts Status ===");
        sender.sendMessage("§7Cache size: §f" + manager.getProxyCache().getCacheSize() + " §7entries");
        sender.sendMessage("§7Graph size: §f" + manager.getAltGraph().size() + " §7nodes");
        sender.sendMessage("§7Rate limit: §f" + manager.getProxyRateLimitStatus());
        sender.sendMessage("§7IP mappings: §f" + manager.getUuidToIpsMap().size() + " §7players");
    }

    private void handleRebuild(CommandSender sender) {
        sender.sendMessage("§6Rebuilding graph from IP data (checking uncached IPs for proxy status)...");

        CompletableFuture.runAsync(() -> {
            manager.rebuildGraph();

            // Schedule player message on the main thread
            Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
                sender.sendMessage("§aGraph rebuild completed!");
            });
        });
    }


    private void handleClearCache(CommandSender sender) {
        manager.getProxyCache().clearCache();
        sender.sendMessage("§aProxy cache cleared!");
    }

    private void handleSave(CommandSender sender) {
        sender.sendMessage("§6Saving all data...");
        CompletableFuture.runAsync(() -> {
            manager.saveAll();
            sender.sendMessage("§aAll data saved successfully!");
        });
    }

    private void handleReload(CommandSender sender) {
        sender.sendMessage("§6Reloading all data...");
        CompletableFuture.runAsync(() -> {
            manager.loadAsync(() -> {
                sender.sendMessage("§aAll data reloaded successfully!");
            });
        });
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage("§6=== DeepAlts Detailed Info ===");
        sender.sendMessage("§7Plugin: §fDeepAlts v" + manager.getPlugin().getDescription().getVersion());
        sender.sendMessage("§7Players tracked: §f" + manager.getUuidToIpsMap().size());
        sender.sendMessage("§7Graph connections: §f" + manager.getAltGraph().size());
        sender.sendMessage("§7Proxy cache entries: §f" + manager.getProxyCache().getCacheSize());
        sender.sendMessage("§7Latest IPs tracked: §f" + manager.getUuidToLatestIpMap().size());
        sender.sendMessage("§7Rate limiting: §f" + manager.getProxyRateLimitStatus());

        // Calculate some statistics
        CompletableFuture.runAsync(() -> {
            int totalIps = manager.getUuidToIpsMap().values().stream()
                    .mapToInt(Set::size)
                    .sum();

            double avgIpsPerPlayer = manager.getUuidToIpsMap().isEmpty() ? 0 :
                    (double) totalIps / manager.getUuidToIpsMap().size();

            sender.sendMessage("§7Total IP records: §f" + totalIps);
            sender.sendMessage("§7Average IPs per player: §f" + String.format("%.2f", avgIpsPerPlayer));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("deepalts.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> completions = new ArrayList<>();

            String[] commands = {"status", "rebuild", "clearcache", "save", "reload", "info"};
            for (String cmd : commands) {
                if (cmd.startsWith(prefix)) {
                    completions.add(cmd);
                }
            }

            return completions;
        }

        return Collections.emptyList();
    }
}