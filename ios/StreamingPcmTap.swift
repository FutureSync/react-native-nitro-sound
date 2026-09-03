import Foundation
import AVFoundation
import NitroModules

/// Taps the microphone via AVAudioEngine and emits raw PCM16LE buffers to a JS callback.
/// Uses the same pattern as the web client's AudioMixer:
///   1. Tap at native sample rate (Float32)
///   2. Stride-decimate to target rate (16kHz)
///   3. Accumulate in a ring buffer (4096 Float32 samples)
///   4. Convert to PCM16LE and emit when full
///
/// Runs in parallel with AVAudioRecorder -- both read from the same hardware mic.
/// Also supports mock mode for simulator testing.
final class StreamingPcmTap {

    private var engine: AVAudioEngine?
    private var pcmListener: ((ArrayBuffer) -> Void)?
    private let queue = DispatchQueue(label: "com.nitro.streaming-pcm", qos: .userInitiated)

    // Ring buffer (matches web AudioMixer)
    private let ringBufferSize = 4096
    private var ringBuffer: [Float] = []
    private var strideAccumulator: Double = 0.0

    // Mock streaming state
    private var mockTimer: DispatchSourceTimer?
    private var mockData: Data?
    private var mockOffset: Int = 0
    private var isMocking = false

    // MARK: - Real mic tap

    func start(sampleRate: Double = 16000) {
        queue.async { [weak self] in
            self?.startOnQueue(targetSampleRate: sampleRate)
        }
    }

    private var tapCallCount = 0

    private func startOnQueue(targetSampleRate: Double) {
        stopEngineOnQueue()

        ringBuffer = []
        ringBuffer.reserveCapacity(ringBufferSize)
        strideAccumulator = 0.0
        tapCallCount = 0

        let engine = AVAudioEngine()
        self.engine = engine

        let inputNode = engine.inputNode
        let nativeFormat = inputNode.outputFormat(forBus: 0)
        let nativeSampleRate = nativeFormat.sampleRate
        let stride = nativeSampleRate / targetSampleRate

        print("[StreamingPcmTap] inputNode native format: rate=\(nativeSampleRate), channels=\(nativeFormat.channelCount), commonFormat=\(nativeFormat.commonFormat.rawValue)")

        // Tap at native format (Float32, native sample rate) — no forced conversion
        let tapFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: nativeSampleRate,
            channels: 1,
            interleaved: true
        )

        guard let tapFormat = tapFormat else {
            print("[StreamingPcmTap] ❌ Failed to create tap format")
            return
        }

        print("[StreamingPcmTap] Installing tap: format=Float32, rate=\(nativeSampleRate), stride=\(String(format: "%.4f", stride))")

        inputNode.installTap(onBus: 0, bufferSize: 4096, format: tapFormat) { [weak self] buffer, _ in
            guard let self = self, let floatData = buffer.floatChannelData else { return }
            let frameCount = Int(buffer.frameLength)
            let channelData = floatData[0]

            self.tapCallCount += 1
            if self.tapCallCount <= 3 || self.tapCallCount % 100 == 0 {
                print("[StreamingPcmTap] tap #\(self.tapCallCount): \(frameCount) frames, ringBuffer \(self.ringBuffer.count)/\(self.ringBufferSize)")
            }

            // Stride-decimate: pick every `stride`-th sample from native rate → target rate
            var pos = self.strideAccumulator
            while Int(pos) < frameCount {
                let sample = channelData[Int(pos)]
                self.ringBuffer.append(sample)

                if self.ringBuffer.count >= self.ringBufferSize {
                    self.flushRingBuffer()
                }

                pos += stride
            }
            // Keep fractional remainder for seamless next buffer
            self.strideAccumulator = pos - Double(frameCount)
        }

