// SPDX-License-Identifier: Apache-2.0
package dev.vanillite.jvm;

import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Access to the host: the function table, the plugin id, linking of downcalls and upcalls, and
 * helpers for the ABI's strings and UUIDs.
 */
public final class Native {
    private Native() {}

    public static final Linker LINKER = Linker.nativeLinker();
    /** The host's function table. */
    public static MemorySegment host = MemorySegment.NULL;
    /** This plugin's id. */
    public static int plugin;
    /** Address of a native function {@code int noop(int)} (measurements). */
    public static long noop;
    /** Address of {@code u64 upcall_loop(fn(int) -> int, u64 n)} (measurements). */
    public static long upcallLoop;

    static void init(long table, int id, long calls) {
        MemorySegment head = MemorySegment.ofAddress(table).reinterpret(4);
        long size = Integer.toUnsignedLong(head.get(JAVA_INT, 0));
        host = MemorySegment.ofAddress(table).reinterpret(size);
        plugin = id;
    }

    /** Links the host function at {@code offset} of the host table. */
    public static MethodHandle link(long offset, FunctionDescriptor descriptor, boolean critical) {
        if (offset + 8 > host.byteSize()) {
            MethodHandle fail = MethodHandles.throwException(
                    descriptor.toMethodType().returnType(), UnsupportedOperationException.class);
            fail = MethodHandles.foldArguments(fail, MethodHandles.constant(UnsupportedOperationException.class,
                    new UnsupportedOperationException("the server's plugin API is too old for this call")));
            return MethodHandles.dropArguments(fail, 0, descriptor.toMethodType().parameterList());
        }
        MemorySegment fn = host.get(ADDRESS, offset);
        return critical
                ? LINKER.downcallHandle(fn, descriptor, Linker.Option.critical(false))
                : LINKER.downcallHandle(fn, descriptor);
    }

    /** An upcall stub for {@code target}; it lives as long as the JVM. */
    public static MemorySegment upcall(MethodHandle target, FunctionDescriptor descriptor) {
        return LINKER.upcallStub(target, descriptor, Arena.global());
    }

    /** An upcall stub for the static method {@code name} of {@code owner}. */
    public static MemorySegment upcall(Class<?> owner, String name, FunctionDescriptor descriptor) {
        try {
            MethodType type = descriptor.toMethodType();
            return upcall(MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findStatic(owner, name, type),
                    descriptor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new RuntimeException(t);
    }

    public static String trace(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    // ----- Scratch memory: one reusable buffer per thread, reset by every call that uses it.

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** Per-thread scratch memory for arguments and results of one host call. */
    public static final class Scratch {
        private final Arena arena = Arena.ofAuto();
        private MemorySegment buf = arena.allocate(64 * 1024, 16);
        private long at;

        /** Starts a call: everything allocated by the previous one is reused. */
        public Scratch reset() {
            at = 0;
            return this;
        }

        public MemorySegment alloc(long size) {
            long start = (at + 15) & ~15L;
            if (start + size > buf.byteSize()) {
                buf = arena.allocate(Math.max(buf.byteSize() * 2, start + size), 16);
                start = 0;
            }
            at = start + size;
            return buf.asSlice(start, size);
        }

        /** A {@code Str} record by value. */
        public MemorySegment str(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            MemorySegment data = alloc(Math.max(bytes.length, 1));
            MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
            MemorySegment rec = alloc(Abi.Str.SIZE);
            rec.set(ADDRESS, Abi.Str.ptr, data);
            rec.set(JAVA_LONG, Abi.Str.len, bytes.length);
            return rec;
        }

        /** A {@code Uuid} record by value. */
        public MemorySegment uuid(UUID id) {
            MemorySegment rec = alloc(Abi.Uuid.SIZE);
            rec.set(JAVA_LONG, Abi.Uuid.hi, id.getMostSignificantBits());
            rec.set(JAVA_LONG, Abi.Uuid.lo, id.getLeastSignificantBits());
            return rec;
        }

        /** A {@code StrBuf} record with {@code cap} bytes. */
        public MemorySegment strBuf(long cap) {
            MemorySegment data = alloc(cap);
            MemorySegment rec = alloc(Abi.StrBuf.SIZE);
            rec.set(ADDRESS, Abi.StrBuf.ptr, data);
            rec.set(JAVA_LONG, Abi.StrBuf.cap, cap);
            rec.set(JAVA_LONG, Abi.StrBuf.len, 0);
            return rec;
        }
    }

    /** The calling thread's scratch memory, reset. */
    public static Scratch scratch() {
        return SCRATCH.get().reset();
    }

    /** Reads a {@code Str} record. */
    public static String string(MemorySegment str) {
        long len = str.get(JAVA_LONG, Abi.Str.len);
        if (len == 0) {
            return "";
        }
        MemorySegment data = str.get(ADDRESS, Abi.Str.ptr).reinterpret(len);
        return new String(data.toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /** Reads a {@code StrBuf} filled by the host; null when it was too small. */
    public static String string(MemorySegment strBuf, boolean exact) {
        long len = strBuf.get(JAVA_LONG, Abi.StrBuf.len);
        long cap = strBuf.get(JAVA_LONG, Abi.StrBuf.cap);
        if (len > cap) {
            return null;
        }
        MemorySegment data = strBuf.get(ADDRESS, Abi.StrBuf.ptr).reinterpret(len);
        return new String(data.toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /** Reads a {@code Uuid} record. */
    public static UUID uuid(MemorySegment rec) {
        return new UUID(rec.get(JAVA_LONG, Abi.Uuid.hi), rec.get(JAVA_LONG, Abi.Uuid.lo));
    }

    /** Logs through the host. */
    public static void log(int level, String message) {
        if (host.byteSize() == 0) {
            System.err.println(message);
            return;
        }
        Host.log(plugin, level, scratch().str(message));
    }
}
