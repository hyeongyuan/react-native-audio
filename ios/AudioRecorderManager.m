//
//  AudioRecorderManager.m
//  AudioRecorderManager
//
//  Created by Joshua Sierles on 15/04/15.
//  Copyright (c) 2015 Joshua Sierles. All rights reserved.
//

#import "AudioRecorderManager.h"
#import <React/RCTConvert.h>
#import <React/RCTBridge.h>
#import <React/RCTUtils.h>
#import <React/RCTEventDispatcher.h>
#import <AVFoundation/AVFoundation.h>

NSString *const AudioRecorderEventProgress = @"recordingProgress";
NSString *const AudioRecorderEventFinished = @"recordingFinished";
NSString *const AudioRecorderEventStatus = @"recordingStatus";

@implementation AudioRecorderManager {

  AVAudioRecorder *_audioRecorder;
  AVAudioPlayer *_audioPlayer;
  NSTimeInterval _duration;

  NSTimeInterval _currentTime;
  id _progressUpdateTimer;
  int _progressUpdateInterval;
  NSDate *_prevProgressUpdateTime;
  NSURL *_audioFileURL;
  NSNumber *_audioQuality;
  NSNumber *_audioEncoding;
  NSNumber *_audioChannels;
  NSNumber *_audioSampleRate;
  AVAudioSession *_recordSession;
  BOOL _meteringEnabled;
  BOOL _measurementMode;
  BOOL _includeBase64;

  NSMutableArray *_timestamps;

  BOOL _hasInterruptionStop;
}

@synthesize bridge = _bridge;

RCT_EXPORT_MODULE();

- (void)sendProgressUpdate {
  if (_audioRecorder && _audioRecorder.isRecording) {
    _currentTime = _audioRecorder.currentTime;
  } else {
    return;
  }

  if (_prevProgressUpdateTime == nil ||
   (([_prevProgressUpdateTime timeIntervalSinceNow] * -1000.0) >= _progressUpdateInterval)) {
      NSMutableDictionary *body = [[NSMutableDictionary alloc] init];
      [body setObject:[NSNumber numberWithFloat:_currentTime] forKey:@"currentTime"];
      if (_meteringEnabled) {
          [_audioRecorder updateMeters];
          float _currentMetering = [_audioRecorder averagePowerForChannel: 0];
          [body setObject:[NSNumber numberWithFloat:_currentMetering] forKey:@"currentMetering"];
   
          float _currentPeakMetering = [_audioRecorder peakPowerForChannel:0];
          [body setObject:[NSNumber numberWithFloat:_currentPeakMetering] forKey:@"currentPeakMetering"];
      }
      [self.bridge.eventDispatcher sendAppEventWithName:AudioRecorderEventProgress body:body];

    _prevProgressUpdateTime = [NSDate date];
  }
}

- (void)sendRecordingStatus {
  // TODO: Add and send recording pause status
  [self.bridge.eventDispatcher sendAppEventWithName:AudioRecorderEventStatus body:@{
      @"isRecording":[NSNumber numberWithBool:_audioRecorder.isRecording]
  }];
}

- (void)saveTimestamp {
  NSTimeInterval timeStamp = [[NSDate date] timeIntervalSince1970];
  // NSTimeInterval is defined as double
  NSInteger time = timeStamp * 1000;
  [_timestamps addObject: [NSNumber numberWithInteger: time]];
}

- (void)stopProgressTimer {
  [_progressUpdateTimer invalidate];
}

