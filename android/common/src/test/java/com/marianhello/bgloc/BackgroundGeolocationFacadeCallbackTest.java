package com.marianhello.bgloc;

import android.content.Context;

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

        invokePrivate("setPendingGeofenceStartCallback", new Class<?>[]{BackgroundGeolocationFacade.StartRequestCallback.class}, callback);
        invokePrivate("resolvePendingGeofenceStartSuccess", new Class<?>[]{});
        invokePrivate("resolvePendingGeofenceStartSuccess", new Class<?>[]{});

        Assert.assertEquals(1, successCount.get());
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

        invokePrivate("setPendingGeofenceStartCallback", new Class<?>[]{BackgroundGeolocationFacade.StartRequestCallback.class}, callback);
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
}
