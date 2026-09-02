//
//  MAURConfigTest.m
//  BackgroundGeolocationTests
//
//  Created by Marian Hello on 02/12/2017.
//  Copyright © 2017 mauron85. All rights reserved.
//

#import <XCTest/XCTest.h>
#import "MAURConfig.h"
#import "MAURGeofenceTransitionHandler.h"
#import "MAURBackgroundGeolocationFacade.h"

@interface MAURConfigTest : XCTestCase

@end

@implementation MAURConfigTest

- (void)setUp {
    [super setUp];
    // Put setup code here. This method is called before the invocation of each test method in the class.
}

- (void)tearDown {
    // Put teardown code here. This method is called after the invocation of each test method in the class.
    [super tearDown];
}

- (void)testInit {
    MAURConfig *config = [[MAURConfig alloc] init];

    XCTAssertFalse([config hasStationaryRadius]);
    XCTAssertFalse([config hasDistanceFilter]);
    XCTAssertFalse([config hasDesiredAccuracy]);
    XCTAssertFalse([config hasDebug]);
    XCTAssertFalse([config hasActivityType]);
    XCTAssertFalse([config hasActivitiesInterval]);
    XCTAssertFalse([config hasStopOnTerminate]);
    XCTAssertFalse([config hasUrl]);
    XCTAssertFalse([config hasSyncUrl]);
    XCTAssertFalse([config hasSyncThreshold]);
    XCTAssertNil(config.allowHttp);
    XCTAssertFalse([config hasHttpHeaders]);
    XCTAssertFalse([config hasSaveBatteryOnBackground]);
    XCTAssertFalse([config hasMaxLocations]);
    XCTAssertFalse([config hasPauseLocationUpdates]);
    XCTAssertFalse([config hasLocationProvider]);
}

- (void)testToDictionary {
    MAURConfig *config = [[MAURConfig alloc] init];
    NSDictionary *dict = [config toDictionary];

    XCTAssertNil(dict[@"url"]);
    XCTAssertNil(dict[@"syncUrl"]);
    XCTAssertNil(dict[@"allowHttp"]);
    XCTAssertNil(dict[@"httpHeaders"]);
    XCTAssertEqualObjects(dict[@"postTemplate"], [MAURConfig getDefaultTemplate]);
}

- (void)testBooleans {
    MAURConfig *config = [[MAURConfig alloc] init];
    
    config._debug = @YES;
    XCTAssertTrue([config isDebugging]);
    
    config._debug = @NO;
    XCTAssertFalse([config isDebugging]);
    
}

- (void)testNullToDictionary {
    MAURConfig *config = [[MAURConfig alloc] init];
    config.url = (id)[NSNull null];
    config.syncUrl = (id)[NSNull null];
    config.allowHttp = @NO;
    config.httpHeaders = (id)[NSNull null];
    config._template = (id)[NSNull null];
    
    NSDictionary *dict = [config toDictionary];
    XCTAssertEqualObjects(dict[@"url"], @"");
    XCTAssertEqualObjects(dict[@"syncUrl"], @"");
    XCTAssertEqualObjects(dict[@"allowHttp"], @NO);
    XCTAssertEqualObjects(dict[@"httpHeaders"], @{});
    XCTAssertEqualObjects(dict[@"postTemplate"], [MAURConfig getDefaultTemplate]);
}

- (void)testMerge {
    MAURConfig *config1 = [[MAURConfig alloc] init];
    config1.distanceFilter = [NSNumber numberWithInt:666];
    MAURConfig *config2 = [[MAURConfig alloc] initWithDefaults];
    config2.distanceFilter = [NSNumber numberWithInt:999];
    
    MAURConfig *merger = [MAURConfig merge:config1 withConfig:config2];
    
    XCTAssertEqual(merger.distanceFilter.intValue, 999);
    XCTAssertEqualObjects(merger.activityType, @"OtherNavigation");
    XCTAssertEqual(config1.distanceFilter.intValue, 666);
    XCTAssertEqual(config2.distanceFilter.intValue, 999);
    XCTAssertNotEqual(merger.distanceFilter, config1.distanceFilter);
    XCTAssertEqual(merger.distanceFilter, config2.distanceFilter);
    XCTAssertNotEqual(config1.distanceFilter, config2.distanceFilter);
}

- (void)testArrayTemplateToString {
    MAURConfig *config = [[MAURConfig alloc] init];
    config._template = @[@"latitude", @"longitude", @"custom"];
    
    XCTAssertEqualObjects([config getTemplateAsString:nil], @"[\"latitude\",\"longitude\",\"custom\"]");
}