- (void)startProgressTimer {
  _progressUpdateInterval = 250;
  //_prevProgressUpdateTime = nil;

  [self stopProgressTimer];

  _progressUpdateTimer = [CADisplayLink displayLinkWithTarget:self selector:@selector(sendProgressUpdate)];
  [_progressUpdateTimer addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
}

- (void)audioRecorderDidFinishRecording:(AVAudioRecorder *)recorder successfully:(BOOL)flag {
  NSString *base64 = @"";
  if (_includeBase64) {
    NSData *data = [NSData dataWithContentsOfURL:_audioFileURL];
    base64 = [data base64EncodedStringWithOptions:0];
  }
    uint64_t audioFileSize = 0;
    audioFileSize = [[[NSFileManager defaultManager] attributesOfItemAtPath:[_audioFileURL path] error:nil] fileSize];

    NSError *playerError;
    _audioPlayer = [[AVAudioPlayer alloc] initWithContentsOfURL:_audioFileURL error:&playerError];

    if (playerError) {
      _duration = 0;
      NSLog(@"error: %@", [playerError localizedDescription]);
    } else {
      _duration = _audioPlayer.duration;
    }
    [self.bridge.eventDispatcher sendAppEventWithName:AudioRecorderEventFinished body:@{
      @"base64":base64,
      @"duration":@(_duration),
      @"status": flag ? @"OK" : @"ERROR",
      @"audioFileURL": [_audioFileURL absoluteString],
      @"audioFileSize": @(audioFileSize),
      @"timestamps": [NSArray arrayWithArray:_timestamps],
      @"forcedStop": _hasInterruptionStop ? @1 : @0,
    }];
    
    // This will resume the music/audio file that was playing before the recording started
    // Without this piece of code, the music/audio will just be stopped
    NSError *error;
    [[AVAudioSession sharedInstance] setActive:NO
                                   withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
                                         error:&error];
    if (error) {
        // TODO: dispatch error over the bridge
        NSLog(@"error: %@", [error localizedDescription]);
    }
}

- (void)audioRecorderEncodeErrorDidOccur:(AVAudioRecorder *)recorder error:(NSError *)error {
    if (error) {
        // TODO: dispatch error over the bridge
        NSLog(@"error: %@", [error localizedDescription]);
    }
}

- (NSString *) applicationDocumentsDirectory
{
  NSArray *paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
  NSString *basePath = ([paths count] > 0) ? [paths objectAtIndex:0] : nil;
  return basePath;
}

- (void)audioInterruptionHandler:(NSNotification *)notification {
      NSDictionary *info = notification.userInfo;
      // Interrupt info
      NSNumber* interruptionType = [[notification userInfo] objectForKey: AVAudioSessionInterruptionTypeKey];
      NSNumber* interruptionOption = [[notification userInfo] objectForKey: AVAudioSessionInterruptionOptionKey];

      switch (interruptionType.unsignedIntegerValue) {
        case AVAudioSessionInterruptionTypeBegan:
          NSLog(@"Begin interruption");
          if (_audioRecorder && _audioRecorder.isRecording) {
            // [self forceStopRecording];
            [_audioRecorder pause];
            _hasInterruptionStop = YES;
          }
          break;

        case AVAudioSessionInterruptionTypeEnded:
          NSLog(@"End interruption");

          switch (interruptionOption.unsignedIntegerValue) {
            case AVAudioSessionInterruptionOptionShouldResume:
              if (_hasInterruptionStop && _audioRecorder && !_audioRecorder.isRecording) {
                NSLog(@"Resume Recording");
                [_audioRecorder record];
                _hasInterruptionStop = NO;
              }
              break;
              
          }          
          break;
      }
}

RCT_EXPORT_METHOD(prepareRecordingAtPath:(NSString *)path sampleRate:(float)sampleRate channels:(nonnull NSNumber *)channels quality:(NSString *)quality encoding:(NSString *)encoding meteringEnabled:(BOOL)meteringEnabled measurementMode:(BOOL)measurementMode includeBase64:(BOOL)includeBase64)
{
  _prevProgressUpdateTime = nil;
  [self stopProgressTimer];

  _audioFileURL = [NSURL fileURLWithPath:path];

  // Default options
  _audioQuality = [NSNumber numberWithInt:AVAudioQualityHigh];
  _audioEncoding = [NSNumber numberWithInt:kAudioFormatAppleIMA4];
  _audioChannels = [NSNumber numberWithInt:2];
  _audioSampleRate = [NSNumber numberWithFloat:44100.0];
  _meteringEnabled = NO;
  _includeBase64 = NO;
  _hasInterruptionStop = NO;

  // Set audio quality from options
  if (quality != nil) {
    if ([quality  isEqual: @"Low"]) {
      _audioQuality =[NSNumber numberWithInt:AVAudioQualityLow];
    } else if ([quality  isEqual: @"Medium"]) {
      _audioQuality =[NSNumber numberWithInt:AVAudioQualityMedium];
    } else if ([quality  isEqual: @"High"]) {
      _audioQuality =[NSNumber numberWithInt:AVAudioQualityHigh];
    }
  }

  // Set channels from options
  if (channels != nil) {
    _audioChannels = channels;
  }

  // Set audio encoding from options
  if (encoding != nil) {
    if ([encoding  isEqual: @"lpcm"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatLinearPCM];
    } else if ([encoding  isEqual: @"ima4"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatAppleIMA4];
    } else if ([encoding  isEqual: @"aac"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatMPEG4AAC];
    } else if ([encoding  isEqual: @"MAC3"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatMACE3];
    } else if ([encoding  isEqual: @"MAC6"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatMACE6];
    } else if ([encoding  isEqual: @"ulaw"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatULaw];
    } else if ([encoding  isEqual: @"alaw"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatALaw];
    } else if ([encoding  isEqual: @"mp1"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatMPEGLayer1];
    } else if ([encoding  isEqual: @"mp2"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatMPEGLayer2];
    } else if ([encoding  isEqual: @"alac"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatAppleLossless];
    } else if ([encoding  isEqual: @"amr"]) {
      _audioEncoding =[NSNumber numberWithInt:kAudioFormatAMR];
    } else if ([encoding  isEqual: @"flac"]) {
        if (@available(iOS 11, *)) _audioEncoding =[NSNumber numberWithInt:kAudioFormatFLAC];
    } else if ([encoding  isEqual: @"opus"]) {
        if (@available(iOS 11, *)) _audioEncoding =[NSNumber numberWithInt:kAudioFormatOpus];
    }
  }

    
  // Set sample rate from options
  _audioSampleRate = [NSNumber numberWithFloat:sampleRate];

  NSDictionary *recordSettings = [NSDictionary dictionaryWithObjectsAndKeys:
          _audioQuality, AVEncoderAudioQualityKey,
          _audioEncoding, AVFormatIDKey,
          _audioChannels, AVNumberOfChannelsKey,
          _audioSampleRate, AVSampleRateKey,
          nil];

  // Enable metering from options
  if (meteringEnabled != NO) {
    _meteringEnabled = meteringEnabled;
  }

  // Measurement mode to disable mic auto gain and high pass filters
  if (measurementMode != NO) {
    _measurementMode = measurementMode;
  }

  if (includeBase64) {
    _includeBase64 = includeBase64;
  }

  _timestamps = [[NSMutableArray alloc] init];

  NSError *error = nil;

  _recordSession = [AVAudioSession sharedInstance];

  if (_measurementMode) {
      [_recordSession setCategory:AVAudioSessionCategoryRecord error:nil];
      [_recordSession setMode:AVAudioSessionModeMeasurement error:nil];
  }else{
      [_recordSession setCategory:AVAudioSessionCategoryMultiRoute error:nil];
  }

  NSNotificationCenter* center = [NSNotificationCenter defaultCenter];
  [center addObserver:self 
            selector:@selector(audioInterruptionHandler:) 
            name:AVAudioSessionInterruptionNotification 
            object:_recordSession];

  _audioRecorder = [[AVAudioRecorder alloc]
                initWithURL:_audioFileURL
                settings:recordSettings
                error:&error];

  _audioRecorder.meteringEnabled = _meteringEnabled;
  _audioRecorder.delegate = self;

  if (error) {
      NSLog(@"error: %@", [error localizedDescription]);
      // TODO: dispatch error over the bridge
    } else {
      [_audioRecorder prepareToRecord];
  }
}

RCT_EXPORT_METHOD(getRecordStatus:(RCTPromiseResolveBlock)resolve reject:(__unused RCTPromiseRejectBlock)reject)
{
  // TODO: Get recording pause status 
  NSDictionary *recordStatus = @{@"isRecording": [NSNumber numberWithBool:_audioRecorder.isRecording]};
  resolve(recordStatus);
}

RCT_EXPORT_METHOD(startRecording)
{
  [self startProgressTimer];
  [_recordSession setActive:YES error:nil];
  [self saveTimestamp];
  [_audioRecorder record];
  [self sendRecordingStatus];
}

RCT_EXPORT_METHOD(stopRecording)
{
  [self saveTimestamp];
  [_audioRecorder stop];
  [_recordSession setCategory:AVAudioSessionCategoryPlayback error:nil];
  _prevProgressUpdateTime = nil;
  [self sendRecordingStatus];
}

RCT_EXPORT_METHOD(pauseRecording)
{
  if (_audioRecorder.isRecording) {
    [_audioRecorder pause];
  }
}

RCT_EXPORT_METHOD(resumeRecording)
{
  if (!_audioRecorder.isRecording) {
    [_audioRecorder record];
  }
}

RCT_EXPORT_METHOD(checkAuthorizationStatus:(RCTPromiseResolveBlock)resolve reject:(__unused RCTPromiseRejectBlock)reject)
{
  AVAudioSessionRecordPermission permissionStatus = [[AVAudioSession sharedInstance] recordPermission];
  switch (permissionStatus) {
    case AVAudioSessionRecordPermissionUndetermined:
      resolve(@("undetermined"));
    break;
    case AVAudioSessionRecordPermissionDenied:
      resolve(@("denied"));
      break;
    case AVAudioSessionRecordPermissionGranted:
      resolve(@("granted"));
      break;
    default:
      reject(RCTErrorUnspecified, nil, RCTErrorWithMessage(@("Error checking device authorization status.")));
      break;
  }
}

RCT_EXPORT_METHOD(requestAuthorization:(RCTPromiseResolveBlock)resolve
                  rejecter:(__unused RCTPromiseRejectBlock)reject)
{
  [[AVAudioSession sharedInstance] requestRecordPermission:^(BOOL granted) {
    if(granted) {
      resolve(@YES);
    } else {
      resolve(@NO);
    }
  }];
}

- (NSString *)getPathForDirectory:(int)directory
{
  NSArray *paths = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, YES);
  return [paths firstObject];
}

- (NSDictionary *)constantsToExport
{
  return @{
    @"MainBundlePath": [[NSBundle mainBundle] bundlePath],
    @"NSCachesDirectoryPath": [self getPathForDirectory:NSCachesDirectory],
    @"NSDocumentDirectoryPath": [self getPathForDirectory:NSDocumentDirectory],
    @"NSLibraryDirectoryPath": [self getPathForDirectory:NSLibraryDirectory]
  };
}

- (void)forceStopRecording
{
    [self saveTimestamp];
    [_audioRecorder stop];
    [_recordSession setCategory:AVAudioSessionCategoryPlayback error:nil];
    _prevProgressUpdateTime = nil;
    [self sendRecordingStatus];
}

@end
