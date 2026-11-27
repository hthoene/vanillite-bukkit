// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.help.HelpMap;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.HelpTopicFactory;

/** The server's help topics: what plugins add, and the factories they register for their command classes. */
final class VHelpMap implements HelpMap {
    private final Map<String, HelpTopic> topics = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<Class<?>, HelpTopicFactory<?>> factories = new ConcurrentHashMap<>();
    private final List<String> ignored = new ArrayList<>();

    @Override
    public synchronized HelpTopic getHelpTopic(String topicName) {
        return topics.get(topicName.isEmpty() ? "" : topicName);
    }

    @Override
    public synchronized Collection<HelpTopic> getHelpTopics() {
        return List.copyOf(topics.values());
    }

    @Override
    public synchronized void addTopic(HelpTopic topic) {
        topics.putIfAbsent(topic.getName(), topic);
    }

    @Override
    public synchronized void clear() {
        topics.clear();
    }

    @Override
    public void registerHelpTopicFactory(Class<?> commandClass, HelpTopicFactory<?> factory) {
        factories.put(commandClass, factory);
    }

    @Override
    public List<String> getIgnoredPlugins() {
        return ignored;
    }
}
