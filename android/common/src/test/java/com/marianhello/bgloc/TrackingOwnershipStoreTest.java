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
        long now = System.currentTimeMillis();
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE, now + 15000L);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());

        store.onServiceStartedAcknowledged();

        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
    }

    @Test
    public void pendingManualStartRetainedBeforeDeadlineWhenServiceStopped() {
        long now = System.currentTimeMillis();
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_MANUAL, now + 15000L);

        TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(false, now + 5000L);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, state.pendingStartOwner);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
    }

    @Test
    public void pendingStartRollsBackAfterDeadlineWhenServiceStopped() {
        long now = System.currentTimeMillis();
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE, now + 100L);

        TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(false, now + 1000L);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, state.pendingStartOwner);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
    }

    @Test
    public void processRestartCommitsPendingStartWhenServiceAlreadyRunning() {
        long now = System.currentTimeMillis();
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_MANUAL, now + 15000L);
        RuntimeEnvironment.application
                .getApplicationContext()
                .getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("service_started", true)
                .commit();

        TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(true, now + 1L);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, state.owner);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
    }

    @Test
    public void lateStartAcknowledgementAfterTimeoutDoesNotCreateInvalidOwnership() {
        long now = System.currentTimeMillis();
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE, now + 100L);

        store.reconcileWithServiceState(false, now + 1000L);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());

        store.onServiceStartedAcknowledged();

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
    }

    @Test
    public void duplicateServiceStartedAckIsIdempotent() {
        long now = System.currentTimeMillis();
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE, now + 15000L);

        store.onServiceStartedAcknowledged();
        store.onServiceStartedAcknowledged();

        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
    }

    @Test
    public void manualOwnerIsPreservedWhenServiceStopped() {
        store.setOwner(TrackingOwnershipStore.OWNER_MANUAL);

        TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(false, System.currentTimeMillis());

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, state.owner);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, store.getOwner());
    }

    @Test
    public void geofenceOwnerIsClearedWhenServiceStoppedWithoutPendingState() {
        store.setOwner(TrackingOwnershipStore.OWNER_GEOFENCE);

        TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(false, System.currentTimeMillis());

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, state.owner);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
    }

    @Test
    public void stalePersistedRunningGeofenceOwnerIsReconciledWhenServiceNotRunning() {
        store.setOwner(TrackingOwnershipStore.OWNER_GEOFENCE);
        RuntimeEnvironment.application
                .getApplicationContext()
                .getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("service_started", true)
                .commit();

        TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(false, System.currentTimeMillis());

        Assert.assertFalse(state.serviceStarted);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
    }

    @Test
    public void stopAckClearsOwnerAndPendingState() {
        long now = System.currentTimeMillis();
        store.setOwner(TrackingOwnershipStore.OWNER_GEOFENCE);
        store.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE, now + 15000L);
        store.setPendingStopOwner(TrackingOwnershipStore.OWNER_GEOFENCE, now + 15000L);

        store.onServiceStoppedAcknowledged();

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStartOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStopOwner());
    }

    @Test
    public void duplicateServiceStoppedAckIsIdempotent() {
        store.setOwner(TrackingOwnershipStore.OWNER_GEOFENCE);

        store.onServiceStoppedAcknowledged();
        store.onServiceStoppedAcknowledged();

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, store.getPendingStopOwner());
    }
}
