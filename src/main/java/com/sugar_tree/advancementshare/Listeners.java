package com.sugar_tree.advancementshare;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class Listeners implements Listener {
    private final AdvancementShare plugin;

    public Listeners(AdvancementShare plugin) {
        this.plugin = plugin;
    }

    /** Catch up a player who was offline while others made progress, and share what they had. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.injectHook(player);
        // One tick later so the player's advancement data is fully loaded.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) plugin.merge(player);
        });
    }
}
