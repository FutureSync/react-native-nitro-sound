import Foundation
import AVFoundation

/**
 * Converts WAV audio files to M4A (AAC) format.
 *
 * Primary path uses AVAssetExportSession (reliable on iOS 26+).
 * Falls back to AVAssetReader/AVAssetWriter pipeline when export session
 * is unavailable or fails.
 *
 * This allows recording in crash-resilient WAV format, then converting
 * to smaller M4A format after successful recording.
 */
class WavToM4aConverter {
    
    /// Result of conversion operation
    enum ConversionResult {
        case success(outputPath: String, duration: TimeInterval)
        case error(message: String)
    }
    
    /// Default AAC bit rate
    private static let defaultBitRate = 128000
    
    /**
     * Convert a WAV file to M4A format.
     *
     * - Parameters:
     *   - wavFilePath: Path to the input WAV file
     *   - m4aFilePath: Optional output path. If nil, will use same path with .m4a extension
     *   - bitRate: Target bit rate for AAC encoding (default: 128kbps)
     *   - deleteWavAfterConversion: Whether to delete the WAV file after successful conversion
     *   - completion: Callback with conversion result
     */
    static func convert(
        wavFilePath: String,
        m4aFilePath: String? = nil,
        bitRate: Int = defaultBitRate,
        deleteWavAfterConversion: Bool = true,
        completion: @escaping (ConversionResult) -> Void
    ) {
        let workItem = DispatchWorkItem(qos: .default, flags: .enforceQoS) {
            let result = convertSync(
                wavFilePath: wavFilePath,
                m4aFilePath: m4aFilePath,
                bitRate: bitRate,
                deleteWavAfterConversion: deleteWavAfterConversion
            )
            
            DispatchQueue.main.async {
                completion(result)
            }
        }
        DispatchQueue.global(qos: .default).async(execute: workItem)
    }
    
    /// Ensure an audio session is active so the hardware AAC encoder is available.
    /// Used only by the legacy AVAssetWriter fallback path.
    private static func ensureAudioSessionForEncoding() -> Bool {
        let session = AVAudioSession.sharedInstance()

        try? session.setActive(false, options: .notifyOthersOnDeactivation)

        let retryDelays: [UInt32] = [100_000, 300_000, 500_000] // microseconds
        for (attempt, delay) in retryDelays.enumerated() {
            do {
                try session.setCategory(.playback, mode: .default)
                try session.setActive(true)
                print("[WavToM4a] Audio session activated (attempt \(attempt + 1))")
                return true
            } catch {
                print("[WavToM4a] Session activation attempt \(attempt + 1) failed: \(error)")
                usleep(delay)
            }
        }
        print("[WavToM4a] Audio session activation failed after all retries")
        return false
    }

    /// Remove a partial/invalid output file left behind by a failed conversion.
    /// Always deletes in failure contexts — the caller decides when to call this.
    private static func cleanupFailedOutput(_ path: String) {
        guard FileManager.default.fileExists(atPath: path) else { return }
        let attrs = try? FileManager.default.attributesOfItem(atPath: path)
        let size = (attrs?[.size] as? UInt64) ?? 0
        try? FileManager.default.removeItem(atPath: path)
        print("[WavToM4a] Cleaned up failed output (\(size)B): \(path.components(separatedBy: "/").last ?? path)")
    }

    /// Format an NSError for detailed diagnostics.
    private static func detailedError(_ error: Error?) -> String {
        guard let error = error else { return "nil" }
        let nsError = error as NSError
        var parts = [
            "domain=\(nsError.domain)",
            "code=\(nsError.code)",
            "desc=\(nsError.localizedDescription)"
        ]
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError {
            parts.append("underlying=\(underlying.domain):\(underlying.code)")
        }
        return parts.joined(separator: ", ")
    }

    // MARK: - Public API

