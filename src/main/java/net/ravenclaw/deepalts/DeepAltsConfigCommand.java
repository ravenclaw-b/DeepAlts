package net.ravenclaw.deepalts;

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
            sender.sendMessage("§f/deepaltsconfig rebuild §7- Rebuild graph from hashed IP data");
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
        sender.sendMessage("§7Proxy cache size: §f" + manager.getProxyCache().getCacheSize() + " §7entries");
        sender.sendMessage("§7Graph size: §f" + manager.getAltGraph().size() + " §7nodes");
        sender.sendMessage("§7Rate limit: §f" + manager.getProxyRateLimitStatus());
        sender.sendMessage("§7Hashed IP mappings: §f" + manager.getUuidToHashedIpsMap().size() + " §7players");
        sender.sendMessage("§7Privacy: §aAll IPs are stored as SHA-256 hashes");
    }

    private void handleRebuild(CommandSender sender) {
        sender.sendMessage("§6Rebuilding graph from hashed IP data...");
        sender.sendMessage("§7Note: Using cached proxy statuses from previous checks.");

        CompletableFuture.runAsync(() -> {
            manager.rebuildGraph();

            // Schedule player message on the main thread
            Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
                sender.sendMessage("§aGraph rebuild completed!");
            });
        });
    }

    private void handleSave(CommandSender sender) {
        sender.sendMessage("§6Saving all data...");
        CompletableFuture.runAsync(() -> {
            manager.saveAll();
            Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
                sender.sendMessage("§aAll data saved successfully!");
            });
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
        sender.sendMessage("§7Players tracked: §f" + manager.getUuidToHashedIpsMap().size());
        sender.sendMessage("§7Graph connections: §f" + manager.getAltGraph().size());
        sender.sendMessage("§7Proxy cache entries: §f" + manager.getProxyCache().getCacheSize());
        sender.sendMessage("§7Latest hashed IPs tracked: §f" + manager.getUuidToLatestHashedIpMap().size());
        sender.sendMessage("§7Rate limiting: §f" + manager.getProxyRateLimitStatus());
        sender.sendMessage("§7Privacy protection: §aAll IPs stored as SHA-256 hashes");

        // Calculate some statistics
        CompletableFuture.runAsync(() -> {
            int totalHashedIps = manager.getUuidToHashedIpsMap().values().stream()
                    .mapToInt(Set::size)
                    .sum();

            double avgHashedIpsPerPlayer = manager.getUuidToHashedIpsMap().isEmpty() ? 0 :
                    (double) totalHashedIps / manager.getUuidToHashedIpsMap().size();

            Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
                sender.sendMessage("§7Total hashed IP records: §f" + totalHashedIps);
                sender.sendMessage("§7Average hashed IPs per player: §f" + String.format("%.2f", avgHashedIpsPerPlayer));
            });
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

            String[] commands = {"status", "rebuild", "save", "reload", "info"};
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