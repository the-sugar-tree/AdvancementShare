package com.sugar_tree.advancementshare.hook;

import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.util.function.BiConsumer;
import java.util.logging.Logger;

/** Picks the hook implementation for the running server version. */
public final class ProgressHooks {
    private ProgressHooks() {}

    /**
     * @param onChange called on the main thread, one tick after a player's progress
     *                 on an advancement changed
     * @return the hook, or null if this version has none (caller falls back to polling)
     */
    public static ProgressHook create(org.bukkit.plugin.Plugin plugin, BiConsumer<Player, Advancement> onChange, Logger logger) {
        // e.g. "26.2-R0.1-SNAPSHOT" -> "26.2"
        String version = Bukkit.getBukkitVersion().split("-")[0];
        try {
            switch (version) {
                case "26.2":
                    return new Spigot26_2Hook(plugin, onChange);
                default:
                    return null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Server internals don't match the " + version + " hook, falling back to polling: " + e);
            return null;
        }
    }
}
