package com.sugar_tree.advancementshare.hook;

import org.bukkit.entity.Player;

/**
 * Server-internal hook that reports when a player's advancement progress changes.
 * Used on servers without a criterion grant event, instead of polling.
 */
public interface ProgressHook {
    /** Start watching this player. Safe to call more than once. */
    void inject(Player player) throws ReflectiveOperationException;

    /** Stop watching this player and put the server's own object back. */
    void eject(Player player) throws ReflectiveOperationException;
}
