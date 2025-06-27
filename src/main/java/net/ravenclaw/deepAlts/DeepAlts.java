package net.ravenclaw.deepAlts;

import org.bukkit.plugin.java.JavaPlugin;

public class DeepAlts extends JavaPlugin {
    private DeepAltsManager manager;

    @Override
    public void onEnable() {
        manager = new DeepAltsManager(this);
        getServer().getPluginManager().registerEvents(manager, this);
        manager.loadAsync(() -> getLogger().info("DeepAlts data and graph loaded."));

        DeepAltsCommand altsCommand = new DeepAltsCommand(manager);
        getCommand("alts").setExecutor(altsCommand);
        getCommand("alts").setTabCompleter(altsCommand);
        getCommand("deepalts").setExecutor(altsCommand);
        getCommand("deepalts").setTabCompleter(altsCommand);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            // Save both IP data and graph
            manager.saveAll();
            getLogger().info("DeepAlts data and graph saved on disable.");
        }
    }

    public DeepAltsManager getManager() {
        return manager;
    }
}