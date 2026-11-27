// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.protocollib;

/**
 * JOML's vector and quaternion (display entity metadata), used by name: the library's old class
 * file version would fail the layer's strict compile checks.
 */
public final class Joml {
    private Joml() {}

    public static final Class<?> VECTOR = load("org.joml.Vector3f");
    public static final Class<?> QUATERNION = load("org.joml.Quaternionf");

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return Void.class;
        }
    }

    static Object vector(float x, float y, float z) {
        try {
            return VECTOR.getConstructor(float.class, float.class, float.class).newInstance(x, y, z);
        } catch (ReflectiveOperationException e) {
            return new float[] {x, y, z};
        }
    }

    static Object quaternion(float x, float y, float z, float w) {
        try {
            return QUATERNION.getConstructor(float.class, float.class, float.class, float.class).newInstance(x, y, z, w);
        } catch (ReflectiveOperationException e) {
            return new float[] {x, y, z, w};
        }
    }

    /** The named float fields of a JOML object (or the elements of a float array). */
    static float[] floats(Object v, String... names) {
        if (v instanceof float[] f) {
            return f;
        }
        float[] out = new float[names.length];
        try {
            for (int i = 0; i < names.length; i++) {
                out[i] = v.getClass().getField(names[i]).getFloat(v);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Vanillite: not a JOML value: " + v, e);
        }
        return out;
    }
}
