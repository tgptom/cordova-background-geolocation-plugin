package com.marianhello.bgloc;

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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class BackgroundGeolocationFacadeStartFlowTest {
    private Context context;
    private TrackingLifecycleCoordinator coordinator;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.application.getApplicationContext();
        context.getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE).edit().clear().commit();
        coordinator = TrackingLifecycleCoordinator.getInstance(context);
        coordinator.clearPendingStart();
        coordinator.clearPendingStop();
        coordinator.clearOwner();
    }

    @Test
    public void geofenceStartDoesNotTimeoutBeforePermissionGrant() {
        TestFacade facade = new TestFacade(context, true, false);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                successCount.incrementAndGet();
            }

            @Override
            public void onError(PluginException exception) {
                errorCount.incrementAndGet();
            }
        });

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);

        Assert.assertEquals(0, facade.startAttempts);
        Assert.assertEquals(0, successCount.get());
        Assert.assertEquals(0, errorCount.get());

        facade.grantLocationPermission();

        Assert.assertEquals(1, facade.startAttempts);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
    }

    @Test
    public void failedGeofenceStartDoesNotDispatchServiceStart() {
        TestFacade facade = new TestFacade(context, false, false);
        final AtomicInteger errorCount = new AtomicInteger(0);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                Assert.fail("Unexpected success callback");
            }

            @Override
            public void onError(PluginException exception) {
                errorCount.incrementAndGet();
            }
        });

        facade.grantLocationPermission();

        Assert.assertEquals(1, errorCount.get());
        Assert.assertEquals(0, facade.startAttempts);
    }

    @Test
    public void geofencePermissionDeniedResolvesCallbackExactlyOnce() {
        TestFacade facade = new TestFacade(context, true, false);
        final AtomicInteger errorCount = new AtomicInteger(0);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                Assert.fail("Unexpected success callback");
            }

            @Override
            public void onError(PluginException exception) {
                errorCount.incrementAndGet();
            }
        });

        facade.denyLocationPermission();
        facade.denyLocationPermission();

        Assert.assertEquals(1, errorCount.get());
        Assert.assertEquals(0, facade.startAttempts);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStartOwner());
    }

    @Test
    public void geofenceDispatchExceptionClearsPendingGenerationAndReturnsError() {
        TestFacade facade = new TestFacade(context, true, true);
        final AtomicInteger errorCount = new AtomicInteger(0);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                Assert.fail("Unexpected success callback");
            }

            @Override
            public void onError(PluginException exception) {
                errorCount.incrementAndGet();
            }
        });

        facade.grantLocationPermission();

        Assert.assertEquals(1, facade.startAttempts);
        Assert.assertEquals(1, errorCount.get());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStartOwner());
        Assert.assertEquals(0L, coordinator.getPendingStartGeneration());
    }

    private static class TestFacade extends BackgroundGeolocationFacade {
        private PermissionManager.PermissionRequestListener locationPermissionListener;
        private final boolean geofenceConfigValid;
        private final boolean throwOnStart;
        int startAttempts = 0;

        TestFacade(Context context, boolean geofenceConfigValid, boolean throwOnStart) {
            super(context, null);
            this.geofenceConfigValid = geofenceConfigValid;
            this.throwOnStart = throwOnStart;
        }

        @Override
        protected void requestLocationPermissions(PermissionManager.PermissionRequestListener listener) {
            this.locationPermissionListener = listener;
        }

        @Override
        protected void requestPostNotificationPermission() {
            // noop for tests
        }

        @Override
        protected boolean isGeofenceStartConfigurationValid() {
            return geofenceConfigValid;
        }

        @Override
        protected void startBackgroundService() {
            startAttempts += 1;
            if (throwOnStart) {
                throw new RuntimeException("Simulated service start dispatch failure");
            }
        }

        void grantLocationPermission() {
            Assert.assertNotNull("Location permission listener was not registered", locationPermissionListener);
            locationPermissionListener.onPermissionGranted();
        }

        void denyLocationPermission() {
            Assert.assertNotNull("Location permission listener was not registered", locationPermissionListener);
            locationPermissionListener.onPermissionDenied((DeniedPermissions) null);
        }
    }
}
