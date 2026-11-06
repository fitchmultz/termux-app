package com.termux.app.activities;

import android.content.ClipData;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.terminal.io.ExtraKeysLayout;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

public final class ExtraKeysActivity extends AppCompatActivity {
    private ExtraKeysLayout layout;
    private LinearLayout rows;
    private String initial;
    private boolean useFile;
    private int customRow = -1;
    private EditText customKey, customLabel;
    private CheckBox customMacro;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        com.termux.shared.activity.media.AppCompatActivityUtils.setNightMode(this,
            com.termux.shared.theme.NightMode.getAppNightMode().getName(), true);
        setTitle(R.string.controls_title);
        String file = (String) TermuxAppSharedProperties.init(this).getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
        initial = state == null ? ExtraKeysLayout.effective(this, file) : state.getString("initial");
        useFile = state != null && state.getBoolean("useFile");
        try {
            initial = new ExtraKeysLayout(initial).json();
            layout = new ExtraKeysLayout(state == null ? initial : state.getString("layout"));
        } catch (JSONException e) {
            new AlertDialog.Builder(this).setMessage(R.string.controls_invalid)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
            return;
        }
        LinearLayout screen = column();
        setContentView(screen);
        TextView heading = new TextView(this);
        heading.setText(R.string.controls_title);
        heading.setTextSize(20);
        screen.addView(heading);
        TextView help = new TextView(this);
        help.setText(R.string.controls_instructions);
        screen.addView(help);
        ScrollView scroll = new ScrollView(this);
        rows = column();
        scroll.addView(rows);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        actions.addView(button(getString(R.string.controls_add_row), v -> { layout.addRow(); changed(); }));
        actions.addView(button(getString(R.string.controls_reset), v -> new AlertDialog.Builder(this)
            .setItems(new String[]{getString(R.string.controls_fold_default), getString(R.string.controls_use_file)}, (d, choice) -> {
                try {
                    layout = choice == 0 ? ExtraKeysLayout.defaults() : new ExtraKeysLayout(file);
                    useFile = choice == 1;
                    render();
                } catch (JSONException e) { message(R.string.controls_invalid); }
            }).show()));
        screen.addView(actions);
        LinearLayout finish = new LinearLayout(this);
        finish.addView(button(getString(android.R.string.cancel), v -> finish()));
        finish.addView(button(getString(R.string.controls_save), v -> {
            boolean saved = useFile ? ExtraKeysLayout.useFile(this) : layout.save(this);
            if (saved) { setResult(RESULT_OK); finish(); }
            else message(R.string.controls_save_failed);
        }));
        screen.addView(finish);
        render();
        if (state != null && state.getInt("customRow", -1) >= 0) {
            customKey(state.getInt("customRow"));
            customKey.setText(state.getString("customKey"));
            customLabel.setText(state.getString("customLabel"));
            customMacro.setChecked(state.getBoolean("customMacro"));
        }
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        button.setOnClickListener(listener);
        return button;
    }

    private void changed() { useFile = false; render(); }

    private void render() {
        rows.removeAllViews();
        for (int r = 0; r < layout.rowCount(); r++) {
            final int row = r;
            TextView title = new TextView(this);
            title.setText(getString(R.string.controls_row, r + 1));
            rows.addView(title);
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            LinearLayout keys = new LinearLayout(this);
            scroll.addView(keys);
            for (int c = 0; c < layout.keyCount(r); c++) {
                final int column = c;
                Button key = button(label(layout.key(r, c)), v -> editKey(row, column));
                key.setOnLongClickListener(v -> v.startDragAndDrop(ClipData.newPlainText("key", ""),
                    new View.DragShadowBuilder(v), new int[]{row, column}, 0));
                dropTarget(key, row, column);
                keys.addView(key);
            }
            Button add = button(getString(R.string.controls_add_key), v -> addKey(row));
            dropTarget(add, row, layout.keyCount(row));
            keys.addView(add);
            rows.addView(scroll);
            if (layout.keyCount(row) == 0) rows.addView(button(getString(R.string.controls_remove_row), v -> {
                layout.removeRow(row); changed();
            }));
        }
        if (layout.rowCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.controls_empty);
            rows.addView(empty);
        }
    }

    private void dropTarget(View view, int row, int column) {
        view.setOnDragListener((v, event) -> {
            if (!(event.getLocalState() instanceof int[])) return false;
            if (event.getAction() == DragEvent.ACTION_DROP) {
                int[] from = (int[]) event.getLocalState();
                layout.move(from[0], from[1], row, column);
                changed();
            }
            return true;
        });
    }

    private void editKey(int row, int column) {
        new AlertDialog.Builder(this).setTitle(label(layout.key(row, column)))
            .setItems(new String[]{getString(R.string.controls_left), getString(R.string.controls_right),
                getString(R.string.controls_move_row), getString(R.string.controls_remove)}, (dialog, action) -> {
                if (action == 0 && column > 0) layout.move(row, column, row, column - 1);
                if (action == 1 && column + 1 < layout.keyCount(row)) layout.move(row, column, row, column + 2);
                if (action == 2) {
                    String[] names = new String[layout.rowCount()];
                    for (int i = 0; i < names.length; i++) names[i] = getString(R.string.controls_row, i + 1);
                    new AlertDialog.Builder(this).setTitle(R.string.controls_move_row).setItems(names, (d, target) -> {
                        layout.move(row, column, target, layout.keyCount(target)); changed();
                    }).show();
                    return;
                }
                if (action == 3) layout.remove(row, column);
                changed();
            }).show();
    }

    private String label(Object key) {
        try {
            String json = new JSONArray().put(new JSONArray().put(key)).toString();
            ExtraKeyButton parsed = new ExtraKeysInfo(json, "none", ExtraKeysConstants.CONTROL_CHARS_ALIASES).getMatrix()[0][0];
            if (parsed.hasCustomDisplay()) return parsed.getDisplay();
            if (ExtraKeysConstants.ACTION_NEW_SESSION.equals(parsed.getKey())) return getString(R.string.action_new_session);
            if (ExtraKeysConstants.ACTION_DRAWER.equals(parsed.getKey())) return getString(R.string.controls_sessions);
            if (ExtraKeysConstants.ACTION_KEYBOARD.equals(parsed.getKey())) return getString(R.string.controls_keyboard);
            return parsed.getDisplay();
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private void addKey(int row) {
        Map<String, Object> choices = new LinkedHashMap<>();
        try {
            ExtraKeysLayout defaults = ExtraKeysLayout.defaults();
            for (int r = 0; r < defaults.rowCount(); r++) for (int c = 0; c < defaults.keyCount(r); c++) {
                Object key = defaults.key(r, c);
                choices.put(label(key), key);
            }
        } catch (JSONException e) { throw new IllegalStateException(e); }
        for (String key : new TreeSet<>(ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.keySet())) choices.putIfAbsent(key, key);
        for (SpecialButton key : new SpecialButton[]{SpecialButton.CTRL, SpecialButton.ALT, SpecialButton.SHIFT, SpecialButton.FN}) {
            choices.putIfAbsent(key.getKey(), key.getKey());
        }
        for (String key : new String[]{ExtraKeysConstants.ACTION_TEXTBAR, ExtraKeysConstants.ACTION_PASTE, ExtraKeysConstants.ACTION_SCROLL}) choices.putIfAbsent(key, key);
        String[] labels = new String[choices.size() + 1];
        choices.keySet().toArray(labels);
        labels[labels.length - 1] = getString(R.string.controls_custom);
        Object[] values = choices.values().toArray();
        new AlertDialog.Builder(this).setTitle(R.string.controls_add_key).setItems(labels, (d, index) -> {
            if (index == values.length) customKey(row);
            else { layout.add(row, values[index]); changed(); }
        }).show();
    }

    private void customKey(int row) {
        customRow = row;
        LinearLayout fields = column();
        EditText key = customKey = new EditText(this);
        key.setHint(R.string.controls_key_hint);
        fields.addView(key);
        EditText label = customLabel = new EditText(this);
        label.setHint(R.string.controls_label_hint);
        fields.addView(label);
        CheckBox macro = customMacro = new CheckBox(this);
        macro.setText(R.string.controls_macro);
        fields.addView(macro);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.controls_custom).setView(fields)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.controls_add_key, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (key.getText().length() == 0) { key.setError(getString(R.string.controls_key_hint)); return; }
            try {
                JSONObject value = new JSONObject().put(macro.isChecked() ? "macro" : "key", key.getText().toString());
                if (label.getText().length() > 0) value.put("display", label.getText().toString());
                layout.add(row, value); changed(); dialog.dismiss();
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }));
        dialog.setOnDismissListener(d -> customRow = -1);
        dialog.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (layout != null) state.putString("layout", layout.json());
        state.putString("initial", initial);
        state.putBoolean("useFile", useFile);
        state.putInt("customRow", customRow);
        if (customRow >= 0) {
            state.putString("customKey", customKey.getText().toString());
            state.putString("customLabel", customLabel.getText().toString());
            state.putBoolean("customMacro", customMacro.isChecked());
        }
    }

    @Override
    public void onBackPressed() {
        if (layout != null && (!layout.json().equals(initial) || useFile)) {
            new AlertDialog.Builder(this).setMessage(R.string.controls_discard)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.controls_discard_action, (d, w) -> finish()).show();
        } else super.onBackPressed();
    }

    private void message(int text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
