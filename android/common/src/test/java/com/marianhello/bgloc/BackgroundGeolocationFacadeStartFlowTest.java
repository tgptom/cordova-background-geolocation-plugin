package com.marianhello.bgloc;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import com.intentfilter.androidpermissions.PermissionManager;
import com.intentfilter.androidpermissions.models.DeniedPermissions;
import com.marianhello.bgloc.service.LocationServiceImpl;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.util.ArrayDeque;
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

    @Test
    public void manualPermissionGrantPromotesSameGenerationToServiceAckPhase() {
        TestFacade facade = new TestFacade(context, true, false);

        facade.start();
        long permissionPendingGeneration = coordinator.getPendingStartGeneration();
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getPendingStartOwner());

        facade.grantLocationPermission();

        Assert.assertEquals(1, facade.startAttempts);
        Assert.assertEquals(permissionPendingGeneration, facade.lastStartRequestGeneration);
        Assert.assertEquals(permissionPendingGeneration, coordinator.getPendingStartGeneration());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getPendingStartOwner());
    }

    @Test
    public void lateStaleStartAckDoesNotResolveNewerGeofenceCallback() {
        TestFacade facade = new TestFacade(context, true, false);
        final AtomicInteger timeoutErrorCount = new AtomicInteger(0);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                Assert.fail("Unexpected success callback");
            }

            @Override
            public void onError(PluginException exception) {
                timeoutErrorCount.incrementAndGet();
            }
        });
        facade.grantLocationPermission();
        long staleGeneration = coordinator.getPendingStartGeneration();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);
        Assert.assertEquals(1, timeoutErrorCount.get());

        final AtomicInteger newerSuccessCount = new AtomicInteger(0);
        final AtomicInteger newerErrorCount = new AtomicInteger(0);
        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                newerSuccessCount.incrementAndGet();
            }

            @Override
            public void onError(PluginException exception) {
                newerErrorCount.incrementAndGet();
            }
        });
        facade.grantLocationPermission();
        long newerGeneration = coordinator.getPendingStartGeneration();
        Assert.assertTrue(newerGeneration > staleGeneration);

        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);

        Assert.assertEquals(0, newerSuccessCount.get());
        Assert.assertEquals(0, newerErrorCount.get());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
        Assert.assertEquals(newerGeneration, coordinator.getPendingStartGeneration());
    }

    @Test
    public void unrelatedOrDuplicateStartEventsCannotResolvePendingGeofenceCallback() {
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
        facade.grantLocationPermission();
        long generation = coordinator.getPendingStartGeneration();
        Assert.assertTrue(generation > 0L);

        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, 0L);
        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, generation + 1L);

        Assert.assertEquals(0, successCount.get());
        Assert.assertEquals(0, errorCount.get());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());

        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, generation);
        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, generation);
        Assert.assertEquals(1, successCount.get());
        Assert.assertEquals(0, errorCount.get());
    }

    @Test
    public void runningStaleGenerationQueuesAndReplaysNewerGeofenceRequest() {
        TestFacade facade = new TestFacade(context, true, false, true);
        final AtomicInteger staleErrorCount = new AtomicInteger(0);
        final AtomicInteger newerSuccessCount = new AtomicInteger(0);
        final AtomicInteger newerErrorCount = new AtomicInteger(0);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                Assert.fail("Unexpected stale success callback");
            }

            @Override
            public void onError(PluginException exception) {
                staleErrorCount.incrementAndGet();
            }
        });
        facade.grantLocationPermission();
        long staleGeneration = coordinator.getPendingStartGeneration();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);
        Assert.assertEquals(1, staleErrorCount.get());

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                newerSuccessCount.incrementAndGet();
            }

            @Override
            public void onError(PluginException exception) {
                newerErrorCount.incrementAndGet();
            }
        });
        facade.grantLocationPermission();
        long newerGeneration = coordinator.getPendingStartGeneration();

        facade.deliverNextStartCommand();
        facade.deliverNextStartCommand();
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
        Assert.assertEquals(newerGeneration, coordinator.getPendingStartGeneration());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);
        Assert.assertEquals(0, newerSuccessCount.get());
        Assert.assertEquals(0, newerErrorCount.get());

        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, 0L);
        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);

        long stopGeneration = coordinator.getPendingStopGeneration();
        facade.deliverStopAcknowledgement(stopGeneration);
        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, stopGeneration + 1L);
        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, stopGeneration);

        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, newerGeneration + 1L);
        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, newerGeneration);

        Assert.assertEquals(1, newerSuccessCount.get());
        Assert.assertEquals(0, newerErrorCount.get());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getOwner());
    }

    @Test
    public void runningStaleGenerationQueuesAndReplaysNewerManualRequest() {
        TestFacade facade = new TestFacade(context, true, false, true);

        facade.startForGeofence(new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                Assert.fail("Unexpected stale geofence success callback");
            }

            @Override
            public void onError(PluginException exception) {
                // expected timeout
            }
        });
        facade.grantLocationPermission();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);

        facade.start();
        facade.grantLocationPermission();
        long newerGeneration = coordinator.getPendingStartGeneration();

        facade.deliverNextStartCommand();
        facade.deliverNextStartCommand();
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getPendingStartOwner());
        Assert.assertEquals(newerGeneration, coordinator.getPendingStartGeneration());

        long stopGeneration = coordinator.getPendingStopGeneration();
        facade.deliverStopAcknowledgement(stopGeneration);
        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, stopGeneration + 1L);
        dispatchLifecycleBroadcast(LocationServiceImpl.MSG_ON_SERVICE_STARTED, newerGeneration);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStartOwner());
    }

    private void dispatchLifecycleBroadcast(int action, long requestGeneration) {
        Intent intent = new Intent(LocationServiceImpl.ACTION_BROADCAST);
        intent.putExtra("action", action);
        intent.putExtra("requestGeneration", requestGeneration);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private static class TestFacade extends BackgroundGeolocationFacade {
        private PermissionManager.PermissionRequestListener locationPermissionListener;
        private final boolean geofenceConfigValid;
        private final boolean throwOnStart;
        private final boolean emulateRealServiceStartBehavior;
        private boolean simulatedServiceRunning = false;
        private final ArrayDeque<Long> startCommandQueue = new ArrayDeque<>();
        int startAttempts = 0;
        long lastStartRequestGeneration = 0L;

        TestFacade(Context context, boolean geofenceConfigValid, boolean throwOnStart) {
            this(context, geofenceConfigValid, throwOnStart, false);
        }

        TestFacade(Context context, boolean geofenceConfigValid, boolean throwOnStart, boolean emulateRealServiceStartBehavior) {
            super(context, null);
            this.geofenceConfigValid = geofenceConfigValid;
            this.throwOnStart = throwOnStart;
            this.emulateRealServiceStartBehavior = emulateRealServiceStartBehavior;
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
        protected void startBackgroundService(long requestGeneration) {
            startAttempts += 1;
            lastStartRequestGeneration = requestGeneration;
            if (throwOnStart) {
                throw new RuntimeException("Simulated service start dispatch failure");
            }
            if (emulateRealServiceStartBehavior) {
                startCommandQueue.add(requestGeneration);
            }
        }

        void deliverNextStartCommand() {
            Long requestGeneration = startCommandQueue.poll();
            if (requestGeneration == null) {
                return;
            }
            if (simulatedServiceRunning) {
                return;
            }
            simulatedServiceRunning = true;
            Intent intent = new Intent(LocationServiceImpl.ACTION_BROADCAST);
            intent.putExtra("action", LocationServiceImpl.MSG_ON_SERVICE_STARTED);
            intent.putExtra("requestGeneration", requestGeneration);
            LocalBroadcastManager.getInstance(RuntimeEnvironment.application.getApplicationContext())
                    .sendBroadcast(intent);
        }

        void deliverStopAcknowledgement(long requestGeneration) {
            simulatedServiceRunning = false;
            Intent intent = new Intent(LocationServiceImpl.ACTION_BROADCAST);
            intent.putExtra("action", LocationServiceImpl.MSG_ON_SERVICE_STOPPED);
            intent.putExtra("requestGeneration", requestGeneration);
            LocalBroadcastManager.getInstance(RuntimeEnvironment.application.getApplicationContext())
                    .sendBroadcast(intent);
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
