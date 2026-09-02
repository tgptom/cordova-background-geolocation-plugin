package com.tenforwardconsulting.bgloc.cordova;

import android.content.Context;
import android.os.Looper;

import com.marianhello.bgloc.BackgroundGeolocationFacade;
import com.marianhello.bgloc.PluginException;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class BackgroundGeolocationPluginStopCallbackTest {
    private BackgroundGeolocationPlugin plugin;

    @Before
    public void setUp() throws Exception {
        plugin = new BackgroundGeolocationPlugin();
        setPrivateField(plugin, "logger", LoggerFactory.getLogger(BackgroundGeolocationPlugin.class));
        setPrivateField(plugin, "facade", new NoopFacade(RuntimeEnvironment.application.getApplicationContext()));
    }

    @Test
    public void concurrentStopCallbacksShareFirstTimeoutDeadline() throws Exception {
        RecordingCallbackContext first = new RecordingCallbackContext();
        RecordingCallbackContext second = new RecordingCallbackContext();

        invokeAddPendingStopCallback(first);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);
        invokeAddPendingStopCallback(second);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS);

        Assert.assertEquals(1, first.errorCount.get());
        Assert.assertEquals(1, second.errorCount.get());
    }

    @Test
    public void stopAcknowledgementSettlesAllPendingCallbacksOnce() throws Exception {
        RecordingCallbackContext first = new RecordingCallbackContext();
        RecordingCallbackContext second = new RecordingCallbackContext();

        invokeAddPendingStopCallback(first);
        invokeAddPendingStopCallback(second);

        plugin.onServiceStatusChanged(BackgroundGeolocationFacade.SERVICE_STOPPED);
        plugin.onServiceStatusChanged(BackgroundGeolocationFacade.SERVICE_STOPPED);

        Assert.assertEquals(1, first.successCount.get());
        Assert.assertEquals(1, second.successCount.get());
        Assert.assertEquals(0, first.errorCount.get());
        Assert.assertEquals(0, second.errorCount.get());
    }

    @Test
    public void timeoutSettlesAllCallbacksOnceAndLateAckDoesNotResettle() throws Exception {
        RecordingCallbackContext first = new RecordingCallbackContext();
        RecordingCallbackContext second = new RecordingCallbackContext();

        invokeAddPendingStopCallback(first);
        invokeAddPendingStopCallback(second);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS);

        plugin.onServiceStatusChanged(BackgroundGeolocationFacade.SERVICE_STOPPED);

        Assert.assertEquals(1, first.errorCount.get());
        Assert.assertEquals(1, second.errorCount.get());
        Assert.assertEquals(0, first.successCount.get());
        Assert.assertEquals(0, second.successCount.get());
    }

    @Test
    public void onDestroyDrainsPendingCallbacksAndClearsTimeoutDeterministically() throws Exception {
        RecordingCallbackContext callback = new RecordingCallbackContext();
        invokeAddPendingStopCallback(callback);

        plugin.onDestroy();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS);

        Assert.assertEquals(1, callback.errorCount.get());
        Assert.assertEquals(0, callback.successCount.get());
        Assert.assertTrue(getPendingStopCallbacks().isEmpty());
        Assert.assertNull(getPrivateField(plugin, "pendingStopTimeoutRunnable"));
    }

    private void invokeAddPendingStopCallback(CallbackContext callbackContext) throws Exception {
        Method method = BackgroundGeolocationPlugin.class.getDeclaredMethod(
                "addPendingStopCallback",
                CallbackContext.class
        );
        method.setAccessible(true);
        method.invoke(plugin, callbackContext);
    }

    @SuppressWarnings("unchecked")
    private List<CallbackContext> getPendingStopCallbacks() throws Exception {
        return (List<CallbackContext>) getPrivateField(plugin, "pendingStopCallbackContexts");
    }

    private static Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class RecordingCallbackContext extends CallbackContext {
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        RecordingCallbackContext() {
            super("test", null);
        }

        @Override
        public void success() {
            successCount.incrementAndGet();
        }

        @Override
        public void sendPluginResult(PluginResult pluginResult) {
            if (pluginResult == null) {
                return;
            }
            if (pluginResult.getStatus() == PluginResult.Status.OK.ordinal()) {
                successCount.incrementAndGet();
                return;
            }
            if (pluginResult.getStatus() == PluginResult.Status.ERROR.ordinal()) {
                errorCount.incrementAndGet();
            }
        }
    }

    private static class NoopFacade extends BackgroundGeolocationFacade {
        NoopFacade(Context context) {
            super(context, null);
        }

        @Override
        public void destroy() {
            // no-op for tests
        }
    }
}
