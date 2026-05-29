package com.margelo.nitro.sound

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import com.margelo.nitro.audiorecorderplayer.Logger
import com.margelo.nitro.audiorecorderplayer.RecordingForegroundService
import com.margelo.nitro.audiorecorderplayer.WavToM4aConverter
import com.margelo.nitro.audiorecorderplayer.WavRecorder

class HybridSound : HybridSoundSpec() {
    private var mediaPlayer: MediaPlayer? = null

    private var playTimer: Timer? = null

    private var recordBackListener: ((recordingMeta: RecordBackType) -> Unit)? = null
    private var playBackListener: ((playbackMeta: PlayBackType) -> Unit)? = null
    private var playbackEndListener: ((playbackEndMeta: PlaybackEndType) -> Unit)? = null

    private var subscriptionDuration: Long = 60L
    
    // Service connection for recording
    private var recordingService: RecordingForegroundService? = null
    private var isServiceBound = false
    private var currentRecordingPath: String? = null

    // Pending recording parameters (used by event-driven service connection)
    private var pendingRecordingParams: PendingRecordingParams? = null
    private var pendingRecordingPromise: Promise<String>? = null

    private data class PendingRecordingParams(
        val filePath: String,
        val audioSource: Int,
        val outputFormat: Int,
        val audioEncoder: Int,
        val samplingRate: Int?,
        val channels: Int?,
        val bitrate: Int?,
        val enableMetering: Boolean,
        val subscriptionDuration: Long
    )

    // Audio focus for call interruption handling
    private var audioManager: AudioManager? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private val handler = Handler(Looper.getMainLooper())

    private val context: Context
        get() = NitroModules.applicationContext ?: throw IllegalStateException("Application context not available")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingForegroundService.RecordingBinder
            recordingService = binder.getService()
            isServiceBound = true
            
            // Setup callback for recording updates
            recordingService?.onRecordingUpdate = { isRecording, currentPosition, metering ->
                handler.post {
                    recordBackListener?.invoke(
                        RecordBackType(
                            isRecording = isRecording,
                            currentPosition = currentPosition,
                            currentMetering = metering,
                            recordSecs = currentPosition
                        )
                    )
                }
            }
            
            // Start pending recording if exists (event-driven, no Thread.sleep)
            val params = pendingRecordingParams
            val promise = pendingRecordingPromise
            if (params != null && promise != null) {
                pendingRecordingParams = null
                pendingRecordingPromise = null
                
                CoroutineScope(Dispatchers.IO).launch {
                    startRecordingOnService(recordingService!!, params, promise)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isServiceBound = false
        }
    }

    // Recording methods
    override fun startRecorder(
        uri: String?,
        audioSets: AudioSet?,
        enableMetering: Boolean?
    ): Promise<String> {
        val promise = Promise<String>()

        // Sanitize audioSets to ignore iOS-specific fields on Android
        val sanitizedAudioSets = audioSets?.copy(
            AVEncoderAudioQualityKeyIOS = null,
            AVModeIOS = null,
            AVEncodingOptionIOS = null,
            AVFormatIDKeyIOS = null,
            AVNumberOfChannelsKeyIOS = null,
            AVLinearPCMBitDepthKeyIOS = null,
            AVLinearPCMIsBigEndianKeyIOS = null,
            AVLinearPCMIsFloatKeyIOS = null,
            AVLinearPCMIsNonInterleavedIOS = null,
            AVSampleRateKeyIOS = null
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create file path (WAV format for crash-resilient recording)
                val filePath = uri ?: run {
                    val dir = context.filesDir
                    val fileName = "sound_${System.currentTimeMillis()}.wav"
                    File(dir, fileName).absolutePath
                }
                currentRecordingPath = filePath

                // Get audio settings
                val audioSource = when (sanitizedAudioSets?.AudioSourceAndroid) {
                    AudioSourceAndroidType.DEFAULT -> MediaRecorder.AudioSource.DEFAULT
                    AudioSourceAndroidType.MIC -> MediaRecorder.AudioSource.MIC
                    AudioSourceAndroidType.VOICE_UPLINK -> MediaRecorder.AudioSource.VOICE_UPLINK
                    AudioSourceAndroidType.VOICE_DOWNLINK -> MediaRecorder.AudioSource.VOICE_DOWNLINK
                    AudioSourceAndroidType.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
                    AudioSourceAndroidType.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
                    AudioSourceAndroidType.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                    AudioSourceAndroidType.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    AudioSourceAndroidType.REMOTE_SUBMIX -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        MediaRecorder.AudioSource.REMOTE_SUBMIX
                    } else {
                        MediaRecorder.AudioSource.MIC
                    }
                    AudioSourceAndroidType.UNPROCESSED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        MediaRecorder.AudioSource.UNPROCESSED
                    } else {
                        MediaRecorder.AudioSource.MIC
                    }
                    AudioSourceAndroidType.RADIO_TUNER -> MediaRecorder.AudioSource.MIC
                    AudioSourceAndroidType.HOTWORD -> MediaRecorder.AudioSource.MIC
                    null -> MediaRecorder.AudioSource.MIC
                }

