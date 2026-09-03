import type { HybridObject } from 'react-native-nitro-modules';

export interface NativeWebSocket extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  /**
   * Connect to a WebSocket server.
   * Resolves when the connection is established (onOpen).
   * Rejects on connection failure or timeout.
   */
  connect(url: string): Promise<void>;

  /**
   * Gracefully close the WebSocket connection.
   */
  disconnect(code: number, reason: string): void;

  /**
   * Send binary data (e.g. PCM audio chunks).
   * Fire-and-forget; errors are reported via onError listener.
   */
  sendBinary(data: ArrayBuffer): void;

  /**
   * Send a text message (e.g. JSON-encoded protocol messages).
   * Fire-and-forget; errors are reported via onError listener.
   */
  sendText(text: string): void;

  /** Whether the WebSocket is currently connected and ready to send. */
  readonly isConnected: boolean;

  /**
   * Send a native-level ping frame.
   * More reliable than application-level heartbeat messages.
   */
  sendPing(): void;

  // ─── Event Listeners (single-slot) ────────────────────────────

  setOnOpenListener(callback: () => void): void;
  setOnTextMessageListener(callback: (message: string) => void): void;
  setOnCloseListener(callback: (code: number, reason: string) => void): void;
  setOnErrorListener(callback: (error: string) => void): void;
  removeAllListeners(): void;
}
