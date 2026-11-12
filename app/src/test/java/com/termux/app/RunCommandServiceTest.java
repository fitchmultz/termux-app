package com.termux.app;

import android.content.Intent;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowService;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class RunCommandServiceTest {
    @Test
    public void nullAndRejectedStartsAreRetired() {
        ServiceController<RunCommandService> controller = Robolectric.buildService(RunCommandService.class).create();
        try {
            RunCommandService service = controller.get();
            service.onStartCommand(null, 0, 7);
            ShadowService shadow = shadowOf(service);
            assertTrue(shadow.isStoppedBySelf());
            assertEquals(7, shadow.getStopSelfId());
            service.onStartCommand(new Intent("invalid"), 0, 8);
            assertEquals(8, shadow.getStopSelfId());
            assertTrue(shadow.isForegroundStopped());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void successfulHandoffRetiresOnlyItsOwnStart() throws Exception {
        ServiceController<RunCommandService> controller = Robolectric.buildService(RunCommandService.class).create();
        try {
            RunCommandService service = controller.get();
            TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(service);
            Object shared = ReflectionHelpers.getField(properties, "mSharedProperties");
            Map<String, Object> values = ReflectionHelpers.getField(shared, "mMap");
            Object previous = values.put(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS, true);
            File executable = File.createTempFile("command", ".sh", service.getCacheDir());
            try {
                assertTrue(executable.setExecutable(true));
                Intent command = new Intent(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND)
                    .putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, executable.getAbsolutePath());
                service.onStartCommand(command, 0, 42);
                Intent forwarded = shadowOf(service).getNextStartedService();
                assertNotNull(forwarded);
                assertEquals(TermuxService.class.getName(), forwarded.getComponent().getClassName());
                assertEquals(42, shadowOf(service).getStopSelfId());
                assertTrue(shadowOf(service).isForegroundStopped());
            } finally {
                executable.delete();
                if (previous == null) values.remove(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS);
                else values.put(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS, previous);
            }
        } finally {
            controller.destroy();
        }
    }
}
