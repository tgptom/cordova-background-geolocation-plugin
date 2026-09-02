package com.marianhello.bgloc;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BackgroundGeolocationFacadeStopForGeofenceTest {
    @Test
    public void alreadyStoppedGeofenceTrackingIsIdempotentNoop() {
        TrackingOwnershipStore.ReconciledState state = new TrackingOwnershipStore.ReconciledState(
                TrackingOwnershipStore.OWNER_NONE,
                TrackingOwnershipStore.OWNER_NONE,
                TrackingOwnershipStore.OWNER_NONE,
                false
        );

        Assert.assertEquals(
                BackgroundGeolocationFacade.STOP_FOR_GEOFENCE_NOOP,
                BackgroundGeolocationFacade.evaluateStopForGeofenceOutcome(state)
        );
    }

    @Test
    public void geofenceOwnedRunningServiceRequiresStop() {
        TrackingOwnershipStore.ReconciledState state = new TrackingOwnershipStore.ReconciledState(
                TrackingOwnershipStore.OWNER_GEOFENCE,
                TrackingOwnershipStore.OWNER_NONE,
                TrackingOwnershipStore.OWNER_NONE,
                true
        );

        Assert.assertEquals(
                BackgroundGeolocationFacade.STOP_FOR_GEOFENCE_REQUEST_STOP,
                BackgroundGeolocationFacade.evaluateStopForGeofenceOutcome(state)
        );
    }

    @Test
    public void manualOwnerTriggersOwnershipConflictOutcome() {
        TrackingOwnershipStore.ReconciledState state = new TrackingOwnershipStore.ReconciledState(
                TrackingOwnershipStore.OWNER_MANUAL,
                TrackingOwnershipStore.OWNER_NONE,
                TrackingOwnershipStore.OWNER_NONE,
                true
        );

        Assert.assertEquals(
                BackgroundGeolocationFacade.STOP_FOR_GEOFENCE_OWNERSHIP_CONFLICT,
                BackgroundGeolocationFacade.evaluateStopForGeofenceOutcome(state)
        );
    }
}
