#import <UIKit/UIKit.h>
#import "MAURGeofenceTransitionHandler.h"
#import "MAURBackgroundGeolocationFacade.h"
#import "MAURConfig.h"
#import "MAURLogging.h"

NSString * const MAURGeofenceTrackingTransitionNotification = @"AppGeofenceTrackingTransition";
NSString * const MAURGeofenceTrackingTransitionTypeKey = @"transitionType";
NSString * const MAURGeofenceTrackingHasActiveInsideGeofenceKey = @"hasActiveInsideGeofence";

static NSInteger const MAURGeofenceTransitionEnter = 1;
static NSInteger const MAURGeofenceTransitionExit = 2;
static NSInteger const MAURGeofenceTransitionDwell = 4;
static NSString * const MAURGeofenceTransitionLogTag = @"BgGeoGeofence";

@implementation MAURGeofenceTransitionHandler

+ (MAURGeofenceTransitionAction)actionForTransitionType:(NSInteger)transitionType
                               hasActiveInsideGeofence:(BOOL)hasActiveInsideGeofence
                                          trackingOwner:(NSInteger)trackingOwner
                                            hasValidUrl:(BOOL)hasValidUrl
                                        stopOnTerminate:(BOOL)stopOnTerminate
{
    if (transitionType == MAURGeofenceTransitionExit) {
        if (hasActiveInsideGeofence || trackingOwner != MAURTrackingOwnerGeofence) {
            return MAURGeofenceTransitionActionIgnore;
        }
        return MAURGeofenceTransitionActionStop;
    }

    if (transitionType != MAURGeofenceTransitionEnter &&
        transitionType != MAURGeofenceTransitionDwell) {
        return MAURGeofenceTransitionActionIgnore;
    }

    if (!hasActiveInsideGeofence || !hasValidUrl || stopOnTerminate || trackingOwner == MAURTrackingOwnerManual) {
        return MAURGeofenceTransitionActionIgnore;
    }

    return MAURGeofenceTransitionActionStart;
}

+ (void)load
{
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(onGeofenceTransition:)
                                                 name:MAURGeofenceTrackingTransitionNotification
                                               object:nil];
}

+ (void)onGeofenceTransition:(NSNotification *)notification
{
    NSDictionary *userInfo = notification.userInfo ?: @{};
    NSInteger transitionType = [userInfo[MAURGeofenceTrackingTransitionTypeKey] integerValue];
    BOOL hasActiveInsideGeofence = [userInfo[MAURGeofenceTrackingHasActiveInsideGeofenceKey] boolValue];

    dispatch_async(dispatch_get_main_queue(), ^{
        MAURBackgroundGeolocationFacade *facade = [MAURBackgroundGeolocationFacade sharedInstance];
        MAURConfig *config = [facade getConfig];
        MAURTrackingOwner trackingOwner = [facade trackingOwner];
        MAURGeofenceTransitionAction action = [self actionForTransitionType:transitionType
                                                  hasActiveInsideGeofence:hasActiveInsideGeofence
                                                             trackingOwner:trackingOwner
                                                               hasValidUrl:[config hasValidUrl]
                                                           stopOnTerminate:[config stopOnTerminate]];

        if (action == MAURGeofenceTransitionActionIgnore) {
            if (transitionType == MAURGeofenceTransitionExit && hasActiveInsideGeofence) {
                DDLogInfo(@"%@ ignored EXIT: another geofence is still active", MAURGeofenceTransitionLogTag);
            } else if (transitionType == MAURGeofenceTransitionExit && trackingOwner != MAURTrackingOwnerGeofence) {
                DDLogInfo(@"%@ ignored EXIT: tracking owner is not geofence", MAURGeofenceTransitionLogTag);
            } else if ((transitionType == MAURGeofenceTransitionEnter || transitionType == MAURGeofenceTransitionDwell)
                       && !hasActiveInsideGeofence) {
                DDLogInfo(@"%@ ignored ENTER/DWELL: no active inside geofence", MAURGeofenceTransitionLogTag);
            } else if ((transitionType == MAURGeofenceTransitionEnter || transitionType == MAURGeofenceTransitionDwell)
                       && trackingOwner == MAURTrackingOwnerManual) {
                DDLogInfo(@"%@ ignored ENTER/DWELL: manual owner is active", MAURGeofenceTransitionLogTag);
            } else {
                DDLogWarn(@"%@ ignored ENTER/DWELL: persisted configuration cannot run after termination",
                          MAURGeofenceTransitionLogTag);
            }
            return;
        }

        if (action == MAURGeofenceTransitionActionStop) {
            if (hasActiveInsideGeofence) {
                DDLogInfo(@"%@ ignored EXIT: another geofence is still active", MAURGeofenceTransitionLogTag);
                return;
            }
            if ([facade trackingOwner] != MAURTrackingOwnerGeofence) {
                DDLogInfo(@"%@ ignored EXIT: tracking owner is not geofence", MAURGeofenceTransitionLogTag);
                return;
            }

            NSError *error = nil;
            BOOL stopped = [facade stopForOwner:MAURTrackingOwnerGeofence error:&error];
            if (!stopped || error != nil) {
                DDLogError(@"%@ failed to stop after EXIT: %@",
                           MAURGeofenceTransitionLogTag,
                           error.localizedDescription ?: @"stop operation failed");
                return;
            }

            DDLogInfo(@"%@ stopped precise tracking after EXIT", MAURGeofenceTransitionLogTag);
            return;
        }

        NSError *error = nil;
        BOOL started = [facade startWithOwner:MAURTrackingOwnerGeofence error:&error];
        if (!started || error != nil) {
            DDLogError(@"%@ failed to start after ENTER/DWELL: %@",
                       MAURGeofenceTransitionLogTag,
                       error.localizedDescription ?: @"location provider did not start");
            return;
        }

        if ([UIApplication sharedApplication].applicationState != UIApplicationStateActive) {
            [facade switchMode:MAURBackgroundMode];
        }
        DDLogInfo(@"%@ started precise tracking after ENTER/DWELL", MAURGeofenceTransitionLogTag);
    });
}

@end
