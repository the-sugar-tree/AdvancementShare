package com.sugar_tree.advancementshare;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.logging.Level;

/**
 * Shares vanilla advancement progress at the criterion level (union).
 * Example: A visits 20 biomes and B visits 36 others -> both end up with all 56.
 *
 * When a player earns a criterion, a grant event hands it to the rest of their group.
 * The event is Paper 1.13.2+ or Spigot 26.3+; on older Spigot we fall back to polling.
 */
public final class AdvancementShare extends JavaPlugin {

    private static final String[] CRITERION_EVENTS = {
            "com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent", // Paper 1.13.2+
            "org.bukkit.event.player.PlayerAdvancementCriterionGrantEvent",              // Spigot 26.3+
    };

    private ProgressStore store;
    private boolean eventDriven;
    private BukkitTask pollTask;
    private BukkitTask saveTask;

    private boolean teamMode;
    private boolean listScope;
    private boolean includeRecipes;
    private Set<String> listed;
    private Set<String> excluded;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new ProgressStore(new File(getDataFolder(), "data.yml"));
        try {
            store.load();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not load data.yml, disabling to avoid overwriting it", e);
            store = null; // must be cleared first: disablePlugin calls onDisable, which would save over the file
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        eventDriven = registerCriterionEvent();
        if (!eventDriven) {
            getLogger().warning("No criterion grant event on this server (needs Paper 1.13.2+ or Spigot 26.3+). "
                    + "Falling back to checking progress every check-interval-ticks.");
        }
        loadSettings();
        for (Player player : getServer().getOnlinePlayers()) merge(player); // /reload with players online
    }

    @Override
    public void onDisable() {
        if (store == null) return;
        try {
            store.save();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not save data.yml", e);
        }
    }

    /**
     * Hooks the criterion grant event by reflection so one jar works on every server.
     * We register on the HandlerList directly because Spigot's event names its static
     * accessor getHandlersLists() instead of getHandlerList(), which @EventHandler can't find.
     */
    private boolean registerCriterionEvent() {
        for (String name : CRITERION_EVENTS) {
            Class<?> eventClass;
            try {
                eventClass = Class.forName(name);
            } catch (ClassNotFoundException e) {
                continue;
            }
            try {
                HandlerList handlers = null;
                for (Method method : eventClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                            && method.getReturnType() == HandlerList.class) {
                        handlers = (HandlerList) method.invoke(null);
                        break;
                    }
                }
                if (handlers == null) continue;
                Method getAdvancement = eventClass.getMethod("getAdvancement");
                Method getCriterion = eventClass.getMethod("getCriterion");
                EventExecutor executor = (listener, event) -> {
                    if (!eventClass.isInstance(event)) return;
                    try {
                        share(((PlayerEvent) event).getPlayer(),
                                (Advancement) getAdvancement.invoke(event),
                                (String) getCriterion.invoke(event));
                    } catch (ReflectiveOperationException e) {
                        throw new EventException(e);
                    }
                };
                // ignoreCancelled: skip grants another plugin canceled. Unregistered on disable via HandlerList.unregisterAll(plugin).
                handlers.register(new RegisteredListener(new Listener() {}, executor, EventPriority.MONITOR, this, true));
                getLogger().info("Sharing via " + name);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                getLogger().log(Level.WARNING, "Could not hook " + name, e);
            }
        }
        return false;
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();
        teamMode = "team".equalsIgnoreCase(config.getString("mode", "server"));
        listScope = "list".equalsIgnoreCase(config.getString("scope", "all"));
        includeRecipes = config.getBoolean("include-recipes", false);
        listed = new HashSet<>(config.getStringList("advancements"));
        excluded = new HashSet<>(config.getStringList("exclude"));

        if (pollTask != null) pollTask.cancel();
        if (saveTask != null) saveTask.cancel();
        if (!eventDriven) {
            long checkTicks = Math.max(1, config.getLong("check-interval-ticks", 20));
            pollTask = getServer().getScheduler().runTaskTimer(this, () -> {
                for (Player player : getServer().getOnlinePlayers()) merge(player);
            }, checkTicks, checkTicks);
        }
        long saveTicks = Math.max(1, config.getLong("save-interval-seconds", 60)) * 20;
        saveTask = getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                store.saveIfDirty();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Could not save data.yml", e);
            }
        }, saveTicks, saveTicks);
    }

    /** Group a player shares with, or null if they share with nobody. */
    private String groupOf(Player player) {
        if (!teamMode) return "server";
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(player.getName());
        return team == null ? null : "team:" + team.getName();
    }

    private boolean isShared(Advancement advancement) {
        if (!includeRecipes && advancement.getKey().getKey().startsWith("recipes/")) return false;
        String key = advancement.getKey().toString();
        return listScope ? listed.contains(key) : !excluded.contains(key);
    }

    private List<Player> othersInGroup(Player player, String group) {
        List<Player> others = new ArrayList<>();
        for (Player other : getServer().getOnlinePlayers()) {
            if (other != player && group.equals(groupOf(other))) others.add(other);
        }
        return others;
    }

    /**
     * One criterion earned by one player: record it and give it to the rest of the group.
     * Awarding fires the grant event again for each receiver, but the store already has
     * the criterion, so store.add returns false and the chain stops there.
     */
    public void share(Player player, Advancement advancement, String criterion) {
        if (!isShared(advancement)) return;
        String group = groupOf(player);
        if (group == null) return;
        if (!store.add(group, advancement.getKey().toString(), criterion)) return;
        for (Player other : othersInGroup(player, group)) {
            other.getAdvancementProgress(advancement).awardCriteria(criterion);
        }
    }

    /**
     * Full sync for one player: share everything they have, and give them everything the group has.
     * Runs on join (to catch up on offline time), on /advshare sync, and as the polling fallback.
     */
    public void merge(Player player) {
        String group = groupOf(player);
        if (group == null) return;
        List<Player> others = othersInGroup(player, group);
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement advancement = it.next();
            if (!isShared(advancement)) continue;
            String key = advancement.getKey().toString();
            Collection<String> awarded = player.getAdvancementProgress(advancement).getAwardedCriteria();
            for (String criterion : awarded) {
                if (store.add(group, key, criterion)) {
                    for (Player other : others) other.getAdvancementProgress(advancement).awardCriteria(criterion);
                }
            }
            Collection<String> valid = advancement.getCriteria();
            for (String criterion : new ArrayList<>(store.get(group, key))) {
                if (!awarded.contains(criterion) && valid.contains(criterion)) {
                    player.getAdvancementProgress(advancement).awardCriteria(criterion);
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            for (Player player : getServer().getOnlinePlayers()) merge(player);
            sender.sendMessage(ChatColor.GREEN + "[AdvancementShare] Config reloaded.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("sync")) {
            for (Player player : getServer().getOnlinePlayers()) merge(player);
            sender.sendMessage(ChatColor.GREEN + "[AdvancementShare] Synced online players.");
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String option : Arrays.asList("reload", "sync")) {
            if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(option);
        }
        return result;
    }
}
