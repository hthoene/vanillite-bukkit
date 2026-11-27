// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

/** A wrapper of a value; on Vanillite the handle is the wrapper's own value object. */
public abstract class AbstractWrapper {
    protected Object handle;
    protected final Class<?> handleType;

    public AbstractWrapper(Class<?> handleType) {
        this.handleType = handleType;
    }

    protected void setHandle(Object handle) {
        if (handle == null) {
            throw new IllegalArgumentException("handle cannot be null");
        }
        this.handle = handle;
    }

    public Object getHandle() {
        return handle;
    }

    public Class<?> getHandleType() {
        return handleType;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AbstractWrapper w && java.util.Objects.equals(handle, w.handle);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(handle);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[handle=" + handle + "]";
    }
}
