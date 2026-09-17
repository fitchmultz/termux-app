package com.termux.app.terminal;

import android.content.Context;
import android.app.AlertDialog;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import com.termux.R;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.function.Consumer;
import java.util.function.Function;

/** Two independent terminal views. Hiding a pane never ends or reattaches its session. */
public final class TerminalPaneLayout extends LinearLayout {
    private final TerminalView[] terminals = new TerminalView[2];
    private final LinearLayout[] panes = new LinearLayout[2];
    private final TextView[] titles = new TextView[2];
    private final View[] headers = new View[2];
    private TextView singlePaneTitle;
    private final ImageButton[] zoom = new ImageButton[2];
    private final int[] fontSizes = new int[2];
    private final View divider;
    private Consumer<TerminalView> onActiveChanged;
    private int active;
    private boolean paired, maximized, reversed, allowCompact;
    private Boolean compactState;
    private float fraction = 0.5f;

    public TerminalPaneLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            final int index = i;
            LinearLayout pane = panes[i] = new LinearLayout(context);
            pane.setOrientation(VERTICAL);
            pane.setPadding(dp(2), dp(2), dp(2), dp(2));
            TextView title = titles[i] = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(13);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setPadding(dp(10), 0, dp(10), 0);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setOnClickListener(v -> activate(index, true));
            title.setFocusable(true);
            LinearLayout header = new LinearLayout(context);
            headers[i] = header;
            header.addView(title, new LayoutParams(0, dp(48), 1));
            ImageButton maximize = zoom[i] = new ImageButton(context);
            maximize.setImageResource(android.R.drawable.ic_menu_crop);
            maximize.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            maximize.setBackgroundColor(Color.TRANSPARENT);
            maximize.setOnClickListener(v -> { activate(index, true); toggleMaximize(); });
            header.addView(maximize, new LayoutParams(dp(48), dp(48)));
            pane.addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
            TerminalView terminal = terminals[i] = new TerminalView(context, null);
            terminal.setId(i == 0 ? R.id.terminal_view : R.id.terminal_view_secondary);
            terminal.setFocusableInTouchMode(true);
            terminal.setDefaultFocusHighlightEnabled(false);
            terminal.setSaveEnabled(false);
            terminal.setBackgroundColor(Color.BLACK);
            terminal.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) activate(index, true);
                return false;
            });
            pane.addView(terminal, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        }
        divider = new View(context);
        divider.setBackgroundColor(Color.rgb(48, 57, 66));
        divider.setContentDescription(context.getString(R.string.split_divider_description));
        divider.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                int[] location = new int[2];
                getLocationOnScreen(location);
                float extent = getOrientation() == HORIZONTAL ? getWidth() : getHeight();
                float position = getOrientation() == HORIZONTAL ? event.getRawX() - location[0] : event.getRawY() - location[1];
                if (extent > 0) setFraction(position / extent);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
            return true;
        });
        addView(panes[0]);
        addView(divider);
        addView(panes[1]);
        updateLayout();
    }

    public TerminalView[] getTerminals() { return terminals.clone(); }
    public TerminalView getActiveTerminal() { return terminals[active]; }
    public boolean isPaired() { return paired; }
    public boolean isShowingBoth() { return paired && panes[0].getVisibility() == VISIBLE && panes[1].getVisibility() == VISIBLE; }
    public float getFraction() { return fraction; }
    public void setOnActiveChanged(Consumer<TerminalView> listener) { onActiveChanged = listener; }

    public void activate(TerminalView terminal) { activate(terminal == terminals[0] ? 0 : 1, false); }

    private void activate(int index, boolean focus) {
        boolean changed = index != active;
        boolean moveFocus = focus || (changed && terminals[active].hasFocus());
        if (changed) {
            terminals[active].cancelPendingInputGesture();
            terminals[active].stopTextSelectionMode();
            active = index;
        }
        updateLayout();
        if (changed && onActiveChanged != null) onActiveChanged.accept(terminals[active]);
        if (moveFocus) terminals[active].requestFocus();
    }

    public boolean showSession(TerminalSession session) {
        if (session == null) return false;
        for (int i = 0; i < 2; i++) {
            if (terminals[i].getCurrentSession() == session) {
                activate(i, false);
                return false;
            }
        }
        boolean changed = terminals[active].attachSession(session);
        updateLayout();
        if (onActiveChanged != null) onActiveChanged.accept(terminals[active]);
        return changed;
    }

    public void openBeside(TerminalSession session) {
        if (session == null || session == getActiveTerminal().getCurrentSession()) return;
        if (getActiveTerminal().getCurrentSession() == null) { showSession(session); return; }
        int other = 1 - active;
        terminals[other].attachSession(session);
        paired = true;
        maximized = false;
        allowCompact = true;
        if (getWidth() / getResources().getDisplayMetrics().density < 600) setOrientation(VERTICAL);
        activate(other, true);
    }

    public void removeSession(TerminalSession session) {
        for (int i = 0; i < 2; i++) {
            if (terminals[i].getCurrentSession() != session) continue;
            terminals[i].stopTextSelectionMode();
            terminals[i].attachSession(null);
            paired = false;
            if (i == active && terminals[1 - i].getCurrentSession() != null) activate(1 - i, true);
        }
        updateLayout();
    }

    public void singlePane() {
        terminals[1 - active].stopTextSelectionMode();
        terminals[1 - active].attachSession(null);
        paired = false;
        maximized = false;
        updateLayout();
    }

    public void toggleMaximize() {
        if (isShowingBoth()) maximized = true;
        else { maximized = false; allowCompact = true; }
        updateLayout();
    }

    public void setSplitOrientation(int orientation) {
        setOrientation(orientation);
        updateLayout();
    }

    public void swap() {
        reversed = !reversed;
        removeAllViews();
        addView(panes[reversed ? 1 : 0]);
        addView(divider);
        addView(panes[reversed ? 0 : 1]);
        updateLayout();
    }

    public void showOptions(View anchor, Runnable newSplit) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(Menu.NONE, 1, Menu.NONE, R.string.split_new);
        if (paired) {
            menu.getMenu().add(Menu.NONE, 2, Menu.NONE, R.string.split_horizontal);
            menu.getMenu().add(Menu.NONE, 3, Menu.NONE, R.string.split_vertical);
            menu.getMenu().add(Menu.NONE, 4, Menu.NONE, isShowingBoth() ? R.string.split_maximize : R.string.split_restore);
            menu.getMenu().add(Menu.NONE, 5, Menu.NONE, R.string.split_swap);
            menu.getMenu().add(Menu.NONE, 6, Menu.NONE, R.string.split_resize);
            menu.getMenu().add(Menu.NONE, 7, Menu.NONE, R.string.split_single);
        }
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: newSplit.run(); break;
                case 2: setSplitOrientation(HORIZONTAL); break;
                case 3: setSplitOrientation(VERTICAL); break;
                case 4: toggleMaximize(); break;
                case 5: swap(); break;
                case 6:
                    SeekBar slider = new SeekBar(getContext());
                    slider.setContentDescription(getContext().getString(R.string.split_resize));
                    slider.setMax(50);
                    slider.setProgress(Math.round(fraction * 100) - 25);
                    slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser) setFraction((progress + 25) / 100f); }
                        public void onStartTrackingTouch(SeekBar bar) {}
                        public void onStopTrackingTouch(SeekBar bar) {}
                    });
                    new AlertDialog.Builder(getContext()).setTitle(R.string.split_resize).setView(slider).setPositiveButton(android.R.string.ok, null).show();
                    break;
                case 7: singlePane(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    public void setFraction(float value) {
        fraction = Math.max(0.25f, Math.min(0.75f, Float.isNaN(value) ? 0.5f : value));
        updateLayout();
    }

    public void setFontSize(TerminalView terminal, int size) {
        int index = terminal == terminals[0] ? 0 : 1;
        fontSizes[index] = size;
        terminal.setTextSize(size);
    }

    public int getFontSize(TerminalView terminal) { return fontSizes[terminal == terminals[0] ? 0 : 1]; }

    public void setSinglePaneTitle(TextView title) {
        singlePaneTitle = title;
        refreshTitles();
    }

    public void refreshTitles() {
        for (int i = 0; i < 2; i++) {
            TerminalSession session = terminals[i].getCurrentSession();
            String name = session == null ? getContext().getString(R.string.split_empty) : session.mSessionName;
            if (TextUtils.isEmpty(name) && session != null) name = session.getTitle();
            if (TextUtils.isEmpty(name)) name = getContext().getString(R.string.split_session);
            titles[i].setText((i == active ? "● " : "○ ") + (i == 0 ? "A · " : "B · ") + name);
            titles[i].setContentDescription(getContext().getString(i == active ? R.string.split_active_pane : R.string.split_focus_pane, name));
            headers[i].setVisibility(singlePaneTitle == null || isShowingBoth() ? VISIBLE : GONE);
            zoom[i].setVisibility(paired ? VISIBLE : GONE);
            zoom[i].setContentDescription(getContext().getString(isShowingBoth() ? R.string.split_maximize : R.string.split_restore));
            panes[i].setBackgroundColor(i == active ? Color.rgb(0, 115, 125) : Color.rgb(32, 38, 45));
        }
        if (singlePaneTitle != null) {
            singlePaneTitle.setText(isShowingBoth() ? getContext().getString(R.string.application_name) : titles[active].getText());
            singlePaneTitle.setContentDescription(titles[active].getContentDescription());
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        boolean compact = MeasureSpec.getSize(widthSpec) / getResources().getDisplayMetrics().density < 600;
        if (compactState == null || compactState != compact) {
            if (compactState != null) allowCompact = false;
            compactState = compact;
            updateLayout();
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void updateLayout() {
        if (divider == null) return;
        boolean compact = compactState == null || compactState;
        boolean both = paired && !maximized && (!compact || allowCompact);
        boolean horizontal = getOrientation() == HORIZONTAL;
        divider.setVisibility(both ? VISIBLE : GONE);
        divider.setLayoutParams(new LayoutParams(horizontal ? dp(16) : LayoutParams.MATCH_PARENT, horizontal ? LayoutParams.MATCH_PARENT : dp(16)));
        for (int i = 0; i < 2; i++) {
            panes[i].setVisibility(both || i == active ? VISIBLE : GONE);
            float weight = both ? ((i == (reversed ? 1 : 0)) ? fraction : 1 - fraction) : 1;
            LayoutParams params = new LayoutParams(horizontal ? 0 : LayoutParams.MATCH_PARENT, horizontal ? LayoutParams.MATCH_PARENT : 0, weight);
            panes[i].setLayoutParams(params);
        }
        refreshTitles();
    }

    public Bundle saveState() {
        Bundle state = new Bundle();
        for (int i = 0; i < 2; i++) {
            TerminalSession session = terminals[i].getCurrentSession();
            if (session != null) state.putString("session" + i, session.mHandle);
            state.putInt("font" + i, fontSizes[i]);
        }
        state.putInt("active", active);
        state.putBoolean("paired", paired);
        state.putBoolean("maximized", maximized);
        state.putBoolean("reversed", reversed);
        state.putBoolean("allowCompact", allowCompact);
        state.putInt("orientation", getOrientation());
        state.putFloat("fraction", fraction);
        return state;
    }

    public void restoreState(Bundle state, Function<String, TerminalSession> lookup) {
        if (state == null) return;
        for (int i = 0; i < 2; i++) {
            TerminalSession session = lookup.apply(state.getString("session" + i));
            if (session != null && (i == 0 || session != terminals[0].getCurrentSession())) terminals[i].attachSession(session);
            int size = state.getInt("font" + i, fontSizes[i]);
            if (size > 0) setFontSize(terminals[i], size);
        }
        paired = state.getBoolean("paired") && terminals[0].getCurrentSession() != null && terminals[1].getCurrentSession() != null;
        maximized = state.getBoolean("maximized");
        allowCompact = state.getBoolean("allowCompact");
        if (state.getBoolean("reversed") != reversed) swap();
        setOrientation(state.getInt("orientation") == VERTICAL ? VERTICAL : HORIZONTAL);
        setFraction(state.getFloat("fraction", 0.5f));
        int index = state.getInt("active") == 1 ? 1 : 0;
        if (terminals[index].getCurrentSession() == null && terminals[1 - index].getCurrentSession() != null) index = 1 - index;
        activate(index, false);
        if (onActiveChanged != null) onActiveChanged.accept(terminals[active]);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