    /**
     * Synchronous WAV → M4A conversion.
     *
     * Strategy:
     *   1. Try AVAssetExportSession (reliable on iOS 26+ where AVAssetWriter
     *      AAC encoding regressed).
     *   2. If export session fails, fall back to AVAssetReader/AVAssetWriter
     *      pipeline (works on older iOS).
     */
    static func convertSync(
        wavFilePath: String,
        m4aFilePath: String? = nil,
        bitRate: Int = defaultBitRate,
        deleteWavAfterConversion: Bool = true
    ) -> ConversionResult {
        let wavURL = URL(fileURLWithPath: wavFilePath)
        
        guard FileManager.default.fileExists(atPath: wavFilePath) else {
            return .error(message: "WAV file not found: \(wavFilePath)")
        }
        
        let outputPath: String
        if let customPath = m4aFilePath {
            outputPath = customPath
        } else {
            let url = URL(fileURLWithPath: wavFilePath)
            outputPath = url.deletingPathExtension().appendingPathExtension("m4a").path
        }
        let outputURL = URL(fileURLWithPath: outputPath)
        
        if FileManager.default.fileExists(atPath: outputPath) {
            try? FileManager.default.removeItem(atPath: outputPath)
        }
        
        let inputAttrs = try? FileManager.default.attributesOfItem(atPath: wavFilePath)
        let inputSize = (inputAttrs?[.size] as? UInt64) ?? 0
        let inputName = wavFilePath.components(separatedBy: "/").last ?? wavFilePath
        let outputName = outputPath.components(separatedBy: "/").last ?? outputPath
        print("[WavToM4a] Converting: \(inputName) (\(inputSize)B) → \(outputName)")
        
        // --- Primary path: AVAssetExportSession ---
        let exportResult = convertViaExportSession(
            wavURL: wavURL,
            outputURL: outputURL,
            outputPath: outputPath,
            deleteWavAfterConversion: deleteWavAfterConversion,
            wavFilePath: wavFilePath
        )
        
        switch exportResult {
        case .success:
            return exportResult
        case .error(let exportError):
            print("[WavToM4a] ExportSession failed (\(exportError)), trying AVAssetWriter fallback...")
            // Clean up any partial output before fallback attempt
            cleanupFailedOutput(outputPath)
        }
        
        // --- Fallback: AVAssetReader + AVAssetWriter ---
        let writerResult = convertViaAssetWriter(
            wavURL: wavURL,
            outputURL: outputURL,
            outputPath: outputPath,
            bitRate: bitRate,
            deleteWavAfterConversion: deleteWavAfterConversion,
            wavFilePath: wavFilePath
        )
        
        if case .error = writerResult {
            cleanupFailedOutput(outputPath)
        }
        
        return writerResult
    }

    // MARK: - AVAssetExportSession (primary, iOS 26-safe)

    private static func convertViaExportSession(
        wavURL: URL,
        outputURL: URL,
        outputPath: String,
        deleteWavAfterConversion: Bool,
        wavFilePath: String
    ) -> ConversionResult {
        let asset = AVAsset(url: wavURL)
        let assetDuration = asset.duration
        
        let composition = AVMutableComposition()
        guard let compositionTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            return .error(message: "ExportSession: Failed to create composition track")
        }
        
        let tracks = asset.tracks(withMediaType: .audio)
        guard let audioTrack = tracks.first else {
            return .error(message: "ExportSession: No audio track found in WAV file")
        }
        
        do {
            try compositionTrack.insertTimeRange(
                CMTimeRange(start: .zero, duration: assetDuration),
                of: audioTrack,
                at: .zero
            )
        } catch {
            return .error(message: "ExportSession: Failed to insert audio track: \(error.localizedDescription)")
        }
        
        guard let exportSession = AVAssetExportSession(
            asset: composition,
            presetName: AVAssetExportPresetAppleM4A
        ) else {
            return .error(message: "ExportSession: Failed to create export session (preset not available)")
        }
        
        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a
        
        print("[WavToM4a] ExportSession: starting export...")
        
