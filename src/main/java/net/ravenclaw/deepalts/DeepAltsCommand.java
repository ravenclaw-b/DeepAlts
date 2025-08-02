package net.ravenclaw.deepalts;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class DeepAltsCommand implements CommandExecutor, TabCompleter {

    private final DeepAltsManager manager;

    public DeepAltsCommand(DeepAltsManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage("§cUsage: /" + label + " <player name or UUID> [uuid]");
            sender.sendMessage("§7Use §f/deepaltsconfig§7 for admin commands");
            return true;
        }

        String input = args[0];
        boolean deep = label.equalsIgnoreCase("deepalts") || label.equalsIgnoreCase("dalts");
        boolean forceUuidOutput = args.length == 2 && args[1].equalsIgnoreCase("uuid");

        CompletableFuture.runAsync(() -> {
            UUID targetUuid;
            String displayTarget;

            boolean isInputUuid = false;
            try {
                UUID.fromString(input);
                isInputUuid = true;
            } catch (IllegalArgumentException ignored) {}

            OfflinePlayer targetPlayer;

            if (isInputUuid) {
                targetUuid = UUID.fromString(input);
                targetPlayer = Bukkit.getOfflinePlayer(targetUuid);
            } else {
                targetPlayer = Bukkit.getOfflinePlayer(input);
                targetUuid = targetPlayer.getUniqueId();
            }

            if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
                sender.sendMessage("§cPlayer not found.");
                return;
            }

            String targetDisplayName = forceUuidOutput ? targetUuid.toString() : (targetPlayer.getName() != null ? targetPlayer.getName() : targetUuid.toString());
            String targetColor = targetPlayer.isOnline() ? "§a" : "§7";
            displayTarget = targetColor + targetDisplayName;

            Set<UUID> results = deep ? manager.getDeepAlts(targetUuid) : manager.getAlts(targetUuid);
            String type = deep ? "§9DeepAlts" : "§3Alts";

            sender.sendMessage("§7Scanning §f" + displayTarget + " §7[" + type + "§7]");

            if (results.isEmpty()) {
                sender.sendMessage("§7No alts found.");
                return;
            }

            List<String> formatted = results.stream().map(uuid -> {
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                String name = forceUuidOutput ? uuid.toString() : (player.getName() != null ? player.getName() : uuid.toString());
                String color = player.isOnline() ? "§a" : "§7";
                return color + name;
            }).collect(Collectors.toList());

            sender.sendMessage("§f §b" + String.join("§f, §b", formatted));
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> completions = new ArrayList<>();

            // Add player names
            completions.addAll(Arrays.stream(Bukkit.getOfflinePlayers())
                    .map(OfflinePlayer::getName)
                    .filter(Objects::nonNull)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .limit(50)
                    .collect(Collectors.toList()));

            return completions;
        } else if (args.length == 2) {
            return Collections.singletonList("uuid");
        }

        return Collections.emptyList();
    }
}