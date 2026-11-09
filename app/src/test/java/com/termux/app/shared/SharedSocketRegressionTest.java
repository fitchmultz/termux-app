// The shared socket package gives test-only fakes access to package-private constructors.
package com.termux.shared.net.socket.local;

import android.app.Application;
import android.content.Context;

import com.termux.shared.errors.Error;
import com.termux.shared.jni.models.JniResult;
import com.termux.shared.shell.am.AmSocketServer;
import com.termux.shared.shell.am.AmSocketServerRunConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Real Java socket wrappers and AM response framing, with no native sockets or AM execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 37}, application = Application.class, shadows = {
    SharedSocketRegressionTest.NativeAvailability.class,
    SharedSocketRegressionTest.AmExecution.class
})
public class SharedSocketRegressionTest {
    private static final int SERVER_FD = 101;
    private static final int CLIENT_FD = 202;
    private static final String COMMAND = "start -n example/.Main";

    private AmSocketServerRunConfig config;
    private LocalSocketManager manager;

    @Before
    public void setUp() {
        NativeAvailability.calls = 0;
        NativeAvailability.fd = -1;
        NativeAvailability.title = null;
        NativeAvailability.result = new JniResult(0, 0, null, 37);
        NativeAvailability.failure = null;
        AmExecution.calls = 0;
        AmExecution.context = null;
        AmExecution.arguments = null;
        AmExecution.checkDisplayPermission = false;
        AmExecution.stdout = "";
        AmExecution.stderr = "";
        AmExecution.error = null;

        config = new AmSocketServerRunConfig("socket regression", "\0shared-regression",
            new LocalSocketManagerClientBase() {
                @Override
                protected String getLogTag() {
                    return "SharedSocketRegressionTest";
                }
            });
        config.setFD(SERVER_FD);
        manager = new LocalSocketManager(RuntimeEnvironment.getApplication(), config);
        // Construct only: never start the manager, load JNI, or allocate an OS socket.
    }

    @Test
    public void availableQueriesConnectedClientFdAndCopiesNativeIntData() {
        LocalClientSocket client = client(CLIENT_FD);
        LocalClientSocket.MutableInt available = new LocalClientSocket.MutableInt(-99);

        assertNull(client.available(available));

        assertEquals(37, available.value);
        assertEquals(1, NativeAvailability.calls);
        assertEquals("Must query the connected client, not the listening server", CLIENT_FD,
            NativeAvailability.fd);
        assertEquals(config.getLogTitle() + " (client)", NativeAvailability.title);
        assertEquals(SERVER_FD, config.getFD().intValue());
    }

    @Test
    public void availableAcceptsZeroAsAValidClientFd() {
        LocalClientSocket.MutableInt available = new LocalClientSocket.MutableInt(-99);

        assertNull(client(0).available(available));

        assertEquals(37, available.value);
        assertEquals(1, NativeAvailability.calls);
        assertEquals(0, NativeAvailability.fd);
    }

    @Test
    public void inputStreamAvailableUsesRealWrapperAndReturnsZeroNativeCount() throws Exception {
        NativeAvailability.result = new JniResult(0, 0, null, 0);

        assertEquals(0, client(CLIENT_FD).getInputStream().available());

        assertEquals(1, NativeAvailability.calls);
        assertEquals(CLIENT_FD, NativeAvailability.fd);
    }

    @Test
    public void availableRejectsInvalidClientFdWithoutCallingNative() {
        LocalClientSocket.MutableInt available = new LocalClientSocket.MutableInt(-99);

        Error error = client(-1).available(available);

        assertNotNull(error);
        assertEquals(LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD.getCode(),
            error.getCode().intValue());
        assertEquals(0, available.value);
        assertEquals(0, NativeAvailability.calls);
    }

