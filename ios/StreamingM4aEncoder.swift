import Foundation
import AVFoundation
import CoreMedia

/// Encodes M4A (AAC) in real-time by tailing a growing WAV file written by AVAudioRecorder.
/// The companion M4A is ready (or nearly ready) by the time recording stops,
/// eliminating the 30-40 s post-recording WAV→M4A conversion.
///
/// Designed to be completely fault-tolerant: any internal failure silently disables
/// the encoder so the app falls back to post-recording conversion.
final class StreamingM4aEncoder {

    // MARK: - Public state

    let m4aFilePath: String
    private(set) var isFinalized = false
    private(set) var error: String?

    // MARK: - Private state

    private let wavFilePath: String
    private var assetWriter: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var formatDescription: CMAudioFormatDescription?

    private var readOffset: UInt64 = 0
    private var headerParsed = false
    private var sampleRate: Float64 = 44100
    private var channels: UInt32 = 1
    private var bitsPerSample: UInt32 = 16
    private var bytesPerFrame: UInt32 = 2

    private var totalFramesWritten: Int64 = 0
    private var timer: DispatchSourceTimer?
    private var isRunning = false
    private var hasFailed = false
    private let queue = DispatchQueue(label: "com.nitro.streaming-m4a", qos: .utility)
    private let lock = NSLock()

    // MARK: - Init

    init(wavFilePath: String) {
        self.wavFilePath = wavFilePath
        self.m4aFilePath = (wavFilePath as NSString).deletingPathExtension + ".m4a"
    }

    // MARK: - Lifecycle

    func start() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !isRunning else { return false }

        let outputURL = URL(fileURLWithPath: m4aFilePath)
        if FileManager.default.fileExists(atPath: m4aFilePath) {
            try? FileManager.default.removeItem(at: outputURL)
        }

