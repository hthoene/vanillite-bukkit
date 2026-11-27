// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.reflect;

/** Converts between a value as the server holds it and as plugins see it. */
public interface EquivalentConverter<T> {
    Object getGeneric(T specific);

    T getSpecific(Object generic);

    Class<T> getSpecificType();
}
