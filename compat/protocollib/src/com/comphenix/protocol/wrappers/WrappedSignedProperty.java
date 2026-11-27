// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import java.util.Objects;

/** A game profile property (e.g. {@code textures}) with its optional signature. */
public class WrappedSignedProperty extends AbstractWrapper {
    private final String name;
    private final String value;
    private final String signature;

    public WrappedSignedProperty(String name, String value, String signature) {
        super(WrappedSignedProperty.class);
        this.name = name;
        this.value = value;
        this.signature = signature;
        this.handle = this;
    }

    public static WrappedSignedProperty fromHandle(Object handle) {
        return (WrappedSignedProperty) handle;
    }

    public static WrappedSignedProperty fromValues(String name, String value, String signature) {
        return new WrappedSignedProperty(name, value, signature);
    }

    public String getName() {
        return name;
    }

    public String getSignature() {
        return signature;
    }

    public String getValue() {
        return value;
    }

    public boolean hasSignature() {
        return signature != null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value, signature);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WrappedSignedProperty p && Objects.equals(p.name, name) && Objects.equals(p.value, value)
                && Objects.equals(p.signature, signature);
    }

    @Override
    public String toString() {
        return "WrappedSignedProperty[name=" + name + ", value=" + value + ", signature=" + signature + "]";
    }
}
