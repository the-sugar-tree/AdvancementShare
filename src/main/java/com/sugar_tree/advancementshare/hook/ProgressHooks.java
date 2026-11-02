package com.sugar_tree.advancementshare.hook;

import com.sugar_tree.advancementshare.hook.v26_2.Spigot26_2Hook;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
    public static ProgressHook create(Plugin plugin, BiConsumer<Player, Advancement> onChange, Logger logger) {
        // e.g. "26.2-R0.1-SNAPSHOT" -> "26.2"
        String version = Bukkit.getBukkitVersion().split("-")[0];
        // Each hook class is compiled against its own server jar, so only touch it on that version.
        try {
            switch (version) {
                case "26.2":
                    return new Spigot26_2Hook(plugin, onChange);
                default:
                    return null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // LinkageError: a server class or member the hook was compiled against is missing (e.g. a patched build)
            logger.warning("Server internals don't match the " + version + " hook, falling back to polling: " + e);
            return null;
        }
    }
}
