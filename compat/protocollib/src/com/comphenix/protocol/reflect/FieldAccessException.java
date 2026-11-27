// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.reflect;

/** A packet field that cannot be read or written. */
public class FieldAccessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FieldAccessException() {}

    public FieldAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public FieldAccessException(String message) {
        super(message);
    }

    public FieldAccessException(Throwable cause) {
        super(cause);
    }

    public static FieldAccessException fromFormat(String format, Object... args) {
        return new FieldAccessException(String.format(format, args));
    }

    @Override
    public String toString() {
        return "FieldAccessException: " + getMessage();
    }
}
