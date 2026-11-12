package com.termux.shared.termux.extrakeys;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/** The {@link Class} that maintains a state of a {@link SpecialButton} */
public class SpecialButtonState {

    /**
     * Lifecycle of one special button: it either does not exist in the current layout or exists as
     * inactive, active (one-shot modifier) or locked (activated by a long hold, so reading it does
     * not deactivate it).
     */
    enum State { UNCREATED, INACTIVE, ACTIVE, LOCKED }

    private State state = State.UNCREATED;

    List<MaterialButton> buttons = new ArrayList<>();

    ExtraKeysView mExtraKeysView;

    /**
     * Initialize a {@link SpecialButtonState} to maintain state of a {@link SpecialButton}.
     *
     * @param extraKeysView The {@link ExtraKeysView} instance in which the {@link SpecialButton}
     *                      is to be registered.
     */
    public SpecialButtonState(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    /** Record that the button exists in the current layout. */
    void markCreated() {
        if (state == State.UNCREATED) setState(State.INACTIVE);
    }

    /** A modifier removed from the layout no longer owns a latch or lock. */
    void markUncreated() {
        setState(State.UNCREATED);
    }

    /** Tap: toggle the one-shot modifier. A locked modifier is released first. */
    void toggle() {
        if (state == State.UNCREATED) return;
        setState(isActive() ? State.INACTIVE : State.ACTIVE);
    }

    /** Long hold: activate as locked, or release an already latched modifier. */
    void longHold() {
        if (state == State.UNCREATED) return;
        setState(isActive() ? State.INACTIVE : State.LOCKED);
    }

    /**
     * A modifier was read by a key press.
     *
     * @param allowRelease {@code false} keeps a locked modifier and any inactive button as is.
     */
    void releaseAfterRead(boolean allowRelease) {
        if (allowRelease && state == State.ACTIVE) setState(State.INACTIVE);
    }

    boolean isCreated() { return state != State.UNCREATED; }
    boolean isActive() { return state == State.ACTIVE || state == State.LOCKED; }
    boolean isLocked() { return state == State.LOCKED; }

    private void setState(State newState) {
        state = newState;
        boolean active = isActive();
        for (MaterialButton button : buttons) {
            button.setTextColor(active ? mExtraKeysView.getButtonActiveTextColor() : mExtraKeysView.getButtonTextColor());
        }
    }

}
