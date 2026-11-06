package com.termux.app.terminal.io;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Editable rows retain the original key objects, including macros and swipe-up actions. */
public final class ExtraKeysLayout {
    private final List<List<Object>> rows = new ArrayList<>();

    public ExtraKeysLayout(String json) throws JSONException {
        new ExtraKeysInfo(json, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        JSONArray matrix = new JSONArray(json);
        for (int r = 0; r < matrix.length(); r++) {
            List<Object> row = new ArrayList<>();
            JSONArray source = matrix.getJSONArray(r);
            for (int c = 0; c < source.length(); c++) row.add(source.get(c));
            rows.add(row);
        }
    }

    public int rowCount() { return rows.size(); }
    public int keyCount(int row) { return rows.get(row).size(); }
    public Object key(int row, int column) { return rows.get(row).get(column); }
    public void addRow() { rows.add(new ArrayList<>()); }
    public void add(int row, Object key) { rows.get(row).add(key); }
    public void remove(int row, int column) { rows.get(row).remove(column); }
    public void removeRow(int row) { rows.remove(row); }

    public void move(int row, int column, int targetRow, int targetColumn) {
        Object key = rows.get(row).remove(column);
        if (row == targetRow && column < targetColumn) targetColumn--;
        rows.get(targetRow).add(targetColumn, key);
    }

    public String json() {
        JSONArray matrix = new JSONArray();
        for (List<Object> row : rows) matrix.put(new JSONArray(row));
        return matrix.toString();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("terminal-controls", Context.MODE_PRIVATE);
    }

    public static String saved(Context context) { return preferences(context).getString("layout", null); }

    public static String effective(Context context, String fileLayout) {
        String saved = saved(context);
        return saved == null ? fileLayout : saved;
    }

    public boolean save(Context context) { return preferences(context).edit().putString("layout", json()).commit(); }
    public static boolean useFile(Context context) { return preferences(context).edit().remove("layout").commit(); }
    public static ExtraKeysLayout defaults() throws JSONException {
        return new ExtraKeysLayout(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
    }
}
