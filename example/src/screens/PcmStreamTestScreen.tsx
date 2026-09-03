import { useRef, useState, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Platform,
  PermissionsAndroid,
  Alert,
} from 'react-native';
import { createSound } from 'react-native-nitro-sound';

interface StreamStats {
  chunkCount: number;
  totalBytes: number;
  lastChunkSize: number;
}

export function PcmStreamTestScreen({ onBack }: { onBack: () => void }) {
  const soundRef = useRef(createSound());
  const [status, setStatus] = useState<'idle' | 'streaming' | 'mock'>('idle');
  const [stats, setStats] = useState<StreamStats>({
    chunkCount: 0,
    totalBytes: 0,
    lastChunkSize: 0,
  });
  const statsRef = useRef<StreamStats>({
    chunkCount: 0,
    totalBytes: 0,
    lastChunkSize: 0,
  });

  const requestPermissions = async () => {
    if (Platform.OS !== 'android') return true;
    const res = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO
    );
    return res === PermissionsAndroid.RESULTS.GRANTED;
  };

  const updateStats = useCallback((chunkSize: number) => {
    const s = statsRef.current;
    s.chunkCount += 1;
    s.totalBytes += chunkSize;
    s.lastChunkSize = chunkSize;
    if (s.chunkCount % 5 === 0 || s.chunkCount <= 3) {
      setStats({ ...s });
    }
  }, []);

  const onStartRealMic = async () => {
    if (!(await requestPermissions())) {
      Alert.alert('Permission required', 'Microphone permission needed');
      return;
    }

    try {
      const sound = soundRef.current;
      statsRef.current = { chunkCount: 0, totalBytes: 0, lastChunkSize: 0 };
      setStats({ chunkCount: 0, totalBytes: 0, lastChunkSize: 0 });

      sound.addPcmChunkListener((chunk: ArrayBuffer) => {
        updateStats(chunk.byteLength);
      });

      await sound.startRecorder(
        undefined,
        {
          AudioSamplingRate: 16000,
          AudioChannels: 1,
          AVSampleRateKeyIOS: 16000,
          AVNumberOfChannelsKeyIOS: 1,
          AVLinearPCMBitDepthKeyIOS: 16,
          AVEncodingOptionIOS: 'lpcm',
        },
        true
      );

      setStatus('streaming');
    } catch (err) {
      Alert.alert('Error', String(err));
    }
  };

  const onStartMockStream = () => {
    try {
      const sound = soundRef.current;
      statsRef.current = { chunkCount: 0, totalBytes: 0, lastChunkSize: 0 };
      setStats({ chunkCount: 0, totalBytes: 0, lastChunkSize: 0 });

      sound.addPcmChunkListener((chunk: ArrayBuffer) => {
        updateStats(chunk.byteLength);
      });

      // Use bundled test WAV — path resolved at runtime by the native asset system
      const testWavPath = Platform.select({
        ios: `${getDocumentsDir()}/test-audio-16khz.wav`,
        android: `${getDocumentsDir()}/test-audio-16khz.wav`,
        default: '',
      });

      sound.startMockPcmStream(testWavPath, 16000);
      setStatus('mock');
    } catch (err) {
      Alert.alert('Error', String(err));
    }
  };

  const onStop = async () => {
    try {
      const sound = soundRef.current;
      sound.removePcmChunkListener();
      sound.stopMockPcmStream();
      if (status === 'streaming') {
        await sound.stopRecorder();
      }
      setStats({ ...statsRef.current });
      setStatus('idle');
    } catch (err) {
      Alert.alert('Error', String(err));
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <TouchableOpacity onPress={onBack} style={styles.backButton}>
        <Text style={styles.backText}>← Back</Text>
      </TouchableOpacity>

      <Text style={styles.title}>PCM Stream Test</Text>
      <Text style={styles.subtitle}>
        Tests addPcmChunkListener / startMockPcmStream
      </Text>

      <View style={styles.statusBox}>
        <Text style={styles.statusLabel}>Status</Text>
        <Text style={styles.statusValue}>{status}</Text>
      </View>

      <View style={styles.statsGrid}>
        <View style={styles.statItem}>
          <Text style={styles.statLabel}>Chunks sent</Text>
          <Text style={styles.statValue}>{stats.chunkCount}</Text>
        </View>
        <View style={styles.statItem}>
          <Text style={styles.statLabel}>Total bytes</Text>
          <Text style={styles.statValue}>
            {stats.totalBytes.toLocaleString()}
          </Text>
        </View>
        <View style={styles.statItem}>
          <Text style={styles.statLabel}>Last chunk</Text>
          <Text style={styles.statValue}>{stats.lastChunkSize} bytes</Text>
        </View>
      </View>

      <View style={styles.buttonGroup}>
        <TouchableOpacity
          style={[styles.button, styles.primaryButton]}
          onPress={onStartRealMic}
          disabled={status !== 'idle'}
        >
          <Text style={styles.buttonText}>Start Real Mic (16kHz)</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.button, styles.secondaryButton]}
          onPress={onStartMockStream}
          disabled={status !== 'idle'}
        >
          <Text style={styles.buttonText}>Start Mock Stream</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.button, styles.stopButton]}
          onPress={onStop}
          disabled={status === 'idle'}
        >
          <Text style={styles.buttonText}>Stop</Text>
        </TouchableOpacity>
      </View>
    </ScrollView>
  );
}

function getDocumentsDir(): string {
  // Placeholder — in production the app passes the actual documents directory.
  // For the example app, the test WAV is copied here at build time.
  if (Platform.OS === 'ios') {
    return '';
  }
  return '';
}

const styles = StyleSheet.create({
  container: {
    padding: 20,
    paddingBottom: 60,
  },
  backButton: {
    marginBottom: 16,
  },
  backText: {
    fontSize: 16,
    color: '#007AFF',
  },
  title: {
    fontSize: 22,
    fontWeight: 'bold',
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 13,
    color: '#888',
    marginBottom: 20,
  },
  statusBox: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 14,
    backgroundColor: '#f0f0f0',
    borderRadius: 10,
    marginBottom: 16,
  },
  statusLabel: {
    fontSize: 16,
    fontWeight: '600',
  },
  statusValue: {
    fontSize: 16,
    fontWeight: '700',
    color: '#007AFF',
  },
  statsGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 24,
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
    padding: 12,
    backgroundColor: '#fff',
    borderRadius: 8,
    marginHorizontal: 4,
    borderWidth: 1,
    borderColor: '#eee',
  },
  statLabel: {
    fontSize: 11,
    color: '#888',
    marginBottom: 4,
  },
  statValue: {
    fontSize: 16,
    fontWeight: '700',
  },
  buttonGroup: {
    gap: 12,
  },
  button: {
    padding: 16,
    borderRadius: 10,
    alignItems: 'center',
  },
  primaryButton: {
    backgroundColor: '#007AFF',
  },
  secondaryButton: {
    backgroundColor: '#34C759',
  },
  stopButton: {
    backgroundColor: '#FF3B30',
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});
