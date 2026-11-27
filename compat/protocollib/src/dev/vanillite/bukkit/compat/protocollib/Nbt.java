// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.protocollib;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Network NBT (a type byte, then the payload without a name) as JSON, for text components: the
 * wire carries components as NBT, ProtocolLib's API as JSON. Bytes become booleans (the only byte
 * fields of components are style flags), lists with mixed elements are written as compounds with
 * an empty key, as Minecraft does.
 */
public final class Nbt {
    private Nbt() {}

    /** Skips a payload of {@code type}. */
    static void skip(Wire.In in, int type) {
        switch (type) {
            case 1 -> in.skip(1);
            case 2 -> in.skip(2);
            case 3, 5 -> in.skip(4);
            case 4, 6 -> in.skip(8);
            case 7 -> in.skip(in.i32());
            case 8 -> in.skip(in.i16() & 0xffff);
            case 9 -> {
                int element = in.u8();
                int n = in.i32();
                for (int i = 0; i < n; i++) {
                    skip(in, element);
                }
            }
            case 10 -> {
                int t;
                while ((t = in.u8()) != 0) {
                    in.skip(in.i16() & 0xffff);
                    skip(in, t);
                }
            }
            case 11 -> in.skip(in.i32() * 4);
            case 12 -> in.skip(in.i32() * 8);
            default -> throw new Wire.Malformed("unknown NBT tag " + type);
        }
    }

    /** A component's network NBT as JSON text. */
    public static String toJson(byte[] nbt) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbt))) {
            int type = in.readUnsignedByte();
            return type == 0 ? "\"\"" : read(in, type).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** JSON text of a component as network NBT. */
    public static byte[] fromJson(String json) {
        JsonElement e = JsonParser.parseString(json);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(type(e));
            write(out, e);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }

    private static JsonElement read(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case 1 -> {
                byte b = in.readByte();
                yield b == 0 || b == 1 ? new JsonPrimitive(b == 1) : new JsonPrimitive(b);
            }
            case 2 -> new JsonPrimitive(in.readShort());
            case 3 -> new JsonPrimitive(in.readInt());
            case 4 -> new JsonPrimitive(in.readLong());
            case 5 -> new JsonPrimitive(in.readFloat());
            case 6 -> new JsonPrimitive(in.readDouble());
            case 7 -> {
                JsonArray a = new JsonArray();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    a.add(in.readByte());
                }
                yield a;
            }
            case 8 -> new JsonPrimitive(in.readUTF());
            case 9 -> {
                int element = in.readUnsignedByte();
                int n = in.readInt();
                JsonArray a = new JsonArray();
                for (int i = 0; i < n; i++) {
                    JsonElement e = read(in, element);
                    // A compound with only an empty key wraps an element of a mixed list.
                    if (e instanceof JsonObject o && o.size() == 1 && o.has("")) {
                        e = o.get("");
                    }
                    a.add(e);
                }
                yield a;
            }
            case 10 -> {
                JsonObject o = new JsonObject();
                int t;
                while ((t = in.readUnsignedByte()) != 0) {
                    String name = in.readUTF();
                    o.add(name, read(in, t));
                }
                yield o;
            }
            case 11 -> {
                JsonArray a = new JsonArray();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    a.add(in.readInt());
                }
                yield a;
            }
            case 12 -> {
                JsonArray a = new JsonArray();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    a.add(in.readLong());
                }
                yield a;
            }
            default -> throw new IOException("unknown NBT tag " + type);
        };
    }

    private static int type(JsonElement e) {
        if (e == null || e instanceof JsonNull) {
            return 8;
        }
        if (e instanceof JsonObject) {
            return 10;
        }
        if (e instanceof JsonArray) {
            return 9;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return 1;
        }
        if (p.isNumber()) {
            double d = p.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE && !p.getAsString().contains(".") ? 3 : 6;
        }
        return 8;
    }

    private static void write(DataOutputStream out, JsonElement e) throws IOException {
        switch (type(e)) {
            case 1 -> out.writeByte(e.getAsBoolean() ? 1 : 0);
            case 3 -> out.writeInt(e.getAsInt());
            case 6 -> out.writeDouble(e.getAsDouble());
            case 8 -> out.writeUTF(e == null || e instanceof JsonNull ? "" : e.getAsString());
            case 10 -> {
                for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                    out.writeByte(type(entry.getValue()));
                    out.writeUTF(entry.getKey());
                    write(out, entry.getValue());
                }
                out.writeByte(0);
            }
            default -> {
                JsonArray a = e.getAsJsonArray();
                int element = a.isEmpty() ? 0 : type(a.get(0));
                boolean mixed = false;
                for (JsonElement x : a) {
                    mixed |= type(x) != element;
                }
                out.writeByte(mixed ? 10 : element);
                out.writeInt(a.size());
                for (JsonElement x : a) {
                    if (mixed && type(x) != 10) {
                        out.writeByte(type(x));
                        out.writeUTF("");
                        write(out, x);
                        out.writeByte(0);
                    } else {
                        write(out, x);
                    }
                }
            }
        }
    }
}
