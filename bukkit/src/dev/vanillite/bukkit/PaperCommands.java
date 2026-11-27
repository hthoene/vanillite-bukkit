// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandRegistrationFlag;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.MonitorLifecycleEventHandlerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.PrioritizedLifecycleEventHandlerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.TagEventTypeProvider;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Paper's lifecycle events with the {@code COMMANDS} registrar: Brigadier commands and
 * {@link BasicCommand}s of Paper plugins. They run in the layer's Brigadier dispatcher and are
 * Bukkit commands of the command map at the same time, so the server's command tree, tab
 * completion and permission filter treat them like every Bukkit command (the client sees a
 * literal with a greedy argument). Other lifecycle events (tags, datapack discovery) are accepted
 * and never fired. The API finds the event types through
 * {@link io.papermc.paper.plugin.lifecycle.event.types.VanilliteLifecycleEventTypes}.
 */
public final class PaperCommands {
    private PaperCommands() {}

    private static final String COMMANDS = "commands";
    private static final CommandDispatcher<CommandSourceStack> DISPATCHER = new CommandDispatcher<>();
    private static final List<Registered> HANDLERS = new ArrayList<>();
    private static boolean started;

    private record Registered(Plugin plugin, Config<?> config) {}

    /** A handler configuration of any lifecycle event type. */
    static final class Config<O extends LifecycleEventOwner>
            implements PrioritizedLifecycleEventHandlerConfiguration<O>, MonitorLifecycleEventHandlerConfiguration<O> {
        final String type;
        final LifecycleEventHandler<LifecycleEvent> handler;
        int priority;

        @SuppressWarnings("unchecked")
        Config(String type, LifecycleEventHandler<?> handler) {
            this.type = type;
            this.handler = (LifecycleEventHandler<LifecycleEvent>) handler;
        }

        @Override
        public Config<O> priority(int value) {
            priority = value;
            return this;
        }

        @Override
        public Config<O> monitor() {
            priority = Integer.MAX_VALUE;
            return this;
        }
    }

    private record Type<O extends LifecycleEventOwner, E extends LifecycleEvent>(String name)
            implements LifecycleEventType.Prioritizable<O, E> {
        @Override
        public PrioritizedLifecycleEventHandlerConfiguration<O> newHandler(LifecycleEventHandler<? super E> handler) {
            return new Config<>(name, handler);
        }
    }

    private record MonitorType<O extends LifecycleEventOwner, E extends LifecycleEvent>(String name)
            implements LifecycleEventType.Monitorable<O, E> {
        @Override
        public MonitorLifecycleEventHandlerConfiguration<O> newHandler(LifecycleEventHandler<? super E> handler) {
            return new Config<>(name, handler);
        }
    }

    /** {@code LifecycleEventTypeProvider.monitor}. */
    public static <O extends LifecycleEventOwner, E extends LifecycleEvent> LifecycleEventType.Monitorable<O, E> monitor(
            String name) {
        return new MonitorType<>(name);
    }

    /** {@code LifecycleEventTypeProvider.prioritized}. */
    public static <O extends LifecycleEventOwner, E extends LifecycleEvent> LifecycleEventType.Prioritizable<O, E>
            prioritized(String name) {
        return new Type<>(name);
    }

    /** {@code LifecycleEventTypeProvider.tagProvider}: tag events are never fired. */
    public static TagEventTypeProvider tagProvider() {
        return new TagEventTypeProvider() {
            @Override
            public <T> LifecycleEventType.Prioritizable<io.papermc.paper.plugin.bootstrap.BootstrapContext,
                    ReloadableRegistrarEvent<io.papermc.paper.tag.PreFlattenTagRegistrar<T>>> preFlatten(RegistryKey<T> key) {
                return new Type<>("tags_pre_flatten");
            }

            @Override
            public <T> LifecycleEventType.Prioritizable<io.papermc.paper.plugin.bootstrap.BootstrapContext,
                    ReloadableRegistrarEvent<io.papermc.paper.tag.PostFlattenTagRegistrar<T>>> postFlatten(RegistryKey<T> key) {
                return new Type<>("tags_post_flatten");
            }
        };
    }

