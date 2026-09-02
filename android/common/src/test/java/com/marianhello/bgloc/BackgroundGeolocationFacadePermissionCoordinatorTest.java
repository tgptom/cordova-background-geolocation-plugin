package com.marianhello.bgloc;

import android.Manifest;
import android.content.Context;
import android.os.Looper;

import com.intentfilter.androidpermissions.PermissionManager;
import com.intentfilter.androidpermissions.models.DeniedPermissions;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class BackgroundGeolocationFacadePermissionCoordinatorTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.application.getApplicationContext();
        context.getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE).edit().clear().commit();
        TrackingLifecycleCoordinator coordinator = TrackingLifecycleCoordinator.getInstance(context);
        coordinator.clearPendingStart();
        coordinator.clearPendingStop();
        coordinator.clearOwner();
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 29)
    public void activityProviderStagesForegroundBackgroundAndActivityRecognition() {
        Config config = Config.getDefault();
        config.setLocationProvider(Config.ACTIVITY_PROVIDER);

        TestPermissionFacade facade = new TestPermissionFacade(context, config);
        facade.start();

        Assert.assertEquals(Arrays.asList(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), facade.nextRequestedPermissions());

        facade.setPermission(Manifest.permission.ACCESS_COARSE_LOCATION, true);
        facade.setPermission(Manifest.permission.ACCESS_FINE_LOCATION, true);
        facade.grantNextPermissionRequest();

        Assert.assertEquals(Arrays.asList(Manifest.permission.ACCESS_BACKGROUND_LOCATION), facade.nextRequestedPermissions());

        facade.setPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION, true);
        facade.grantNextPermissionRequest();

        Assert.assertEquals(Arrays.asList(Manifest.permission.ACTIVITY_RECOGNITION), facade.nextRequestedPermissions());

        facade.setPermission(Manifest.permission.ACTIVITY_RECOGNITION, true);
        facade.grantNextPermissionRequest();

        Assert.assertEquals(1, facade.startAttempts);
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 30)
    public void android11BackgroundPermissionUsesSettingsThenDeniesOnResumeWhenStillMissing() {
        Config config = Config.getDefault();
        TestPermissionFacade facade = new TestPermissionFacade(context, config);
        final AtomicInteger geofenceErrors = new AtomicInteger(0);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                Assert.fail("Unexpected geofence start success");
            }

            @Override
            public void onError(PluginException exception) {
                geofenceErrors.incrementAndGet();
            }
        });

        Assert.assertEquals(Arrays.asList(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), facade.nextRequestedPermissions());

        facade.setPermission(Manifest.permission.ACCESS_COARSE_LOCATION, true);
        facade.setPermission(Manifest.permission.ACCESS_FINE_LOCATION, true);
        facade.grantNextPermissionRequest();

        Assert.assertEquals(1, facade.settingsOpenCount);
        Assert.assertEquals(0, facade.startAttempts);

        facade.resume();

        Assert.assertEquals(1, geofenceErrors.get());
        Assert.assertEquals(0, facade.startAttempts);
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 30)
    public void android11BackgroundPermissionStartsAfterGrantingInSettingsAndResuming() {
        Config config = Config.getDefault();
        TestPermissionFacade facade = new TestPermissionFacade(context, config);

        facade.start();
        Assert.assertEquals(Arrays.asList(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), facade.nextRequestedPermissions());

        facade.setPermission(Manifest.permission.ACCESS_COARSE_LOCATION, true);
        facade.setPermission(Manifest.permission.ACCESS_FINE_LOCATION, true);
        facade.grantNextPermissionRequest();

        Assert.assertEquals(1, facade.settingsOpenCount);
        facade.setPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION, true);
        facade.resume();

        Assert.assertEquals(1, facade.startAttempts);
    }

    @Test
    public void geofencePermissionFlowTimesOutWhenPermissionResultNeverReturns() {
        Config config = Config.getDefault();
        TestPermissionFacade facade = new TestPermissionFacade(context, config, 1000L);
        final AtomicInteger geofenceErrors = new AtomicInteger(0);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                Assert.fail("Unexpected geofence start success");
            }

            @Override
            public void onError(PluginException exception) {
                geofenceErrors.incrementAndGet();
            }
        });

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);

        Assert.assertEquals(1, geofenceErrors.get());
        Assert.assertEquals(0, facade.startAttempts);
    }

    private static class TestPermissionFacade extends BackgroundGeolocationFacade {
        private final ArrayDeque<PermissionRequest> pendingPermissionRequests = new ArrayDeque<>();
        private final Map<String, Boolean> permissionState = new HashMap<>();
        private final Config config;
        private final long permissionTimeoutMs;

        int settingsOpenCount = 0;
        int startAttempts = 0;

        TestPermissionFacade(Context context, Config config) {
            this(context, config, 10 * 60 * 1000L);
        }

        TestPermissionFacade(Context context, Config config, long permissionTimeoutMs) {
            super(context, null);
            this.config = config;
            this.permissionTimeoutMs = permissionTimeoutMs;
        }

        @Override
        protected long getPermissionRequestTimeoutMs() {
            return permissionTimeoutMs;
        }

        @Override
        public synchronized Config getConfig() {
            return config;
        }

        @Override
        protected void requestPermissions(List<String> permissions, PermissionManager.PermissionRequestListener listener) {
            pendingPermissionRequests.add(new PermissionRequest(permissions, listener));
        }

        @Override
        protected boolean hasPermission(String permission) {
            return Boolean.TRUE.equals(permissionState.get(permission));
        }

        @Override
        protected void openApplicationSettingsForBackgroundLocationPermission() {
            settingsOpenCount += 1;
        }

        @Override
        protected void requestPostNotificationPermission() {
            // noop for tests
        }

        @Override
        protected void startBackgroundService(long requestGeneration) {
            startAttempts += 1;
        }

        void setPermission(String permission, boolean granted) {
            permissionState.put(permission, granted);
        }

        List<String> nextRequestedPermissions() {
            PermissionRequest request = pendingPermissionRequests.peek();
            Assert.assertNotNull("Expected a pending permission request", request);
            return request.permissions;
        }

        void grantNextPermissionRequest() {
            PermissionRequest request = pendingPermissionRequests.poll();
            Assert.assertNotNull("Expected a pending permission request", request);
            request.listener.onPermissionGranted();
        }

        @SuppressWarnings("unused")
        void denyNextPermissionRequest() {
            PermissionRequest request = pendingPermissionRequests.poll();
            Assert.assertNotNull("Expected a pending permission request", request);
            request.listener.onPermissionDenied((DeniedPermissions) null);
        }
    }

    private static class PermissionRequest {
        final List<String> permissions;
        final PermissionManager.PermissionRequestListener listener;

        PermissionRequest(List<String> permissions, PermissionManager.PermissionRequestListener listener) {
            this.permissions = permissions;
            this.listener = listener;
        }
    }
}