    @Test
    public void availableNativeFailureResetsCountAndPreservesDiagnostic() {
        NativeAvailability.result = new JniResult(-1, 9, "simulated bad descriptor", 999);
        LocalClientSocket.MutableInt available = new LocalClientSocket.MutableInt(-99);

        Error error = client(CLIENT_FD).available(available);

        assertAvailabilityError(error, available);
        assertTrue(error.getMessage().contains("simulated bad descriptor"));
        assertEquals(1, NativeAvailability.calls);
        assertEquals(CLIENT_FD, NativeAvailability.fd);
    }

    @Test
    public void availableNullNativeResultIsAnErrorNotStaleData() {
        NativeAvailability.result = null;
        LocalClientSocket.MutableInt available = new LocalClientSocket.MutableInt(-99);

        Error error = client(CLIENT_FD).available(available);

        assertAvailabilityError(error, available);
        assertTrue(error.getMessage().contains("null"));
        assertEquals(1, NativeAvailability.calls);
    }

    @Test
    public void availableNativeLinkageFailureIsConvertedToAnError() {
        NativeAvailability.failure = new UnsatisfiedLinkError("simulated native failure");
        LocalClientSocket.MutableInt available = new LocalClientSocket.MutableInt(-99);

        Error error = client(CLIENT_FD).available(available);

        assertAvailabilityError(error, available);
        assertTrue(error.getMessage().contains("Exception in availableNative()"));
        assertTrue(error.getMessage().contains("simulated native failure"));
        assertEquals(1, NativeAvailability.calls);
    }

    @Test
    public void expiredDeadlineReportsNoBytesWithoutCallingNative() {
        LocalClientSocket client = client(CLIENT_FD);
        expireDeadline(client);
        LocalClientSocket.MutableInt available = new LocalClientSocket.MutableInt(-99);

        assertNull(client.available(available));

        assertEquals(0, available.value);
        assertEquals(0, NativeAvailability.calls);
    }

    @Test
    public void explicitDeadlineBypassStillQueriesClientFd() {
        LocalClientSocket client = client(CLIENT_FD);
        expireDeadline(client);
        LocalClientSocket.MutableInt available = new LocalClientSocket.MutableInt(-99);

        assertNull(client.available(available, false));

        assertEquals(37, available.value);
        assertEquals(1, NativeAvailability.calls);
        assertEquals(CLIENT_FD, NativeAvailability.fd);
    }

    @Test
    public void amExecutionFailureSendsOneFailureAndPreservesStdoutAndStderr() {
        RecordingClient client = new RecordingClient(manager);
        AmExecution.stdout = "partial output\n";
        AmExecution.stderr = "original diagnostic\n";
        AmExecution.error = new Error(1, "execution failed");

        AmSocketServer.processAmClient(manager, client);

        assertOnlyResponse(client, "1\0partial output\n\0original diagnostic\n\n\n"
            + AmExecution.error);
        assertEquals(1, AmExecution.calls);
        assertArrayEquals(new String[]{"start", "-n", "example/.Main"}, AmExecution.arguments);
    }

    @Test
    public void amExecutionFailureWithoutStderrSendsOnlyTheErrorDiagnostic() {
        RecordingClient client = new RecordingClient(manager);
        AmExecution.error = new Error(1, "execution failed without stderr");

        AmSocketServer.processAmClient(manager, client);

        assertOnlyResponse(client, "1\0\0" + AmExecution.error);
        assertEquals(1, AmExecution.calls);
    }

    @Test
    public void amExecutionSuccessSendsOneSuccessWithBothOutputStreams() {
        RecordingClient client = new RecordingClient(manager);
        AmExecution.stdout = "command output\n";
        AmExecution.stderr = "non-fatal diagnostic\n";

        AmSocketServer.processAmClient(manager, client);

        assertOnlyResponse(client, "0\0command output\n\0non-fatal diagnostic\n");
        assertEquals(1, AmExecution.calls);
        assertSame(manager.getContext(), AmExecution.context);
        assertTrue(AmExecution.checkDisplayPermission);
    }

