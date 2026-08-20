package com.termux.shared.termux.settings.properties;

/** Typed application value for the validated terminal-session drawer property. */
public enum TerminalSessionDrawerPosition {
    START(TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_START),
    END(TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_END);

    private final String mPropertyValue;

    TerminalSessionDrawerPosition(String propertyValue) {
        mPropertyValue = propertyValue;
    }

    public String getPropertyValue() {
        return mPropertyValue;
    }

    public static TerminalSessionDrawerPosition fromInternalValue(String value) {
        for (TerminalSessionDrawerPosition position : values()) {
            if (position.mPropertyValue.equals(value)) return position;
        }
        if (!TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_SESSION_DRAWER_POSITION.equals(value))
            return fromInternalValue(TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_SESSION_DRAWER_POSITION);
        throw new IllegalStateException("Invalid default terminal-session drawer position: " + value);
    }
}
