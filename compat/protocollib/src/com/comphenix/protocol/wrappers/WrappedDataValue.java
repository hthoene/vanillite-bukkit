// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

/** One entity metadata value: index, serializer and value. */
public class WrappedDataValue extends AbstractWrapper {
    private int index;
    private WrappedDataWatcher.Serializer serializer;
    private Object value;

    public WrappedDataValue(Object handle) {
        super(WrappedDataValue.class);
        WrappedDataValue other = (WrappedDataValue) handle;
        this.index = other.index;
        this.serializer = other.serializer;
        this.value = other.value;
        this.handle = this;
    }

    public WrappedDataValue(int index, WrappedDataWatcher.Serializer serializer, Object value) {
        super(WrappedDataValue.class);
        this.index = index;
        this.serializer = serializer;
        this.value = value;
        this.handle = this;
    }

    public static WrappedDataValue fromWrappedValue(int index, WrappedDataWatcher.Serializer serializer, Object value) {
        return new WrappedDataValue(index, serializer, value);
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public WrappedDataWatcher.Serializer getSerializer() {
        return serializer;
    }

    public void setSerializer(WrappedDataWatcher.Serializer serializer) {
        this.serializer = serializer;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Object getRawValue() {
        return value;
    }

    public void setRawValue(Object value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WrappedDataValue v && v.index == index && java.util.Objects.equals(v.serializer, serializer)
                && java.util.Objects.equals(v.value, value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(index, serializer, value);
    }

    @Override
    public String toString() {
        return "WrappedDataValue[index=" + index + ", serializer=" + serializer + ", value=" + value + "]";
    }
}
