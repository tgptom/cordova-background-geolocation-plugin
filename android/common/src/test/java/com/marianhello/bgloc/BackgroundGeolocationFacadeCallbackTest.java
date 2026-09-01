package com.marianhello.bgloc;

import android.content.Context;

import com.marianhello.bgloc.service.LocationServiceImpl;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class BackgroundGeolocationFacadeCallbackTest {
    private BackgroundGeolocationFacade facade;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.application.getApplicationContext();
        context.getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE).edit().clear().commit();
        facade = new BackgroundGeolocationFacade(context, null);
    }

    @Test
    public void geofenceStartSuccessCallbackInvokedAtMostOnce() throws Exception {
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        BackgroundGeolocationFacade.StartRequestCallback callback = new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                successCount.incrementAndGet();
            }

            @Override
            public void onError(PluginException exception) {
                errorCount.incrementAndGet();
            }
        };

        invokePrivate(
                "setPendingGeofenceStartCallback",
                new Class<?>[]{BackgroundGeolocationFacade.StartRequestCallback.class, long.class},
                callback,
                11L
        );
        resolveWithResult(new TrackingLifecycleCoordinator.LifecycleActionResult(
                LocationServiceImpl.MSG_ON_SERVICE_STARTED,
                11L,
                11L,
                TrackingOwnershipStore.OWNER_GEOFENCE,
                false,
                false,
                false
        ));
        resolveWithResult(new TrackingLifecycleCoordinator.LifecycleActionResult(
                LocationServiceImpl.MSG_ON_SERVICE_STARTED,
                11L,
                11L,
                TrackingOwnershipStore.OWNER_GEOFENCE,
                false,
                false,
                false
        ));

        Assert.assertEquals(1, successCount.get());
        Assert.assertEquals(0, errorCount.get());
    }

    @Test
    public void staleOrUnrelatedLifecycleResultDoesNotResolvePendingGeofenceCallback() throws Exception {
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        BackgroundGeolocationFacade.StartRequestCallback callback = new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                successCount.incrementAndGet();
            }

            @Override
            public void onError(PluginException exception) {
                errorCount.incrementAndGet();
            }
        };

        invokePrivate(
                "setPendingGeofenceStartCallback",
                new Class<?>[]{BackgroundGeolocationFacade.StartRequestCallback.class, long.class},
                callback,
                22L
        );

        resolveWithResult(new TrackingLifecycleCoordinator.LifecycleActionResult(
                LocationServiceImpl.MSG_ON_SERVICE_STARTED,
                21L,
                0L,
                TrackingOwnershipStore.OWNER_NONE,
                true,
                true,
                false
        ));
        resolveWithResult(new TrackingLifecycleCoordinator.LifecycleActionResult(
                LocationServiceImpl.MSG_ON_SERVICE_STARTED,
                22L,
                22L,
                TrackingOwnershipStore.OWNER_MANUAL,
                false,
                false,
                false
        ));
        resolveWithResult(new TrackingLifecycleCoordinator.LifecycleActionResult(
                LocationServiceImpl.MSG_ON_SERVICE_STARTED,
                23L,
                23L,
                TrackingOwnershipStore.OWNER_GEOFENCE,
                false,
                false,
                false
        ));
        resolveWithResult(new TrackingLifecycleCoordinator.LifecycleActionResult(
                LocationServiceImpl.MSG_ON_SERVICE_STARTED,
                0L,
                0L,
                TrackingOwnershipStore.OWNER_NONE,
                true,
                false,
                false
        ));

        Assert.assertEquals(0, successCount.get());
        Assert.assertEquals(0, errorCount.get());
    }

    @Test
    public void geofenceStartErrorCallbackInvokedAtMostOnce() throws Exception {
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        BackgroundGeolocationFacade.StartRequestCallback callback = new BackgroundGeolocationFacade.StartRequestCallback() {
            @Override
            public void onSuccess() {
                successCount.incrementAndGet();
            }

            @Override
            public void onError(PluginException exception) {
                errorCount.incrementAndGet();
            }
        };

        invokePrivate(
                "setPendingGeofenceStartCallback",
                new Class<?>[]{BackgroundGeolocationFacade.StartRequestCallback.class, long.class},
                callback,
                9L
        );
        invokePrivate("notifyGeofenceStartError", new Class<?>[]{PluginException.class}, new PluginException("failed", PluginException.START_FAILED_ERROR));
        invokePrivate("notifyGeofenceStartError", new Class<?>[]{PluginException.class}, new PluginException("failed", PluginException.START_FAILED_ERROR));

        Assert.assertEquals(0, successCount.get());
        Assert.assertEquals(1, errorCount.get());
    }

    private Object invokePrivate(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = BackgroundGeolocationFacade.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(facade, args);
    }

    private void resolveWithResult(TrackingLifecycleCoordinator.LifecycleActionResult result) throws Exception {
        invokePrivate(
                "resolvePendingGeofenceStartSuccess",
                new Class<?>[]{TrackingLifecycleCoordinator.LifecycleActionResult.class},
                result
        );
    }
}