        engine.prepare()
        do {
            try engine.start()
            print("[StreamingPcmTap] ✅ Engine started: native \(nativeSampleRate)Hz → target \(targetSampleRate)Hz, stride \(String(format: "%.4f", stride)), ringBuffer \(ringBufferSize) samples")
        } catch {
            print("[StreamingPcmTap] ❌ Engine start failed: \(error)")
            self.engine = nil
        }
    }

    private var flushCount = 0

    /// Converts the Float32 ring buffer to PCM16LE and emits it.
    private func flushRingBuffer() {
        guard !ringBuffer.isEmpty else { return }

        flushCount += 1
        let count = ringBuffer.count
        var int16Buffer = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let clamped = max(-1.0, min(1.0, ringBuffer[i]))
            int16Buffer[i] = Int16(clamped * 32767.0)
        }

        let data = int16Buffer.withUnsafeBytes { Data($0) }
        ringBuffer.removeAll(keepingCapacity: true)

        if flushCount <= 3 || flushCount % 50 == 0 {
            print("[StreamingPcmTap] flush #\(flushCount): \(data.count) bytes (\(count) samples)")
        }

        emitBuffer(data: data)
    }

    func stop() {
        queue.async { [weak self] in
            self?.stopEngineOnQueue()
            self?.stopMockOnQueue()
        }
    }

    private func stopEngineOnQueue() {
        if let engine = engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            self.engine = nil
            // Flush any remaining samples
            if !ringBuffer.isEmpty {
                flushRingBuffer()
            }
            print("[StreamingPcmTap] Engine stopped")
        }
    }

    // MARK: - Listener

    func setListener(_ callback: ((ArrayBuffer) -> Void)?) {
        pcmListener = callback
    }

    // MARK: - Mock PCM stream (reads WAV file, emits chunks on a timer)

    func startMock(wavFilePath: String, sampleRateHz: Double = 16000) {
        queue.async { [weak self] in
            self?.startMockOnQueue(wavFilePath: wavFilePath, sampleRateHz: sampleRateHz)
        }
    }

    private func startMockOnQueue(wavFilePath: String, sampleRateHz: Double) {
        stopMockOnQueue()

        guard let fileData = FileManager.default.contents(atPath: wavFilePath) else {
            print("[StreamingPcmTap] Mock WAV file not found: \(wavFilePath)")
            return
        }

        guard fileData.count > 44 else {
            print("[StreamingPcmTap] WAV file too small: \(fileData.count) bytes")
            return
        }
        mockData = fileData.subdata(in: 44..<fileData.count)
        mockOffset = 0
        isMocking = true

        // ~100ms of PCM16LE per tick: sampleRate * 2 bytes/sample * 0.1s
        let bytesPerTick = Int(sampleRateHz * 2.0 * 0.1)
        let interval: TimeInterval = 0.1

        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak self] in
            self?.emitMockChunk(bytesPerTick: bytesPerTick)
        }
        timer.resume()
        mockTimer = timer
        print("[StreamingPcmTap] Mock stream started from \(wavFilePath), \(bytesPerTick) bytes/tick")
    }

    private func emitMockChunk(bytesPerTick: Int) {
        guard isMocking, let data = mockData else { return }
        let remaining = data.count - mockOffset
        if remaining <= 0 {
            queue.async { [weak self] in
                self?.stopMockOnQueue()
            }
            print("[StreamingPcmTap] Mock stream finished (EOF)")
            return
        }

        let chunkSize = min(bytesPerTick, remaining)
        let chunk = data.subdata(in: mockOffset..<(mockOffset + chunkSize))
        mockOffset += chunkSize
        emitBuffer(data: chunk)
    }

    func stopMock() {
        queue.async { [weak self] in
            self?.stopMockOnQueue()
        }
    }

    private func stopMockOnQueue() {
        mockTimer?.cancel()
        mockTimer = nil
        mockData = nil
        mockOffset = 0
        if isMocking {
            isMocking = false
            print("[StreamingPcmTap] Mock stream stopped")
        }
    }

    // MARK: - Shared buffer emission

    private func emitBuffer(data: Data) {
        guard let listener = pcmListener else { return }
        do {
            let arrayBuffer = try ArrayBuffer.copy(data: data)
            listener(arrayBuffer)
        } catch {
            print("[StreamingPcmTap] Failed to create ArrayBuffer: \(error)")
        }
    }
}
