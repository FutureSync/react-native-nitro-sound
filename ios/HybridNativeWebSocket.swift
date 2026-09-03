import Foundation
import NitroModules

final class HybridNativeWebSocket: HybridNativeWebSocketSpec_base, HybridNativeWebSocketSpec_protocol {

    // MARK: - Properties

    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private let queue = DispatchQueue(label: "com.nitrosound.websocket", qos: .userInitiated)
    private var operationQueue: OperationQueue = {
        let q = OperationQueue()
        q.name = "NativeWebSocket"
        q.maxConcurrentOperationCount = 1
        return q
    }()

    private var onOpenListener: (() -> Void)?
    private var onTextMessageListener: ((String) -> Void)?
    private var onCloseListener: ((Double, String) -> Void)?
    private var onErrorListener: ((String) -> Void)?

    private var _isConnected = false
    private var isReceiving = false

    var isConnected: Bool { _isConnected }

    var memorySize: Int { 0 }

    // MARK: - Lifecycle

    func connect(url: String) throws -> Promise<Void> {
        let promise = Promise<Void>()

        queue.async { [weak self] in
            guard let self = self else {
                promise.reject(withError: RuntimeError.error(withMessage: "WebSocket deallocated"))
                return
            }

            self.cleanupConnection()

            guard let wsUrl = URL(string: url) else {
                promise.reject(withError: RuntimeError.error(withMessage: "Invalid WebSocket URL"))
                return
            }

            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 10
            config.waitsForConnectivity = false

            self.session = URLSession(
                configuration: config,
                delegate: nil,
                delegateQueue: self.operationQueue
            )

            self.task = self.session?.webSocketTask(with: wsUrl)

            var resolved = false

            self.task?.resume()
            self._isConnected = true
            self.isReceiving = true
            self.startReceiveLoop(connectPromise: promise, resolved: &resolved)

            if !resolved {
                resolved = true
                promise.resolve(withResult: ())
            }
        }

        return promise
    }

    func disconnect(code: Double, reason: String) throws {
        queue.async { [weak self] in
            guard let self = self else { return }
            let closeCode = URLSessionWebSocketTask.CloseCode(rawValue: Int(code)) ?? .normalClosure
            self.task?.cancel(with: closeCode, reason: reason.data(using: .utf8))
            self._isConnected = false
            self.isReceiving = false
        }
    }

    // MARK: - Send

    func sendBinary(data: ArrayBuffer) throws {
        guard _isConnected, let task = task else { return }

        let nsData = data.toData()
        let message = URLSessionWebSocketTask.Message.data(nsData)

        task.send(message) { [weak self] error in
            if let error = error {
                self?.queue.async {
                    self?.onErrorListener?("sendBinary failed: \(error.localizedDescription)")
                }
            }
        }
    }

    func sendText(text: String) throws {
        guard _isConnected, let task = task else { return }

        let message = URLSessionWebSocketTask.Message.string(text)

        task.send(message) { [weak self] error in
            if let error = error {
                self?.queue.async {
                    self?.onErrorListener?("sendText failed: \(error.localizedDescription)")
                }
            }
        }
    }

    func sendPing() throws {
        task?.sendPing { [weak self] error in
            if let error = error {
                self?.queue.async {
                    self?.onErrorListener?("ping failed: \(error.localizedDescription)")
                }
            }
        }
    }

    // MARK: - Listeners

    func setOnOpenListener(callback: @escaping () -> Void) throws {
        onOpenListener = callback
    }

    func setOnTextMessageListener(callback: @escaping (String) -> Void) throws {
        onTextMessageListener = callback
    }

    func setOnCloseListener(callback: @escaping (Double, String) -> Void) throws {
        onCloseListener = callback
    }

    func setOnErrorListener(callback: @escaping (String) -> Void) throws {
        onErrorListener = callback
    }

    func removeAllListeners() throws {
        onOpenListener = nil
        onTextMessageListener = nil
        onCloseListener = nil
        onErrorListener = nil
    }

    // MARK: - Receive Loop

    private func startReceiveLoop(connectPromise: Promise<Void>? = nil, resolved: inout Bool) {
        scheduleReceive()

        if let listener = onOpenListener {
            listener()
        }
    }

    private func scheduleReceive() {
        guard isReceiving, let task = task else { return }

        task.receive { [weak self] result in
            guard let self = self, self.isReceiving else { return }

            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.onTextMessageListener?(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.onTextMessageListener?(text)
                    }
                @unknown default:
                    break
                }
                self.scheduleReceive()

            case .failure(let error):
                self._isConnected = false
                self.isReceiving = false

                let nsError = error as NSError
                if nsError.code == 57 || // Socket is not connected
                   nsError.domain == NSPOSIXErrorDomain {
                    self.onCloseListener?(
                        Double(URLSessionWebSocketTask.CloseCode.abnormalClosure.rawValue),
                        error.localizedDescription
                    )
                } else {
                    self.onErrorListener?(error.localizedDescription)
                    self.onCloseListener?(
                        Double(URLSessionWebSocketTask.CloseCode.abnormalClosure.rawValue),
                        error.localizedDescription
                    )
                }
            }
        }
    }

    // MARK: - Cleanup

    private func cleanupConnection() {
        isReceiving = false
        _isConnected = false
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
    }

    deinit {
        cleanupConnection()
        onOpenListener = nil
        onTextMessageListener = nil
        onCloseListener = nil
        onErrorListener = nil
    }
}

// MARK: - ArrayBuffer Extension

private extension ArrayBuffer {
    func toData() -> Data {
        let ptr = data
        let size = Int(self.size)
        return Data(bytes: ptr, count: size)
    }
}
