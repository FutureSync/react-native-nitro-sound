package com.margelo.nitro.audiorecorderplayer

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Streams PCM audio data into an M4A (AAC-LC) file in real time.
 *
 * Designed to run on the same thread as WavRecorder's recording loop:
 *   1. [start]  – create codec + muxer (called once before recording begins)
 *   2. [encode] – feed PCM chunks as they arrive from AudioRecord
 *   3. [stop]   – signal EOS, drain remaining frames, finalise the container
 *
 * The software AAC encoder processes at ~1.8× real-time on low-end devices,
 * so it always keeps up with the 1× recording rate without blocking.
 *
 * If anything goes wrong the encoder silently disables itself ([failed] = true)
 * and the caller falls back to post-recording WavToM4aConverter.
 */
class StreamingM4aEncoder {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false

    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerSample = 0

    private var totalPcmBytes = 0L
    private var presentationTimeUs = 0L

    @Volatile var failed = false
        private set
    @Volatile var outputPath: String? = null
        private set

    private val bufferInfo = MediaCodec.BufferInfo()

    companion object {
        private const val BIT_RATE = 128_000
        private const val CODEC_TIMEOUT_US = 0L   // non-blocking in the hot path
        private const val DRAIN_TIMEOUT_US = 100_000L // 100 ms for final drain
    }

    /**
     * Initialise the AAC encoder and M4A muxer.
     * @param m4aPath  destination file (parent dirs are created automatically)
     * @return true if the encoder is ready; false ⟹ fall back to post-recording conversion
     */
    fun start(
        m4aPath: String,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int
    ): Boolean {
        try {
            this.sampleRate = sampleRateHz
            this.channelCount = channels
            this.bytesPerSample = channels * (bitsPerSample / 8)
            this.outputPath = m4aPath

            File(m4aPath).parentFile?.mkdirs()

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRateHz,
                channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }

            muxer = MediaMuxer(m4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            totalPcmBytes = 0L
            presentationTimeUs = 0L
            failed = false
            return true
        } catch (e: Exception) {
            Logger.e("[StreamingM4a] Failed to start: ${e.message}", e)
            failed = true
            releaseQuietly()
            return false
        }
    }

    /**
     * Feed raw PCM bytes from AudioRecord.  Non-blocking: submits what the
     * codec can accept right now and drains available output frames.
     * Safe to call from the recording thread at microphone rate.
     */
    fun encode(pcmData: ByteArray, offset: Int, length: Int) {
        if (failed || encoder == null) return
        try {
            submitInput(pcmData, offset, length)
            drainOutput(eos = false)
        } catch (e: Exception) {
            Logger.e("[StreamingM4a] encode error, disabling: ${e.message}", e)
            failed = true
        }
    }

    /**
     * Signal end-of-stream, drain remaining frames, and finalise the M4A.
     * Blocks until all buffered audio is flushed (typically < 200 ms).
     * @return true if the M4A was finalised successfully
     */
    fun stop(): Boolean {
        if (failed || encoder == null) {
            releaseQuietly()
            return false
        }
        try {
            // Signal EOS to the encoder
            val idx = encoder!!.dequeueInputBuffer(DRAIN_TIMEOUT_US)
            if (idx >= 0) {
                encoder!!.queueInputBuffer(idx, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // Drain all remaining output
            drainOutput(eos = true)

            if (muxerStarted) {
                muxer?.stop()
            }
            releaseQuietly()
            return !failed
        } catch (e: Exception) {
            Logger.e("[StreamingM4a] stop error: ${e.message}", e)
            failed = true
            releaseQuietly()
            return false
        }
    }

    /** Release without trying to finalise — used on pause or error. */
    fun release() {
        failed = true
        releaseQuietly()
    }

    // ── private ───────────────────────────────────────────────

    private fun submitInput(pcmData: ByteArray, offset: Int, length: Int) {
        val enc = encoder ?: return
        var consumed = 0
        while (consumed < length) {
            val idx = enc.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (idx < 0) break // codec busy — we'll catch up next call
            val buf = enc.getInputBuffer(idx) ?: break
            buf.clear()
            val chunk = minOf(length - consumed, buf.remaining())
            buf.put(pcmData, offset + consumed, chunk)

            val samples = (totalPcmBytes + consumed) / bytesPerSample
            val pts = samples * 1_000_000L / sampleRate

            enc.queueInputBuffer(idx, 0, chunk, pts, 0)
            consumed += chunk
        }
        totalPcmBytes += consumed
        presentationTimeUs = totalPcmBytes / bytesPerSample * 1_000_000L / sampleRate
    }

    private fun drainOutput(eos: Boolean) {
        val enc = encoder ?: return
        val timeout = if (eos) DRAIN_TIMEOUT_US else CODEC_TIMEOUT_US
        while (true) {
            val idx = enc.dequeueOutputBuffer(bufferInfo, timeout)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer!!.addTrack(enc.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(idx)
                    if (outBuf != null && bufferInfo.size > 0 && muxerStarted) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer!!.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(idx, false)
                    if (isEos) return
                }
                else -> {
                    if (!eos) return // nothing available right now
                    // When draining after EOS, keep trying until we get EOS flag
                }
            }
        }
    }

    private fun releaseQuietly() {
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer?.release() } catch (_: Exception) {}
        encoder = null
        muxer = null
        muxerStarted = false
        trackIndex = -1
    }
}
