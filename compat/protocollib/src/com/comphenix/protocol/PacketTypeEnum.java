// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/** The packet types of one protocol state and sender. */
public class PacketTypeEnum implements Iterable<PacketType> {
    private final Set<PacketType> members = Collections.synchronizedSet(new LinkedHashSet<>());

    public PacketTypeEnum() {}

    public boolean registerMember(PacketType type, String name) {
        return members.add(type);
    }

    public boolean hasMember(PacketType type) {
        return members.contains(type);
    }

    public Set<PacketType> values() {
        return Set.copyOf(members);
    }

    @Override
    public Iterator<PacketType> iterator() {
        return values().iterator();
    }
}
