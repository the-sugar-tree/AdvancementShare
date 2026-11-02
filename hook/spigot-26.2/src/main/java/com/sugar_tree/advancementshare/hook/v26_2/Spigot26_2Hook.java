package com.sugar_tree.advancementshare.hook.v26_2;

import com.sugar_tree.advancementshare.hook.ProgressHook;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Spigot 26.2 (Mojang names, not obfuscated). Compiled against the 26.2 server jar,
 * so this class must only be loaded on 26.2.
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
public final class Spigot26_2Hook implements ProgressHook {

    private final Plugin plugin;
    private final BiConsumer<Player, Advancement> onChange;

    // private final Set<AdvancementHolder> progressChanged: no accessor, so still a reflected field
    private final Field progressChanged;

    private final Queue<Change> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    public Spigot26_2Hook(Plugin plugin, BiConsumer<Player, Advancement> onChange) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.onChange = onChange;
        progressChanged = PlayerAdvancements.class.getDeclaredField("progressChanged");
        if (progressChanged.getType() != Set.class) {
            throw new NoSuchFieldException("progressChanged is " + progressChanged.getType().getName() + ", expected Set");
        }
        progressChanged.setAccessible(true); // final instance field: writable once accessible
        // Direct calls link lazily on first use, so check them now and let create() fall back to polling.
        requireMethod(CraftPlayer.class, "getHandle", ServerPlayer.class);
        requireMethod(ServerPlayer.class, "getAdvancements", PlayerAdvancements.class);
        requireMethod(AdvancementHolder.class, "toBukkit", Advancement.class);
    }

    private static void requireMethod(Class<?> owner, String name, Class<?> returnType) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0 && method.getReturnType() == returnType) return;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "() returning " + returnType.getName());
    }

    @Override
    public void inject(Player player) throws ReflectiveOperationException {
        PlayerAdvancements advancements = advancementsOf(player);
        Set<AdvancementHolder> current = changedSet(advancements);
        if (current instanceof WatchedSet) return;
        WatchedSet watched = new WatchedSet(player.getUniqueId());
        watched.fillQuietly(current); // keep anything not yet flushed this tick
        progressChanged.set(advancements, watched);
    }

    @Override
    public void eject(Player player) throws ReflectiveOperationException {
        PlayerAdvancements advancements = advancementsOf(player);
        Set<AdvancementHolder> current = changedSet(advancements);
        if (!(current instanceof WatchedSet)) return;
        progressChanged.set(advancements, new HashSet<>(current));
    }

    private static PlayerAdvancements advancementsOf(Player player) {
        return ((CraftPlayer) player).getHandle().getAdvancements();
    }

    @SuppressWarnings("unchecked")
    private Set<AdvancementHolder> changedSet(PlayerAdvancements advancements) throws IllegalAccessException {
        return (Set<AdvancementHolder>) progressChanged.get(advancements);
    }

    private void report(UUID player, AdvancementHolder holder) {
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
            Player player = Bukkit.getPlayer(change.player());
            if (player == null) continue;
            onChange.accept(player, change.holder().toBukkit());
        }
    }

    private record Change(UUID player, AdvancementHolder holder) {}

    /** Behaves exactly like the HashSet it replaces, but reports every add(). */
    private final class WatchedSet extends HashSet<AdvancementHolder> {
        private final UUID player;

        WatchedSet(UUID player) {
            this.player = player;
        }

        void fillQuietly(Collection<AdvancementHolder> items) {
            for (AdvancementHolder item : items) super.add(item);
        }

        @Override
        public boolean add(AdvancementHolder holder) {
            boolean added = super.add(holder);
            // Report even if already present: a second criterion of the same advancement in one tick.
            report(player, holder);
            return added;
        }
    }
}
