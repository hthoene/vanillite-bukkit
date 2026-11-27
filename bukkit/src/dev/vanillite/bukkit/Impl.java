// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes the layer's partial API implementations instantiable: for an abstract class that
 * implements some methods of a large API interface, it generates (once, at run time) a final
 * subclass whose every still-abstract method throws {@link UnsupportedOperationException} naming
 * the method. Implemented methods stay ordinary virtual calls.
 */
public final class Impl {
    private Impl() {}

    private static final Map<Class<?>, Class<?>> GENERATED = new ConcurrentHashMap<>();
    private static final Map<java.util.List<Class<?>>, Class<?>> GENERATED_WITH = new ConcurrentHashMap<>();
    private static final ClassDesc UOE = ClassDesc.of("java.lang.UnsupportedOperationException");

    /** The generated concrete subclass of {@code base}. */
    @SuppressWarnings("unchecked")
    public static <T> Class<? extends T> of(Class<T> base) {
        return (Class<? extends T>) GENERATED.computeIfAbsent(base, b -> generate(b, java.util.List.of()));
    }

    /**
     * The generated concrete subclass of {@code base} that also implements the API interfaces
     * {@code extra} (their methods that {@code base} does not have throw), e.g. an item meta of a
     * kind the layer implements only in part.
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<? extends T> of(Class<T> base, Class<?>... extra) {
        if (extra.length == 0) {
            return of(base);
        }
        java.util.List<Class<?>> key = new java.util.ArrayList<>();
        key.add(base);
        key.addAll(java.util.List.of(extra));
        return (Class<? extends T>) GENERATED_WITH.computeIfAbsent(java.util.List.copyOf(key),
                k -> generate(base, k.subList(1, k.size())));
    }

    /** A constructor of the generated subclass of {@code base} with the given parameter types. */
    public static MethodHandle constructor(Class<?> base, Class<?>... params) {
        try {
            MethodType type = MethodType.methodType(void.class, params);
            return MethodHandles.privateLookupIn(of(base), MethodHandles.lookup())
                    .findConstructor(of(base), type)
                    .asType(MethodType.methodType(base, params));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new instance of the generated subclass of {@code base} (no-argument constructor). */
    public static <T> T create(Class<T> base) {
        try {
            return base.cast(of(base).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(Method m) {
        return m.getName() + MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString();
    }

    private static Class<?> generate(Class<?> base, java.util.List<Class<?>> extra) {
        Set<String> concrete = new HashSet<>();
        Map<String, Method> missing = new LinkedHashMap<>();
        Deque<Class<?>> interfaces = new ArrayDeque<>();
        for (Class<?> c = base; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isAbstract(m.getModifiers())) {
                    concrete.add(key(m));
                }
            }
            interfaces.addAll(java.util.List.of(c.getInterfaces()));
        }
        interfaces.addAll(extra);
        Set<Class<?>> seen = new HashSet<>();
        java.util.List<Method> abstracts = new java.util.ArrayList<>();
        for (Class<?> c = base; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isAbstract(mod) && (Modifier.isPublic(mod) || Modifier.isProtected(mod))) {
                    abstracts.add(m);
                }
            }
        }
        while (!interfaces.isEmpty()) {
            Class<?> i = interfaces.pop();
            if (!seen.add(i)) {
                continue;
            }
            interfaces.addAll(java.util.List.of(i.getInterfaces()));
            for (Method m : i.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) {
                    continue;
                }
                if (Modifier.isAbstract(m.getModifiers())) {
                    abstracts.add(m);
                } else {
                    concrete.add(key(m));
                }
            }
        }
        for (Method m : abstracts) {
            String k = key(m);
            if (!concrete.contains(k)) {
                missing.putIfAbsent(k, m);
            }
        }
        StringBuilder name = new StringBuilder(base.getName()).append("$Impl");
        for (Class<?> i : extra) {
            name.append('$').append(i.getSimpleName());
        }
        ClassDesc self = ClassDesc.of(name.toString());
        ClassDesc parent = ClassDesc.of(base.getName());
        String api = apiName(base);
        byte[] bytes = ClassFile.of().build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
            cb.withSuperclass(parent);
            if (!extra.isEmpty()) {
                cb.withInterfaceSymbols(extra.stream().map(i -> ClassDesc.of(i.getName())).toList());
            }
            for (Constructor<?> c : base.getDeclaredConstructors()) {
                if (Modifier.isPrivate(c.getModifiers())) {
                    continue;
                }
                MethodTypeDesc type = MethodType.methodType(void.class, c.getParameterTypes()).describeConstable().orElseThrow();
                cb.withMethodBody(ConstantDescs.INIT_NAME, type, ClassFile.ACC_PUBLIC, code -> {
                    code.aload(0);
                    int slot = 1;
                    for (Class<?> p : c.getParameterTypes()) {
                        var kind = java.lang.classfile.TypeKind.from(p);
                        code.loadLocal(kind, slot);
                        slot += kind.slotSize();
                    }
                    code.invokespecial(parent, ConstantDescs.INIT_NAME, type);
                    code.return_();
                });
            }
            for (Method m : missing.values()) {
                MethodTypeDesc type = MethodType.methodType(m.getReturnType(), m.getParameterTypes()).describeConstable().orElseThrow();
                String message = "Vanillite: " + m.getDeclaringClass().getSimpleName() + "." + m.getName()
                        + " is not implemented yet (" + api + ")";
                int flags = Modifier.isProtected(m.getModifiers()) ? ClassFile.ACC_PROTECTED : ClassFile.ACC_PUBLIC;
                cb.withMethodBody(m.getName(), type, flags, code -> code
                        .new_(UOE)
                        .dup()
                        .ldc(message)
                        .invokespecial(UOE, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                        .athrow());
            }
        });
        try {
            return MethodHandles.privateLookupIn(base, MethodHandles.lookup()).defineClass(bytes);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String apiName(Class<?> base) {
        for (Class<?> c = base; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (!i.getName().startsWith("dev.vanillite")) {
                    return i.getName();
                }
            }
        }
        return base.getName();
    }
}
