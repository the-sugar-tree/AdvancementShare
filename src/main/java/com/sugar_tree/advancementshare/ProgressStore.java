package com.sugar_tree.advancementshare;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Shared progress per group: group -> advancement key -> awarded criteria.
 * A group is "server" or "team:<name>". Criteria only ever get added (union).
 */
public class ProgressStore {
    // Advancement keys contain ':' and '/', team names may contain '.', so use a separator nobody types.
    private static final char PATH_SEPARATOR = '\u001F';

    private final File file;
    private final Map<String, Map<String, Set<String>>> groups = new HashMap<>();
    private boolean dirty = false;

    public ProgressStore(File file) {
        this.file = file;
    }

    /** Returns true if the criterion was not already recorded. */
    public boolean add(String group, String advancement, String criterion) {
        boolean added = groups
                .computeIfAbsent(group, g -> new HashMap<>())
                .computeIfAbsent(advancement, a -> new HashSet<>())
                .add(criterion);
        if (added) dirty = true;
        return added;
    }

    public Set<String> get(String group, String advancement) {
        Map<String, Set<String>> advs = groups.get(group);
        if (advs == null) return Collections.emptySet();
        Set<String> criteria = advs.get(advancement);
        return criteria == null ? Collections.<String>emptySet() : criteria;
    }

    public void load() throws IOException, InvalidConfigurationException {
        groups.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(PATH_SEPARATOR);
        yaml.load(file);
        ConfigurationSection root = yaml.getConfigurationSection("groups");
        if (root == null) return;
        for (String group : root.getKeys(false)) {
            ConfigurationSection advs = root.getConfigurationSection(group);
            if (advs == null) continue;
            for (String adv : advs.getKeys(false)) {
                for (String criterion : advs.getStringList(adv)) {
                    groups.computeIfAbsent(group, g -> new HashMap<>())
                            .computeIfAbsent(adv, a -> new HashSet<>())
                            .add(criterion);
                }
            }
        }
        dirty = false;
    }

    public void saveIfDirty() throws IOException {
        if (dirty) save();
    }

    public void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(PATH_SEPARATOR);
        for (Map.Entry<String, Map<String, Set<String>>> group : groups.entrySet()) {
            for (Map.Entry<String, Set<String>> adv : group.getValue().entrySet()) {
                List<String> criteria = new ArrayList<>(adv.getValue());
                Collections.sort(criteria);
                yaml.set("groups" + PATH_SEPARATOR + group.getKey() + PATH_SEPARATOR + adv.getKey(), criteria);
            }
        }
        yaml.save(file);
        dirty = false;
    }
}
