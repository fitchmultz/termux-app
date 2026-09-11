package com.termux.app.terminal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** Data only: a draft is never an instruction to execute or upload anything. */
public final class EvidenceDraft {
    public static final int MAX_TEXT = 16384;
    public static final int MAX_FILES = 8;
    public String label = "";
    public String text = "";
    public final ArrayList<String> files = new ArrayList<>();

    public boolean isEmpty() { return text.isEmpty() && files.isEmpty(); }

    public String payload() {
        StringBuilder result = new StringBuilder(text);
        for (String path : files) {
            if (result.length() > 0) result.append('\n');
            result.append(path);
        }
        return result.toString();
    }

    public static boolean needsBracketedPaste(String text) {
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    public String toJson() throws JSONException {
        return new JSONObject().put("label", label).put("text", text).put("files", new JSONArray(files)).toString();
    }

    public static EvidenceDraft fromJson(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        EvidenceDraft draft = new EvidenceDraft();
        draft.label = object.optString("label", "");
        draft.text = object.getString("text");
        JSONArray files = object.getJSONArray("files");
        if (draft.text.length() > MAX_TEXT || files.length() > MAX_FILES) throw new JSONException("Draft exceeds limits");
        for (int i = 0; i < files.length(); i++) {
            String path = files.getString(i);
            if (!path.startsWith("/") || path.length() > 4096 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0)
                throw new JSONException("Invalid attachment path");
            draft.files.add(path);
        }
        return draft;
    }
}
