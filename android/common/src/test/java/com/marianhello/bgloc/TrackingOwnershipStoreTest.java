package com.marianhello.bgloc;

import android.content.Context;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class TrackingOwnershipStoreTest {
    private TrackingOwnershipStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.application.getApplicationContext();
        context.getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE).edit().clear().commit();
        store = new TrackingOwnershipStore(context);
    }

    @Test
    public void pendingStartCommitsOnlyOnServiceStartedAck() {
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());

        store.commitOwnerOnServiceStarted();

        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
    }

    @Test
    public void pendingStartRollsBackWhenServiceNotStarted() {
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE);

        store.reconcileWithServiceState(false);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
    }

    @Test
    public void processRestartCommitsPendingStartWhenServiceAlreadyRunning() {
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_MANUAL);

        int owner = store.reconcileWithServiceState(true);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, owner);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
    }

    @Test
    public void manualOwnerIsPreservedWhenServiceStopped() {
        store.setOwner(TrackingOwnershipStore.OWNER_MANUAL);

        int owner = store.reconcileWithServiceState(false);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, owner);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, store.getOwner());
    }

    @Test
    public void geofenceOwnerIsClearedWhenServiceStopped() {
        store.setOwner(TrackingOwnershipStore.OWNER_GEOFENCE);

        int owner = store.reconcileWithServiceState(false);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, owner);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
    }

    @Test
    public void stopAckClearsOwnerAndPendingState() {
        store.setOwner(TrackingOwnershipStore.OWNER_GEOFENCE);
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE);
        store.setPendingStopOwner(TrackingOwnershipStore.OWNER_GEOFENCE);

        store.clearOwnerOnServiceStopped();

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStopOwner());
    }
}
