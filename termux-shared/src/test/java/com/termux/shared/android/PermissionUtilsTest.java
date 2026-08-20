package com.termux.shared.android;

import android.os.Build;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PermissionUtilsTest {

    @Test
    public void prioritizeManageStorageUsesManagePermissionOnAndroid11AndNewer() {
        assertFalse(PermissionUtils.shouldRequestLegacyStoragePermission(
            true, Build.VERSION_CODES.R, true));
        assertFalse(PermissionUtils.shouldRequestLegacyStoragePermission(
            true, Build.VERSION_CODES.R + 5, true));
    }

    @Test
    public void prioritizeManageStorageRetainsLegacyPermissionBeforeAndroid11() {
        assertTrue(PermissionUtils.shouldRequestLegacyStoragePermission(
            true, Build.VERSION_CODES.Q, true));
    }

    @Test
    public void nonPrioritizedRequestsFollowLegacyStorageCapability() {
        assertTrue(PermissionUtils.shouldRequestLegacyStoragePermission(
            false, Build.VERSION_CODES.R, true));
        assertFalse(PermissionUtils.shouldRequestLegacyStoragePermission(
            false, Build.VERSION_CODES.R, false));
    }

    @Test
    public void unavailableLegacyStorageAlwaysUsesManagePermission() {
        assertFalse(PermissionUtils.shouldRequestLegacyStoragePermission(
            true, Build.VERSION_CODES.Q, false));
        assertFalse(PermissionUtils.shouldRequestLegacyStoragePermission(
            true, Build.VERSION_CODES.R, false));
    }
}
