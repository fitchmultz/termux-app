package com.termux.terminal;

/** Per-screen progressive keyboard flags. Supports disambiguation, event and alternate-key reporting. */
final class KittyKeyboardState {
    // Preserve PR5366's upstream policy: 32 entries including current, not a protocol maximum.
    // https://github.com/JM-FRANK/termux-app/blob/a9f40a5b813146091b41d0a130043bd0740beff8/terminal-emulator/src/main/java/com/termux/terminal/KittyKeyboardState.java#L5-L9
    // The current flags occupy one entry; reserve at most 31 saved entries.
    private static final int MAX_DEPTH = 31;
    private final int[] mSavedFlags = new int[MAX_DEPTH];
    private int mDepth;
    private int mFlags;
    private long mGeneration;

    int getFlags() {
        return mFlags;
    }

    long getGeneration() {
        return mGeneration;
    }

    void invalidateEvents() {
        mGeneration++;
    }

    void set(int flags, int mode) {
        flags &= KeyHandler.KITTY_DISAMBIGUATE | KeyHandler.KITTY_REPORT_EVENTS |
            KeyHandler.KITTY_REPORT_ALTERNATE_KEYS;
        if (mode >= 1 && mode <= 3) mGeneration++;
        switch (mode) {
            case 1: mFlags = flags; break;
            case 2: mFlags |= flags; break;
            case 3: mFlags &= ~flags; break;
            default: break;
        }
    }

    void push(int flags) {
        if (mDepth == MAX_DEPTH) {
            System.arraycopy(mSavedFlags, 1, mSavedFlags, 0, MAX_DEPTH - 1);
            mDepth--;
        }
        mSavedFlags[mDepth++] = mFlags;
        set(flags, 1);
    }

    void pop(int count) {
        if (count <= 0) return;
        mGeneration++;
        if (count > mDepth) {
            reset();
        } else {
            mDepth -= count;
            mFlags = mSavedFlags[mDepth];
        }
    }

    void reset() {
        mDepth = mFlags = 0;
        mGeneration++;
    }
}
