// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

/** A wrapper that can be copied. */
public interface ClonableWrapper {
    Object getHandle();

    ClonableWrapper deepClone();
}
