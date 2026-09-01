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

        if (transitionType == MAURGeofenceTransitionExit) {
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

        if (transitionType != MAURGeofenceTransitionEnter &&
            transitionType != MAURGeofenceTransitionDwell) {
            return;
        }

        if (!hasActiveInsideGeofence) {
            DDLogInfo(@"%@ ignored ENTER/DWELL: no active inside geofence", MAURGeofenceTransitionLogTag);
            return;
        }

        MAURConfig *config = [facade getConfig];
        if (![config hasValidUrl] || [config stopOnTerminate]) {
            DDLogWarn(@"%@ ignored ENTER/DWELL: persisted configuration cannot run after termination",
                      MAURGeofenceTransitionLogTag);
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