- (void)testDictionaryTemplateToString {
    MAURConfig *config = [[MAURConfig alloc] init];
    config._template = @{
                           @"lat": @"latitude",
                           @"lon": @"longitude",
                           @"foo": @"bar"
                        };
    
    XCTAssertEqualObjects([config getTemplateAsString:nil], @"{\"lat\":\"latitude\",\"lon\":\"longitude\",\"foo\":\"bar\"}");
}

- (void)testMergeEmpty {
    MAURConfig *config1 = [[MAURConfig alloc] init];
    MAURConfig *config2 = [[MAURConfig alloc] init];
    
    config1.url = @"url";
    config1.syncUrl = @"syncurl";
    
    config2.url = @"";
    config2.syncUrl = @"";

    MAURConfig *merger = [MAURConfig merge:config1 withConfig:config2];
    
    XCTAssertEqualObjects(merger.url, @"");
    XCTAssertEqualObjects(merger.syncUrl, @"");
}

- (void)testMergeNullFromDictionary {
    MAURConfig *config1 = [[MAURConfig alloc] init];

    config1.url = @"url";
    config1.syncUrl = @"syncurl";
    config1.httpHeaders = @{@"foo": @"bar"};
    config1._template = @{@"lat": @"@latitude"};
    
    NSDictionary *configProps = @{
                                  @"url": [NSNull null],
                                  @"syncUrl": [NSNull null],
                                  @"httpHeaders": [NSNull null],
                                  @"postTemplate": [NSNull null]
                                 };
    MAURConfig *config2 = [MAURConfig fromDictionary:configProps];
    
    XCTAssertFalse([config2 hasValidUrl]);
    XCTAssertFalse([config2 hasValidSyncUrl]);
    
    MAURConfig *merger = [MAURConfig merge:config1 withConfig:config2];
    
    XCTAssertEqualObjects(merger.url, @"");
    XCTAssertEqualObjects(merger.syncUrl, @"");
    XCTAssertEqualObjects(merger.httpHeaders, @{});
    XCTAssertEqualObjects(merger._template, [MAURConfig getDefaultTemplate]);
}

- (void)testGeofenceTransitionExitStopsOnlyForGeofenceOwner {
    MAURGeofenceTransitionAction shouldStop = [MAURGeofenceTransitionHandler actionForTransitionType:2
                                                                             hasActiveInsideGeofence:NO
                                                                                        trackingOwner:MAURTrackingOwnerGeofence
                                                                                          hasValidUrl:YES
                                                                                      stopOnTerminate:NO];
    XCTAssertEqual(shouldStop, MAURGeofenceTransitionActionStop);

    MAURGeofenceTransitionAction shouldIgnore = [MAURGeofenceTransitionHandler actionForTransitionType:2
                                                                               hasActiveInsideGeofence:NO
                                                                                          trackingOwner:MAURTrackingOwnerManual
                                                                                            hasValidUrl:YES
                                                                                        stopOnTerminate:NO];
    XCTAssertEqual(shouldIgnore, MAURGeofenceTransitionActionIgnore);
}

- (void)testGeofenceTransitionEnterIgnoresManualOwnerAndStartsForValidConfig {
    MAURGeofenceTransitionAction manualOwner = [MAURGeofenceTransitionHandler actionForTransitionType:1
                                                                              hasActiveInsideGeofence:YES
                                                                                         trackingOwner:MAURTrackingOwnerManual
                                                                                           hasValidUrl:YES
                                                                                       stopOnTerminate:NO];
    XCTAssertEqual(manualOwner, MAURGeofenceTransitionActionIgnore);

    MAURGeofenceTransitionAction invalidConfig = [MAURGeofenceTransitionHandler actionForTransitionType:1
                                                                                hasActiveInsideGeofence:YES
                                                                                           trackingOwner:MAURTrackingOwnerNone
                                                                                             hasValidUrl:NO
                                                                                         stopOnTerminate:NO];
    XCTAssertEqual(invalidConfig, MAURGeofenceTransitionActionIgnore);

    MAURGeofenceTransitionAction valid = [MAURGeofenceTransitionHandler actionForTransitionType:1
                                                                        hasActiveInsideGeofence:YES
                                                                                   trackingOwner:MAURTrackingOwnerNone
                                                                                     hasValidUrl:YES
                                                                                 stopOnTerminate:NO];
    XCTAssertEqual(valid, MAURGeofenceTransitionActionStart);
}

- (void)testHttpsRequiredByDefaultUnlessAllowHttpEnabled {
    MAURConfig *config = [[MAURConfig alloc] initWithDefaults];
    config.url = @"http://example.com/location";
    config.syncUrl = @"http://example.com/sync";

    XCTAssertFalse([config hasAllowedUrl]);
    XCTAssertFalse([config hasAllowedSyncUrl]);

    config.allowHttp = @YES;
    XCTAssertTrue([config hasAllowedUrl]);
    XCTAssertTrue([config hasAllowedSyncUrl]);
}

@end
