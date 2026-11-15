package com.termux.app;

import android.app.Application;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.termux.app.terminal.io.FullScreenWorkAround;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.termux.data.TermuxUrlUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.TermuxShellManager;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.Collections;
import java.util.LinkedHashSet;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = TermuxActivityTest.TestApplication.class)
public class TermuxActivityTest {

    public static class TestApplication extends TermuxApplication {
        @Override public void onCreate() { }
    }

    @Test
    public void bootstrapJobReplacesActivityObserversWithoutReplacingTheWriter() throws Exception {
        TermuxApplication application = (TermuxApplication) RuntimeEnvironment.getApplication();
        TermuxInstaller installer = application.getBootstrapInstaller();
        // Seed a running job at the filesystem boundary, without extracting into a real prefix.
        Class<?> jobClass = Class.forName("com.termux.app.TermuxInstaller$BootstrapJob");
        Object job = ReflectionHelpers.callConstructor(jobClass, ClassParameter.from(TermuxInstaller.class, installer));
        ReflectionHelpers.setField(installer, "mJob", job);
        java.util.concurrent.atomic.AtomicInteger oldCompletions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger newCompletions = new java.util.concurrent.atomic.AtomicInteger();
        org.robolectric.android.controller.ActivityController<android.app.Activity> old =
            Robolectric.buildActivity(android.app.Activity.class).setup();
        TermuxInstaller.setupBootstrapIfNeeded(old.get(), oldCompletions::incrementAndGet);
        android.app.Dialog oldProgress = ShadowDialog.getLatestDialog();
        Assert.assertTrue(oldProgress.isShowing());
        TermuxInstaller.detachBootstrapObserver(old.get());
        Assert.assertNull(ReflectionHelpers.getField(job, "observer"));
        Assert.assertFalse(oldProgress.isShowing());
        old.pause().stop().destroy();

        org.robolectric.android.controller.ActivityController<android.app.Activity> replacement =
            Robolectric.buildActivity(android.app.Activity.class).setup();
        TermuxInstaller.setupBootstrapIfNeeded(replacement.get(), newCompletions::incrementAndGet);
        Assert.assertSame(job, ReflectionHelpers.getField(installer, "mJob"));
        android.app.Dialog newProgress = ShadowDialog.getLatestDialog();
        Assert.assertNotSame(oldProgress, newProgress);
        Assert.assertTrue(newProgress.isShowing());
        TermuxInstaller.detachBootstrapObserver(old.get());
        Assert.assertNotNull(ReflectionHelpers.getField(job, "observer"));
        ReflectionHelpers.callInstanceMethod(job, "complete");
        Assert.assertEquals(0, oldCompletions.get());
        Assert.assertEquals(1, newCompletions.get());
        Assert.assertNull(ReflectionHelpers.getField(job, "observer"));
        Assert.assertNull(ReflectionHelpers.getField(installer, "mJob"));
        Assert.assertFalse(newProgress.isShowing());

        Object backgroundJob = ReflectionHelpers.callConstructor(jobClass,
            ClassParameter.from(TermuxInstaller.class, installer));
        ReflectionHelpers.setField(installer, "mJob", backgroundJob);
        TermuxInstaller.setupBootstrapIfNeeded(replacement.get(), newCompletions::incrementAndGet);
        TermuxInstaller.detachBootstrapObserver(replacement.get());
        ReflectionHelpers.callInstanceMethod(backgroundJob, "complete");
        Assert.assertEquals(1, newCompletions.get());
        Assert.assertNull(ReflectionHelpers.getField(backgroundJob, "observer"));
        Assert.assertNull(ReflectionHelpers.getField(installer, "mJob"));
        replacement.pause().stop().destroy();
    }

