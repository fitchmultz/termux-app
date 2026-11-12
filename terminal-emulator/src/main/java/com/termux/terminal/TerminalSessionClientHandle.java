package com.termux.terminal;

/**
 * Stable callback handle shared by a session's retained screens, images and parser transfers.
 * Replacing the delegate releases the old activity without walking that retained object graph.
 */
final class TerminalSessionClientHandle implements TerminalSessionClient {
    private volatile TerminalSessionClient delegate;

    TerminalSessionClientHandle(TerminalSessionClient client) {
        delegate = client;
    }

    void setClient(TerminalSessionClient client) {
        delegate = client;
    }

    @Override public void onTextChanged(TerminalSession session) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onTextChanged(session);
    }

    @Override public void onTitleChanged(TerminalSession session) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onTitleChanged(session);
    }

    @Override public void onSessionFinished(TerminalSession session) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onSessionFinished(session);
    }

    @Override public void onInputRejected(TerminalSession session, TerminalSession.InputRejectionReason reason) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onInputRejected(session, reason);
    }

    @Override public void onCopyTextToClipboard(TerminalSession session, String text) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onCopyTextToClipboard(session, text);
    }

    @Override public void onPasteTextFromClipboard(TerminalSession session) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onPasteTextFromClipboard(session);
    }

    @Override public void onBell(TerminalSession session) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onBell(session);
    }

    @Override public void onColorsChanged(TerminalSession session) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onColorsChanged(session);
    }

    @Override public void onTerminalCursorStateChange(boolean state) {
        TerminalSessionClient client = delegate;
        if (client != null) client.onTerminalCursorStateChange(state);
    }

    @Override public void setTerminalShellPid(TerminalSession session, int pid) {
        TerminalSessionClient client = delegate;
        if (client != null) client.setTerminalShellPid(session, pid);
    }

    @Override public Integer getTerminalCursorStyle() {
        TerminalSessionClient client = delegate;
        return client == null ? null : client.getTerminalCursorStyle();
    }

    @Override public void logError(String tag, String message) {
        TerminalSessionClient client = delegate;
        if (client != null) client.logError(tag, message);
    }

    @Override public void logWarn(String tag, String message) {
        TerminalSessionClient client = delegate;
        if (client != null) client.logWarn(tag, message);
    }

    @Override public void logInfo(String tag, String message) {
        TerminalSessionClient client = delegate;
        if (client != null) client.logInfo(tag, message);
    }

    @Override public void logDebug(String tag, String message) {
        TerminalSessionClient client = delegate;
        if (client != null) client.logDebug(tag, message);
    }

    @Override public void logVerbose(String tag, String message) {
        TerminalSessionClient client = delegate;
        if (client != null) client.logVerbose(tag, message);
    }

    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {
        TerminalSessionClient client = delegate;
        if (client != null) client.logStackTraceWithMessage(tag, message, e);
    }

    @Override public void logStackTrace(String tag, Exception e) {
        TerminalSessionClient client = delegate;
        if (client != null) client.logStackTrace(tag, e);
    }
}
