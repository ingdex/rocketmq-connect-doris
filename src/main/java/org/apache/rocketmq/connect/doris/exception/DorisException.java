package org.apache.rocketmq.connect.doris.exception;

public class DorisException extends RuntimeException {
    private static final long serialVersionUID = 2L;

    public DorisException(String message) {
        super(message);
    }

    public DorisException(String name, Object value) {
        this(name, value, null);
    }

    public DorisException(String name, Object value, String message) {
        super("Invalid value " + value + " for configuration " + name + (message == null ? "" : ": " + message));
    }
}