        isRunning = true

        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 0.5, repeating: 0.5)
        t.setEventHandler { [weak self] in self?.processNewData() }
        t.resume()
        timer = t

        print("[StreamingM4a] Started for \((wavFilePath as NSString).lastPathComponent)")
        return true
    }

    /// Call from the recording-stop path. Reads remaining PCM data and finalizes the writer.
    func finalize() {
        lock.lock()
        guard isRunning else { lock.unlock(); return }
        isRunning = false
        lock.unlock()

        timer?.cancel()
        timer = nil

        // Small delay to let any in-flight timer callback finish
        queue.sync { }

        drainAllRemainingData()

        guard let writer = assetWriter, let input = writerInput,
              writer.status == .writing else {
            failSilently("Writer not in writing state during finalize")
            return
        }

        input.markAsFinished()

        let sem = DispatchSemaphore(value: 0)
        writer.finishWriting { sem.signal() }
        sem.wait()

        if writer.status == .completed {
            isFinalized = true
            let size = (try? FileManager.default.attributesOfItem(atPath: m4aFilePath)[.size] as? UInt64) ?? 0
            print("[StreamingM4a] Finalized: \((m4aFilePath as NSString).lastPathComponent), size=\(size)B, frames=\(totalFramesWritten)")
        } else {
            failSilently("Finalization failed: \(writer.error?.localizedDescription ?? "unknown")")
        }
    }

    func cleanup() {
        lock.lock()
        isRunning = false
        lock.unlock()

        timer?.cancel()
        timer = nil

        if let w = assetWriter, w.status == .writing { w.cancelWriting() }
        assetWriter = nil
        writerInput = nil
        formatDescription = nil
    }

    // MARK: - Internal helpers

    private func failSilently(_ message: String) {
        error = message
        isFinalized = false
        hasFailed = true
        print("[StreamingM4a] FAILED (non-fatal): \(message)")
        try? FileManager.default.removeItem(atPath: m4aFilePath)
    }

    // MARK: - WAV header

    private func parseWavHeader(_ handle: FileHandle) -> Bool {
        handle.seek(toFileOffset: 0)
        let header = handle.readData(ofLength: 44)
        guard header.count >= 44 else { return false }

        guard String(data: header.subdata(in: 0..<4), encoding: .ascii) == "RIFF",
              String(data: header.subdata(in: 8..<12), encoding: .ascii) == "WAVE"
        else { return false }

        header.withUnsafeBytes { buf in
            channels = UInt32(buf.load(fromByteOffset: 22, as: UInt16.self))
            sampleRate = Float64(buf.load(fromByteOffset: 24, as: UInt32.self))
            bitsPerSample = UInt32(buf.load(fromByteOffset: 34, as: UInt16.self))
        }

        guard channels > 0, sampleRate > 0, bitsPerSample > 0 else { return false }
        bytesPerFrame = channels * (bitsPerSample / 8)
        guard bytesPerFrame > 0 else { return false }

        // Scan for "data" chunk (usually at offset 36, but may differ).
        var offset: UInt64 = 12
        let fileSize = handle.seekToEndOfFile()
        while offset + 8 <= fileSize {
            handle.seek(toFileOffset: offset)
            let chunkHeader = handle.readData(ofLength: 8)
            guard chunkHeader.count == 8 else { break }
            let chunkId = String(data: chunkHeader.subdata(in: 0..<4), encoding: .ascii) ?? ""
            let chunkSize: UInt32 = chunkHeader.withUnsafeBytes { $0.load(fromByteOffset: 4, as: UInt32.self) }
            if chunkId == "data" {
                readOffset = offset + 8
                headerParsed = true
                return true
            }
            let padded = UInt64(chunkSize) + (UInt64(chunkSize) & 1)
            offset += 8 + padded
        }
        return false
    }

    // MARK: - AVAssetWriter setup

    private func setupWriter() -> Bool {
        let outputURL = URL(fileURLWithPath: m4aFilePath)

        guard let writer = try? AVAssetWriter(outputURL: outputURL, fileType: .m4a) else {
            error = "Failed to create AVAssetWriter"
            return false
        }

        var channelLayout = AudioChannelLayout()
        channelLayout.mChannelLayoutTag = channels == 1
            ? kAudioChannelLayoutTag_Mono
            : kAudioChannelLayoutTag_Stereo
        let layoutData = Data(bytes: &channelLayout, count: MemoryLayout<AudioChannelLayout>.size)

        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: 128_000,
            AVChannelLayoutKey: layoutData
        ]

        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: outputSettings)
        input.expectsMediaDataInRealTime = true

        guard writer.canAdd(input) else {
            error = "Cannot add writer input"
            return false
        }
        writer.add(input)

        guard writer.startWriting() else {
            error = "startWriting failed: \(writer.error?.localizedDescription ?? "unknown")"
            return false
        }
        writer.startSession(atSourceTime: .zero)

        var asbd = AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
            mBytesPerPacket: bytesPerFrame,
            mFramesPerPacket: 1,
            mBytesPerFrame: bytesPerFrame,
            mChannelsPerFrame: channels,
            mBitsPerChannel: bitsPerSample,
            mReserved: 0
        )

        var desc: CMAudioFormatDescription?
        let status = CMAudioFormatDescriptionCreate(
            allocator: kCFAllocatorDefault,
            asbd: &asbd,
            layoutSize: 0,
            layout: nil,
            magicCookieSize: 0,
            magicCookie: nil,
            extensions: nil,
            formatDescriptionOut: &desc
        )
        guard status == noErr, let fmtDesc = desc else {
            error = "CMAudioFormatDescriptionCreate failed (\(status))"
            writer.cancelWriting()
            return false
        }

        self.assetWriter = writer
        self.writerInput = input
        self.formatDescription = fmtDesc
        return true
    }

    // MARK: - Data processing

    private func processNewData() {
        guard isRunning, !hasFailed else { return }
        autoreleasepool { readAndEncode() }
    }

    private func drainAllRemainingData() {
        guard !hasFailed else { return }
        for _ in 0..<10 {
            let didRead = readAndEncode()
            if !didRead { break }
        }
    }

    @discardableResult
    private func readAndEncode() -> Bool {
        guard !hasFailed else { return false }

        guard let handle = FileHandle(forReadingAtPath: wavFilePath) else { return false }
        defer { handle.closeFile() }

        let fileSize = handle.seekToEndOfFile()

        if !headerParsed {
            guard fileSize >= 44, parseWavHeader(handle) else { return false }
            guard setupWriter() else {
                hasFailed = true
                return false
            }
        }

        guard let writer = assetWriter, writer.status == .writing,
              let input = writerInput,
              fileSize > readOffset else { return false }

        let available = Int(fileSize - readOffset)
        let aligned = available - (available % Int(bytesPerFrame))
        guard aligned > 0 else { return false }

        handle.seek(toFileOffset: readOffset)
        let data = handle.readData(ofLength: aligned)
        guard !data.isEmpty else { return false }

        guard input.isReadyForMoreMediaData else { return false }

        if let sb = makeSampleBuffer(from: data) {
            if input.append(sb) {
                totalFramesWritten += Int64(data.count / Int(bytesPerFrame))
                readOffset += UInt64(data.count)
                return true
            } else {
                failSilently("append failed: \(writer.error?.localizedDescription ?? "unknown")")
                return false
            }
        }
        return false
    }

    // MARK: - CMSampleBuffer factory

    private func makeSampleBuffer(from data: Data) -> CMSampleBuffer? {
        guard let fmtDesc = formatDescription else { return nil }

        let byteCount = data.count
        let frameCount = byteCount / Int(bytesPerFrame)
        guard frameCount > 0 else { return nil }

        var blockBuffer: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: byteCount,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: byteCount,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr, let block = blockBuffer else { return nil }

        status = data.withUnsafeBytes { raw in
            guard let baseAddr = raw.baseAddress else { return OSStatus(-1) }
            return CMBlockBufferReplaceDataBytes(
                with: baseAddr,
                blockBuffer: block,
                offsetIntoDestination: 0,
                dataLength: byteCount
            )
        }
        guard status == noErr else { return nil }

        let pts = CMTime(value: totalFramesWritten, timescale: CMTimeScale(sampleRate))

        var sampleBuffer: CMSampleBuffer?
        status = CMAudioSampleBufferCreateReadyWithPacketDescriptions(
            allocator: kCFAllocatorDefault,
            dataBuffer: block,
            formatDescription: fmtDesc,
            sampleCount: CMItemCount(frameCount),
            presentationTimeStamp: pts,
            packetDescriptions: nil,
            sampleBufferOut: &sampleBuffer
        )

        return status == noErr ? sampleBuffer : nil
    }
}
