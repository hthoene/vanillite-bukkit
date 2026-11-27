// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.protocollib;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Minecraft's wire format: a reader over a packet body and a growing writer. */
public final class Wire {
    private Wire() {}

    /** Thrown for a body that does not match the expected format. */
    public static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    /** Reads a packet body. */
    public static final class In {
        private final byte[] data;
        private int at;

        public In(byte[] data, int at) {
            this.data = data;
            this.at = at;
        }

        public int position() {
            return at;
        }

        public boolean more() {
            return at < data.length;
        }

        public byte[] rest() {
            byte[] out = Arrays.copyOfRange(data, at, data.length);
            at = data.length;
            return out;
        }

        private void need(int n) {
            if (n < 0 || at + n > data.length) {
                throw new Malformed("packet ends early at byte " + at);
            }
        }

        public int u8() {
            need(1);
            return data[at++] & 0xff;
        }

        public byte i8() {
            need(1);
            return data[at++];
        }

        public boolean bool() {
            return u8() != 0;
        }

        public short i16() {
            need(2);
            short v = (short) (((data[at] & 0xff) << 8) | (data[at + 1] & 0xff));
            at += 2;
            return v;
        }

        public int i32() {
            need(4);
            int v = ((data[at] & 0xff) << 24) | ((data[at + 1] & 0xff) << 16) | ((data[at + 2] & 0xff) << 8)
                    | (data[at + 3] & 0xff);
            at += 4;
            return v;
        }

        public long i64() {
            return ((long) i32() << 32) | (i32() & 0xffffffffL);
        }

        public float f32() {
            return Float.intBitsToFloat(i32());
        }

        public double f64() {
            return Double.longBitsToDouble(i64());
        }

        public int varInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = u8();
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new Malformed("VarInt too long");
        }

        public long varLong() {
            long value = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                int b = u8();
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new Malformed("VarLong too long");
        }

        public String string() {
            int len = varInt();
            need(len);
            String s = new String(data, at, len, StandardCharsets.UTF_8);
            at += len;
            return s;
        }

        public byte[] bytes(int len) {
            need(len);
            byte[] out = Arrays.copyOfRange(data, at, at + len);
            at += len;
            return out;
        }

        public UUID uuid() {
            return new UUID(i64(), i64());
        }

        /** A packed block position: x, y, z. */
        public int[] blockPos() {
            long v = i64();
            return new int[] {(int) (v >> 38), (int) (v << 52 >> 52), (int) (v << 26 >> 38)};
        }

        /** The bytes from {@code start} to the end; the reader is then at the end. */
        public byte[] restFrom(int start) {
            at = start;
            return rest();
        }

        /** The bytes from {@code start} to the current position. */
        public byte[] slice(int start) {
            return Arrays.copyOfRange(data, start, at);
        }

        /** Skips a chat session ({@code RemoteChatSession.Data}): id, key expiry, key and signature. */
        public void skipChatSession() {
            skip(16 + 8);
            skip(varInt());
            skip(varInt());
        }

        /** Skips one network NBT tag (type byte and payload) and returns its bytes. */
        public byte[] nbt() {
            int start = at;
            int type = u8();
            if (type != 0) {
                Nbt.skip(this, type);
            }
            return Arrays.copyOfRange(data, start, at);
        }

        void skip(int n) {
            need(n);
            at += n;
        }
    }

    /** Writes a packet body. */
    public static final class Out {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        public byte[] bytes() {
            return out.toByteArray();
        }

        public Out u8(int v) {
            out.write(v);
            return this;
        }

        public Out bool(boolean v) {
            return u8(v ? 1 : 0);
        }

        public Out i16(int v) {
            out.write(v >>> 8);
            out.write(v);
            return this;
        }

        public Out i32(int v) {
            out.write(v >>> 24);
            out.write(v >>> 16);
            out.write(v >>> 8);
            out.write(v);
            return this;
        }

        public Out i64(long v) {
            i32((int) (v >>> 32));
            return i32((int) v);
        }

        public Out f32(float v) {
            return i32(Float.floatToIntBits(v));
        }

        public Out f64(double v) {
            return i64(Double.doubleToLongBits(v));
        }

        public Out varInt(int v) {
            while ((v & ~0x7f) != 0) {
                out.write((v & 0x7f) | 0x80);
                v >>>= 7;
            }
            out.write(v);
            return this;
        }

        public Out varLong(long v) {
            while ((v & ~0x7fL) != 0) {
                out.write((int) (v & 0x7f) | 0x80);
                v >>>= 7;
            }
            out.write((int) v);
            return this;
        }

        public Out string(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varInt(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        public Out raw(byte[] b) {
            out.write(b, 0, b.length);
            return this;
        }

        public Out uuid(UUID id) {
            i64(id.getMostSignificantBits());
            return i64(id.getLeastSignificantBits());
        }

        public Out blockPos(int x, int y, int z) {
            return i64(((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff));
        }
    }

    /** Reads a low-precision vector ({@code LpVec3}): x, y, z. */
    public static double[] lpVec3(In in) {
        int lowest = in.u8();
        if (lowest == 0) {
            return new double[3];
        }
        int middle = in.u8();
        long highest = in.i32() & 0xffffffffL;
        long buffer = highest << 16 | (long) middle << 8 | lowest;
        long scale = lowest & 3;
        if ((lowest & 4) == 4) {
            scale |= (in.varInt() & 0xffffffffL) << 2;
        }
        return new double[] {unpack(buffer >> 3) * scale, unpack(buffer >> 18) * scale, unpack(buffer >> 33) * scale};
    }

    /** Writes a low-precision vector ({@code LpVec3}). */
    public static void lpVec3(Out out, double x, double y, double z) {
        x = sanitize(x);
        y = sanitize(y);
        z = sanitize(z);
        double max = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (max < 3.051944088384301E-5) {
            out.u8(0);
            return;
        }
        long scale = (long) Math.ceil(max);
        boolean partial = (scale & 3L) != scale;
        long markers = partial ? scale & 3L | 4L : scale;
        long buffer = markers | pack(x / scale) << 3 | pack(y / scale) << 18 | pack(z / scale) << 33;
        out.u8((int) buffer);
        out.u8((int) (buffer >> 8));
        out.i32((int) (buffer >> 16));
        if (partial) {
            out.varInt((int) (scale >> 2));
        }
    }

    private static double sanitize(double v) {
        return Double.isNaN(v) ? 0.0 : Math.clamp(v, -1.7179869183E10, 1.7179869183E10);
    }

    private static long pack(double v) {
        return Math.round((v * 0.5 + 0.5) * 32766.0);
    }

    private static double unpack(long v) {
        return Math.min((double) (v & 32767L), 32766.0) * 2.0 / 32766.0 - 1.0;
    }
}
