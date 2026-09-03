package com.margelo.nitro.sound

import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Promise
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class HybridNativeWebSocket : HybridNativeWebSocketSpec() {

    private var ws: WebSocket? = null
    private var client: OkHttpClient? = null

    @Volatile
    private var _isConnected = false

    private var onOpenListener: (() -> Unit)? = null
    private var onTextMessageListener: ((String) -> Unit)? = null
    private var onCloseListener: ((Double, String) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null

    override val isConnected: Boolean
        get() = _isConnected

    override val memorySize: Long
        get() = 0

    // ─── Lifecycle ──────────────────────────────────────────────────

    override fun connect(url: String): Promise<Unit> {
        val promise = Promise<Unit>()

        cleanupConnection()

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        client = httpClient

        val request = Request.Builder()
            .url(url)
            .build()

        var resolved = false

        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected = true
                onOpenListener?.invoke()
                if (!resolved) {
                    resolved = true
                    promise.resolve(Unit)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onTextMessageListener?.invoke(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                onTextMessageListener?.invoke(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected = false
                webSocket.close(code, reason)
                onCloseListener?.invoke(code.toDouble(), reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected = false
                onCloseListener?.invoke(code.toDouble(), reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected = false
                val errorMsg = t.message ?: "Unknown WebSocket error"
                onErrorListener?.invoke(errorMsg)
                onCloseListener?.invoke(1006.0, errorMsg)
                if (!resolved) {
                    resolved = true
                    promise.reject(Exception(errorMsg))
                }
            }
        })

        return promise
    }

    override fun disconnect(code: Double, reason: String) {
        _isConnected = false
        ws?.close(code.toInt(), reason)
    }

    // ─── Send ───────────────────────────────────────────────────────

    override fun sendBinary(data: ArrayBuffer) {
        if (!_isConnected) return
        val buffer = data.getBuffer(false)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val byteString = bytes.toByteString()
        val success = ws?.send(byteString) ?: false
        if (!success) {
            onErrorListener?.invoke("sendBinary failed: WebSocket send returned false")
        }
    }

    override fun sendText(text: String) {
        if (!_isConnected) return
        val success = ws?.send(text) ?: false
        if (!success) {
            onErrorListener?.invoke("sendText failed: WebSocket send returned false")
        }
    }

    override fun sendPing() {
        // OkHttp handles ping/pong automatically via pingInterval.
        // Manual ping is not exposed, but the 25s auto-ping covers this.
    }

    // ─── Listeners ──────────────────────────────────────────────────

    override fun setOnOpenListener(callback: () -> Unit) {
        onOpenListener = callback
    }

    override fun setOnTextMessageListener(callback: (String) -> Unit) {
        onTextMessageListener = callback
    }

    override fun setOnCloseListener(callback: (Double, String) -> Unit) {
        onCloseListener = callback
    }

    override fun setOnErrorListener(callback: (String) -> Unit) {
        onErrorListener = callback
    }

    override fun removeAllListeners() {
        onOpenListener = null
        onTextMessageListener = null
        onCloseListener = null
        onErrorListener = null
    }

    // ─── Cleanup ────────────────────────────────────────────────────

    private fun cleanupConnection() {
        _isConnected = false
        ws?.cancel()
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    protected fun finalize() {
        cleanupConnection()
    }
}