    /** A plugin's {@code LifecycleEventManager.registerEventHandler}. */
    static void add(Plugin plugin, Object config) {
        if (!(config instanceof Config<?> c)) {
            return;
        }
        synchronized (HANDLERS) {
            HANDLERS.add(new Registered(plugin, c));
        }
        if (started && COMMANDS.equals(c.type)) {
            fire(List.of(new Registered(plugin, c)));
        }
    }

    /** Fires {@code COMMANDS} for the handlers so far (once the plugins of the start are enabled). */
    static void start() {
        started = true;
        List<Registered> commands;
        synchronized (HANDLERS) {
            commands = HANDLERS.stream().filter(r -> COMMANDS.equals(r.config.type)).toList();
        }
        fire(commands);
    }

    private static void fire(List<Registered> handlers) {
        List<Registered> sorted = new ArrayList<>(handlers);
        sorted.sort(Comparator.comparingInt(r -> r.config.priority));
        for (Registered r : sorted) {
            if (!r.plugin.isEnabled()) {
                continue;
            }
            Registrar registrar = new Registrar(r.plugin);
            ReloadableRegistrarEvent<Commands> event = new ReloadableRegistrarEvent<>() {
                @Override
                public Commands registrar() {
                    return registrar;
                }

                @Override
                public Cause cause() {
                    return Cause.INITIAL;
                }
            };
            try {
                r.config.handler.run(event);
            } catch (Throwable t) {
                Native.log(Abi.log_level.ERROR, "Command registration of " + r.plugin.getName() + " failed: "
                        + Native.trace(t));
            }
        }
        if (!dev.vanillite.bukkit.Commands.register()) {
            dev.vanillite.bukkit.Commands.updateAll();
        }
    }

    /** The command source of a sender. */
    record Source(CommandSender sender, Location location, Entity executor) implements CommandSourceStack {
        static Source of(CommandSender sender) {
            Entity e = sender instanceof Entity entity ? entity : null;
            Location at = e != null ? e.getLocation() : Layer.server.getWorlds().isEmpty() ? null
                    : Layer.server.getWorlds().getFirst().getSpawnLocation();
            return new Source(sender, at, e);
        }

        @Override
        public Location getLocation() {
            return location;
        }

        @Override
        public CommandSender getSender() {
            return sender;
        }

        @Override
        public Entity getExecutor() {
            return executor;
        }

        @Override
        public Player getPlayerOrThrow() throws CommandSyntaxException {
            if (executor instanceof Player p) {
                return p;
            }
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
        }

        @Override
        public Entity getEntityOrThrow() throws CommandSyntaxException {
            if (executor != null) {
                return executor;
            }
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
        }

        @Override
        public CommandSourceStack withLocation(Location at) {
            return new Source(sender, at, executor);
        }

        @Override
        public CommandSourceStack withExecutor(Entity entity) {
            return new Source(sender, location, entity);
        }

        @Override
        public void sendReply(ComponentLike message) {
            sender.sendMessage(message);
        }

        @Override
        public void sendSuccess(ComponentLike message, boolean broadcast) {
            sender.sendMessage(message);
        }

        @Override
        public void sendFailure(ComponentLike message) {
            sender.sendMessage(message.asComponent().colorIfAbsent(NamedTextColor.RED));
        }
    }

    /** The {@code COMMANDS} registrar of one plugin. */
    private static final class Registrar implements Commands {
        private final Plugin plugin;

