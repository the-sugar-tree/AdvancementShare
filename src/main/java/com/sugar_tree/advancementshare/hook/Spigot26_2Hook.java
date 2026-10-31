package com.sugar_tree.advancementshare.hook;

import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Spigot 26.2 (Mojang names, not obfuscated).
 *
 * Every time a criterion is granted, PlayerAdvancements.award(holder, criterion) does:
 *     if (progress.grantProgress(criterion)) { unregisterListeners(holder); progressChanged.add(holder); ... }
 * We swap that final HashSet field for a subclass that reports add().
 *
 * add() is also called on revoke, visibility updates and while a datapack reload rebuilds
 * progress, so we only queue the change and handle it on the next tick. The handler diffs
 * against the shared store, so these extra reports are harmless.
 *
 * The object lives per player session (recreated on rejoin, kept across respawn and reload),
 * so inject on join.
 */
final class Spigot26_2Hook implements ProgressHook {

    private final Plugin plugin;
    private final BiConsumer<Player, Advancement> onChange;

    private final Method getHandle;        // CraftPlayer.getHandle() -> ServerPlayer
    private final Method getAdvancements;  // ServerPlayer.getAdvancements() -> PlayerAdvancements
    private final Field progressChanged;   // PlayerAdvancements.progressChanged : Set<AdvancementHolder>
    private final Method toBukkit;         // AdvancementHolder.toBukkit() -> org.bukkit.advancement.Advancement

    private final Queue<Change> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    Spigot26_2Hook(Plugin plugin, BiConsumer<Player, Advancement> onChange) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.onChange = onChange;
        ClassLoader loader = Bukkit.getServer().getClass().getClassLoader();
        Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer", false, loader);
        Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", false, loader);
        Class<?> playerAdvancements = Class.forName("net.minecraft.server.PlayerAdvancements", false, loader);
        Class<?> holder = Class.forName("net.minecraft.advancements.AdvancementHolder", false, loader);

        getHandle = craftPlayer.getMethod("getHandle");
        getAdvancements = serverPlayer.getMethod("getAdvancements");
        progressChanged = playerAdvancements.getDeclaredField("progressChanged");
        if (progressChanged.getType() != Set.class) {
            throw new NoSuchFieldException("progressChanged is " + progressChanged.getType().getName() + ", expected Set");
        }
        progressChanged.setAccessible(true); // final instance field: writable once accessible
        toBukkit = holder.getMethod("toBukkit");
    }

    @Override
    public void inject(Player player) throws ReflectiveOperationException {
        Object advancements = advancementsOf(player);
        Set<?> current = (Set<?>) progressChanged.get(advancements);
        if (current instanceof WatchedSet) return;
        WatchedSet watched = new WatchedSet(player.getUniqueId());
        watched.fillQuietly(current); // keep anything not yet flushed this tick
        progressChanged.set(advancements, watched);
    }

    @Override
    public void eject(Player player) throws ReflectiveOperationException {
        Object advancements = advancementsOf(player);
        Set<?> current = (Set<?>) progressChanged.get(advancements);
        if (!(current instanceof WatchedSet)) return;
        progressChanged.set(advancements, new HashSet<Object>(current));
    }

    private Object advancementsOf(Player player) throws ReflectiveOperationException {
        return getAdvancements.invoke(getHandle.invoke(player));
    }

    private void report(UUID player, Object holder) {
        pending.add(new Change(player, holder));
        // Scheduler is thread-safe, in case some plugin awards off the main thread.
        if (drainScheduled.compareAndSet(false, true) && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, this::drain);
        }
    }

    private void drain() {
        drainScheduled.set(false);
        Set<Change> seen = new HashSet<>();
        Change change;
        while ((change = pending.poll()) != null) {
            if (!seen.add(change)) continue;
            Player player = Bukkit.getPlayer(change.player);
            if (player == null) continue;
            Advancement advancement;
            try {
                advancement = (Advancement) toBukkit.invoke(change.holder);
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().warning("Could not convert advancement: " + e);
                continue;
            }
            onChange.accept(player, advancement);
        }
    }

    private static final class Change {
        final UUID player;
        final Object holder;

        Change(UUID player, Object holder) {
            this.player = player;
            this.holder = holder;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Change)) return false;
            Change other = (Change) o;
            return player.equals(other.player) && holder.equals(other.holder);
        }

        @Override
        public int hashCode() {
            return player.hashCode() * 31 + holder.hashCode();
        }
    }

    /** Behaves exactly like the HashSet it replaces, but reports every add(). */
    private final class WatchedSet extends HashSet<Object> {
        private final UUID player;

        WatchedSet(UUID player) {
            this.player = player;
        }

        void fillQuietly(Collection<?> items) {
            for (Object item : items) super.add(item);
        }

        @Override
        public boolean add(Object holder) {
            boolean added = super.add(holder);
            // Report even if already present: a second criterion of the same advancement in one tick.
            report(player, holder);
            return added;
        }
    }
}
