package com.marianhello.bgloc;

import android.content.Context;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class GeofenceTransitionHandlerTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.application.getApplicationContext();
        context.getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void receiverPathReturnsPromptlyWithoutBlockingWait() {
        TrackingOwnershipStore store = new TrackingOwnershipStore(context);
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_MANUAL, System.currentTimeMillis() + 15000L);

        long startedAtNanos = System.nanoTime();
        GeofenceTransitionHandler.onGeofenceTransition(
                context,
                GeofenceOwnershipEvaluator.TRANSITION_ENTER,
                true
        );
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;

        Assert.assertTrue("Handler should return promptly without waiting for lifecycle ack", elapsedMs < 250L);
        Assert.assertEquals(
                "Pending manual ownership should retain precedence while startup is in flight",
                TrackingOwnershipStore.OWNER_MANUAL,
                store.getPendingStartOwner()
        );
    }
}