    @Test
    public void amReadFailureSendsOneFailureWithoutRunningCommand() {
        RecordingClient client = new RecordingClient(manager);
        client.readError = new Error(1, "input read failed");

        AmSocketServer.processAmClient(manager, client);

        assertOnlyResponse(client, "1\0\0" + client.readError);
        assertEquals(0, AmExecution.calls);
    }

    private LocalClientSocket client(int fd) {
        return new LocalClientSocket(manager, fd, new TestPeerCred());
    }

    private void expireDeadline(LocalClientSocket client) {
        config.setDeadline(1L);
        // The deadline uses wall time. Avoid sleeps or dependence on the virtual looper clock.
        ReflectionHelpers.setField(client, "mCreationTime", System.currentTimeMillis() - 60_000L);
    }

    private void assertAvailabilityError(Error error, LocalClientSocket.MutableInt available) {
        assertNotNull(error);
        assertEquals(LocalSocketErrno.ERRNO_CHECK_AVAILABLE_DATA_ON_CLIENT_SOCKET_FAILED.getCode(),
            error.getCode().intValue());
        assertEquals(0, available.value);
    }

    private void assertOnlyResponse(RecordingClient client, String expected) {
        // Compare the entire list: observing a failure frame alone would miss a second success.
        assertEquals(Collections.singletonList(expected), client.responses);
        assertEquals(Collections.singletonList(true), client.closeOutputRequests);
        assertEquals(1, client.readCalls);
        assertTrue(client.closeInputRequested);
    }

    private static class TestPeerCred extends PeerCred {
        @Override
        public void fillPeerCred(Context context) {
            // Identity lookup is unrelated to framing or FD selection; never inspect host users.
        }
    }

    private static class RecordingClient extends LocalClientSocket {
        final List<String> responses = new ArrayList<>();
        final List<Boolean> closeOutputRequests = new ArrayList<>();
        Error readError;
        int readCalls;
        boolean closeInputRequested;

        RecordingClient(LocalSocketManager manager) {
            super(manager, CLIENT_FD, new TestPeerCred());
        }

        @Override
        public Error readDataOnInputStream(StringBuilder data, boolean closeStreamOnFinish) {
            readCalls++;
            closeInputRequested = closeStreamOnFinish;
            if (readError == null) data.append(COMMAND);
            return readError;
        }

        @Override
        public Error sendDataToOutputStream(String data, boolean closeStreamOnFinish) {
            // Record every send, even after close, so a duplicate response cannot be hidden.
            responses.add(data);
            closeOutputRequests.add(closeStreamOnFinish);
            return null;
        }
    }

    /** Shadow only JNI, leaving LocalClientSocket.available and the manager wrapper real. */
    @Implements(value = LocalSocketManager.class, isInAndroidSdk = false)
    public static class NativeAvailability {
        static int calls;
        static int fd;
        static String title;
        static JniResult result;
        static LinkageError failure;

        @Implementation
        protected static JniResult availableNative(String serverTitle, int socketFd) {
            calls++;
            title = serverTitle;
            fd = socketFd;
            if (failure != null) throw failure;
            return result;
        }
    }

    /** Shadow only command execution; parsing, processAmClient and result framing stay real. */
    @Implements(value = AmSocketServer.class, isInAndroidSdk = false)
    public static class AmExecution {
        static int calls;
        static Context context;
        static String[] arguments;
        static boolean checkDisplayPermission;
        static String stdout;
        static String stderr;
        static Error error;

        @Implementation
        protected static Error runAmCommand(Context commandContext, String[] commandArguments,
                                            StringBuilder commandStdout, StringBuilder commandStderr,
                                            boolean checkDisplayOverAppsPermission) {
            calls++;
            context = commandContext;
            arguments = commandArguments.clone();
            checkDisplayPermission = checkDisplayOverAppsPermission;
            commandStdout.append(stdout);
            commandStderr.append(stderr);
            return error;
        }
    }
}
