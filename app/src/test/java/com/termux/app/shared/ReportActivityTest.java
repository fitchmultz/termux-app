package com.termux.app.shared;

import android.app.Application;
import android.content.Context;

import androidx.recyclerview.widget.RecyclerView;

import com.termux.shared.R;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.data.DataUtils;
import com.termux.shared.models.ReportInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenuItem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, application = Application.class)
public class ReportActivityTest {

    @Test
    public void fileBackedReportSurvivesRecreationAndIsDeletedOnFinalDismissal() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        char[] payload = new char[DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES + 1];
        Arrays.fill(payload, 'x');
        String contents = "Report start\n\n" + new String(payload) + "\n\nReport end";
        ReportInfo report = new ReportInfo("test", "ReportActivityTest", "Large report");
        report.setReportString(contents);
        File savedReport = new File(context.getCacheDir(), "saved-report.md");
        report.setReportSaveFileLabelAndPath("Report", savedReport.getAbsolutePath());

        ReportActivity.NewInstanceResult intents = ReportActivity.newInstance(context, report);
        assertNotNull(intents.contentIntent);
        assertNotNull("Large reports must use file transport", intents.deleteIntent);
        File[] files = new File(context.getCacheDir(), "report_activity").listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        File backingFile = files[0];
        assertTrue(backingFile.isFile());

        ActivityController<ReportActivity> controller =
            Robolectric.buildActivity(ReportActivity.class, intents.contentIntent).setup();
        try {
            ReportActivity original = controller.get();
            assertReportContents(original, contents, savedReport);

            controller.recreate();

            ReportActivity replacement = controller.get();
            assertNotSame(original, replacement);
            assertTrue("Recreation must retain the report for its replacement", backingFile.isFile());
            assertReportContents(replacement, contents, savedReport);
            replacement.onBackPressed();
            assertTrue(replacement.isFinishing());
        } finally {
            controller.pause().stop().destroy();
            Files.deleteIfExists(savedReport.toPath());
        }

        assertFalse("Final dismissal must delete the backing file", backingFile.exists());
    }

    private static void assertReportContents(ReportActivity activity, String contents, File savedReport)
        throws IOException {
        assertFalse("The report must open successfully", activity.isFinishing());
        assertEquals("Large report", activity.getSupportActionBar().getTitle());
        RecyclerView view = activity.findViewById(R.id.recycler_view);
        assertNotNull(view.getAdapter());
        assertTrue(view.getAdapter().getItemCount() > 0);

        Files.deleteIfExists(savedReport.toPath());
        activity.onOptionsItemSelected(new RoboMenuItem(R.id.menu_item_save_report_to_file));
        assertEquals(contents, Files.readString(savedReport.toPath()));
    }
}