        let semaphore = DispatchSemaphore(value: 0)
        exportSession.exportAsynchronously { semaphore.signal() }
        semaphore.wait()
        
        switch exportSession.status {
        case .completed:
            let duration = assetDuration.seconds
            return validateAndFinalize(
                outputPath: outputPath,
                duration: duration,
                deleteWavAfterConversion: deleteWavAfterConversion,
                wavFilePath: wavFilePath,
                method: "ExportSession"
            )
            
        case .failed, .cancelled:
            let errDetail = detailedError(exportSession.error)
            print("[WavToM4a] ExportSession FAILED: \(errDetail)")
            return .error(message: "ExportSession failed: \(exportSession.error?.localizedDescription ?? "unknown")")
            
        default:
            return .error(message: "ExportSession unexpected status: \(exportSession.status.rawValue)")
        }
    }

    // MARK: - AVAssetWriter (legacy fallback)

    private static func convertViaAssetWriter(
        wavURL: URL,
        outputURL: URL,
        outputPath: String,
        bitRate: Int,
        deleteWavAfterConversion: Bool,
        wavFilePath: String
    ) -> ConversionResult {
        guard ensureAudioSessionForEncoding() else {
            return .error(message: "Audio session unavailable - hardware AAC encoder cannot be accessed")
        }
        
        let asset = AVAsset(url: wavURL)
        let assetDurationValue = asset.duration
        
        guard let track = asset.tracks(withMediaType: .audio).first else {
            return .error(message: "AssetWriter: No audio track found in WAV file")
        }
        
        let formatDescriptions = track.formatDescriptions as? [CMFormatDescription] ?? []
        guard let formatDescription = formatDescriptions.first else {
            return .error(message: "AssetWriter: Could not get audio format description")
        }
        guard let sourceFormat = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)?.pointee else {
            return .error(message: "AssetWriter: Could not parse audio stream basic description")
        }
        let sampleRate = sourceFormat.mSampleRate
        let channels = sourceFormat.mChannelsPerFrame
        
        print("[WavToM4a] AssetWriter: source \(sampleRate)Hz, \(channels)ch, bitRate=\(bitRate)")
        
        guard let reader = try? AVAssetReader(asset: asset) else {
            return .error(message: "AssetWriter: Failed to create asset reader")
        }
        
        let readerOutputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]
        
        let readerOutput = AVAssetReaderTrackOutput(track: track, outputSettings: readerOutputSettings)
        guard reader.canAdd(readerOutput) else {
            return .error(message: "AssetWriter: Cannot add reader output")
        }
        reader.add(readerOutput)
        
        guard let writer = try? AVAssetWriter(outputURL: outputURL, fileType: .m4a) else {
            return .error(message: "AssetWriter: Failed to create asset writer")
        }
        
        var channelLayout = AudioChannelLayout()
        channelLayout.mChannelLayoutTag = channels == 2
            ? kAudioChannelLayoutTag_Stereo
            : kAudioChannelLayoutTag_Mono
        let channelLayoutData = Data(bytes: &channelLayout, count: MemoryLayout<AudioChannelLayout>.size)
        
        let writerInputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: bitRate,
            AVChannelLayoutKey: channelLayoutData
        ]
        
        let writerInput = AVAssetWriterInput(mediaType: .audio, outputSettings: writerInputSettings)
        writerInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(writerInput) else {
            return .error(message: "AssetWriter: Cannot add writer input")
        }
        writer.add(writerInput)
        
        guard reader.startReading() else {
            return .error(message: "AssetWriter: Failed to start reading: \(detailedError(reader.error))")
        }
        guard writer.startWriting() else {
            return .error(message: "AssetWriter: Failed to start writing: \(detailedError(writer.error))")
        }
        writer.startSession(atSourceTime: .zero)
        
        let queue = DispatchQueue(label: "com.nitrosound.wavtom4a", qos: .default)
        let semaphore = DispatchSemaphore(value: 0)
        var conversionError: String? = nil
        var samplesWritten = 0
        
        writerInput.requestMediaDataWhenReady(on: queue) {
            while writerInput.isReadyForMoreMediaData {
                if let sampleBuffer = readerOutput.copyNextSampleBuffer() {
                    if !writerInput.append(sampleBuffer) {
                        let errDetail = detailedError(writer.error)
                        conversionError = "Encode failed after \(samplesWritten) samples: \(writer.error?.localizedDescription ?? "encoder unavailable")"
                        print("[WavToM4a] AssetWriter append() FAILED at sample \(samplesWritten): \(errDetail)")
                        semaphore.signal()
                        return
                    }
                    samplesWritten += 1
                } else {
                    writerInput.markAsFinished()
                    if reader.status == .failed {
                        conversionError = "Reader failed: \(detailedError(reader.error))"
                        print("[WavToM4a] AssetWriter reader FAILED: \(detailedError(reader.error))")
                    } else {
                        print("[WavToM4a] AssetWriter encoding done, \(samplesWritten) buffers written")
                    }
                    semaphore.signal()
                    return
                }
            }
        }
        
        semaphore.wait()
        
        if let error = conversionError {
            print("[WavToM4a] AssetWriter FAILED: \(error)")
            writer.cancelWriting()
            return .error(message: error)
        }
        
        let finishSemaphore = DispatchSemaphore(value: 0)
        writer.finishWriting { finishSemaphore.signal() }
        finishSemaphore.wait()
        
        if writer.status == .failed {
            let errDetail = detailedError(writer.error)
            print("[WavToM4a] AssetWriter finishWriting FAILED: \(errDetail)")
            return .error(message: "Writer failed: \(writer.error?.localizedDescription ?? "unknown")")
        }
        
        let duration = assetDurationValue.seconds
        return validateAndFinalize(
            outputPath: outputPath,
            duration: duration,
            deleteWavAfterConversion: deleteWavAfterConversion,
            wavFilePath: wavFilePath,
            method: "AssetWriter"
        )
    }

    // MARK: - Shared validation & finalization

    private static func validateAndFinalize(
        outputPath: String,
        duration: TimeInterval,
        deleteWavAfterConversion: Bool,
        wavFilePath: String,
        method: String
    ) -> ConversionResult {
        guard FileManager.default.fileExists(atPath: outputPath) else {
            return .error(message: "\(method): Output file was not created")
        }
        
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: outputPath)
            let fileSize = attributes[.size] as? Int64 ?? 0
            print("[WavToM4a] \(method) OK: output=\(fileSize)B, duration=\(String(format: "%.2f", duration))s")
            
            if fileSize == 0 {
                cleanupFailedOutput(outputPath)
                return .error(message: "\(method): Output file is empty (0 bytes)")
            }
        } catch {
            print("[WavToM4a] WARN: Could not get file attributes: \(error)")
        }
        
        if duration > 0 {
            let outputAsset = AVAsset(url: URL(fileURLWithPath: outputPath))
            let outputDuration = outputAsset.duration.seconds
            let tolerance = max(duration * 0.1, 0.5)
            
            let durationDiff: Double = Swift.abs(outputDuration - duration)
            if durationDiff > tolerance {
                print("[WavToM4a] WARN: Duration mismatch src=\(String(format: "%.2f", duration))s vs out=\(String(format: "%.2f", outputDuration))s — deleting M4A, keeping WAV")
                cleanupFailedOutput(outputPath)
                return .error(message: "\(method): Duration mismatch (src=\(String(format: "%.2f", duration))s, out=\(String(format: "%.2f", outputDuration))s) — WAV preserved for retry")
            }
        }
        
        if deleteWavAfterConversion {
            do {
                try FileManager.default.removeItem(atPath: wavFilePath)
                print("[WavToM4a] Deleted source WAV")
            } catch {
                print("[WavToM4a] WARN: Failed to delete WAV file: \(error)")
            }
        }
        
        return .success(outputPath: outputPath, duration: duration)
    }
}
