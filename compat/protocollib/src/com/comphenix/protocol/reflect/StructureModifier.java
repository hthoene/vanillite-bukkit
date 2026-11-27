// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.reflect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The fields of one type of a packet, in the order of the Vanilla packet class. On Vanillite a
 * packet is decoded from its wire format into a list of typed fields; a modifier reads and writes
 * the ones of its type and marks the packet changed, so it is encoded again.
 */
public class StructureModifier<T> {
    /** The fields a modifier works on. */
    public interface Fields {
        Object get(int slot);

        void set(int slot, Object value);

        /** Slots whose type is assignable to {@code type} (null: all). */
        int[] slots(Class<?> type);

        String describe();

        Object target();
    }

    private final Fields fields;
    private final int[] slots;
    private final Class<?> type;
    private final EquivalentConverter<T> converter;

    public StructureModifier(Class<?> targetType) {
        this(null, new int[0], targetType, null);
    }

    public StructureModifier(Class<?> targetType, Class<?> superclassExclude, boolean requireDefault) {
        this(null, new int[0], targetType, null);
    }

    /** A modifier over the slots of {@code fields} that hold {@code type}. */
    public StructureModifier(Fields fields, int[] slots, Class<?> type, EquivalentConverter<T> converter) {
        this.fields = fields;
        this.slots = slots;
        this.type = type;
        this.converter = converter;
    }

    private int slot(int index) {
        if (index < 0 || index >= slots.length) {
            throw new FieldAccessException("No field with type " + (type == null ? "Object" : type.getName())
                    + " exists at index " + index + " of " + (fields == null ? "this structure" : fields.describe())
                    + (slots.length == 0 && fields != null ? " (Vanillite maps no such field of this packet)" : ""));
        }
        return slots[index];
    }

    @SuppressWarnings("unchecked")
    public T read(int index) throws FieldAccessException {
        Object value = fields.get(slot(index));
        return converter == null ? (T) value : converter.getSpecific(value);
    }

    public T readSafely(int index) throws FieldAccessException {
        return index >= 0 && index < slots.length ? read(index) : null;
    }

    public Optional<T> optionRead(int index) {
        try {
            return Optional.ofNullable(readSafely(index));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public StructureModifier<T> write(int index, T value) throws FieldAccessException {
        fields.set(slot(index), converter == null ? value : converter.getGeneric(value));
        return this;
    }

    public StructureModifier<T> writeSafely(int index, T value) throws FieldAccessException {
        if (index >= 0 && index < slots.length) {
            write(index, value);
        }
        return this;
    }

    public StructureModifier<T> modify(int index, UnaryOperator<T> select) throws FieldAccessException {
        return write(index, select.apply(read(index)));
    }

    /** Vanillite: fields always hold a value; nothing to fill in. */
    public StructureModifier<T> writeDefaults() throws FieldAccessException {
        return this;
    }

    public Class<?> getFieldType() {
        return type;
    }

    public Class<?> getTargetType() {
        return fields == null ? null : fields.target().getClass();
    }

    public Object getTarget() {
        return fields == null ? null : fields.target();
    }

    public int size() {
        return slots.length;
    }

    public List<T> getValues() throws FieldAccessException {
        List<T> out = new ArrayList<>(slots.length);
        for (int i = 0; i < slots.length; i++) {
            out.add(read(i));
        }
        return out;
    }

    public <R> StructureModifier<R> withType(Class<?> fieldType) {
        return withType(fieldType, null);
    }

    public <R> StructureModifier<R> withType(Class<?> fieldType, EquivalentConverter<R> converter) {
        return new StructureModifier<>(fields, fields == null ? new int[0] : fields.slots(fieldType), fieldType, converter);
    }

    public <R> StructureModifier<R> withParamType(Class<?> fieldType, EquivalentConverter<R> converter,
            Class<?>... paramTypes) {
        return withType(fieldType, converter);
    }

    public StructureModifier<T> withTarget(Object target) {
        if (target instanceof com.comphenix.protocol.events.PacketContainer p) {
            return p.getModifier().withType(type, converter);
        }
        throw new FieldAccessException("Vanillite: only packets can be targets");
    }

    @Override
    public String toString() {
        return "StructureModifier[fieldType=" + (type == null ? "Object" : type.getName()) + ", size=" + slots.length + "]";
    }
}
