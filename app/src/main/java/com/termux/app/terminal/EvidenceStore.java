package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** App-owned drafts and import work survive activity replacement; private preferences survive process death. */
public final class EvidenceStore {
    private final Context context;
    private final SharedPreferences saved;
    private final Map<String, EvidenceDraft> drafts = new LinkedHashMap<>();
    private Runnable listener;
    private boolean importing;
    private String notice;

    public EvidenceStore(Context context) {
        this.context = context.getApplicationContext();
        saved = context.getSharedPreferences("evidence-drafts", Context.MODE_PRIVATE);
        if (saved.contains("import_pending")) {
            notice = context.getString(R.string.evidence_import_interrupted);
            saved.edit().remove("import_pending").apply();
        }
    }

    public EvidenceDraft get(String handle) {
        EvidenceDraft draft = drafts.get(handle);
        if (draft == null) {
            String json = saved.getString("draft:" + handle, null);
            try { draft = json == null ? new EvidenceDraft() : EvidenceDraft.fromJson(json); }
            catch (JSONException e) {
                notice = context.getString(R.string.evidence_save_failed);
                draft = new EvidenceDraft();
            }
            drafts.put(handle, draft);
        }
        return draft;
    }

    public boolean save(String handle) {
        EvidenceDraft draft = get(handle);
        try {
            SharedPreferences.Editor edit = saved.edit();
            if (draft.isEmpty()) edit.remove("draft:" + handle);
            else edit.putString("draft:" + handle, draft.toJson());
            if (edit.commit()) return true;
        } catch (JSONException ignored) { }
        notice = context.getString(R.string.evidence_save_failed);
        return false;
    }

    public ArrayList<String> savedHandles() {
        ArrayList<String> result = new ArrayList<>();
        for (String key : saved.getAll().keySet()) if (key.startsWith("draft:")) result.add(key.substring(6));
        return result;
    }

    public boolean isImporting() { return importing; }
    public void listen(Runnable callback) { listener = callback; }
    public void unlisten(Runnable callback) { if (listener == callback) listener = null; }
    public String takeNotice() { String value = notice; notice = null; return value; }

    public void importEvidence(String handle, String label, String text, ArrayList<Uri> files) {
        if (importing) { notifyResult(R.string.evidence_import_busy); return; }
        EvidenceDraft target = get(handle);
        String combined = target.text.isEmpty() ? text : text.isEmpty() ? target.text : target.text + "\n\n" + text;
        if (combined.length() > EvidenceDraft.MAX_TEXT || target.files.size() + files.size() > EvidenceDraft.MAX_FILES) {
            notifyResult(R.string.evidence_limits);
            return;
        }
        for (Uri uri : files) {
            if (uri == null || !"content".equals(uri.getScheme())) { notifyResult(R.string.evidence_import_failed); return; }
        }
        if (!saved.edit().putBoolean("import_pending", true).commit()) { notifyResult(R.string.evidence_save_failed); return; }
        importing = true;
        if (listener != null) listener.run();
        File directory = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".local/share/termux/evidence");
        new Thread(() -> {
            ArrayList<File> imported = new ArrayList<>();
            boolean success = false;
            try {
                for (Uri uri : files) imported.add(EvidenceFiles.importFile(context.getContentResolver(), uri, directory));
                success = true;
            } catch (Exception ignored) {
                for (File file : imported) file.delete();
            }
            final boolean complete = success;
            new Handler(Looper.getMainLooper()).post(() -> {
                importing = false;
                if (complete) {
                    target.text = combined;
                    target.label = label;
                    for (File file : imported) target.files.add(file.getAbsolutePath());
                    if (save(handle)) notice = context.getString(R.string.evidence_imported, label);
                } else notice = context.getString(R.string.evidence_import_failed);
                saved.edit().remove("import_pending").apply();
                if (listener != null) listener.run();
            });
        }, "TermuxEvidenceImport").start();
    }

    private void notifyResult(int message) {
        notice = context.getString(message);
        if (listener != null) listener.run();
    }
}
