/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.connectivity;

import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.NETWORK_STACK;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_OEM;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_PRODUCT;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_VENDOR;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_REQUIRED;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.net.ConnectivitySettingsManager.UIDS_ALLOWED_ON_RESTRICTED_NETWORKS;
import static android.net.INetd.PERMISSION_INTERNET;
import static android.net.INetd.PERMISSION_NONE;
import static android.net.INetd.PERMISSION_SYSTEM;
import static android.net.INetd.PERMISSION_UNINSTALLED;
import static android.net.INetd.PERMISSION_UPDATE_DEVICE_STATS;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.connectivity.PermissionMonitor.NETWORK;
import static com.android.server.connectivity.PermissionMonitor.SYSTEM;

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.INetd;
import android.net.UidRange;
import android.net.Uri;
import android.os.Build;
import android.os.SystemConfigManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.net.module.util.CollectionUtils;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class PermissionMonitorTest {
    private static final UserHandle MOCK_USER1 = UserHandle.of(0);
    private static final UserHandle MOCK_USER2 = UserHandle.of(1);
    private static final int MOCK_UID1 = 10001;
    private static final int MOCK_UID2 = 10086;
    private static final int SYSTEM_UID1 = 1000;
    private static final int SYSTEM_UID2 = 1008;
    private static final int VPN_UID = 10002;
    private static final String REAL_SYSTEM_PACKAGE_NAME = "android";
    private static final String MOCK_PACKAGE1 = "appName1";
    private static final String MOCK_PACKAGE2 = "appName2";
    private static final String SYSTEM_PACKAGE1 = "sysName1";
    private static final String SYSTEM_PACKAGE2 = "sysName2";
    private static final String PARTITION_SYSTEM = "system";
    private static final String PARTITION_OEM = "oem";
    private static final String PARTITION_PRODUCT = "product";
    private static final String PARTITION_VENDOR = "vendor";
    private static final int VERSION_P = Build.VERSION_CODES.P;
    private static final int VERSION_Q = Build.VERSION_CODES.Q;
    private static final int PERMISSION_TRAFFIC_ALL =
            PERMISSION_INTERNET | PERMISSION_UPDATE_DEVICE_STATS;

    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;
    @Mock private INetd mNetdService;
    @Mock private UserManager mUserManager;
    @Mock private PermissionMonitor.Dependencies mDeps;
    @Mock private SystemConfigManager mSystemConfigManager;

    private PermissionMonitor mPermissionMonitor;
    private NetdMonitor mNetdMonitor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);
        when(mUserManager.getUserHandles(eq(true))).thenReturn(
                Arrays.asList(new UserHandle[] { MOCK_USER1, MOCK_USER2 }));
        when(mContext.getSystemServiceName(SystemConfigManager.class))
                .thenReturn(Context.SYSTEM_CONFIG_SERVICE);
        when(mContext.getSystemService(Context.SYSTEM_CONFIG_SERVICE))
                .thenReturn(mSystemConfigManager);
        if (mContext.getSystemService(SystemConfigManager.class) == null) {
            // Test is using mockito-extended
            doCallRealMethod().when(mContext).getSystemService(SystemConfigManager.class);
        }
        when(mSystemConfigManager.getSystemPermissionUids(anyString())).thenReturn(new int[0]);
        final Context asUserCtx = mock(Context.class, AdditionalAnswers.delegatesTo(mContext));
        doReturn(UserHandle.ALL).when(asUserCtx).getUser();
        when(mContext.createContextAsUser(eq(UserHandle.ALL), anyInt())).thenReturn(asUserCtx);
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(new ArraySet<>());
        // Set DEVICE_INITIAL_SDK_INT to Q that SYSTEM_UID won't have restricted network permission
        // by default.
        doReturn(VERSION_Q).when(mDeps).getDeviceFirstSdkInt();

        mPermissionMonitor = new PermissionMonitor(mContext, mNetdService, mDeps);
        mNetdMonitor = new NetdMonitor(mNetdService);

        when(mPackageManager.getInstalledPackages(anyInt())).thenReturn(/* empty app list */ null);
        mPermissionMonitor.startMonitoring();
    }

    private boolean hasRestrictedNetworkPermission(String partition, int targetSdkVersion,
            String packageName, int uid, String... permissions) {
        final PackageInfo packageInfo =
                packageInfoWithPermissions(REQUESTED_PERMISSION_GRANTED, permissions, partition);
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo.targetSdkVersion = targetSdkVersion;
        packageInfo.applicationInfo.uid = uid;
        return mPermissionMonitor.hasRestrictedNetworkPermission(packageInfo);
    }

    private static PackageInfo systemPackageInfoWithPermissions(String... permissions) {
        return packageInfoWithPermissions(
                REQUESTED_PERMISSION_GRANTED, permissions, PARTITION_SYSTEM);
    }

    private static PackageInfo vendorPackageInfoWithPermissions(String... permissions) {
        return packageInfoWithPermissions(
                REQUESTED_PERMISSION_GRANTED, permissions, PARTITION_VENDOR);
    }

    private static PackageInfo packageInfoWithPermissions(int permissionsFlags,
            String[] permissions, String partition) {
        int[] requestedPermissionsFlags = new int[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            requestedPermissionsFlags[i] = permissionsFlags;
        }
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.requestedPermissions = permissions;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.requestedPermissionsFlags = requestedPermissionsFlags;
        int privateFlags = 0;
        switch (partition) {
            case PARTITION_OEM:
                privateFlags = PRIVATE_FLAG_OEM;
                break;
            case PARTITION_PRODUCT:
                privateFlags = PRIVATE_FLAG_PRODUCT;
                break;
            case PARTITION_VENDOR:
                privateFlags = PRIVATE_FLAG_VENDOR;
                break;
        }
        packageInfo.applicationInfo.privateFlags = privateFlags;
        return packageInfo;
    }

    private static PackageInfo buildPackageInfo(String packageName, int uid,
            String... permissions) {
        final PackageInfo pkgInfo = systemPackageInfoWithPermissions(permissions);
        pkgInfo.packageName = packageName;
        pkgInfo.applicationInfo.uid = uid;
        return pkgInfo;
    }

    // TODO: Move this method to static lib.
    private static @NonNull <T> T[] appendElement(Class<T> kind, @Nullable T[] array, T element) {
        final T[] result;
        if (array != null) {
            result = Arrays.copyOf(array, array.length + 1);
        } else {
            result = (T[]) Array.newInstance(kind, 1);
        }
        result[result.length - 1] = element;
        return result;
    }

    private void buildAndMockPackageInfoWithPermissions(String packageName, int uid,
            String... permissions) throws Exception {
        final PackageInfo packageInfo = buildPackageInfo(packageName, uid, permissions);
        // This will return the wrong UID for the package when queried with other users.
        doReturn(packageInfo).when(mPackageManager)
                .getPackageInfo(eq(packageName), anyInt() /* flag */);
        final String[] oldPackages = mPackageManager.getPackagesForUid(uid);
        // If it's duplicated package, no need to set it again.
        if (CollectionUtils.contains(oldPackages, packageName)) return;

        // Combine the package if this uid is shared with other packages.
        final String[] newPackages = appendElement(String.class, oldPackages, packageName);
        doReturn(newPackages).when(mPackageManager).getPackagesForUid(eq(uid));
    }

    private void addPackage(String packageName, int uid, String... permissions) throws Exception {
        buildAndMockPackageInfoWithPermissions(packageName, uid, permissions);
        mPermissionMonitor.onPackageAdded(packageName, uid);
    }

    @Test
    public void testHasPermission() {
        PackageInfo app = systemPackageInfoWithPermissions();
        assertFalse(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));
        assertFalse(mPermissionMonitor.hasPermission(app, NETWORK_STACK));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_INTERNAL));

        app = systemPackageInfoWithPermissions(CHANGE_NETWORK_STATE, NETWORK_STACK);
        assertTrue(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));
        assertTrue(mPermissionMonitor.hasPermission(app, NETWORK_STACK));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_INTERNAL));

        app = systemPackageInfoWithPermissions(
                CONNECTIVITY_USE_RESTRICTED_NETWORKS, CONNECTIVITY_INTERNAL);
        assertFalse(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));
        assertFalse(mPermissionMonitor.hasPermission(app, NETWORK_STACK));
        assertTrue(mPermissionMonitor.hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertTrue(mPermissionMonitor.hasPermission(app, CONNECTIVITY_INTERNAL));

        app = packageInfoWithPermissions(REQUESTED_PERMISSION_REQUIRED, new String[] {
                CONNECTIVITY_USE_RESTRICTED_NETWORKS, CONNECTIVITY_INTERNAL, NETWORK_STACK },
                PARTITION_SYSTEM);
        assertFalse(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));
        assertFalse(mPermissionMonitor.hasPermission(app, NETWORK_STACK));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_INTERNAL));

        app = systemPackageInfoWithPermissions(CHANGE_NETWORK_STATE);
        app.requestedPermissions = null;
        assertFalse(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));

        app = systemPackageInfoWithPermissions(CHANGE_NETWORK_STATE);
        app.requestedPermissionsFlags = null;
        assertFalse(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));
    }

    @Test
    public void testIsVendorApp() {
        PackageInfo app = systemPackageInfoWithPermissions();
        assertFalse(mPermissionMonitor.isVendorApp(app.applicationInfo));
        app = packageInfoWithPermissions(REQUESTED_PERMISSION_GRANTED,
                new String[] {}, PARTITION_OEM);
        assertTrue(mPermissionMonitor.isVendorApp(app.applicationInfo));
        app = packageInfoWithPermissions(REQUESTED_PERMISSION_GRANTED,
                new String[] {}, PARTITION_PRODUCT);
        assertTrue(mPermissionMonitor.isVendorApp(app.applicationInfo));
        app = vendorPackageInfoWithPermissions();
        assertTrue(mPermissionMonitor.isVendorApp(app.applicationInfo));
    }

    @Test
    public void testHasNetworkPermission() {
        PackageInfo app = systemPackageInfoWithPermissions();
        assertFalse(mPermissionMonitor.hasNetworkPermission(app));
        app = systemPackageInfoWithPermissions(CHANGE_NETWORK_STATE);
        assertTrue(mPermissionMonitor.hasNetworkPermission(app));
        app = systemPackageInfoWithPermissions(NETWORK_STACK);
        assertFalse(mPermissionMonitor.hasNetworkPermission(app));
        app = systemPackageInfoWithPermissions(CONNECTIVITY_USE_RESTRICTED_NETWORKS);
        assertFalse(mPermissionMonitor.hasNetworkPermission(app));
        app = systemPackageInfoWithPermissions(CONNECTIVITY_INTERNAL);
        assertFalse(mPermissionMonitor.hasNetworkPermission(app));
    }

    @Test
    public void testHasRestrictedNetworkPermission() {
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, MOCK_PACKAGE1, MOCK_UID1));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, MOCK_PACKAGE1, MOCK_UID1, CHANGE_NETWORK_STATE));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, MOCK_PACKAGE1, MOCK_UID1, NETWORK_STACK));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, MOCK_PACKAGE1, MOCK_UID1, CONNECTIVITY_INTERNAL));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, MOCK_PACKAGE1, MOCK_UID1,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, MOCK_PACKAGE1, MOCK_UID1, CHANGE_WIFI_STATE));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, MOCK_PACKAGE1, MOCK_UID1,
                PERMISSION_MAINLINE_NETWORK_STACK));

        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_Q, MOCK_PACKAGE1, MOCK_UID1));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_Q, MOCK_PACKAGE1, MOCK_UID1, CONNECTIVITY_INTERNAL));
    }

    @Test
    public void testHasRestrictedNetworkPermissionSystemUid() {
        doReturn(VERSION_P).when(mDeps).getDeviceFirstSdkInt();
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, SYSTEM_PACKAGE1, SYSTEM_UID));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, SYSTEM_PACKAGE1, SYSTEM_UID, CONNECTIVITY_INTERNAL));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_P, SYSTEM_PACKAGE1, SYSTEM_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));

        doReturn(VERSION_Q).when(mDeps).getDeviceFirstSdkInt();
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_Q, SYSTEM_PACKAGE1, SYSTEM_UID));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_Q, SYSTEM_PACKAGE1, SYSTEM_UID, CONNECTIVITY_INTERNAL));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_SYSTEM, VERSION_Q, SYSTEM_PACKAGE1, SYSTEM_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
    }

    @Test
    public void testHasRestrictedNetworkPermissionVendorApp() {
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_P, MOCK_PACKAGE1, MOCK_UID1));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_P, MOCK_PACKAGE1, MOCK_UID1, CHANGE_NETWORK_STATE));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_P, MOCK_PACKAGE1, MOCK_UID1, NETWORK_STACK));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_P, MOCK_PACKAGE1, MOCK_UID1, CONNECTIVITY_INTERNAL));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_P, MOCK_PACKAGE1, MOCK_UID1,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_P, MOCK_PACKAGE1, MOCK_UID1, CHANGE_WIFI_STATE));

        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE1, MOCK_UID1));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE1, MOCK_UID1, CONNECTIVITY_INTERNAL));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE1, MOCK_UID1, CHANGE_NETWORK_STATE));
    }

    @Test
    public void testHasRestrictedNetworkPermissionUidAllowedOnRestrictedNetworks() {
        mPermissionMonitor.updateUidsAllowedOnRestrictedNetworks(Set.of(MOCK_UID1));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE1, MOCK_UID1));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE1, MOCK_UID1, CHANGE_NETWORK_STATE));
        assertTrue(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE1, MOCK_UID1, CONNECTIVITY_INTERNAL));

        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE2, MOCK_UID2));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE2, MOCK_UID2, CHANGE_NETWORK_STATE));
        assertFalse(hasRestrictedNetworkPermission(
                PARTITION_VENDOR, VERSION_Q, MOCK_PACKAGE2, MOCK_UID2, CONNECTIVITY_INTERNAL));

    }

    private boolean wouldBeCarryoverPackage(String partition, int targetSdkVersion, int uid) {
        final PackageInfo packageInfo = packageInfoWithPermissions(
                REQUESTED_PERMISSION_GRANTED, new String[] {}, partition);
        packageInfo.applicationInfo.targetSdkVersion = targetSdkVersion;
        packageInfo.applicationInfo.uid = uid;
        return mPermissionMonitor.isCarryoverPackage(packageInfo.applicationInfo);
    }

    @Test
    public void testIsCarryoverPackage() {
        doReturn(VERSION_P).when(mDeps).getDeviceFirstSdkInt();
        assertTrue(wouldBeCarryoverPackage(PARTITION_SYSTEM, VERSION_P, SYSTEM_UID));
        assertTrue(wouldBeCarryoverPackage(PARTITION_VENDOR, VERSION_P, SYSTEM_UID));
        assertFalse(wouldBeCarryoverPackage(PARTITION_SYSTEM, VERSION_P, MOCK_UID1));
        assertTrue(wouldBeCarryoverPackage(PARTITION_VENDOR, VERSION_P, MOCK_UID1));
        assertTrue(wouldBeCarryoverPackage(PARTITION_SYSTEM, VERSION_Q, SYSTEM_UID));
        assertTrue(wouldBeCarryoverPackage(PARTITION_VENDOR, VERSION_Q, SYSTEM_UID));
        assertFalse(wouldBeCarryoverPackage(PARTITION_SYSTEM, VERSION_Q, MOCK_UID1));
        assertFalse(wouldBeCarryoverPackage(PARTITION_VENDOR, VERSION_Q, MOCK_UID1));

        doReturn(VERSION_Q).when(mDeps).getDeviceFirstSdkInt();
        assertFalse(wouldBeCarryoverPackage(PARTITION_SYSTEM, VERSION_P, SYSTEM_UID));
        assertTrue(wouldBeCarryoverPackage(PARTITION_VENDOR, VERSION_P, SYSTEM_UID));
        assertFalse(wouldBeCarryoverPackage(PARTITION_SYSTEM, VERSION_P, MOCK_UID1));
        assertTrue(wouldBeCarryoverPackage(PARTITION_VENDOR, VERSION_P, MOCK_UID1));
        assertFalse(wouldBeCarryoverPackage(PARTITION_SYSTEM, VERSION_Q, SYSTEM_UID));
        assertFalse(wouldBeCarryoverPackage(PARTITION_VENDOR, VERSION_Q, SYSTEM_UID));
        assertFalse(wouldBeCarryoverPackage(PARTITION_SYSTEM, VERSION_Q, MOCK_UID1));
        assertFalse(wouldBeCarryoverPackage(PARTITION_VENDOR, VERSION_Q, MOCK_UID1));

        assertFalse(wouldBeCarryoverPackage(PARTITION_OEM, VERSION_Q, SYSTEM_UID));
        assertFalse(wouldBeCarryoverPackage(PARTITION_PRODUCT, VERSION_Q, SYSTEM_UID));
        assertFalse(wouldBeCarryoverPackage(PARTITION_OEM, VERSION_Q, MOCK_UID1));
        assertFalse(wouldBeCarryoverPackage(PARTITION_PRODUCT, VERSION_Q, MOCK_UID1));
    }

    private boolean wouldBeUidAllowedOnRestrictedNetworks(int uid) {
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = uid;
        return mPermissionMonitor.isUidAllowedOnRestrictedNetworks(applicationInfo);
    }

    @Test
    public void testIsAppAllowedOnRestrictedNetworks() {
        mPermissionMonitor.updateUidsAllowedOnRestrictedNetworks(new ArraySet<>());
        assertFalse(wouldBeUidAllowedOnRestrictedNetworks(MOCK_UID1));
        assertFalse(wouldBeUidAllowedOnRestrictedNetworks(MOCK_UID2));

        mPermissionMonitor.updateUidsAllowedOnRestrictedNetworks(
                new ArraySet<>(new Integer[] { MOCK_UID1 }));
        assertTrue(wouldBeUidAllowedOnRestrictedNetworks(MOCK_UID1));
        assertFalse(wouldBeUidAllowedOnRestrictedNetworks(MOCK_UID2));

        mPermissionMonitor.updateUidsAllowedOnRestrictedNetworks(
                new ArraySet<>(new Integer[] { MOCK_UID2 }));
        assertFalse(wouldBeUidAllowedOnRestrictedNetworks(MOCK_UID1));
        assertTrue(wouldBeUidAllowedOnRestrictedNetworks(MOCK_UID2));

        mPermissionMonitor.updateUidsAllowedOnRestrictedNetworks(
                new ArraySet<>(new Integer[] { 123 }));
        assertFalse(wouldBeUidAllowedOnRestrictedNetworks(MOCK_UID1));
        assertFalse(wouldBeUidAllowedOnRestrictedNetworks(MOCK_UID2));
    }

    private void assertBackgroundPermission(boolean hasPermission, String name, int uid,
            String... permissions) throws Exception {
        addPackage(name, uid, permissions);
        assertEquals(hasPermission, mPermissionMonitor.hasUseBackgroundNetworksPermission(uid));
    }

    @Test
    public void testHasUseBackgroundNetworksPermission() throws Exception {
        assertFalse(mPermissionMonitor.hasUseBackgroundNetworksPermission(SYSTEM_UID));
        assertBackgroundPermission(false, SYSTEM_PACKAGE1, SYSTEM_UID);
        assertBackgroundPermission(false, SYSTEM_PACKAGE1, SYSTEM_UID, CONNECTIVITY_INTERNAL);
        assertBackgroundPermission(true, SYSTEM_PACKAGE1, SYSTEM_UID, CHANGE_NETWORK_STATE);
        assertBackgroundPermission(true, SYSTEM_PACKAGE1, SYSTEM_UID, NETWORK_STACK);

        assertFalse(mPermissionMonitor.hasUseBackgroundNetworksPermission(MOCK_UID1));
        assertBackgroundPermission(false, MOCK_PACKAGE1, MOCK_UID1);
        assertBackgroundPermission(true, MOCK_PACKAGE1, MOCK_UID1,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS);

        assertFalse(mPermissionMonitor.hasUseBackgroundNetworksPermission(MOCK_UID2));
        assertBackgroundPermission(false, MOCK_PACKAGE2, MOCK_UID2);
        assertBackgroundPermission(false, MOCK_PACKAGE2, MOCK_UID2,
                CONNECTIVITY_INTERNAL);
        assertBackgroundPermission(true, MOCK_PACKAGE2, MOCK_UID2, NETWORK_STACK);
    }

    private class NetdMonitor {
        private final HashMap<Integer, Boolean> mUidsNetworkPermission = new HashMap<>();
        private final HashMap<Integer, Integer> mAppIdsTrafficPermission = new HashMap<>();

        NetdMonitor(INetd mockNetd) throws Exception {
            // Add hook to verify and track result of networkSetPermission.
            doAnswer((InvocationOnMock invocation) -> {
                final Object[] args = invocation.getArguments();
                final Boolean isSystem = args[0].equals(PERMISSION_SYSTEM);
                for (final int uid : (int[]) args[1]) {
                    // TODO: Currently, permission monitor will send duplicate commands for each uid
                    // corresponding to each user. Need to fix that and uncomment below test.
                    // if (mApps.containsKey(uid) && mApps.get(uid) == isSystem) {
                    //     fail("uid " + uid + " is already set to " + isSystem);
                    // }
                    mUidsNetworkPermission.put(uid, isSystem);
                }
                return null;
            }).when(mockNetd).networkSetPermissionForUser(anyInt(), any(int[].class));

            // Add hook to verify and track result of networkClearPermission.
            doAnswer((InvocationOnMock invocation) -> {
                final Object[] args = invocation.getArguments();
                for (final int uid : (int[]) args[0]) {
                    // TODO: Currently, permission monitor will send duplicate commands for each uid
                    // corresponding to each user. Need to fix that and uncomment below test.
                    // if (!mApps.containsKey(uid)) {
                    //     fail("uid " + uid + " does not exist.");
                    // }
                    mUidsNetworkPermission.remove(uid);
                }
                return null;
            }).when(mockNetd).networkClearPermissionForUser(any(int[].class));

            // Add hook to verify and track result of trafficSetNetPerm.
            doAnswer((InvocationOnMock invocation) -> {
                final Object[] args = invocation.getArguments();
                final int permission = (int) args[0];
                for (final int appId : (int[]) args[1]) {
                    mAppIdsTrafficPermission.put(appId, permission);
                }
                return null;
            }).when(mockNetd).trafficSetNetPermForUids(anyInt(), any(int[].class));
        }

        public void expectNetworkPerm(Boolean permission, UserHandle[] users, int... appIds) {
            for (final UserHandle user : users) {
                for (final int appId : appIds) {
                    final int uid = user.getUid(appId);
                    if (!mUidsNetworkPermission.containsKey(uid)) {
                        fail("uid " + uid + " does not exist.");
                    }
                    if (mUidsNetworkPermission.get(uid) != permission) {
                        fail("uid " + uid + " has wrong permission: " +  permission);
                    }
                }
            }
        }

        public void expectNoNetworkPerm(UserHandle[] users, int... appIds) {
            for (final UserHandle user : users) {
                for (final int appId : appIds) {
                    final int uid = user.getUid(appId);
                    if (mUidsNetworkPermission.containsKey(uid)) {
                        fail("uid " + uid + " has listed permissions, expected none.");
                    }
                }
            }
        }

        public void expectTrafficPerm(int permission, int... appIds) {
            for (final int appId : appIds) {
                if (!mAppIdsTrafficPermission.containsKey(appId)) {
                    fail("appId " + appId + " does not exist.");
                }
                if (mAppIdsTrafficPermission.get(appId) != permission) {
                    fail("appId " + appId + " has wrong permission: "
                            + mAppIdsTrafficPermission.get(appId));
                }
            }
        }
    }

    @Test
    public void testUserAndPackageAddRemove() throws Exception {
        // MOCK_UID1: MOCK_PACKAGE1 only has network permission.
        // SYSTEM_UID: SYSTEM_PACKAGE1 has system permission.
        // SYSTEM_UID: SYSTEM_PACKAGE2 only has network permission.
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1, CHANGE_NETWORK_STATE);
        buildAndMockPackageInfoWithPermissions(SYSTEM_PACKAGE1, SYSTEM_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS);
        buildAndMockPackageInfoWithPermissions(SYSTEM_PACKAGE2, SYSTEM_UID, CHANGE_NETWORK_STATE);

        // Add SYSTEM_PACKAGE2, expect only have network permission.
        mPermissionMonitor.onUserAdded(MOCK_USER1);
        addPackageForUsers(new UserHandle[]{MOCK_USER1}, SYSTEM_PACKAGE2, SYSTEM_UID);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1}, SYSTEM_UID);

        // Add SYSTEM_PACKAGE1, expect permission escalate.
        addPackageForUsers(new UserHandle[]{MOCK_USER1}, SYSTEM_PACKAGE1, SYSTEM_UID);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1}, SYSTEM_UID);

        mPermissionMonitor.onUserAdded(MOCK_USER2);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1, MOCK_USER2},
                SYSTEM_UID);

        // Remove SYSTEM_PACKAGE2, expect keep system permission.
        when(mPackageManager.getPackagesForUid(MOCK_USER1.getUid(SYSTEM_UID)))
                .thenReturn(new String[]{SYSTEM_PACKAGE1});
        when(mPackageManager.getPackagesForUid(MOCK_USER2.getUid(SYSTEM_UID)))
                .thenReturn(new String[]{SYSTEM_PACKAGE1});
        removePackageForUsers(new UserHandle[]{MOCK_USER1, MOCK_USER2},
                SYSTEM_PACKAGE2, SYSTEM_UID);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1, MOCK_USER2},
                SYSTEM_UID);

        // Add SYSTEM_PACKAGE2, expect keep system permission.
        addPackageForUsers(new UserHandle[]{MOCK_USER1, MOCK_USER2}, SYSTEM_PACKAGE2, SYSTEM_UID);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1, MOCK_USER2},
                SYSTEM_UID);

        addPackageForUsers(new UserHandle[]{MOCK_USER1, MOCK_USER2}, MOCK_PACKAGE1, MOCK_UID1);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1, MOCK_USER2},
                SYSTEM_UID);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1, MOCK_USER2},
                MOCK_UID1);

        // Remove MOCK_UID1, expect no permission left for all user.
        when(mPackageManager.getPackagesForUid(MOCK_USER1.getUid(MOCK_UID1)))
                .thenReturn(new String[]{});
        when(mPackageManager.getPackagesForUid(MOCK_USER2.getUid(MOCK_UID1)))
                .thenReturn(new String[]{});
        removePackageForUsers(new UserHandle[]{MOCK_USER1, MOCK_USER2}, MOCK_PACKAGE1, MOCK_UID1);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1, MOCK_USER2}, MOCK_UID1);

        // Remove SYSTEM_PACKAGE1, expect permission downgrade.
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[]{SYSTEM_PACKAGE2});
        removePackageForUsers(new UserHandle[]{MOCK_USER1, MOCK_USER2},
                SYSTEM_PACKAGE1, SYSTEM_UID);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1, MOCK_USER2},
                SYSTEM_UID);

        mPermissionMonitor.onUserRemoved(MOCK_USER1);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER2}, SYSTEM_UID);

        // Remove all packages, expect no permission left.
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[]{});
        removePackageForUsers(new UserHandle[]{MOCK_USER2}, SYSTEM_PACKAGE2, SYSTEM_UID);
        mNetdMonitor.expectNoNetworkPerm(
                new UserHandle[]{MOCK_USER1, MOCK_USER2}, SYSTEM_UID, MOCK_UID1);

        // Remove last user, expect no redundant clearPermission is invoked.
        mPermissionMonitor.onUserRemoved(MOCK_USER2);
        mNetdMonitor.expectNoNetworkPerm(
                new UserHandle[]{MOCK_USER1, MOCK_USER2}, SYSTEM_UID, MOCK_UID1);
    }

    @Test
    public void testUidFilteringDuringVpnConnectDisconnectAndUidUpdates() throws Exception {
        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS | MATCH_ANY_USER))).thenReturn(
                List.of(buildPackageInfo(SYSTEM_PACKAGE1, SYSTEM_UID1, CHANGE_NETWORK_STATE,
                                CONNECTIVITY_USE_RESTRICTED_NETWORKS),
                        buildPackageInfo(MOCK_PACKAGE1, MOCK_UID1),
                        buildPackageInfo(MOCK_PACKAGE2, MOCK_UID2),
                        buildPackageInfo(SYSTEM_PACKAGE2, VPN_UID)));
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1);
        mPermissionMonitor.startMonitoring();
        // Every app on user 0 except MOCK_UID2 are under VPN.
        final Set<UidRange> vpnRange1 = new HashSet<>(Arrays.asList(new UidRange[] {
                new UidRange(0, MOCK_UID2 - 1),
                new UidRange(MOCK_UID2 + 1, UserHandle.PER_USER_RANGE - 1)}));
        final Set<UidRange> vpnRange2 = Collections.singleton(new UidRange(MOCK_UID2, MOCK_UID2));

        // When VPN is connected, expect a rule to be set up for user app MOCK_UID1
        mPermissionMonitor.onVpnUidRangesAdded("tun0", vpnRange1, VPN_UID);
        verify(mNetdService).firewallAddUidInterfaceRules(eq("tun0"),
                aryEq(new int[] {MOCK_UID1}));

        reset(mNetdService);

        // When MOCK_UID1 package is uninstalled and reinstalled, expect Netd to be updated
        mPermissionMonitor.onPackageRemoved(
                MOCK_PACKAGE1, MOCK_USER1.getUid(MOCK_UID1));
        verify(mNetdService).firewallRemoveUidInterfaceRules(aryEq(new int[] {MOCK_UID1}));
        mPermissionMonitor.onPackageAdded(MOCK_PACKAGE1, MOCK_USER1.getUid(MOCK_UID1));
        verify(mNetdService).firewallAddUidInterfaceRules(eq("tun0"),
                aryEq(new int[] {MOCK_UID1}));

        reset(mNetdService);

        // During VPN uid update (vpnRange1 -> vpnRange2), ConnectivityService first deletes the
        // old UID rules then adds the new ones. Expect netd to be updated
        mPermissionMonitor.onVpnUidRangesRemoved("tun0", vpnRange1, VPN_UID);
        verify(mNetdService).firewallRemoveUidInterfaceRules(aryEq(new int[] {MOCK_UID1}));
        mPermissionMonitor.onVpnUidRangesAdded("tun0", vpnRange2, VPN_UID);
        verify(mNetdService).firewallAddUidInterfaceRules(eq("tun0"),
                aryEq(new int[] {MOCK_UID2}));

        reset(mNetdService);

        // When VPN is disconnected, expect rules to be torn down
        mPermissionMonitor.onVpnUidRangesRemoved("tun0", vpnRange2, VPN_UID);
        verify(mNetdService).firewallRemoveUidInterfaceRules(aryEq(new int[] {MOCK_UID2}));
        assertNull(mPermissionMonitor.getVpnUidRanges("tun0"));
    }

    @Test
    public void testUidFilteringDuringPackageInstallAndUninstall() throws Exception {
        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS | MATCH_ANY_USER))).thenReturn(
                List.of(buildPackageInfo(SYSTEM_PACKAGE1, SYSTEM_UID1, CHANGE_NETWORK_STATE,
                                NETWORK_STACK, CONNECTIVITY_USE_RESTRICTED_NETWORKS),
                        buildPackageInfo(SYSTEM_PACKAGE2, VPN_UID)));
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1);

        mPermissionMonitor.startMonitoring();
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(MOCK_USER1));
        mPermissionMonitor.onVpnUidRangesAdded("tun0", vpnRange, VPN_UID);

        // Newly-installed package should have uid rules added
        mPermissionMonitor.onPackageAdded(MOCK_PACKAGE1, MOCK_USER1.getUid(MOCK_UID1));
        verify(mNetdService).firewallAddUidInterfaceRules(eq("tun0"),
                aryEq(new int[] {MOCK_UID1}));

        // Removed package should have its uid rules removed
        mPermissionMonitor.onPackageRemoved(
                MOCK_PACKAGE1, MOCK_USER1.getUid(MOCK_UID1));
        verify(mNetdService).firewallRemoveUidInterfaceRules(aryEq(new int[] {MOCK_UID1}));
    }


    // Normal package add/remove operations will trigger multiple intent for uids corresponding to
    // each user. To simulate generic package operations, the onPackageAdded/Removed will need to be
    // called multiple times with the uid corresponding to each user.
    private void addPackageForUsers(UserHandle[] users, String packageName, int uid) {
        for (final UserHandle user : users) {
            mPermissionMonitor.onPackageAdded(packageName, user.getUid(uid));
        }
    }

    private void removePackageForUsers(UserHandle[] users, String packageName, int uid) {
        for (final UserHandle user : users) {
            mPermissionMonitor.onPackageRemoved(packageName, user.getUid(uid));
        }
    }

    @Test
    public void testPackagePermissionUpdate() throws Exception {
        // MOCK_UID1: MOCK_PACKAGE1 only has internet permission.
        // MOCK_UID2: MOCK_PACKAGE2 does not have any permission.
        // SYSTEM_UID1: SYSTEM_PACKAGE1 has internet permission and update device stats permission.
        // SYSTEM_UID2: SYSTEM_PACKAGE2 has only update device stats permission.
        SparseIntArray netdPermissionsAppIds = new SparseIntArray();
        netdPermissionsAppIds.put(MOCK_UID1, PERMISSION_INTERNET);
        netdPermissionsAppIds.put(MOCK_UID2, PERMISSION_NONE);
        netdPermissionsAppIds.put(SYSTEM_UID1, PERMISSION_TRAFFIC_ALL);
        netdPermissionsAppIds.put(SYSTEM_UID2, PERMISSION_UPDATE_DEVICE_STATS);

        // Send the permission information to netd, expect permission updated.
        mPermissionMonitor.sendPackagePermissionsToNetd(netdPermissionsAppIds);

        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_NONE, MOCK_UID2);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, SYSTEM_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_UPDATE_DEVICE_STATS, SYSTEM_UID2);

        // Update permission of MOCK_UID1, expect new permission show up.
        mPermissionMonitor.sendPackagePermissionsForUid(MOCK_UID1, PERMISSION_TRAFFIC_ALL);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);

        // Change permissions of SYSTEM_UID2, expect new permission show up and old permission
        // revoked.
        mPermissionMonitor.sendPackagePermissionsForUid(SYSTEM_UID2, PERMISSION_INTERNET);
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, SYSTEM_UID2);

        // Revoke permission from SYSTEM_UID1, expect no permission stored.
        mPermissionMonitor.sendPackagePermissionsForUid(SYSTEM_UID1, PERMISSION_NONE);
        mNetdMonitor.expectTrafficPerm(PERMISSION_NONE, SYSTEM_UID1);
    }

    @Test
    public void testPackageInstall() throws Exception {
        addPackage(MOCK_PACKAGE1, MOCK_UID1, INTERNET, UPDATE_DEVICE_STATS);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);

        addPackage(MOCK_PACKAGE2, MOCK_UID2, INTERNET);
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID2);
    }

    @Test
    public void testPackageInstallSharedUid() throws Exception {
        addPackage(MOCK_PACKAGE1, MOCK_UID1, INTERNET, UPDATE_DEVICE_STATS);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);

        // Install another package with the same uid and no permissions should not cause the UID to
        // lose permissions.
        addPackage(MOCK_PACKAGE2, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);
    }

    @Test
    public void testPackageUninstallBasic() throws Exception {
        addPackage(MOCK_PACKAGE1, MOCK_UID1, INTERNET, UPDATE_DEVICE_STATS);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);

        when(mPackageManager.getPackagesForUid(MOCK_UID1)).thenReturn(new String[]{});
        mPermissionMonitor.onPackageRemoved(MOCK_PACKAGE1, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_UNINSTALLED, MOCK_UID1);
    }

    @Test
    public void testPackageRemoveThenAdd() throws Exception {
        addPackage(MOCK_PACKAGE1, MOCK_UID1, INTERNET, UPDATE_DEVICE_STATS);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);

        when(mPackageManager.getPackagesForUid(MOCK_UID1)).thenReturn(new String[]{});
        mPermissionMonitor.onPackageRemoved(MOCK_PACKAGE1, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_UNINSTALLED, MOCK_UID1);

        addPackage(MOCK_PACKAGE1, MOCK_UID1, INTERNET);
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID1);
    }

    @Test
    public void testPackageUpdate() throws Exception {
        addPackage(MOCK_PACKAGE1, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_NONE, MOCK_UID1);

        addPackage(MOCK_PACKAGE1, MOCK_UID1, INTERNET);
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID1);
    }

    @Test
    public void testPackageUninstallWithMultiplePackages() throws Exception {
        addPackage(MOCK_PACKAGE1, MOCK_UID1, INTERNET, UPDATE_DEVICE_STATS);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);

        // Install another package with the same uid but different permissions.
        addPackage(MOCK_PACKAGE2, MOCK_UID1, INTERNET);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);

        // Uninstall MOCK_PACKAGE1 and expect only INTERNET permission left.
        when(mPackageManager.getPackagesForUid(eq(MOCK_UID1)))
                .thenReturn(new String[]{MOCK_PACKAGE2});
        mPermissionMonitor.onPackageRemoved(MOCK_PACKAGE1, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID1);
    }

    @Test
    public void testRealSystemPermission() throws Exception {
        // Use the real context as this test must ensure the *real* system package holds the
        // necessary permission.
        final Context realContext = InstrumentationRegistry.getContext();
        final PermissionMonitor monitor = new PermissionMonitor(realContext, mNetdService);
        final PackageManager manager = realContext.getPackageManager();
        final PackageInfo systemInfo = manager.getPackageInfo(REAL_SYSTEM_PACKAGE_NAME,
                GET_PERMISSIONS | MATCH_ANY_USER);
        assertTrue(monitor.hasPermission(systemInfo, CONNECTIVITY_USE_RESTRICTED_NETWORKS));
    }

    @Test
    public void testUpdateUidPermissionsFromSystemConfig() throws Exception {
        when(mPackageManager.getInstalledPackages(anyInt())).thenReturn(new ArrayList<>());
        when(mSystemConfigManager.getSystemPermissionUids(eq(INTERNET)))
                .thenReturn(new int[]{ MOCK_UID1, MOCK_UID2 });
        when(mSystemConfigManager.getSystemPermissionUids(eq(UPDATE_DEVICE_STATS)))
                .thenReturn(new int[]{ MOCK_UID2 });

        mPermissionMonitor.startMonitoring();
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID2);
    }

    private BroadcastReceiver expectBroadcastReceiver(String... actions) {
        final ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext, times(1)).registerReceiver(receiverCaptor.capture(),
                argThat(filter -> {
                    for (String action : actions) {
                        if (!filter.hasAction(action)) {
                            return false;
                        }
                    }
                    return true;
                }), any(), any());
        return receiverCaptor.getValue();
    }

    @Test
    public void testIntentReceiver() throws Exception {
        final BroadcastReceiver receiver = expectBroadcastReceiver(
                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED);

        // Verify receiving PACKAGE_ADDED intent.
        final Intent addedIntent = new Intent(Intent.ACTION_PACKAGE_ADDED,
                Uri.fromParts("package", MOCK_PACKAGE1, null /* fragment */));
        addedIntent.putExtra(Intent.EXTRA_UID, MOCK_UID1);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1, INTERNET,
                UPDATE_DEVICE_STATS);
        receiver.onReceive(mContext, addedIntent);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);

        // Verify receiving PACKAGE_REMOVED intent.
        when(mPackageManager.getPackagesForUid(MOCK_UID1)).thenReturn(null);
        final Intent removedIntent = new Intent(Intent.ACTION_PACKAGE_REMOVED,
                Uri.fromParts("package", MOCK_PACKAGE1, null /* fragment */));
        removedIntent.putExtra(Intent.EXTRA_UID, MOCK_UID1);
        receiver.onReceive(mContext, removedIntent);
        mNetdMonitor.expectTrafficPerm(PERMISSION_UNINSTALLED, MOCK_UID1);
    }

    private ContentObserver expectRegisterContentObserver(Uri expectedUri) {
        final ArgumentCaptor<ContentObserver> captor =
                ArgumentCaptor.forClass(ContentObserver.class);
        verify(mDeps).registerContentObserver(any(),
                argThat(uri -> uri.equals(expectedUri)), anyBoolean(), captor.capture());
        return captor.getValue();
    }

    @Test
    public void testUidsAllowedOnRestrictedNetworksChanged() throws Exception {
        final ContentObserver contentObserver = expectRegisterContentObserver(
                Settings.Global.getUriFor(UIDS_ALLOWED_ON_RESTRICTED_NETWORKS));

        mPermissionMonitor.onUserAdded(MOCK_USER1);
        // Prepare PackageInfo for MOCK_PACKAGE1 and MOCK_PACKAGE2
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE2, MOCK_UID2);

        // MOCK_UID1 is listed in setting that allow to use restricted networks, MOCK_UID1
        // should have SYSTEM permission.
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(
                new ArraySet<>(new Integer[] { MOCK_UID1 }));
        contentObserver.onChange(true /* selfChange */);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1}, MOCK_UID1);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1}, MOCK_UID2);

        // MOCK_UID2 is listed in setting that allow to use restricted networks, MOCK_UID2
        // should have SYSTEM permission but MOCK_UID1 should revoke permission.
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(
                new ArraySet<>(new Integer[] { MOCK_UID2 }));
        contentObserver.onChange(true /* selfChange */);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1}, MOCK_UID2);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1}, MOCK_UID1);

        // No uid lists in setting, should revoke permission from all uids.
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(new ArraySet<>());
        contentObserver.onChange(true /* selfChange */);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1}, MOCK_UID1, MOCK_UID2);
    }

    @Test
    public void testUidsAllowedOnRestrictedNetworksChangedWithSharedUid() throws Exception {
        final ContentObserver contentObserver = expectRegisterContentObserver(
                Settings.Global.getUriFor(UIDS_ALLOWED_ON_RESTRICTED_NETWORKS));

        mPermissionMonitor.onUserAdded(MOCK_USER1);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1, CHANGE_NETWORK_STATE);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE2, MOCK_UID1);

        // MOCK_PACKAGE1 have CHANGE_NETWORK_STATE, MOCK_UID1 should have NETWORK permission.
        addPackageForUsers(new UserHandle[]{MOCK_USER1}, MOCK_PACKAGE1, MOCK_UID1);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1}, MOCK_UID1);

        // MOCK_UID1 is listed in setting that allow to use restricted networks, MOCK_UID1
        // should upgrade to SYSTEM permission.
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(
                new ArraySet<>(new Integer[] { MOCK_UID1 }));
        contentObserver.onChange(true /* selfChange */);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1}, MOCK_UID1);

        // No app lists in setting, MOCK_UID1 should downgrade to NETWORK permission.
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(new ArraySet<>());
        contentObserver.onChange(true /* selfChange */);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1}, MOCK_UID1);

        // MOCK_PACKAGE1 removed, should revoke permission from MOCK_UID1.
        when(mPackageManager.getPackagesForUid(MOCK_UID1)).thenReturn(new String[]{MOCK_PACKAGE2});
        removePackageForUsers(new UserHandle[]{MOCK_USER1}, MOCK_PACKAGE1, MOCK_UID1);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1}, MOCK_UID1);
    }

    @Test
    public void testUidsAllowedOnRestrictedNetworksChangedWithMultipleUsers() throws Exception {
        final ContentObserver contentObserver = expectRegisterContentObserver(
                Settings.Global.getUriFor(UIDS_ALLOWED_ON_RESTRICTED_NETWORKS));

        // One user MOCK_USER1
        mPermissionMonitor.onUserAdded(MOCK_USER1);
        // Prepare PackageInfo for MOCK_PACKAGE1 and MOCK_PACKAGE2.
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE2, MOCK_UID2);

        // MOCK_UID1 is listed in setting that allow to use restricted networks, MOCK_UID1
        // in MOCK_USER1 should have SYSTEM permission and MOCK_UID2 has no permissions.
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(
                new ArraySet<>(new Integer[] { MOCK_UID1 }));
        contentObserver.onChange(true /* selfChange */);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1}, MOCK_UID1);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1}, MOCK_UID2);

        // Add user MOCK_USER2.
        mPermissionMonitor.onUserAdded(MOCK_USER2);
        // MOCK_UID1 in both users should all have SYSTEM permission and MOCK_UID2 has no
        // permissions in either user.
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1, MOCK_USER2}, MOCK_UID1);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1, MOCK_USER2}, MOCK_UID2);

        // MOCK_UID2 is listed in setting that allow to use restricted networks, MOCK_UID2
        // in both users should have SYSTEM permission and MOCK_UID1 has no permissions.
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(
                new ArraySet<>(new Integer[] { MOCK_UID2 }));
        contentObserver.onChange(true /* selfChange */);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1, MOCK_USER2}, MOCK_UID2);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1, MOCK_USER2}, MOCK_UID1);

        // Remove user MOCK_USER1
        mPermissionMonitor.onUserRemoved(MOCK_USER1);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER2}, MOCK_UID2);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER2}, MOCK_UID1);

        // No uid lists in setting, should revoke permission from all uids.
        when(mDeps.getUidsAllowedOnRestrictedNetworks(any())).thenReturn(new ArraySet<>());
        contentObserver.onChange(true /* selfChange */);
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER2}, MOCK_UID1, MOCK_UID2);
    }

    @Test
    public void testOnExternalApplicationsAvailable() throws Exception {
        final BroadcastReceiver receiver = expectBroadcastReceiver(
                Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);

        // Initial the permission state. MOCK_PACKAGE1 and MOCK_PACKAGE2 are installed on external
        // and have different uids. There has no permission for both uids.
        when(mUserManager.getUserHandles(eq(true))).thenReturn(List.of(MOCK_USER1));
        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS | MATCH_ANY_USER))).thenReturn(
                List.of(buildPackageInfo(MOCK_PACKAGE1, MOCK_UID1),
                        buildPackageInfo(MOCK_PACKAGE2, MOCK_UID2)));
        mPermissionMonitor.startMonitoring();
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1}, MOCK_UID1, MOCK_UID2);
        mNetdMonitor.expectTrafficPerm(PERMISSION_NONE, MOCK_UID1, MOCK_UID2);

        // Verify receiving EXTERNAL_APPLICATIONS_AVAILABLE intent and update permission to netd.
        final Intent externalIntent = new Intent(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        externalIntent.putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST,
                new String[] { MOCK_PACKAGE1 , MOCK_PACKAGE2});
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS, INTERNET);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE2, MOCK_UID2, CHANGE_NETWORK_STATE,
                UPDATE_DEVICE_STATS);
        receiver.onReceive(mContext, externalIntent);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1}, MOCK_UID1);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1}, MOCK_UID2);
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_UPDATE_DEVICE_STATS, MOCK_UID2);
    }

    @Test
    public void testOnExternalApplicationsAvailable_AppsNotRegisteredOnStartMonitoring()
            throws Exception {
        final BroadcastReceiver receiver = expectBroadcastReceiver(
                Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);

        // One user MOCK_USER1
        mPermissionMonitor.onUserAdded(MOCK_USER1);

        // Initial the permission state. MOCK_PACKAGE1 and MOCK_PACKAGE2 are installed on external
        // and have different uids. There has no permission for both uids.
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS, INTERNET);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE2, MOCK_UID2, CHANGE_NETWORK_STATE,
                UPDATE_DEVICE_STATS);

        // Verify receiving EXTERNAL_APPLICATIONS_AVAILABLE intent and update permission to netd.
        final Intent externalIntent = new Intent(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        externalIntent.putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST,
                new String[] { MOCK_PACKAGE1 , MOCK_PACKAGE2});
        receiver.onReceive(mContext, externalIntent);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1}, MOCK_UID1);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1}, MOCK_UID2);
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_UPDATE_DEVICE_STATS, MOCK_UID2);
    }

    @Test
    public void testOnExternalApplicationsAvailableWithSharedUid()
            throws Exception {
        final BroadcastReceiver receiver = expectBroadcastReceiver(
                Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);

        // Initial the permission state. MOCK_PACKAGE1 and MOCK_PACKAGE2 are installed on external
        // storage and shared on MOCK_UID1. There has no permission for MOCK_UID1.
        when(mUserManager.getUserHandles(eq(true))).thenReturn(List.of(MOCK_USER1));
        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS | MATCH_ANY_USER))).thenReturn(
                List.of(buildPackageInfo(MOCK_PACKAGE1, MOCK_UID1),
                        buildPackageInfo(MOCK_PACKAGE2, MOCK_UID1)));
        mPermissionMonitor.startMonitoring();
        mNetdMonitor.expectNoNetworkPerm(new UserHandle[]{MOCK_USER1}, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_NONE, MOCK_UID1);

        // Verify receiving EXTERNAL_APPLICATIONS_AVAILABLE intent and update permission to netd.
        final Intent externalIntent = new Intent(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        externalIntent.putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST, new String[] {MOCK_PACKAGE1});
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1, CHANGE_NETWORK_STATE);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE2, MOCK_UID1, UPDATE_DEVICE_STATS);
        receiver.onReceive(mContext, externalIntent);
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1}, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_UPDATE_DEVICE_STATS, MOCK_UID1);
    }

    @Test
    public void testOnExternalApplicationsAvailableWithSharedUid_DifferentStorage()
            throws Exception {
        final BroadcastReceiver receiver = expectBroadcastReceiver(
                Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);

        // Initial the permission state. MOCK_PACKAGE1 is installed on external storage and
        // MOCK_PACKAGE2 is installed on device. These two packages are shared on MOCK_UID1.
        // MOCK_UID1 has NETWORK and INTERNET permissions.
        when(mUserManager.getUserHandles(eq(true))).thenReturn(List.of(MOCK_USER1));
        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS | MATCH_ANY_USER))).thenReturn(
                List.of(buildPackageInfo(MOCK_PACKAGE1, MOCK_UID1),
                        buildPackageInfo(MOCK_PACKAGE2, MOCK_UID1, CHANGE_NETWORK_STATE,
                                INTERNET)));
        mPermissionMonitor.startMonitoring();
        mNetdMonitor.expectNetworkPerm(NETWORK, new UserHandle[]{MOCK_USER1}, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_INTERNET, MOCK_UID1);

        // Verify receiving EXTERNAL_APPLICATIONS_AVAILABLE intent and update permission to netd.
        final Intent externalIntent = new Intent(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        externalIntent.putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST, new String[] {MOCK_PACKAGE1});
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE1, MOCK_UID1,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS, UPDATE_DEVICE_STATS);
        buildAndMockPackageInfoWithPermissions(MOCK_PACKAGE2, MOCK_UID1, CHANGE_NETWORK_STATE,
                INTERNET);
        receiver.onReceive(mContext, externalIntent);
        mNetdMonitor.expectNetworkPerm(SYSTEM, new UserHandle[]{MOCK_USER1}, MOCK_UID1);
        mNetdMonitor.expectTrafficPerm(PERMISSION_TRAFFIC_ALL, MOCK_UID1);
    }
}