        Registrar(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public CommandDispatcher<CommandSourceStack> getDispatcher() {
            return DISPATCHER;
        }

        @Override
        public Set<String> register(LiteralCommandNode<CommandSourceStack> node, String description,
                Collection<String> aliases) {
            return registerWithFlags(plugin.getPluginMeta(), node, description, aliases, Set.of());
        }

        @Override
        public Set<String> register(PluginMeta meta, LiteralCommandNode<CommandSourceStack> node, String description,
                Collection<String> aliases) {
            return registerWithFlags(meta, node, description, aliases, Set.of());
        }

        @Override
        public Set<String> registerWithFlags(PluginMeta meta, LiteralCommandNode<CommandSourceStack> node,
                String description, Collection<String> aliases, Set<CommandRegistrationFlag> flags) {
            String label = node.getLiteral().toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            names.add(label);
            for (String alias : aliases) {
                names.add(alias.toLowerCase(Locale.ROOT));
            }
            String namespace = meta.getName().toLowerCase(Locale.ROOT).replace(' ', '_');
            for (String name : names) {
                DISPATCHER.getRoot().addChild(copy(node, name));
                DISPATCHER.getRoot().addChild(copy(node, namespace + ":" + name));
            }
            var command = new Wrapped(label, description, aliases, plugin) {
                @Override
                public boolean execute(CommandSender sender, String used, String[] args) {
                    String line = (used + " " + String.join(" ", args)).strip();
                    try {
                        DISPATCHER.execute(line, Source.of(sender));
                    } catch (CommandSyntaxException e) {
                        sender.sendMessage(net.kyori.adventure.text.Component.text(e.getMessage(), NamedTextColor.RED));
                    }
                    return true;
                }

                @Override
                public List<String> tabComplete(CommandSender sender, String used, String[] args) {
                    String line = used + " " + String.join(" ", args);
                    var parse = DISPATCHER.parse(line, Source.of(sender));
                    return DISPATCHER.getCompletionSuggestions(parse).join().getList().stream()
                            .map(Suggestion::getText).toList();
                }

                @Override
                public boolean testPermissionSilent(CommandSender sender) {
                    return node.canUse(Source.of(sender));
                }
            };
            Layer.server.commandMap.register(namespace, command);
            return Set.copyOf(names);
        }

        @Override
        public Set<String> register(String label, String description, Collection<String> aliases, BasicCommand basic) {
            return register(plugin.getPluginMeta(), label, description, aliases, basic);
        }

        @Override
        public Set<String> register(PluginMeta meta, String label, String description, Collection<String> aliases,
                BasicCommand basic) {
            String namespace = meta.getName().toLowerCase(Locale.ROOT).replace(' ', '_');
            var command = new Wrapped(label.toLowerCase(Locale.ROOT), description, aliases, plugin) {
                @Override
                public boolean execute(CommandSender sender, String used, String[] args) {
                    if (!testPermission(sender)) {
                        return true;
                    }
                    try {
                        basic.execute(Source.of(sender), args);
                    } catch (CommandSyntaxException e) {
                        sender.sendMessage(net.kyori.adventure.text.Component.text(e.getMessage(), NamedTextColor.RED));
                    }
                    return true;
                }

                @Override
                public List<String> tabComplete(CommandSender sender, String used, String[] args) {
                    return List.copyOf(basic.suggest(Source.of(sender), args));
                }

                @Override
                public boolean testPermissionSilent(CommandSender sender) {
                    String permission = basic.permission();
                    return basic.canUse(sender) && (permission == null || sender.hasPermission(permission));
                }
            };
            Layer.server.commandMap.register(namespace, command);
            List<String> names = new ArrayList<>(List.of(command.getName()));
            names.addAll(aliases);
            return Set.copyOf(names);
        }

        private static LiteralCommandNode<CommandSourceStack> copy(LiteralCommandNode<CommandSourceStack> node,
                String name) {
            var b = LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                    .requires(node.getRequirement())
                    .executes(node.getCommand());
            if (node.getRedirect() != null) {
                b.forward(node.getRedirect(), node.getRedirectModifier(), node.isFork());
            }
            for (CommandNode<CommandSourceStack> child : node.getChildren()) {
                b.then(child);
            }
            return b.build();
        }
    }

    /** A Bukkit command of a Paper plugin's registrar. */
    private abstract static class Wrapped extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;

        Wrapped(String name, String description, Collection<String> aliases, Plugin plugin) {
            super(name, description == null ? "" : description, "/" + name, new ArrayList<>(aliases));
            this.plugin = plugin;
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }

        @Override
        public boolean testPermission(CommandSender sender) {
            if (testPermissionSilent(sender)) {
                return true;
            }
            sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "I'm sorry, but you do not have permission to perform this command.", NamedTextColor.RED));
            return false;
        }
    }

}
