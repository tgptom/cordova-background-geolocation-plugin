package com.marianhello.bgloc;

import android.Manifest;
import android.accounts.Account;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.github.jparkie.promise.Promise;
import com.intentfilter.androidpermissions.PermissionManager;
import com.intentfilter.androidpermissions.models.DeniedPermissions;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.bgloc.provider.LocationProvider;
import com.marianhello.bgloc.service.LocationService;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceProxy;
import com.marianhello.bgloc.data.LocationTransform;
import com.marianhello.bgloc.sync.AccountHelper;
import com.marianhello.bgloc.sync.NotificationHelper;
import com.marianhello.bgloc.sync.SyncService;
import com.marianhello.logging.DBLogReader;
import com.marianhello.logging.LogEntry;
import com.marianhello.logging.LoggerManager;
import com.marianhello.logging.UncaughtExceptionLogger;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.event.Level;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class BackgroundGeolocationFacade {

    public static final int SERVICE_STARTED = 1;
    public static final int SERVICE_STOPPED = 0;
    public static final int AUTHORIZATION_AUTHORIZED = 1;
    public static final int AUTHORIZATION_DENIED = 0;

    public static final String[] PERMISSIONS = {
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
    };
    private static final String[] BACKGROUND_PERMISSION = {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
    };
    private static final String[] ACTIVITY_RECOGNITION_PERMISSION = {
            Manifest.permission.ACTIVITY_RECOGNITION
    };
    private static final String[] POST_NOTIFICATIONS_PERMISSION = {
            Manifest.permission.POST_NOTIFICATIONS
    };

    private boolean mServiceBroadcastReceiverRegistered = false;
    private boolean mLocationModeChangeReceiverRegistered = false;
    private boolean mIsPaused = false;
    private static final long PERMISSION_PENDING_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long START_ACK_TIMEOUT_MS = 15000L;
    private static final long STOP_ACK_TIMEOUT_MS = 15000L;

    private Config mConfig;
    private final Context mContext;
    private final PluginDelegate mDelegate;
    private final LocationService mService;
    private final TrackingLifecycleCoordinator mLifecycleCoordinator;
    private final Handler mPermissionHandler;

    private BackgroundLocation mStationaryLocation;
    private StartRequestCallback mPendingGeofenceStartCallback;
    private long mPendingGeofenceStartGeneration;

    private org.slf4j.Logger logger;
    static final int STOP_FOR_GEOFENCE_NOOP = 0;
    static final int STOP_FOR_GEOFENCE_REQUEST_STOP = 1;
    static final int STOP_FOR_GEOFENCE_OWNERSHIP_CONFLICT = 2;

    public interface StartRequestCallback {
        void onSuccess();
        void onError(PluginException exception);
    }

    public BackgroundGeolocationFacade(Context context, PluginDelegate delegate) {
        mContext = context;
        mDelegate = delegate;
        mService = new LocationServiceProxy(context);
        mLifecycleCoordinator = TrackingLifecycleCoordinator.getInstance(context);
        mPermissionHandler = new Handler(Looper.getMainLooper());

        UncaughtExceptionLogger.register(context.getApplicationContext());

        logger = LoggerManager.getLogger(BackgroundGeolocationFacade.class);
        updateDbLogging(getConfig());

        logger.info("Initializing plugin");

        NotificationHelper.registerAllChannels(getApplicationContext());
    }

    private BroadcastReceiver locationModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logger.debug("Authorization has changed");
            if (mDelegate != null) {
                mDelegate.onAuthorizationChanged(getAuthorizationStatus());
            }
        }
    };

    private BroadcastReceiver serviceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            if (bundle == null) {
                return;
            }
            int action = bundle.getInt("action");

            switch (action) {
                case LocationServiceImpl.MSG_ON_LOCATION: {
                    logger.debug("Received MSG_ON_LOCATION");
                    bundle.setClassLoader(LocationServiceImpl.class.getClassLoader());
                    BackgroundLocation location = (BackgroundLocation) bundle.getParcelable("payload");
                    if (mDelegate != null) {
                        mDelegate.onLocationChanged(location);
                    }
                    return;
                }

                case LocationServiceImpl.MSG_ON_STATIONARY: {
                    logger.debug("Received MSG_ON_STATIONARY");
                    bundle.setClassLoader(LocationServiceImpl.class.getClassLoader());
                    BackgroundLocation location = (BackgroundLocation) bundle.getParcelable("payload");
                    mStationaryLocation = location;
                    if (mDelegate != null) {
                        mDelegate.onStationaryChanged(location);
                    }
                    return;
                }

                case LocationServiceImpl.MSG_ON_ACTIVITY: {
                    logger.debug("Received MSG_ON_ACTIVITY");
                    bundle.setClassLoader(LocationServiceImpl.class.getClassLoader());
                    BackgroundActivity activity = (BackgroundActivity) bundle.getParcelable("payload");
                    if (mDelegate != null) {
                        mDelegate.onActivityChanged(activity);
                    }
                    return;
                }

                case LocationServiceImpl.MSG_ON_ERROR: {
                    logger.debug("Received MSG_ON_ERROR");
                    Bundle errorBundle = bundle.getBundle("payload");
                    Integer errorCode = errorBundle.getInt("code");
                    String errorMessage = errorBundle.getString("message");
                    if (mDelegate != null) {
                        mDelegate.onError(new PluginException(errorMessage, errorCode));
                    }
                    return;
                }

                case LocationServiceImpl.MSG_ON_SERVICE_STARTED: {
                    logger.debug("Received MSG_ON_SERVICE_STARTED");
                    if (mDelegate != null) {
                        mDelegate.onServiceStatusChanged(SERVICE_STARTED);
                    }
                    return;
                }

                case LocationServiceImpl.MSG_ON_SERVICE_STOPPED: {
                    logger.debug("Received MSG_ON_SERVICE_STOPPED");
                    if (mDelegate != null) {
                        mDelegate.onServiceStatusChanged(SERVICE_STOPPED);
                    }
                    return;
                }

                case LocationServiceImpl.MSG_ON_ABORT_REQUESTED: {
                    logger.debug("Received MSG_ON_ABORT_REQUESTED");

                    if (mDelegate != null) {
                        // We have a delegate, tell it that there's a request.
                        // It will decide whether to stop or not.
                        mDelegate.onAbortRequested();
                    } else {
                        // No delegate, we may be running in the background.
                        // Let's just stop.
                        stop();
                    }

                    return;
                }

                case LocationServiceImpl.MSG_ON_HTTP_AUTHORIZATION: {
                    logger.debug("Received MSG_ON_HTTP_AUTHORIZATION");

                    if (mDelegate != null) {
                        mDelegate.onHttpAuthorization();
                    }

                    return;
                }
            }
        }
    };

    private final TrackingLifecycleCoordinator.LifecycleResultListener lifecycleResultListener =
            new TrackingLifecycleCoordinator.LifecycleResultListener() {
                @Override
                public void onLifecycleResult(TrackingLifecycleCoordinator.LifecycleActionResult lifecycleResult) {
                    resolvePendingGeofenceStartSuccess(lifecycleResult);
                    resolvePendingGeofenceStartFailureIfNeeded();
                }
            };
    private PermissionManager.PermissionRequestListener mPendingBackgroundLocationSettingsListener;

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private synchronized void registerLocationModeChangeReceiver() {
        if (mLocationModeChangeReceiverRegistered) return;

        getContext().registerReceiver(locationModeChangeReceiver, new IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION));
        mLocationModeChangeReceiverRegistered = true;
    }

    private synchronized void unregisterLocationModeChangeReceiver() {
        if (!mLocationModeChangeReceiverRegistered) return;

        Context context = getContext();
        if (context != null) {
            context.unregisterReceiver(locationModeChangeReceiver);
        }
        mLocationModeChangeReceiverRegistered = false;
    }

    private synchronized void registerServiceBroadcast() {
        if (mServiceBroadcastReceiverRegistered) return;

        mLifecycleCoordinator.addLifecycleResultListener(lifecycleResultListener);
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(serviceBroadcastReceiver, new IntentFilter(LocationServiceImpl.ACTION_BROADCAST));
        mServiceBroadcastReceiverRegistered = true;
    }

    private synchronized void unregisterServiceBroadcast() {
        if (!mServiceBroadcastReceiverRegistered) return;

        Context context = getContext();
        if (context != null) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(serviceBroadcastReceiver);
        }
        mLifecycleCoordinator.removeLifecycleResultListener(lifecycleResultListener);

        mServiceBroadcastReceiverRegistered = false;
    }

    public void start() {
        mLifecycleCoordinator.reconcileState();
        startInternal(TrackingOwnershipStore.OWNER_MANUAL, false, null);
    }

    public void startForGeofence(StartRequestCallback callback) {
        TrackingOwnershipStore.ReconciledState state = mLifecycleCoordinator.reconcileState();
        if (state.pendingStartOwner == TrackingOwnershipStore.OWNER_GEOFENCE) {
            if (callback != null) {
                callback.onError(new PluginException(
                        "Geofence start request is already pending.",
                        PluginException.START_FAILED_ERROR
                ));
            }
            return;
        }
        if ((state.serviceStarted && state.owner == TrackingOwnershipStore.OWNER_MANUAL)
                || state.pendingStartOwner == TrackingOwnershipStore.OWNER_MANUAL) {
            logger.info("Ignoring geofence start because manual tracking owner is active");
            if (callback != null) {
                callback.onError(new PluginException("Tracking already started manually.", PluginException.OWNERSHIP_CONFLICT_ERROR));
            }
            return;
        }
        startInternal(TrackingOwnershipStore.OWNER_GEOFENCE, true, callback);
    }

    private void startInternal(final int requestedOwner,
                               final boolean requireGeofenceConfig,
                               final StartRequestCallback geofenceCallback) {
        logger.debug("Starting service");
        final long permissionPendingGeneration = requestedOwner == TrackingOwnershipStore.OWNER_MANUAL
                ? mLifecycleCoordinator.requestPermissionPendingStart(requestedOwner, PERMISSION_PENDING_TIMEOUT_MS)
                : 0L;
        if (requestedOwner == TrackingOwnershipStore.OWNER_GEOFENCE) {
            setPendingGeofenceStartCallback(geofenceCallback, 0L);
        }

        final AtomicBoolean permissionSettled = new AtomicBoolean(false);
        final Runnable permissionTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (permissionSettled.compareAndSet(false, true)) {
                    logger.warn("Timed out waiting for location permissions");
                    clearPendingBackgroundLocationSettingsListener();
                    if (permissionPendingGeneration != 0L) {
                        clearPendingStartRequest(permissionPendingGeneration, false);
                    }
                    notifyGeofenceStartError(new PluginException("Permission request timed out", PluginException.PERMISSION_DENIED_ERROR));
                    if (mDelegate != null) {
                        mDelegate.onAuthorizationChanged(BackgroundGeolocationFacade.AUTHORIZATION_DENIED);
                    }
                }
            }
        };
        mPermissionHandler.postDelayed(permissionTimeoutRunnable, getPermissionRequestTimeoutMs());

        requestLocationPermissions(new PermissionManager.PermissionRequestListener() {
            @Override
            public void onPermissionGranted() {
                if (!permissionSettled.compareAndSet(false, true)) {
                    return;
                }
                mPermissionHandler.removeCallbacks(permissionTimeoutRunnable);
                logger.info("User granted requested permissions");
                requestPostNotificationPermission();

                // watch location mode changes
                registerLocationModeChangeReceiver();
                if (mDelegate != null || geofenceCallback != null) {
                    registerServiceBroadcast();
                }

                if (requireGeofenceConfig && !isGeofenceStartConfigurationValid()) {
                    notifyGeofenceStartError(new PluginException(
                            "Unable to start geofence-owned background geolocation due to invalid configuration.",
                            PluginException.START_FAILED_ERROR
                    ));
                    return;
                }

                final long pendingGeneration;
                if (permissionPendingGeneration != 0L) {
                    boolean promoted = mLifecycleCoordinator.promotePendingStartToServiceAck(
                            permissionPendingGeneration,
                            START_ACK_TIMEOUT_MS,
                            null
                    );
                    if (!promoted) {
                        return;
                    }
                    pendingGeneration = permissionPendingGeneration;
                } else {
                    pendingGeneration = mLifecycleCoordinator.requestStart(requestedOwner, START_ACK_TIMEOUT_MS, new TrackingLifecycleCoordinator.TimeoutCallback() {
                        @Override
                        public void onTimeout(long generation) {
                            if (requestedOwner == TrackingOwnershipStore.OWNER_GEOFENCE) {
                                notifyGeofenceStartError(new PluginException(
                                        "Unable to start geofence-owned background geolocation.",
                                        PluginException.START_FAILED_ERROR
                                ));
                            }
                        }
                    });
                }

                try {
                    startBackgroundService(pendingGeneration);
                    if (requestedOwner == TrackingOwnershipStore.OWNER_GEOFENCE) {
                        setPendingGeofenceStartGeneration(pendingGeneration);
                    }
                } catch (SecurityException e) {
                    clearPendingStartRequest(pendingGeneration, true);
                    notifyGeofenceStartError(new PluginException(
                            "Unable to start geofence-owned background geolocation due to missing permission.",
                            e,
                            PluginException.START_FAILED_ERROR
                    ));
                } catch (IllegalStateException e) {
                    clearPendingStartRequest(pendingGeneration, true);
                    notifyGeofenceStartError(new PluginException(
                            "Unable to start geofence-owned background geolocation due to foreground-service restrictions.",
                            e,
                            PluginException.START_FAILED_ERROR
                    ));
                } catch (RuntimeException e) {
                    clearPendingStartRequest(pendingGeneration, true);
                    notifyGeofenceStartError(new PluginException(
                            "Unable to start geofence-owned background geolocation.",
                            e,
                            PluginException.START_FAILED_ERROR
                    ));
                }
            }

            @Override
            public void onPermissionDenied(DeniedPermissions deniedPermissions) {
                if (!permissionSettled.compareAndSet(false, true)) {
                    return;
                }
                mPermissionHandler.removeCallbacks(permissionTimeoutRunnable);
                logger.info("User denied requested permissions");
                if (permissionPendingGeneration != 0L) {
                    clearPendingStartRequest(permissionPendingGeneration, false);
                }
                notifyGeofenceStartError(new PluginException("Permission denied", PluginException.PERMISSION_DENIED_ERROR));
                if (mDelegate != null) {
                    mDelegate.onAuthorizationChanged(BackgroundGeolocationFacade.AUTHORIZATION_DENIED);
                }
            }
        });
    }

    protected void requestLocationPermissions(PermissionManager.PermissionRequestListener listener) {
        requestPermissions(Arrays.asList(PERMISSIONS), new PermissionManager.PermissionRequestListener() {
            @Override
            public void onPermissionGranted() {
                if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    listener.onPermissionDenied((DeniedPermissions) null);
                    return;
                }
                if (requiresPreciseLocationPermission() && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    logger.warn("Foreground permission grant is approximate-only; precise location is required by current provider.");
                    listener.onPermissionDenied((DeniedPermissions) null);
                    return;
                }
                requestBackgroundLocationPermissionIfNeeded(listener);
            }

            @Override
            public void onPermissionDenied(DeniedPermissions deniedPermissions) {
                listener.onPermissionDenied(deniedPermissions);
            }
        });
    }

    private void requestBackgroundLocationPermissionIfNeeded(PermissionManager.PermissionRequestListener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestActivityRecognitionPermissionIfNeeded(listener);
            return;
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissions(Arrays.asList(BACKGROUND_PERMISSION), new PermissionManager.PermissionRequestListener() {
                @Override
                public void onPermissionGranted() {
                    requestActivityRecognitionPermissionIfNeeded(listener);
                }

                @Override
                public void onPermissionDenied(DeniedPermissions deniedPermissions) {
                    listener.onPermissionDenied(deniedPermissions);
                }
            });
            return;
        }
        setPendingBackgroundLocationSettingsListener(listener);
        logger.info("Background location requires settings consent on Android 11+; opening app settings for allow-all-the-time.");
        openApplicationSettingsForBackgroundLocationPermission();
    }

    private void requestActivityRecognitionPermissionIfNeeded(PermissionManager.PermissionRequestListener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || getConfig().getLocationProvider() != Config.ACTIVITY_PROVIDER
                || hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            listener.onPermissionGranted();
            return;
        }
        requestPermissions(Arrays.asList(ACTIVITY_RECOGNITION_PERMISSION), listener);
    }

    protected void requestPostNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            return;
        }
        requestPermissions(Arrays.asList(POST_NOTIFICATIONS_PERMISSION), new PermissionManager.PermissionRequestListener() {
            @Override
            public void onPermissionGranted() {} // noop

            @Override
            public void onPermissionDenied(DeniedPermissions deniedPermissions) {} // noop
        });
    }

    public void stop() {
        logger.debug("Stopping service");
        unregisterLocationModeChangeReceiver();
        // Note: we cannot unregistered service broadcast here
        // because no stop notification from service will arrive
        // unregisterServiceBroadcast();

        TrackingOwnershipStore.ReconciledState state = mLifecycleCoordinator.reconcileState();
        long stopGeneration = 0L;
        if (state.owner != TrackingOwnershipStore.OWNER_NONE) {
            stopGeneration = mLifecycleCoordinator.requestStop(state.owner, STOP_ACK_TIMEOUT_MS, null);
        }
        if (state.pendingStartOwner == TrackingOwnershipStore.OWNER_GEOFENCE) {
            notifyGeofenceStartError(new PluginException(
                    "Unable to start geofence-owned background geolocation.",
                    PluginException.START_FAILED_ERROR
            ));
        }
        mLifecycleCoordinator.cancelPendingStart();
        stopBackgroundService(stopGeneration);
    }

    public boolean stopForGeofence() throws PluginException {
        logger.debug("Stopping geofence-owned service");
        unregisterLocationModeChangeReceiver();

        TrackingOwnershipStore.ReconciledState state = mLifecycleCoordinator.reconcileState();
        int outcome = evaluateStopForGeofenceOutcome(state);
        if (outcome == STOP_FOR_GEOFENCE_OWNERSHIP_CONFLICT) {
            throw new PluginException(
                    "Tracking is currently owned by manual start.",
                    PluginException.OWNERSHIP_CONFLICT_ERROR
            );
        }
        if (outcome == STOP_FOR_GEOFENCE_NOOP) {
            logger.info("Ignoring stopForGeofence because geofence owner is not active");
            return false;
        }

        if (state.pendingStartOwner == TrackingOwnershipStore.OWNER_GEOFENCE) {
            mLifecycleCoordinator.cancelPendingStart();
            notifyGeofenceStartError(new PluginException(
                    "Geofence-owned start was cancelled by stopForGeofence request.",
                    PluginException.START_FAILED_ERROR
            ));
        }

        long stopGeneration = 0L;
        if (outcome == STOP_FOR_GEOFENCE_REQUEST_STOP) {
            stopGeneration = mLifecycleCoordinator.requestStop(
                    TrackingOwnershipStore.OWNER_GEOFENCE,
                    STOP_ACK_TIMEOUT_MS,
                    null
            );
            stopBackgroundService(stopGeneration);
            return true;
        }
        return false;
    }

    static int evaluateStopForGeofenceOutcome(TrackingOwnershipStore.ReconciledState state) {
        if (state == null) {
            return STOP_FOR_GEOFENCE_NOOP;
        }
        if (state.owner == TrackingOwnershipStore.OWNER_MANUAL) {
            return STOP_FOR_GEOFENCE_OWNERSHIP_CONFLICT;
        }
        if (state.owner == TrackingOwnershipStore.OWNER_GEOFENCE) {
            return STOP_FOR_GEOFENCE_REQUEST_STOP;
        }
        if (state.pendingStartOwner == TrackingOwnershipStore.OWNER_GEOFENCE) {
            return STOP_FOR_GEOFENCE_NOOP;
        }
        return STOP_FOR_GEOFENCE_NOOP;
    }

    public JSONObject getGeofenceCompanionStatus() throws PluginException, JSONException {
        TrackingOwnershipStore.ReconciledState state = mLifecycleCoordinator.reconcileState();
        Config config = getStoredConfig();
        JSONObject json = new JSONObject();
        json.put("statusSchemaVersion", 2);
        json.put("pendingOwnersSupported", true);
        json.put("compatibilityNote", GeofenceTransitionHandler.COMPATIBILITY_NOTE);
        json.put("trackingOwner", state.owner);
        json.put("pendingStartOwner", state.pendingStartOwner);
        json.put("pendingStopOwner", state.pendingStopOwner);
        json.put("serviceStarted", state.serviceStarted);
        json.put("hasValidUrl", config != null && config.hasAllowedUrl());
        json.put("startForegroundEnabled", config != null && Boolean.TRUE.equals(config.getStartForeground()));
        return json;
    }

    public void pause() {
        mIsPaused = true;
        mService.startForeground();
    }

    public void resume() {
        mIsPaused = false;
        mService.stopHeadlessTask();
        retryPendingBackgroundLocationSettingsPermission();
        if (!getConfig().getStartForeground()) {
            mService.stopForeground();
        }
    }

    public void destroy() {
        logger.info("Destroying plugin");
        clearPendingBackgroundLocationSettingsListener();

        unregisterLocationModeChangeReceiver();
        unregisterServiceBroadcast();

        if (getConfig().getStopOnTerminate()) {
            stopBackgroundService();
        } else {
            mService.startHeadlessTask();
        }
    }

    public Collection<BackgroundLocation> getLocations() {
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        return dao.getAllLocations();
    }

    public Collection<BackgroundLocation> getValidLocations() {
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        return dao.getValidLocations();
    }

    public Collection<BackgroundLocation> getValidLocationsAndDelete() {
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        return dao.getValidLocationsAndDelete();
    }

    public BackgroundLocation getStationaryLocation() {
        return mStationaryLocation;
    }

    public void deleteLocation(Long locationId) {
        logger.info("Deleting location locationId={}", locationId);
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        dao.deleteLocationById(locationId.longValue());
    }

    public void deleteAllLocations() {
        logger.info("Deleting all locations");
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        dao.deleteAllLocations();
    }

    public BackgroundLocation getCurrentLocation(int timeout, long maximumAge, boolean enableHighAccuracy) throws PluginException {
        logger.info("Getting current location with timeout:{} maximumAge:{} enableHighAccuracy:{}", timeout, maximumAge, enableHighAccuracy);

        LocationManager locationManager = LocationManager.getInstance(getContext());
        Promise<Location> promise = locationManager.getCurrentLocation(timeout, maximumAge, enableHighAccuracy);
        try {
            promise.await();
            Location location = promise.get();
            if (location != null) {
                return BackgroundLocation.fromLocation(location);
            }

            Throwable error = promise.getError();
            if (error == null) {
                throw new PluginException("Location not available", 2); // LOCATION_UNAVAILABLE
            }
            if (error instanceof LocationManager.PermissionDeniedException) {
                logger.warn("Getting current location failed due missing permissions");
                throw new PluginException("Permission denied", 1); // PERMISSION_DENIED
            }
            if (error instanceof TimeoutException) {
                throw new PluginException("Location request timed out", 3); // TIME_OUT
            }

            throw new PluginException(error.getMessage(), 2); // LOCATION_UNAVAILABLE
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting location", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting location", e);
        }
    }

    public void switchMode(final int mode) {
        mService.executeProviderCommand(LocationProvider.CMD_SWITCH_MODE, mode);
    }

    public void sendCommand(final int commandId) {
        mService.executeProviderCommand(commandId, 0);
    }

    public synchronized void configure(Config config) throws PluginException {
        try
        {
            Config newConfig = Config.merge(getStoredConfig(), config);
            persistConfiguration(newConfig);
            logger.debug("Service configured with: {}", newConfig.toString());
            mConfig = newConfig;
            updateDbLogging(newConfig);
            mService.configure(newConfig);
        } catch (Exception e) {
            logger.error("Configuration error: {}", e.getMessage());
            throw new PluginException("Configuration error", e, PluginException.CONFIGURE_ERROR);
        }
    }

    public synchronized Config getConfig() {
        if (mConfig != null) {
            return mConfig;
        }

        try {
            mConfig = getStoredConfig();
        } catch (PluginException e) {
            logger.error("Error getting stored config will use default", e.getMessage());
            mConfig = Config.getDefault();
        }

        updateDbLogging(mConfig);
        return mConfig;
    }

    public synchronized Config getStoredConfig() throws PluginException {
        try {
            ConfigurationDAO dao = DAOFactory.createConfigurationDAO(getContext());
            Config config = dao.retrieveConfiguration();
            if (config == null) {
                config = Config.getDefault();
            }
            return config;
        } catch (JSONException e) {
            logger.error("Error getting stored config: {}", e.getMessage());
            throw new PluginException("Error getting stored config", e, PluginException.JSON_ERROR);
        }
    }

    public Collection<LogEntry> getLogEntries(int limit) {
        DBLogReader logReader = new DBLogReader();
        return logReader.getEntries(limit, 0, Level.DEBUG);
    }

    public Collection<LogEntry> getLogEntries(int limit, int offset, String minLevel) {
        DBLogReader logReader = new DBLogReader();
        return logReader.getEntries(limit, offset, Level.valueOf(minLevel));
    }

    /**
     * Force location sync
     *
     * Method is ignoring syncThreshold and also user sync settings preference
     * and sync locations to defined syncUrl
     */
    public void forceSync() {
        logger.debug("Sync locations forced");
        ResourceResolver resolver = ResourceResolver.newInstance(getContext());
        Account syncAccount = AccountHelper.CreateSyncAccount(getContext(), resolver.getAccountName(),
                resolver.getAccountType());
        SyncService.sync(syncAccount, resolver.getAuthority(), true);
    }

    public int getAuthorizationStatus() {
        return hasPermissions() ? AUTHORIZATION_AUTHORIZED : AUTHORIZATION_DENIED;
    }

    public boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.POST_NOTIFICATIONS);
    }

    public boolean hasPermissions() {
        return hasPermissions(getContext(), PERMISSIONS);
    }

    public boolean locationServicesEnabled() throws PluginException {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int locationMode = 0;
            try {
                locationMode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);
                return locationMode != Settings.Secure.LOCATION_MODE_OFF;
            } catch (SettingNotFoundException e) {
                logger.error("Location services check failed", e);
                throw new PluginException("Location services check failed", e, PluginException.SETTINGS_ERROR);
            }
        } else {
            String locationProviders = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
            return !TextUtils.isEmpty(locationProviders);
        }
    }

    public void registerHeadlessTask(final String taskRunnerClass) {
        logger.info("Registering headless task: {}", taskRunnerClass);
        mService.registerHeadlessTask(taskRunnerClass);
    }

    protected void startBackgroundService() {
        startBackgroundService(0L);
    }

    protected void startBackgroundService(long requestGeneration) {
        logger.info("Attempt to start bg service");
        if (mIsPaused) {
            if (requestGeneration != 0L && mService instanceof LocationServiceProxy) {
                ((LocationServiceProxy) mService).startForegroundService(requestGeneration);
            } else {
                mService.startForegroundService();
            }
        } else {
            if (requestGeneration != 0L && mService instanceof LocationServiceProxy) {
                ((LocationServiceProxy) mService).start(requestGeneration);
            } else {
                mService.start();
            }
        }
    }

    protected void stopBackgroundService() {
        stopBackgroundService(0L);
    }

    protected void stopBackgroundService(long requestGeneration) {
        logger.info("Attempt to stop bg service");
        if (requestGeneration != 0L && mService instanceof LocationServiceProxy) {
            ((LocationServiceProxy) mService).stop(requestGeneration);
        } else {
            mService.stop();
        }
    }

    protected boolean isGeofenceStartConfigurationValid() {
        try {
            Config config = getStoredConfig();
            return config != null && config.hasAllowedUrl() && Boolean.TRUE.equals(config.getStartForeground());
        } catch (PluginException e) {
            logger.error("Unable to validate geofence start configuration", e);
            return false;
        }
    }

    private synchronized void setPendingGeofenceStartCallback(StartRequestCallback callback, long generation) {
        mPendingGeofenceStartCallback = callback;
        mPendingGeofenceStartGeneration = generation;
    }

    private synchronized void setPendingGeofenceStartGeneration(long generation) {
        if (mPendingGeofenceStartCallback != null) {
            mPendingGeofenceStartGeneration = generation;
        }
    }

    private synchronized void clearPendingStartRequest(long generation, boolean markFailed) {
        if (markFailed) {
            mLifecycleCoordinator.clearPendingStart(generation);
        } else {
            mLifecycleCoordinator.clearPendingStartRequestOnly(generation);
        }
    }

    private void retryPendingBackgroundLocationSettingsPermission() {
        PermissionManager.PermissionRequestListener listener = consumePendingBackgroundLocationSettingsListener();
        if (listener == null) {
            return;
        }
        if (hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestActivityRecognitionPermissionIfNeeded(listener);
        } else {
            listener.onPermissionDenied((DeniedPermissions) null);
        }
    }

    private synchronized void setPendingBackgroundLocationSettingsListener(PermissionManager.PermissionRequestListener listener) {
        mPendingBackgroundLocationSettingsListener = listener;
    }

    private synchronized PermissionManager.PermissionRequestListener consumePendingBackgroundLocationSettingsListener() {
        PermissionManager.PermissionRequestListener listener = mPendingBackgroundLocationSettingsListener;
        mPendingBackgroundLocationSettingsListener = null;
        return listener;
    }

    private synchronized void clearPendingBackgroundLocationSettingsListener() {
        mPendingBackgroundLocationSettingsListener = null;
    }

    protected void openApplicationSettingsForBackgroundLocationPermission() {
        showAppSettings(getContext());
    }

    protected boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean requiresPreciseLocationPermission() {
        return getConfig().getLocationProvider() != Config.RAW_PROVIDER;
    }

    protected void requestPermissions(List<String> permissions, PermissionManager.PermissionRequestListener listener) {
        PermissionManager permissionManager = PermissionManager.getInstance(getContext());
        permissionManager.checkPermissions(permissions, listener);
    }

    protected long getPermissionRequestTimeoutMs() {
        return PERMISSION_PENDING_TIMEOUT_MS;
    }

    private void resolvePendingGeofenceStartSuccess(TrackingLifecycleCoordinator.LifecycleActionResult lifecycleResult) {
        StartRequestCallback callback;
        long expectedGeneration;
        synchronized (this) {
            if (mPendingGeofenceStartCallback == null) {
                return;
            }
            expectedGeneration = mPendingGeofenceStartGeneration;
            if (expectedGeneration == 0L
                    || lifecycleResult == null
                    || lifecycleResult.eventAction != LocationServiceImpl.MSG_ON_SERVICE_STARTED
                    || lifecycleResult.committedOwner != TrackingOwnershipStore.OWNER_GEOFENCE
                    || lifecycleResult.committedGeneration == 0L
                    || lifecycleResult.committedGeneration != expectedGeneration) {
                return;
            }
            callback = mPendingGeofenceStartCallback;
            mPendingGeofenceStartCallback = null;
            mPendingGeofenceStartGeneration = 0L;
        }
        if (callback != null) {
            callback.onSuccess();
        }
    }

    private void resolvePendingGeofenceStartFailureIfNeeded() {
        StartRequestCallback callback;
        long expectedGeneration;
        synchronized (this) {
            if (mPendingGeofenceStartCallback == null) {
                return;
            }
            expectedGeneration = mPendingGeofenceStartGeneration;
            if (expectedGeneration == 0L) {
                return;
            }
            if (mLifecycleCoordinator.getPendingStartGeneration() == expectedGeneration
                    && mLifecycleCoordinator.getPendingStartOwner() == TrackingOwnershipStore.OWNER_GEOFENCE) {
                return;
            }
            if (!mLifecycleCoordinator.isTerminalStartGenerationForOwner(
                    expectedGeneration,
                    TrackingOwnershipStore.OWNER_GEOFENCE
            )) {
                return;
            }
            callback = mPendingGeofenceStartCallback;
            mPendingGeofenceStartCallback = null;
            mPendingGeofenceStartGeneration = 0L;
        }
        callback.onError(new PluginException(
                "Unable to start geofence-owned background geolocation.",
                PluginException.START_FAILED_ERROR
        ));
    }

    private void notifyGeofenceStartError(PluginException exception) {
        StartRequestCallback callback;
        synchronized (this) {
            callback = mPendingGeofenceStartCallback;
            mPendingGeofenceStartCallback = null;
            mPendingGeofenceStartGeneration = 0L;
        }
        if (callback != null) {
            callback.onError(exception);
        }
    }

    public boolean isRunning() {
        return ((LocationServiceProxy) mService).isRunning();
    }

    private void persistConfiguration(Config config) throws NullPointerException {
        ConfigurationDAO dao = DAOFactory.createConfigurationDAO(getContext());
        dao.persistConfiguration(config);
    }

    private Context getContext() {
        return mContext;
    }

    private Context getApplicationContext() {
        return mContext.getApplicationContext();
    }

    private synchronized void updateDbLogging(Config config) {
        if (config != null && config.isDebugging()) {
            LoggerManager.enableDBLogging();
        } else {
            LoggerManager.disableDBLogging();
        }
    }

    public static void showAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    public static void showLocationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    public static boolean hasPermissions(Context context, String[] permissions) {
        for (String perm: permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sets a transform for each coordinate about to be committed (sent or saved for later sync).
     * You can use this for modifying the coordinates in any way.
     *
     * If the transform returns <code>null</code>, it will prevent the location from being committed.
     * @param transform - the transform listener
     */
    public static void setLocationTransform(LocationTransform transform) {
        LocationServiceImpl.setLocationTransform(transform);
    }

    public static LocationTransform getLocationTransform() {
        return LocationServiceImpl.getLocationTransform();
    }
}
