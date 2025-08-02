package net.ravenclaw.deepalts;

import org.bukkit.plugin.java.JavaPlugin;

public class DeepAlts extends JavaPlugin {
    private DeepAltsManager manager;

    @Override
    public void onEnable() {
        manager = new DeepAltsManager(this);
        getServer().getPluginManager().registerEvents(manager, this);
        manager.loadAsync(() -> getLogger().info("DeepAlts data, graph, and proxy cache loaded."));

        DeepAltsCommand altsCommand = new DeepAltsCommand(manager);
        if (getCommand("alts") != null) {
            getCommand("alts").setExecutor(altsCommand);
            getCommand("alts").setTabCompleter(altsCommand);
        } else {
            getLogger().warning("Command 'alts' not found in plugin.yml");
        }

        if (getCommand("deepalts") != null) {
            getCommand("deepalts").setExecutor(altsCommand);
            getCommand("deepalts").setTabCompleter(altsCommand);
        } else {
            getLogger().warning("Command 'deepalts' not found in plugin.yml");
        }

        // Register config command
        DeepAltsConfigCommand configCommand = new DeepAltsConfigCommand(manager);
        if (getCommand("deepaltsconfig") != null) {
            getCommand("deepaltsconfig").setExecutor(configCommand);
            getCommand("deepaltsconfig").setTabCompleter(configCommand);
        } else {
            getLogger().warning("Command 'deepaltsconfig' not found in plugin.yml");
        }
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            try {
                manager.saveAll();
                getLogger().info("DeepAlts data, graph, and proxy cache saved on disable.");
            } catch (Exception e) {
                getLogger().severe("Error saving data on disable: " + e.getMessage());
            }
        }
    }

    public DeepAltsManager getManager() {
        return manager;
    }
}