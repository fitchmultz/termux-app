package com.termux.app.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxApplication;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Native composition and evidence staging. Only explicit Insert/Send writes to a PTY. */
public final class EvidenceDock {
    private static final int PICK_FILES = 2106;
    private final TermuxActivity activity;
    private final EditText editor;
    private final TextView targetView;
    private final LinearLayout attachments;
    private final EvidenceStore store;
    private final Runnable storeChanged = this::refreshFromStore;
    private TerminalSession session;
    private String pickerTarget, pickerLabel;

    public EvidenceDock(TermuxActivity activity) {
        this.activity = activity;
        editor = activity.findViewById(R.id.terminal_toolbar_stacked_text_input);
        targetView = activity.findViewById(R.id.evidence_target);
        attachments = activity.findViewById(R.id.evidence_attachments);
        store = ((TermuxApplication) activity.getApplication()).getEvidenceStore();
        editor.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            if (dest.length() - (dend - dstart) + end - start <= EvidenceDraft.MAX_TEXT) return null;
            message(R.string.evidence_limits);
            return dest.subSequence(dstart, dend);
        }});
        activity.findViewById(R.id.evidence_add_files).setOnClickListener(v -> pickFiles());
        activity.findViewById(R.id.evidence_insert).setOnClickListener(v -> insert(false));
        activity.findViewById(R.id.evidence_send).setOnClickListener(v -> insert(true));
        activity.findViewById(R.id.evidence_more).setOnClickListener(this::showMore);
        attachments.setContentDescription(activity.getString(R.string.evidence_file_note));
        refreshTarget();
    }

    private EvidenceDraft draft(String handle) { return store.get(handle); }

    public void onStart() { store.listen(storeChanged); refreshFromStore(); }
    public void onStop() { saveDraft(); store.unlisten(storeChanged); }

    private void refreshFromStore() {
        if (session != null) editor.setText(draft(session.mHandle).text);
        refreshTarget();
        refreshAttachments();
        String notice = store.takeNotice();
        if (notice != null) activity.showToast(notice, true);
    }

    public void setSession(TerminalSession target) {
        if (session == target) { refreshTarget(); return; }
        if (editor.hasFocus() && activity.getTerminalView() != null) activity.getTerminalView().requestFocus();
        editor.clearComposingText();
        saveDraft();
        session = target;
        editor.setText(session == null ? "" : draft(session.mHandle).text);
        refreshTarget();
        refreshAttachments();
    }

    private String label(TerminalSession target) {
        String name = target.mSessionName;
        if (TextUtils.isEmpty(name)) name = target.getTitle();
        int index = activity.getTermuxService() == null ? -1 : activity.getTermuxService().getIndexOfSession(target);
        return "[" + (index + 1) + "] " + (TextUtils.isEmpty(name) ? activity.getString(R.string.split_session) : name);
    }

    public void refreshTarget() {
        targetView.setText(store.isImporting() ? activity.getString(R.string.evidence_importing) : session == null
            ? activity.getString(R.string.evidence_no_session) : activity.getString(R.string.evidence_target, label(session)));
        boolean ready = session != null && session.isRunning() && !store.isImporting();
        editor.setEnabled(session != null && !store.isImporting());
        activity.findViewById(R.id.evidence_insert).setEnabled(ready);
        activity.findViewById(R.id.evidence_send).setEnabled(ready);
        activity.findViewById(R.id.evidence_add_files).setEnabled(ready);
        activity.findViewById(R.id.evidence_more).setEnabled(!store.isImporting());
    }

    public void saveDraft() {
        if (session == null) return;
        EvidenceDraft draft = draft(session.mHandle);
        draft.text = editor.getText().toString();
        draft.label = label(session);
        if (!store.save(session.mHandle)) message(R.string.evidence_save_failed);
    }

    public void stageSelection(String text) {
        if (text == null || text.isEmpty() || session == null) return;
        String combined = editor.length() == 0 ? text : editor.getText() + "\n\n" + text;
        if (combined.length() > EvidenceDraft.MAX_TEXT) { message(R.string.evidence_limits); return; }
        editor.setText(combined);
        saveDraft();
        activity.showEvidenceDock();
    }

    private void insert(boolean send) {
        if (session == null || !session.isRunning() || session.getEmulator() == null) { message(R.string.evidence_no_session); return; }
        saveDraft();
        TerminalSession target = session;
        String payload = draft(target.mHandle).payload();
        if (payload.isEmpty()) { message(R.string.evidence_empty); return; }
        if (EvidenceDraft.needsBracketedPaste(payload) && !target.getEmulator().isBracketedPasteModeEnabled()) {
            message(R.string.evidence_no_bracketed_paste);
            return;
        }
        Runnable deliver = () -> {
            if (session != target || activity.getCurrentSession() != target || !target.isRunning()) { message(R.string.evidence_changed_target); return; }
            if (store.isImporting() || (EvidenceDraft.needsBracketedPaste(payload) && !target.getEmulator().isBracketedPasteModeEnabled())) {
                message(R.string.evidence_no_bracketed_paste);
                return;
            }
            target.getEmulator().paste(payload);
            if (send) target.write("\r");
            editor.setText("");
            draft(target.mHandle).files.clear();
            saveDraft();
            refreshAttachments();
            activity.getTerminalView().requestFocus();
            message(R.string.evidence_delivered);
        };
        if (send) new AlertDialog.Builder(activity).setMessage(activity.getString(R.string.evidence_send_confirm, label(target)))
            .setPositiveButton(R.string.evidence_send, (dialog, which) -> deliver.run()).setNegativeButton(android.R.string.cancel, null).show();
        else deliver.run();
    }

    private void pickFiles() {
        if (session == null || store.isImporting()) return;
        saveDraft();
        pickerTarget = session.mHandle;
        pickerLabel = label(session);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivityForResult(intent, PICK_FILES); }
        catch (ActivityNotFoundException e) { message(R.string.evidence_import_failed); }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FILES) return false;
        if (resultCode == Activity.RESULT_OK && data != null && pickerTarget != null) {
            try { store.importEvidence(pickerTarget, pickerLabel, "", uris(data)); }
            catch (RuntimeException e) { message(R.string.evidence_import_failed); }
        }
        pickerTarget = null;
        pickerLabel = null;
        return true;
    }

    public void handleSharedIntent(Intent intent) {
        if (intent == null || !(Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) return;
        if (activity.getTermuxService() == null) { message(R.string.evidence_no_session); return; }
        if (store.isImporting()) { message(R.string.evidence_import_busy); return; }
        final String text;
        final ArrayList<Uri> files;
        try {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            text = shared == null ? "" : shared.toString();
            files = uris(intent);
        } catch (RuntimeException e) { message(R.string.evidence_import_failed); return; }
        if (text.length() > EvidenceDraft.MAX_TEXT || files.size() > EvidenceDraft.MAX_FILES) { message(R.string.evidence_limits); return; }
        if (text.isEmpty() && files.isEmpty()) { message(R.string.evidence_empty); return; }
        List<TerminalSession> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (TermuxSession candidate : activity.getTermuxService().getTermuxSessions()) {
            if (!candidate.getTerminalSession().isRunning()) continue;
            targets.add(candidate.getTerminalSession());
            labels.add(label(candidate.getTerminalSession()));
        }
        if (targets.isEmpty()) { message(R.string.evidence_no_session); return; }
        new AlertDialog.Builder(activity).setTitle(R.string.evidence_choose_target).setItems(labels.toArray(new String[0]), (dialog, which) -> {
            TerminalSession target = targets.get(which);
            activity.getTermuxTerminalSessionClient().setCurrentSession(target);
            activity.showEvidenceDock();
            saveDraft();
            store.importEvidence(target.mHandle, label(target), text, files);
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    static ArrayList<Uri> uris(Intent intent) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) uris.addAll(streams);
        } else {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) uris.add(stream);
        }
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null && !uris.contains(uri)) uris.add(uri);
            }
        }
        if (intent.getData() != null && !uris.contains(intent.getData())) uris.add(intent.getData());
        return uris;
    }

    private void refreshAttachments() {
        attachments.removeAllViews();
        if (session == null) return;
        final String handle = session.mHandle;
        for (String path : new ArrayList<>(draft(handle).files)) {
            Button button = new Button(activity);
            button.setText(new File(path).getName());
            button.setAllCaps(false);
            button.setContentDescription(path + "; " + activity.getString(R.string.evidence_remove));
            button.setOnClickListener(v -> new AlertDialog.Builder(activity).setMessage(path).setPositiveButton(R.string.evidence_remove, (dialog, which) -> {
                if (store.isImporting()) return;
                draft(handle).files.remove(path);
                store.save(handle);
                refreshAttachments();
            }).setNeutralButton(R.string.evidence_open, (dialog, which) -> {
                Uri uri = UriUtils.getContentUri(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, path);
                String mime = activity.getContentResolver().getType(uri);
                Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime == null ? "application/octet-stream" : mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { activity.startActivity(open); }
                catch (ActivityNotFoundException | SecurityException e) { message(R.string.evidence_import_failed); }
            }).setNegativeButton(android.R.string.cancel, null).show());
            attachments.addView(button);
        }
    }

    private void showMore(View anchor) {
        if (store.isImporting()) return;
        final TerminalSession target = session;
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.evidence_clear);
        menu.getMenu().add(0, 2, 0, R.string.evidence_saved);
        menu.getMenu().add(0, 3, 0, R.string.evidence_file_note);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1 && session != null) new AlertDialog.Builder(activity).setMessage(R.string.evidence_clear_confirm)
                .setPositiveButton(R.string.evidence_clear, (dialog, which) -> { if (session != target || store.isImporting()) return; editor.setText(""); draft(session.mHandle).files.clear(); saveDraft(); refreshAttachments(); })
                .setNegativeButton(android.R.string.cancel, null).show();
            else if (item.getItemId() == 2) recoverDraft();
            else if (item.getItemId() == 3) new AlertDialog.Builder(activity).setMessage(R.string.evidence_file_note).setPositiveButton(android.R.string.ok, null).show();
            return true;
        });
        menu.show();
    }

    private void recoverDraft() {
        if (session == null) { message(R.string.evidence_no_session); return; }
        saveDraft();
        final TerminalSession target = session;
        ArrayList<String> handles = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (String handle : store.savedHandles()) {
            if (handle.equals(session.mHandle)) continue;
            EvidenceDraft candidate = draft(handle);
            if (candidate.isEmpty()) continue;
            handles.add(handle);
            labels.add(candidate.label + " · " + candidate.text.length() + " chars · " + candidate.files.size() + " files");
        }
        if (handles.isEmpty()) { message(R.string.evidence_no_saved); return; }
        new AlertDialog.Builder(activity).setTitle(R.string.evidence_saved).setItems(labels.toArray(new String[0]), (dialog, which) -> {
            String handle = handles.get(which);
            new AlertDialog.Builder(activity).setMessage(R.string.evidence_recover_confirm)
                .setPositiveButton(android.R.string.ok, (confirm, index) -> {
                    if (session != target || store.isImporting()) { message(R.string.evidence_changed_target); return; }
                    EvidenceDraft recovered = draft(handle);
                    editor.setText(recovered.text);
                    EvidenceDraft current = draft(session.mHandle);
                    current.files.clear();
                    current.files.addAll(recovered.files);
                    saveDraft();
                    refreshAttachments();
                }).setNegativeButton(android.R.string.cancel, null).show();
        }).show();
    }

    public void saveState(Bundle state) {
        saveDraft();
        state.putString("evidence_picker_target", pickerTarget);
        state.putString("evidence_picker_label", pickerLabel);
        state.putBoolean("evidence_visible", activity.findViewById(R.id.terminal_toolbar_stacked_text_input_row).getVisibility() == View.VISIBLE);
    }

    public void restoreState(Bundle state) {
        pickerTarget = state.getString("evidence_picker_target");
        pickerLabel = state.getString("evidence_picker_label");
        if (state.getBoolean("evidence_visible")) activity.showEvidenceDock();
    }

    private void message(int resource) { activity.showToast(activity.getString(resource), true); }
}
