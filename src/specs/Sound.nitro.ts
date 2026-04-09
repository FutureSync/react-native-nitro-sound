import type { HybridObject } from 'react-native-nitro-modules';

// Enums
export enum AudioSourceAndroidType {
  DEFAULT = 0,
  MIC = 1,
  VOICE_UPLINK = 2,
  VOICE_DOWNLINK = 3,
  VOICE_CALL = 4,
  CAMCORDER = 5,
  VOICE_RECOGNITION = 6,
  VOICE_COMMUNICATION = 7,
  REMOTE_SUBMIX = 8,
  UNPROCESSED = 9,
  RADIO_TUNER = 1998,
  HOTWORD = 1999,
}

export enum OutputFormatAndroidType {
  DEFAULT = 0,
  THREE_GPP = 1,
  MPEG_4 = 2,
  AMR_NB = 3,
  AMR_WB = 4,
  AAC_ADIF = 5,
  AAC_ADTS = 6,
  OUTPUT_FORMAT_RTP_AVP = 7,
  MPEG_2_TS = 8,
  WEBM = 9,
}

export enum AudioEncoderAndroidType {
  DEFAULT = 0,
  AMR_NB = 1,
  AMR_WB = 2,
  AAC = 3,
  HE_AAC = 4,
  AAC_ELD = 5,
  VORBIS = 6,
}

export type AVEncodingOption =
  | 'lpcm'
  | 'ima4'
  | 'aac'
  | 'MAC3'
  | 'MAC6'
  | 'ulaw'
  | 'alaw'
  | 'mp1'
  | 'mp2'
  | 'mp4'
  | 'alac'
  | 'amr'
  | 'flac'
  | 'opus';

export type AVModeIOSOption =
  | 'gameChatAudio'
  | 'measurement'
  | 'moviePlayback'
  | 'spokenAudio'
  | 'videoChat'
  | 'videoRecording'
  | 'voiceChat'
  | 'voicePrompt';

export enum AVEncoderAudioQualityIOSType {
  min = 0,
  low = 0x20,
  medium = 0x40,
  high = 0x60,
  max = 0x7f,
}

export enum AVLinearPCMBitDepthKeyIOSType {
  bit8 = 8,
  bit16 = 16,
  bit24 = 24,
  bit32 = 32,
}

// Types
export type AudioQualityType = 'low' | 'medium' | 'high';

// Interfaces
// Platform-specific audio settings
export interface IOSAudioSet {
  AVEncoderAudioQualityKeyIOS?: AVEncoderAudioQualityIOSType;
  AVModeIOS?: AVModeIOSOption;
  AVEncodingOptionIOS?: AVEncodingOption;
  AVFormatIDKeyIOS?: AVEncodingOption;
  AVNumberOfChannelsKeyIOS?: number;
  AVLinearPCMBitDepthKeyIOS?: AVLinearPCMBitDepthKeyIOSType;
  AVLinearPCMIsBigEndianKeyIOS?: boolean;
  AVLinearPCMIsFloatKeyIOS?: boolean;
  AVLinearPCMIsNonInterleavedIOS?: boolean;
  AVSampleRateKeyIOS?: number;
}

export interface AndroidAudioSet {
  AudioSourceAndroid?: AudioSourceAndroidType;
  OutputFormatAndroid?: OutputFormatAndroidType;
  AudioEncoderAndroid?: AudioEncoderAndroidType;
}

export interface CommonAudioSet {
  AudioQuality?: AudioQualityType;
  AudioChannels?: number;
  AudioSamplingRate?: number;
  AudioEncodingBitRate?: number;
  IncludeBase64?: boolean;
}

export interface AudioSet
  extends IOSAudioSet, AndroidAudioSet, CommonAudioSet {}

export interface RecordBackType {
  isRecording?: boolean;
  currentPosition: number;
  currentMetering?: number;
  recordSecs?: number;
}

export interface PlayBackType {
  isMuted?: boolean;
  duration: number;
  currentPosition: number;
}

export interface PlaybackEndType {
  duration: number;
  currentPosition: number;
}

export interface RestoredRecording {
  /** Path to the restored M4A file */
  uri: string;
  /** Duration in milliseconds */
  duration: number;
  /** Original WAV file path (before conversion) */
  originalPath: string;
}