    private void assertUrlsAre(String text, String... urls) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, urls);
        Assert.assertEquals(expected, TermuxUrlUtils.extractUrls(text));
    }

    @Test
    public void testExtractUrls() {
        assertUrlsAre("hello http://example.com world", "http://example.com");

        assertUrlsAre("http://example.com\nhttp://another.com", "http://example.com", "http://another.com");

        assertUrlsAre("hello http://example.com world and http://more.example.com with secure https://more.example.com",
            "http://example.com", "http://more.example.com", "https://more.example.com");

        assertUrlsAre("hello https://example.com/#bar https://example.com/foo#bar",
            "https://example.com/#bar", "https://example.com/foo#bar");
    }

    @Test
    public void overlappingActivityDestroyDoesNotClobberReplacementSessionClientOrFullscreenListener() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxAppSharedProperties.init(app);
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TermuxService service = Robolectric.buildService(TermuxService.class).create().get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            new ComponentName(app, TermuxService.class), service.onBind(new Intent(app, TermuxService.class)));

        ActivityController<TermuxActivity> firstController = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity first = firstController.create().get();
        View firstContent = ((ViewGroup) first.findViewById(android.R.id.content)).getChildAt(0);
        FullScreenWorkAround.apply(first);
        first.mTermuxService = service;
        service.setTermuxTerminalSessionClient(first.mTermuxTerminalSessionActivityClient);

        ActivityController<TermuxActivity> secondController = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity second = secondController.create().get();
        View secondContent = ((ViewGroup) second.findViewById(android.R.id.content)).getChildAt(0);
        FullScreenWorkAround.apply(second);
        second.mTermuxService = service;
        service.setTermuxTerminalSessionClient(second.mTermuxTerminalSessionActivityClient);

        // Simulate first activity finishing teardown after replacement activity already bound.
        Rect visibleFrame = new Rect();
        secondContent.getWindowVisibleDisplayFrame(visibleFrame);
        Assert.assertTrue(visibleFrame.height() > 0);
        firstContent.getRootView().layout(0, 0, visibleFrame.width(), visibleFrame.height());
        secondContent.getRootView().layout(0, 0, visibleFrame.width(), visibleFrame.height());
        firstController.destroy();
        Assert.assertSame(second.mTermuxTerminalSessionActivityClient, service.getTermuxTerminalSessionClient());

        firstContent.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        firstContent.getViewTreeObserver().dispatchOnGlobalLayout();
        Assert.assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, firstContent.getLayoutParams().height);

        secondContent.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        secondContent.getViewTreeObserver().dispatchOnGlobalLayout();
        Assert.assertEquals(visibleFrame.height(), secondContent.getLayoutParams().height);

        secondController.destroy();
        Assert.assertNotSame(second.mTermuxTerminalSessionActivityClient, service.getTermuxTerminalSessionClient());
        service.onDestroy();
    }

    @Test
    public void messageDialogPreservesWhiteHeaderTitleAndDialogThemeButtonColors() {
        AlertDialog dialog = showSampleMessageDialog();
        int surfaceColor = dialogSurfaceColor(dialog);
        TextView messageView = dialog.findViewById(com.termux.shared.R.id.dialog_message);
        int buttonColor = dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor();

        Assert.assertTrue("Expected light dialog surface in day mode", Color.red(surfaceColor) > 200);
        Assert.assertTrue("Expected dark body text in day mode", Color.red(messageView.getCurrentTextColor()) < 80);
        Assert.assertTrue("Expected dark button text in day mode", Color.red(buttonColor) < 80);
        dialog.dismiss();
    }

    @Test
    @Config(qualifiers = "night")
    public void messageDialogNightModeUsesDarkWindowSurfaceForLightText() {
        AlertDialog dialog = showSampleMessageDialog();
        int surfaceColor = dialogSurfaceColor(dialog);
        TextView messageView = dialog.findViewById(com.termux.shared.R.id.dialog_message);
        int buttonColor = dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor();

        Assert.assertTrue("Expected dark dialog surface in night mode", Color.red(surfaceColor) < 100);
        Assert.assertTrue("Expected light body text in night mode", Color.red(messageView.getCurrentTextColor()) > 200);
        Assert.assertTrue("Expected light button text in night mode", Color.red(buttonColor) > 200);
        dialog.dismiss();
    }

    private static AlertDialog showSampleMessageDialog() {
        android.app.Activity host = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        MessageDialogUtils.showMessage(host, "Header", "Body https://example.com",
            "Yes", null, "No", null, null);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        Assert.assertNotNull(dialog);
        Assert.assertTrue(dialog.isShowing());

        TextView titleView = dialog.findViewById(com.termux.shared.R.id.dialog_title);
        Assert.assertEquals(Color.WHITE, titleView.getCurrentTextColor());
        Assert.assertEquals(
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor(),
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getCurrentTextColor());
        return dialog;
    }

    private static int dialogSurfaceColor(AlertDialog dialog) {
        InsetDrawable inset = (InsetDrawable) dialog.getWindow().getDecorView().getBackground();
        GradientDrawable shape = (GradientDrawable) inset.getDrawable();
        return shape.getColor().getDefaultColor();
    }

}
