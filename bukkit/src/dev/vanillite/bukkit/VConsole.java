// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.ConsoleCommandSender;

/** The server console as a {@link ConsoleCommandSender}: messages go to the log. */
public abstract class VConsole implements ConsoleCommandSender {
    @Override
    public String getName() {
        return "CONSOLE";
    }

    @Override
    public void sendMessage(String message) {
        Native.log(Abi.log_level.INFO, message.replaceAll("§.", ""));
    }

    @Override
    public void sendMessage(String... messages) {
        for (String m : messages) {
            sendMessage(m);
        }
    }

    @Override
    public void sendMessage(Component message) {
        sendMessage(PlainTextComponentSerializer.plainText().serialize(message));
    }

    @Override
    public boolean hasPermission(String node) {
        return true;
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public boolean isPermissionSet(String name) {
        return true;
    }
}