export interface AudioValidationResult {
  /** Whether the audio file is valid for upload */
  isValid: boolean;
  /** Duration in seconds (0 if invalid) */
  duration: number;
  /** File size in bytes */
  fileSize: number;
  /** Error message if invalid */
  error?: string;
}

export interface MergeResult {
  /** Path to the merged M4A file */
  outputPath: string;
  /** Duration in seconds */
  duration: number;
  /** Number of input files merged */
  inputCount: number;
}

export type RecordBackListener = (recordingMeta: RecordBackType) => void;
export type PlayBackListener = (playbackMeta: PlayBackType) => void;
export type PlaybackEndListener = (playbackEndMeta: PlaybackEndType) => void;

export interface Sound extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  // Recording methods
  startRecorder(
    uri?: string,
    audioSets?: AudioSet,
    meteringEnabled?: boolean
  ): Promise<string>;
  pauseRecorder(): Promise<string>;
  resumeRecorder(): Promise<string>;
  stopRecorder(): Promise<string>;

  // Playback methods
  startPlayer(
    uri?: string,
    httpHeaders?: Record<string, string>
  ): Promise<string>;
  stopPlayer(): Promise<string>;
  pausePlayer(): Promise<string>;
  resumePlayer(): Promise<string>;
  seekToPlayer(time: number): Promise<string>;
  setVolume(volume: number): Promise<string>;
  setPlaybackSpeed(playbackSpeed: number): Promise<string>;

  // Subscription
  setSubscriptionDuration(sec: number): void;

  // Listeners
  addRecordBackListener(
    callback: (recordingMeta: RecordBackType) => void
  ): void;
  removeRecordBackListener(): void;
  addPlayBackListener(callback: (playbackMeta: PlayBackType) => void): void;
  removePlayBackListener(): void;
  addPlaybackEndListener(
    callback: (playbackEndMeta: PlaybackEndType) => void
  ): void;
  removePlaybackEndListener(): void;

  // Utility methods
  mmss(secs: number): string;
  mmssss(milisecs: number): string;

  // Recovery methods
  /**
   * Restore any pending recordings that were interrupted by app crash.
   * Call this when your app starts to recover any incomplete recordings.
   *
   * @param directory Optional directory to scan. If not provided, uses default recording directory.
   * @returns Array of restored recordings (converted to M4A)
   */
  restorePendingRecordings(directory?: string): Promise<RestoredRecording[]>;

  /**
   * Restore a single WAV recording file by converting it to M4A.
   * Use this when you need to restore a specific file and update your local database.
   *
   * @param wavFilePath Path to the WAV file to restore
   * @returns The restored recording info with new M4A path
   * @throws Error if file doesn't exist or conversion fails
   */
  restoreRecording(wavFilePath: string): Promise<RestoredRecording>;

  // Audio processing methods

  /**
   * Merge multiple audio files (WAV/M4A) into a single M4A.
   * - Repairs WAV headers before merge (iOS + Android)
   * - Validates output duration vs input durations
   * - Does NOT delete input files — caller decides when to delete
   *
   * @param filePaths Array of audio file paths to merge
   * @param outputPath Optional output path. If not provided, generates one in Documents.
   * @returns MergeResult with output path, duration, and input count
   */
  mergeAudioFiles(
    filePaths: string[],
    outputPath?: string
  ): Promise<MergeResult>;

  /**
   * Get duration of an audio file in seconds.
   * Works with WAV, M4A, and other formats supported by the platform.
   *
   * @param filePath Path to the audio file
   * @returns Duration in seconds
   * @throws Error if file is invalid or unreadable
   */
  getAudioDuration(filePath: string): Promise<number>;

  /**
   * Validate an audio file for upload readiness.
   * Checks: file exists, minimum size (1KB), decodable by native APIs, duration >= minDuration.
   *
   * @param filePath Path to the audio file
   * @param minDurationSecs Minimum acceptable duration in seconds (default: 1.0)
   * @returns AudioValidationResult with isValid, duration, fileSize, and optional error
   */
  validateAudio(
    filePath: string,
    minDurationSecs?: number
  ): Promise<AudioValidationResult>;

  /**
   * Reset the recorder to a clean state.
   * Use this to recover from stuck states (e.g., after iOS call interruption
   * where the native recorder was stopped but JS state is still "paused/recording").
   *
   * Stops any active recording, deactivates audio session, and clears all internal state.
   * Safe to call even when no recording is active.
   *
   * @returns "Recorder reset" on success
   */
  resetRecordingState(): Promise<string>;
}