                val outputFormat = when (sanitizedAudioSets?.OutputFormatAndroid) {
                    OutputFormatAndroidType.DEFAULT -> MediaRecorder.OutputFormat.DEFAULT
                    OutputFormatAndroidType.THREE_GPP -> MediaRecorder.OutputFormat.THREE_GPP
                    OutputFormatAndroidType.MPEG_4 -> MediaRecorder.OutputFormat.MPEG_4
                    OutputFormatAndroidType.AMR_NB -> MediaRecorder.OutputFormat.AMR_NB
                    OutputFormatAndroidType.AMR_WB -> MediaRecorder.OutputFormat.AMR_WB
                    OutputFormatAndroidType.AAC_ADIF -> MediaRecorder.OutputFormat.MPEG_4
                    OutputFormatAndroidType.AAC_ADTS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        MediaRecorder.OutputFormat.AAC_ADTS
                    } else {
                        MediaRecorder.OutputFormat.MPEG_4
                    }
                    OutputFormatAndroidType.OUTPUT_FORMAT_RTP_AVP -> MediaRecorder.OutputFormat.MPEG_4
                    OutputFormatAndroidType.MPEG_2_TS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        MediaRecorder.OutputFormat.MPEG_2_TS
                    } else {
                        MediaRecorder.OutputFormat.MPEG_4
                    }
                    OutputFormatAndroidType.WEBM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        MediaRecorder.OutputFormat.WEBM
                    } else {
                        MediaRecorder.OutputFormat.MPEG_4
                    }
                    null -> MediaRecorder.OutputFormat.MPEG_4
                }

                val audioEncoder = when (sanitizedAudioSets?.AudioEncoderAndroid) {
                    AudioEncoderAndroidType.DEFAULT -> MediaRecorder.AudioEncoder.DEFAULT
                    AudioEncoderAndroidType.AMR_NB -> MediaRecorder.AudioEncoder.AMR_NB
                    AudioEncoderAndroidType.AMR_WB -> MediaRecorder.AudioEncoder.AMR_WB
                    AudioEncoderAndroidType.AAC -> MediaRecorder.AudioEncoder.AAC
                    AudioEncoderAndroidType.HE_AAC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        MediaRecorder.AudioEncoder.HE_AAC
                    } else {
                        MediaRecorder.AudioEncoder.AAC
                    }
                    AudioEncoderAndroidType.AAC_ELD -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        MediaRecorder.AudioEncoder.AAC_ELD
                    } else {
                        MediaRecorder.AudioEncoder.AAC
                    }
                    AudioEncoderAndroidType.VORBIS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        MediaRecorder.AudioEncoder.VORBIS
                    } else {
                        MediaRecorder.AudioEncoder.AAC
                    }
                    null -> MediaRecorder.AudioEncoder.AAC
                }

                // Quality settings
                val audioQuality = sanitizedAudioSets?.AudioQuality ?: AudioQualityType.HIGH
                data class QualitySettings(val samplingRate: Int, val channels: Int, val bitrate: Int)
                val presets = mapOf(
                    AudioQualityType.LOW to QualitySettings(22050, 1, 64000),
                    AudioQualityType.MEDIUM to QualitySettings(44100, 1, 128000),
                    AudioQualityType.HIGH to QualitySettings(48000, 2, 192000)
                )
                val defaults = presets[audioQuality]

                val samplingRate = sanitizedAudioSets?.AudioSamplingRate?.toInt() ?: defaults?.samplingRate
                val channels = sanitizedAudioSets?.AudioChannels?.toInt() ?: defaults?.channels
                val bitrate = sanitizedAudioSets?.AudioEncodingBitRate?.toInt() ?: defaults?.bitrate

                // Store pending recording params for event-driven start
                val params = PendingRecordingParams(
                    filePath = filePath,
                    audioSource = audioSource,
                    outputFormat = outputFormat,
                    audioEncoder = audioEncoder,
                    samplingRate = samplingRate,
                    channels = channels,
                    bitrate = bitrate,
                    enableMetering = enableMetering ?: false,
                    subscriptionDuration = subscriptionDuration
                )
                
                // Check if service is already bound and available
                val existingService = recordingService
                if (isServiceBound && existingService != null) {
                    // Service already connected, start recording directly
                    startRecordingOnService(existingService, params, promise)
                } else {
                    // Reject any previous pending promise to avoid orphaned promises
                    pendingRecordingPromise?.reject(Exception("Recording superseded by a new startRecorder call"))
                    
                    // Store pending params - will be picked up in onServiceConnected
                    pendingRecordingParams = params
                    pendingRecordingPromise = promise
                    
                    // Start the foreground service and bind
                    handler.post {
                        RecordingForegroundService.start(context)
                        
                        val intent = Intent(context, RecordingForegroundService::class.java)
                        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                        
                        setupAudioFocus()
                    }
                }
            } catch (e: Exception) {
                pendingRecordingParams = null
                pendingRecordingPromise = null
                cleanupServiceOnError()
                promise.reject(e)
            }
        }

        return promise
    }

    override fun pauseRecorder(): Promise<String> {
        return Promise.parallel {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val service = RecordingForegroundService.getInstance()
                if (service != null && service.pauseRecording()) {
                    "Recorder paused"
                } else {
                    throw Exception("Failed to pause recording")
                }
            } else {
                throw Exception("Pause is not supported on Android API < 24")
            }
        }
    }

    override fun resumeRecorder(): Promise<String> {
        return Promise.parallel {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val service = RecordingForegroundService.getInstance()
                if (service != null && service.resumeRecording()) {
                    "Recorder resumed"
                } else {
                    throw Exception("Failed to resume recording")
                }
            } else {
                throw Exception("Resume is not supported on Android API < 24")
            }
        }
    }

    override fun resetRecordingState(): Promise<String> {
        return Promise.parallel {
            try {
                val service = RecordingForegroundService.getInstance()
                service?.stopRecording()
            } catch (e: Exception) {
                Logger.d("[resetRecordingState] Stop during reset failed (safe to ignore): ${e.message}")
            }

            handler.post {
                // Note: The recording timer lives inside RecordingForegroundService
                // and is stopped automatically by service.stopRecording() above.
                // No local timer to cancel here.
                if (isServiceBound) {
                    try {
                        context.unbindService(serviceConnection)
                    } catch (_: Exception) {}
                    isServiceBound = false
                }
            }

            currentRecordingPath = null
            recordBackListener = null

            "Recorder reset"
        }
    }

    override fun stopRecorder(): Promise<String> {
        val promise = Promise<String>()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val service = RecordingForegroundService.getInstance()
                val wavPath = service?.stopRecording() ?: currentRecordingPath
                
                handler.post {
                    // Unbind from service
                    if (isServiceBound) {
                        try {
                            context.unbindService(serviceConnection)
                        } catch (e: Exception) {
                            // Ignore unbind errors
                        }
                        isServiceBound = false
                    }
                    
                    // Stop the service
                    RecordingForegroundService.stop(context)
                    
                    // Release audio focus
                    releaseAudioFocus()
                }

                currentRecordingPath = null
                
                if (wavPath == null) {
                    promise.reject(Exception("Recorder not started or path is unavailable."))
                    return@launch
                }

                // Return WAV immediately so JS can dismiss UI; convert via restoreRecording / app pipeline.
                WavRecorder.repairWavFile(wavPath)
                val wavFile = File(wavPath)
                if (!wavFile.exists()) {
                    promise.reject(Exception("Recording file not found after stop: $wavPath"))
                    return@launch
                }
                val fileUri = Uri.fromFile(wavFile).toString()
                Logger.d("[Sound] stopRecorder returning WAV; convert in restoreRecording or app layer")
                promise.resolve(fileUri)
            } catch (e: Exception) {
                handler.post {
                    if (isServiceBound) {
                        try {
                            context.unbindService(serviceConnection)
                        } catch (ex: Exception) {
                            // Ignore
                        }
                        isServiceBound = false
                    }
                    RecordingForegroundService.stop(context)
                    releaseAudioFocus()
                }
                promise.reject(e)
            }
        }

        return promise
    }

    // Playback methods
    override fun startPlayer(
        uri: String?,
        httpHeaders: Map<String, String>?
    ): Promise<String> {
        val promise = Promise<String>()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (uri == null) {
                    promise.reject(Exception("URI is required"))
                    return@launch
                }

                // Clean up any existing player first
                mediaPlayer?.let { existingPlayer ->
                    try {
                        if (existingPlayer.isPlaying) {
                            existingPlayer.stop()
                        }
                        existingPlayer.reset()
                        existingPlayer.release()
                    } catch (e: Exception) {
                        // Ignore cleanup errors
                    }
                }

                handler.post {
                    stopPlayTimer()
                }

                mediaPlayer = MediaPlayer().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setAudioStreamType(AudioManager.STREAM_MUSIC)
                    }

                    val isPromiseResolved = java.util.concurrent.atomic.AtomicBoolean(false)

                    setOnErrorListener { _, what, extra ->
                        handler.post {
                            stopPlayTimer()
                            if (isPromiseResolved.compareAndSet(false, true)) {
                                promise.reject(Exception("MediaPlayer error: what=$what, extra=$extra"))
                            }
                        }
                        true
                    }

                    setOnCompletionListener { player ->
                        handler.post {
                            stopPlayTimer()

                            val safeDuration = try {
                                player.duration.toDouble()
                            } catch (e: IllegalStateException) {
                                0.0
                            }

                            playBackListener?.invoke(
                                PlayBackType(
                                    isMuted = false,
                                    duration = safeDuration,
                                    currentPosition = safeDuration
                                )
                            )

                            playbackEndListener?.invoke(
                                PlaybackEndType(
                                    duration = safeDuration,
                                    currentPosition = safeDuration
                                )
                            )
                        }
                    }

                    when {
                        uri.startsWith("http") -> {
                            val headers = httpHeaders ?: emptyMap()
                            setDataSource(context, Uri.parse(uri), headers)
                        }
                        uri.startsWith("content://") -> {
                            setDataSource(context, Uri.parse(uri))
                        }
                        uri.startsWith("file://") -> {
                            setDataSource(context, Uri.parse(uri))
                        }
                        else -> {
                            setDataSource(uri)
                        }
                    }

                    prepare()

                    handler.post {
                        try {
                            if (isPromiseResolved.compareAndSet(false, true)) {
                                start()
                                startPlayTimer()
                                promise.resolve(uri)
                            }
                        } catch (e: Exception) {
                            if (isPromiseResolved.compareAndSet(false, true)) {
                                promise.reject(Exception("Failed to start MediaPlayer: ${e.message}", e))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                promise.reject(e)
            }
        }

        return promise
    }

    override fun stopPlayer(): Promise<String> {
        val promise = Promise<String>()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.stop()
                    }
                    player.reset()
                    player.release()
                }
                mediaPlayer = null

                handler.post {
                    stopPlayTimer()
                }

                promise.resolve("Player stopped")
            } catch (e: Exception) {
                try {
                    mediaPlayer?.reset()
                    mediaPlayer?.release()
                } catch (releaseError: Exception) {
                    // Ignore
                }
                mediaPlayer = null

                handler.post {
                    stopPlayTimer()
                }

                promise.reject(e)
            }
        }

        return promise
    }

    override fun pausePlayer(): Promise<String> {
        return Promise.parallel {
            mediaPlayer?.pause()
            stopPlayTimer()
            "Player paused"
        }
    }

    override fun resumePlayer(): Promise<String> {
        return Promise.parallel {
            mediaPlayer?.start()
            startPlayTimer()
            "Player resumed"
        }
    }

    override fun seekToPlayer(time: Double): Promise<String> {
        return Promise.parallel {
            mediaPlayer?.seekTo(time.toInt())
            "Seeked to ${time}ms"
        }
    }

    override fun setVolume(volume: Double): Promise<String> {
        return Promise.parallel {
            val volumeFloat = volume.toFloat()
            mediaPlayer?.setVolume(volumeFloat, volumeFloat)
            "Volume set to $volume"
        }
    }

    override fun setPlaybackSpeed(playbackSpeed: Double): Promise<String> {
        return Promise.parallel {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val player = mediaPlayer ?: throw Exception("No player instance")
                try {
                    val params = try {
                        player.playbackParams
                    } catch (_: Exception) {
                        android.media.PlaybackParams()
                    }
                    params.speed = playbackSpeed.toFloat()
                    player.playbackParams = params
                    "Playback speed set to $playbackSpeed"
                } catch (e: Exception) {
                    throw e
                }
            } else {
                throw Exception("Playback speed is not supported on Android API < 23")
            }
        }
    }

    // Subscription
    override fun setSubscriptionDuration(sec: Double) {
        subscriptionDuration = (sec * 1000).toLong()
    }

    // Listeners
    override fun addRecordBackListener(callback: (recordingMeta: RecordBackType) -> Unit) {
        recordBackListener = callback
    }

    override fun removeRecordBackListener() {
        recordBackListener = null
    }

    override fun addPlayBackListener(callback: (playbackMeta: PlayBackType) -> Unit) {
        playBackListener = callback
    }

    override fun removePlayBackListener() {
        playBackListener = null
    }

    override fun addPlaybackEndListener(callback: (playbackEndMeta: PlaybackEndType) -> Unit) {
        handler.post {
            playbackEndListener = callback
        }
    }

    override fun removePlaybackEndListener() {
        handler.post {
            playbackEndListener = null
        }
    }

    // Utility methods
    override fun mmss(secs: Double): String {
        val totalSeconds = secs.toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun mmssss(milisecs: Double): String {
        val totalSeconds = (milisecs / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val milliseconds = ((milisecs % 1000) / 10).toInt()
        return String.format("%02d:%02d:%02d", minutes, seconds, milliseconds)
    }

    // Recovery methods
    /**
     * Restore any pending recordings that were interrupted by app crash.
     * Scans for WAV files, repairs them if needed, converts to M4A, and returns the results.
     */
    override fun restorePendingRecordings(directory: String?): Promise<Array<RestoredRecording>> {
        val promise = Promise<Array<RestoredRecording>>()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scanDir = if (directory != null) {
                    val dir = File(directory)
                    // Validate path to prevent directory traversal attacks
                    if (!validatePathSecurity(dir.absolutePath)) {
                        promise.reject(Exception("Access denied: directory is outside allowed paths"))
                        return@launch
                    }
                    dir
                } else {
                    context.filesDir
                }
                
                if (!scanDir.exists() || !scanDir.isDirectory) {
                    promise.resolve(emptyArray())
                    return@launch
                }
                
                // Find all WAV files
                val wavFiles = scanDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".wav", ignoreCase = true)
                } ?: emptyArray()
                
                if (wavFiles.isEmpty()) {
                    promise.resolve(emptyArray())
                    return@launch
                }
                
                val restoredRecordings = mutableListOf<RestoredRecording>()
                
                for (wavFile in wavFiles) {
                    try {
                        val wavPath = wavFile.absolutePath
                        
                        // Repair WAV header if needed
                        WavRecorder.repairWavFile(wavPath)
                        
                        // Convert to M4A
                        val result = WavToM4aConverter.convert(
                            wavFilePath = wavPath,
                            deleteWavAfterConversion = true
                        )
                        
                        when (result) {
                            is WavToM4aConverter.ConversionResult.Success -> {
                                val fileUri = Uri.fromFile(File(result.outputPath)).toString()
                                restoredRecordings.add(
                                    RestoredRecording(
                                        uri = fileUri,
                                        duration = result.duration.toDouble(),
                                        originalPath = wavPath
                                    )
                                )
                            }
                            is WavToM4aConverter.ConversionResult.Error -> {
                                Logger.w("[Sound] WAV→M4A conversion failed for $wavPath: ${result.message}. Returning WAV file.")
                                if (wavFile.exists()) {
                                    val fileUri = Uri.fromFile(wavFile).toString()
                                    val estimatedDuration = estimateWavDuration(wavPath)
                                    restoredRecordings.add(
                                        RestoredRecording(
                                            uri = fileUri,
                                            duration = estimatedDuration,
                                            originalPath = wavPath
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Log error but continue with other files
                        Logger.e("[Sound] Error restoring recording ${wavFile.name}: ${e.message}", e)
                    }
                }
                
                promise.resolve(restoredRecordings.toTypedArray())
            } catch (e: Exception) {
                promise.reject(e)
            }
        }
        
        return promise
    }

    /**
     * Restore a single WAV recording file by converting it to M4A.
     * Use this when you need to restore a specific file and update your local database.
     */
    override fun restoreRecording(wavFilePath: String): Promise<RestoredRecording> {
        val promise = Promise<RestoredRecording>()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Validate path to prevent path traversal attacks
                if (!validatePathSecurity(wavFilePath)) {
                    promise.reject(Exception("Access denied: file path is outside allowed paths"))
                    return@launch
                }
                
                val wavFile = File(wavFilePath)
                
                if (!wavFile.exists()) {
                    promise.reject(Exception("WAV file not found: $wavFilePath"))
                    return@launch
                }
                
                if (!wavFile.name.endsWith(".wav", ignoreCase = true)) {
                    promise.reject(Exception("File is not a WAV file: $wavFilePath"))
                    return@launch
                }
                
                // Repair WAV header if needed
                WavRecorder.repairWavFile(wavFilePath)
                
                // Convert to M4A
                val result = WavToM4aConverter.convert(
                    wavFilePath = wavFilePath,
                    deleteWavAfterConversion = true
                )
                
                when (result) {
                    is WavToM4aConverter.ConversionResult.Success -> {
                        val fileUri = Uri.fromFile(File(result.outputPath)).toString()
                        promise.resolve(
                            RestoredRecording(
                                uri = fileUri,
                                duration = result.duration.toDouble(),
                                originalPath = wavFilePath
                            )
                        )
                    }
                    is WavToM4aConverter.ConversionResult.Error -> {
                        Logger.w("[Sound] WAV→M4A conversion failed for $wavFilePath: ${result.message}. Returning WAV file.")
                        if (wavFile.exists()) {
                            val fileUri = Uri.fromFile(wavFile).toString()
                            val estimatedDuration = estimateWavDuration(wavFilePath)
                            promise.resolve(
                                RestoredRecording(
                                    uri = fileUri,
                                    duration = estimatedDuration,
                                    originalPath = wavFilePath
                                )
                            )
                        } else {
                            promise.reject(Exception("Conversion failed: ${result.message}"))
                        }
                    }
                }
            } catch (e: Exception) {
                promise.reject(e)
            }
        }
        
        return promise
    }

    override fun mergeAudioFiles(filePaths: Array<String>, outputPath: String?): Promise<MergeResult> {
        val promise = Promise<MergeResult>()

        CoroutineScope(Dispatchers.IO).launch {
            val tempFilesToDelete = java.util.Collections.synchronizedList(mutableListOf<File>())
            try {
                if (filePaths.isEmpty()) {
                    promise.reject(Exception("No input files"))
                    return@launch
                }

                val resolvedOut = outputPath?.trim()?.takeIf { it.isNotEmpty() }
                    ?: File(context.filesDir, "merged_${System.currentTimeMillis()}.m4a").absolutePath

                if (resolvedOut.isNotEmpty() && !validatePathSecurity(resolvedOut)) {
                    promise.reject(Exception("Access denied: output path is outside allowed paths"))
                    return@launch
                }

                Logger.d("[Merge] Starting merge: ${filePaths.size} files → $resolvedOut")

                var totalInputSize = 0L
                var skippedCount = 0

                // Validate all paths and collect metadata first
                data class InputSegment(val index: Int, val path: String, val isWav: Boolean, val size: Long)
                val inputs = mutableListOf<InputSegment>()

                for ((index, rawPath) in filePaths.withIndex()) {
                    if (!validatePathSecurity(rawPath)) {
                        promise.reject(Exception("Access denied: input path is outside allowed paths: $rawPath"))
                        return@launch
                    }

                    val inputFile = File(rawPath)
                    if (!inputFile.exists()) {
                        promise.reject(Exception("File not found: $rawPath"))
                        return@launch
                    }

                    totalInputSize += inputFile.length()
                    inputs.add(InputSegment(index, rawPath, rawPath.endsWith(".wav", ignoreCase = true), inputFile.length()))
                }

                // Promote WAV inputs that have a companion streaming-encoded M4A
                val promoted = mutableListOf<InputSegment>()
                val remaining = mutableListOf<InputSegment>()
                for (seg in inputs) {
                    if (seg.isWav) {
                        val companionM4a = seg.path.replace(Regex("\\.wav$", RegexOption.IGNORE_CASE), ".m4a")
                        val companionFile = File(companionM4a)
                        if (companionFile.exists() && companionFile.length() > 0) {
                            Logger.d("[Merge] Using streaming-encoded M4A for ${File(seg.path).name}")
                            promoted.add(InputSegment(seg.index, companionM4a, false, companionFile.length()))
                        } else {
                            remaining.add(seg)
                        }
                    } else {
                        remaining.add(seg)
                    }
                }
                val allInputs = (promoted + remaining).sortedBy { it.index }
                val wavInputs = allInputs.filter { it.isWav }
                val m4aInputs = allInputs.filter { !it.isWav }

                Logger.d("[Merge] Inputs: wav=${wavInputs.size} m4a=${m4aInputs.size} promoted=${promoted.size}")

                // Fastest path: all WAVs were streaming-encoded → single M4A copy, zero conversion
                if (wavInputs.isEmpty() && m4aInputs.size == 1) {
                    val seg = m4aInputs[0]
                    File(seg.path).copyTo(File(resolvedOut), overwrite = true)
                    val outFile = File(resolvedOut)
                    if (!outFile.exists() || outFile.length() == 0L) {
                        promise.reject(Exception("Merge failed: output file missing or empty"))
                        return@launch
                    }
                    val durationSec = getAudioDurationSecondsOrThrow(resolvedOut)
                    val outSize = outFile.length()
                    Logger.d("[Merge] COMPLETE (streaming): path=$resolvedOut, duration=${String.format("%.2f", durationSec)}s, size=${outSize / 1024}KB")
                    promise.resolve(MergeResult(outputPath = resolvedOut, duration = durationSec, inputCount = 1.0))
                    return@launch
                }

                // Fast path: single WAV input → convert directly to output, skip concatenation
                if (wavInputs.size == 1 && m4aInputs.isEmpty()) {
                    val seg = wavInputs[0]
                    WavRecorder.repairWavFile(seg.path)
                    Logger.d("[Merge] Single-WAV fast path: ${File(seg.path).name} (${seg.size}B) → $resolvedOut")
                    val conv = WavToM4aConverter.convertSync(
                        wavFilePath = seg.path,
                        m4aFilePath = resolvedOut,
                        deleteWavAfterConversion = false
                    )
                    when (conv) {
                        is WavToM4aConverter.ConversionResult.Success -> {
                            val outFile = File(resolvedOut)
                            if (!outFile.exists() || outFile.length() == 0L) {
                                promise.reject(Exception("Merge failed: output file missing or empty"))
                                return@launch
                            }
                            val durationSec = getAudioDurationSecondsOrThrow(resolvedOut)
                            val outSize = outFile.length()
                            Logger.d("[Merge] COMPLETE (fast): path=$resolvedOut, duration=${String.format("%.2f", durationSec)}s, size=${outSize / 1024}KB")
                            promise.resolve(MergeResult(outputPath = resolvedOut, duration = durationSec, inputCount = 1.0))
                        }
                        is WavToM4aConverter.ConversionResult.Error -> {
                            Logger.e("[Merge] Single-WAV convert failed: ${conv.message}")
                            promise.reject(Exception("WAV conversion failed: ${conv.message}"))
                        }
                    }
                    return@launch
                }

                val conversionResults = if (wavInputs.size > 1) {
                    Logger.d("[Merge] Converting ${wavInputs.size} WAV segments in parallel")
                    wavInputs.map { seg ->
                        async(Dispatchers.IO) {
                            WavRecorder.repairWavFile(seg.path)
                            val tempM4a = File(context.cacheDir, "merge_seg_${System.currentTimeMillis()}_${seg.index}.m4a")
                            tempFilesToDelete.add(tempM4a)
                            Logger.d("[Merge] Input[${seg.index}]: ${File(seg.path).name} (WAV, ${seg.size}B) → converting")
                            val conv = WavToM4aConverter.convertSync(
                                wavFilePath = seg.path,
                                m4aFilePath = tempM4a.absolutePath,
                                deleteWavAfterConversion = false
                            )
                            Pair(seg, conv)
                        }
                    }.awaitAll()
                } else {
                    wavInputs.map { seg ->
                        WavRecorder.repairWavFile(seg.path)
                        val tempM4a = File(context.cacheDir, "merge_seg_${System.currentTimeMillis()}_${seg.index}.m4a")
                        tempFilesToDelete.add(tempM4a)
                        Logger.d("[Merge] Input[${seg.index}]: ${File(seg.path).name} (WAV, ${seg.size}B) → converting")
                        val conv = WavToM4aConverter.convertSync(
                            wavFilePath = seg.path,
                            m4aFilePath = tempM4a.absolutePath,
                            deleteWavAfterConversion = false
                        )
                        Pair(seg, conv)
                    }
                }

                // Build ordered segment paths (preserving original order)
                val segmentPaths = mutableListOf<String>()
                val convertedMap = conversionResults.associate { (seg, result) -> seg.index to result }

                for (seg in allInputs) {
                    if (seg.isWav) {
                        when (val conv = convertedMap[seg.index]) {
                            is WavToM4aConverter.ConversionResult.Success ->
                                segmentPaths.add(conv.outputPath)
                            is WavToM4aConverter.ConversionResult.Error -> {
                                Logger.w("[Merge] SKIP WAV (conversion failed): ${File(seg.path).name} — ${conv.message}")
                                skippedCount++
                            }
                            else -> { skippedCount++ }
                        }
                    } else {
                        if (!hasAudioTrack(seg.path)) {
                            Logger.d("[Merge] SKIP file (no audio track): ${File(seg.path).name}")
                            skippedCount++
                            continue
                        }
                        Logger.d("[Merge] Input[${seg.index}]: ${File(seg.path).name} (M4A, ${seg.size}B)")
                        segmentPaths.add(seg.path)
                    }
                }

                if (segmentPaths.isEmpty()) {
                    promise.reject(Exception("No audio tracks in inputs"))
                    return@launch
                }

                Logger.d("[Merge] Total input: ${segmentPaths.size} segments, ${totalInputSize / 1024}KB (skipped: $skippedCount)")

                File(resolvedOut).parentFile?.mkdirs()
                val mergedCount: Int
                if (segmentPaths.size == 1) {
                    val singleSrc = File(segmentPaths[0])
                    val outTarget = File(resolvedOut)
                    singleSrc.copyTo(outTarget, overwrite = true)
                    mergedCount = 1
                    Logger.d("[Merge] Single-segment fast path: copied ${singleSrc.name} → ${outTarget.name}")
                } else {
                    mergedCount = concatenateM4aFiles(segmentPaths, resolvedOut)
                }

                val outFile = File(resolvedOut)
                if (!outFile.exists() || outFile.length() == 0L) {
                    promise.reject(Exception("Merge failed: output file missing or empty"))
                    return@launch
                }

                val durationSec = getAudioDurationSecondsOrThrow(resolvedOut)
                val outSize = outFile.length()

                Logger.d("[Merge] COMPLETE: path=$resolvedOut, duration=${String.format("%.2f", durationSec)}s, size=${outSize / 1024}KB, mergedSegments=$mergedCount")

                promise.resolve(
                    MergeResult(
                        outputPath = resolvedOut,
                        duration = durationSec,
                        inputCount = mergedCount.toDouble()
                    )
                )
            } catch (e: Exception) {
                Logger.e("[Merge] Failed: ${e.message}", e)
                promise.reject(e)
            } finally {
                for (f in tempFilesToDelete) {
                    try {
                        if (f.exists()) f.delete()
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }
        }

        return promise
    }

    override fun getAudioDuration(filePath: String): Promise<Double> {
        val promise = Promise<Double>()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val duration = getAudioDurationSecondsOrThrow(filePath)
                promise.resolve(duration)
            } catch (e: Exception) {
                promise.reject(e)
            }
        }

        return promise
    }

    override fun validateAudio(filePath: String, minDurationSecs: Double?): Promise<AudioValidationResult> {
        val promise = Promise<AudioValidationResult>()
        val minSec = minDurationSecs ?: 1.0

        CoroutineScope(Dispatchers.IO).launch {
            val file = File(filePath)
            if (!file.exists()) {
                promise.resolve(
                    AudioValidationResult(
                        isValid = false,
                        duration = 0.0,
                        fileSize = 0.0,
                        error = "File not found"
                    )
                )
                return@launch
            }

            val fileSize = file.length().toDouble()
            if (fileSize < 1024) {
                promise.resolve(
                    AudioValidationResult(
                        isValid = false,
                        duration = 0.0,
                        fileSize = fileSize,
                        error = "File too small"
                    )
                )
                return@launch
            }

            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(filePath)
                    val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (ms == null || ms <= 0L) {
                        promise.resolve(
                            AudioValidationResult(
                                isValid = false,
                                duration = 0.0,
                                fileSize = fileSize,
                                error = "Cannot determine duration"
                            )
                        )
                        return@launch
                    }
                    val durationSec = ms / 1000.0
                    if (durationSec < minSec) {
                        promise.resolve(
                            AudioValidationResult(
                                isValid = false,
                                duration = durationSec,
                                fileSize = fileSize,
                                error = "Duration too short"
                            )
                        )
                        return@launch
                    }
                    promise.resolve(
                        AudioValidationResult(
                            isValid = true,
                            duration = durationSec,
                            fileSize = fileSize,
                            error = null
                        )
                    )
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            } catch (_: Exception) {
                promise.resolve(
                    AudioValidationResult(
                        isValid = false,
                        duration = 0.0,
                        fileSize = fileSize,
                        error = "Cannot determine duration"
                    )
                )
            }
        }

        return promise
    }

    // Private methods
    
    /**
     * Start recording on a connected service instance.
     * This is called either directly (if service already bound) or from onServiceConnected.
     */
    private fun startRecordingOnService(
        service: RecordingForegroundService,
        params: PendingRecordingParams,
        promise: Promise<String>
    ) {
        try {
            val success = service.startRecording(
                filePath = params.filePath,
                audioSource = params.audioSource,
                outputFormat = params.outputFormat,
                audioEncoder = params.audioEncoder,
                samplingRate = params.samplingRate,
                channels = params.channels,
                bitrate = params.bitrate,
                enableMetering = params.enableMetering,
                subscriptionDuration = params.subscriptionDuration
            )
            
            if (success) {
                val fileUri = Uri.fromFile(File(params.filePath)).toString()
                promise.resolve(fileUri)
            } else {
                cleanupServiceOnError()
                promise.reject(Exception("Failed to start recording in service"))
            }
        } catch (e: Exception) {
            cleanupServiceOnError()
            promise.reject(e)
        }
    }
    
    /**
     * Cleanup service binding and stop service when an error occurs.
     */
    private fun cleanupServiceOnError() {
        handler.post {
            if (isServiceBound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (ex: Exception) {
                    // Ignore unbind errors
                }
                isServiceBound = false
            }
            RecordingForegroundService.stop(context)
            releaseAudioFocus()
        }
    }
    
    /**
     * Estimate WAV file duration by reading the byte rate from the WAV header.
     * Falls back to a default assumption (44100Hz mono 16-bit) if header cannot be read.
     *
     * @param filePath Path to the WAV file
     * @return Estimated duration in milliseconds
     */
    private fun estimateWavDuration(filePath: String): Double {
        return try {
            val file = File(filePath)
            if (!file.exists() || file.length() < 44) return 0.0
            
            RandomAccessFile(file, "r").use { raf ->
                // Read byte rate from WAV header offset 28 (little-endian Int32)
                raf.seek(28)
                val bytes = ByteArray(4)
                raf.readFully(bytes)
                val byteRate = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                
                if (byteRate > 0) {
                    val dataSize = file.length() - 44
                    (dataSize.toDouble() / byteRate) * 1000.0
                } else {
                    // Fallback: assume 44100Hz, 16-bit, mono
                    (file.length() - 44) / 88.2
                }
            }
        } catch (e: Exception) {
            Logger.w("[Sound] Could not read WAV header for duration estimation: ${e.message}")
            val file = File(filePath)
            if (file.exists()) (file.length() - 44) / 88.2 else 0.0
        }
    }
    
    /**
     * Validate that the given path is within allowed directories.
     * Prevents path traversal attacks by ensuring the path is under
     * the app's files directory or cache directory.
     */
    private fun validatePathSecurity(path: String): Boolean {
        val canonicalPath = File(path).canonicalPath
        val allowedDirs = listOf(
            context.filesDir.canonicalPath,
            context.cacheDir.canonicalPath,
            context.getExternalFilesDir(null)?.canonicalPath
        ).filterNotNull()
        
        return allowedDirs.any { canonicalPath.startsWith(it) }
    }

    private fun hasAudioTrack(filePath: String): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return true
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun isAudioFormatCompatible(ref: MediaFormat, other: MediaFormat): Boolean {
        val mimeA = ref.getString(MediaFormat.KEY_MIME) ?: return false
        val mimeB = other.getString(MediaFormat.KEY_MIME) ?: return false
        if (mimeA != mimeB) return false
        if (ref.containsKey(MediaFormat.KEY_SAMPLE_RATE) && other.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            if (ref.getInteger(MediaFormat.KEY_SAMPLE_RATE) != other.getInteger(MediaFormat.KEY_SAMPLE_RATE)) {
                return false
            }
        }
        if (ref.containsKey(MediaFormat.KEY_CHANNEL_COUNT) && other.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            if (ref.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != other.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) {
                return false
            }
        }
        return true
    }

    /**
     * Concatenates AAC/M4A segments with identical audio format (mime, sample rate, channels).
     * WAV inputs must be converted to M4A before calling this.
     * @return the number of segments actually written to the output
     */
    private fun concatenateM4aFiles(segmentPaths: List<String>, outputPath: String): Int {
        if (segmentPaths.isEmpty()) throw Exception("No segments to merge")

        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) {
            outFile.delete()
        }

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerTrackIndex = -1
        var cumulativeOffsetUs = 0L
        var referenceFormat: MediaFormat? = null
        var mergedCount = 0
        // Compute sample duration for the PTS gap between segments (one AAC frame = 1024 samples)
        var sampleDurationUs = 23220L // default ~23ms for 44100Hz AAC

        try {
            val muxerInstance = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = muxerInstance

            for ((segmentIndex, path) in segmentPaths.withIndex()) {
                val extractor = MediaExtractor()
                extractor.setDataSource(path)

                var audioTrackIndex = -1
                var trackFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        trackFormat = f
                        break
                    }
                }

                if (audioTrackIndex < 0 || trackFormat == null) {
                    extractor.release()
                    Logger.w("[Merge] SKIP segment (no audio track): $path")
                    continue
                }

                if (segmentIndex == 0 || referenceFormat == null) {
                    referenceFormat = trackFormat
                    muxerTrackIndex = muxerInstance.addTrack(trackFormat)
                    muxerInstance.start()
                    muxerStarted = true

                    if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val sr = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (sr > 0) {
                            sampleDurationUs = (1024L * 1_000_000L) / sr
                        }
                    }
                } else {
                    if (!isAudioFormatCompatible(referenceFormat!!, trackFormat)) {
                        extractor.release()
                        throw Exception("Incompatible audio: sample rate or channels differ between inputs")
                    }
                }

                extractor.selectTrack(audioTrackIndex)

                val bufferSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxOf(64 * 1024, trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                } else {
                    256 * 1024
                }
                val buffer = ByteBuffer.allocate(bufferSize)

                var segmentMaxPtsUs = 0L
                var samplesInSegment = 0

                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    buffer.position(0)
                    buffer.limit(sampleSize)

                    val adjustedPts = extractor.sampleTime + cumulativeOffsetUs

                    val bufferInfo = MediaCodec.BufferInfo()
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = adjustedPts
                    bufferInfo.flags = extractor.sampleFlags

                    muxerInstance.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    segmentMaxPtsUs = maxOf(segmentMaxPtsUs, adjustedPts)
                    samplesInSegment++

                    if (!extractor.advance()) break
                }

                // Use one AAC frame duration as the gap instead of a fixed 1ms
                cumulativeOffsetUs = segmentMaxPtsUs + sampleDurationUs
                mergedCount++

                Logger.d("[Merge] Segment[$segmentIndex]: ${File(path).name}, $samplesInSegment samples, maxPts=${segmentMaxPtsUs}us")

                extractor.release()
            }
        } finally {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: Exception) {
                    // ignore
                }
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
                // ignore
            }
        }

        return mergedCount
    }

    private fun getAudioDurationSecondsOrThrow(filePath: String): Double {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (ms != null && ms > 0L) {
                return ms / 1000.0
            }
        } catch (e: Exception) {
            Logger.w("[Sound] MediaMetadataRetriever failed for $filePath: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        // Fallback: calculate duration from WAV header if file is WAV format
        if (filePath.endsWith(".wav", ignoreCase = true)) {
            val wavDurationMs = estimateWavDuration(filePath)
            if (wavDurationMs > 0.0) return wavDurationMs / 1000.0
        }

        throw Exception("Cannot determine duration for: ${File(filePath).name}")
    }
    
    private fun startPlayTimer() {
        playTimer?.cancel()
        playTimer = Timer()
        playTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                mediaPlayer?.let { player ->
                    try {
                        val safeDuration = try {
                            player.duration.toDouble()
                        } catch (e: IllegalStateException) {
                            -1.0
                        }
                        
                        val safeCurrentPosition = try {
                            player.currentPosition.toDouble()
                        } catch (e: IllegalStateException) {
                            -1.0
                        }
                        
                        if (safeDuration >= 0 && safeCurrentPosition >= 0) {
                            handler.post {
                                playBackListener?.invoke(
                                    PlayBackType(
                                        isMuted = false,
                                        duration = safeDuration,
                                        currentPosition = safeCurrentPosition
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        handler.post {
                            stopPlayTimer()
                        }
                    }
                }
            }
        }, 0, subscriptionDuration)
    }

    private fun stopPlayTimer() {
        playTimer?.cancel()
        playTimer = null
    }

    // Audio Focus Handling for Call Interruption
    private fun setupAudioFocus() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        
        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Audio focus lost (video, phone call, other audio apps) - pause recording
                    handler.post {
                        val service = RecordingForegroundService.getInstance()
                        if (service != null && service.isCurrentlyRecording()) {
                            // Get current position before pausing
                            val currentPosition = service.getCurrentRecordingTime()
                            
                            service.pauseRecording()
                            
                            recordBackListener?.invoke(
                                RecordBackType(
                                    isRecording = false,
                                    currentPosition = currentPosition,
                                    currentMetering = null,
                                    recordSecs = currentPosition
                                )
                            )
                        }
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Audio focus regained - don't auto-resume
                }
            }
        }
        
        // Use new AudioFocusRequest API for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener!!, handler)
                .build()
            
            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioFocusChangeListener?.let { listener ->
                audioManager?.abandonAudioFocus(listener)
            }
        }
        audioFocusChangeListener = null
        audioManager = null
    }
}
