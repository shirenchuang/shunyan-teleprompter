package com.shunyan.prompter;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

final class SpeechEngine {
    static final int SAMPLE_RATE = 16000;

    interface Listener {
        void onReady();
        void onText(String text);
        void onSentenceEnd();
        void onError();
        default void onInputSilenced(boolean silenced) {}
    }

    static final class Decoder implements AutoCloseable {
        private final OnlineRecognizer recognizer;
        private final OnlineStream stream;
        private String previous = "";

        Decoder(AssetManager assets) {
            OnlineModelConfig model = new OnlineModelConfig();
            model.setTransducer(new OnlineTransducerModelConfig(
                    "speech/encoder-epoch-99-avg-1.int8.onnx",
                    "speech/decoder-epoch-99-avg-1.onnx",
                    "speech/joiner-epoch-99-avg-1.int8.onnx"));
            model.setTokens("speech/tokens.txt");
            model.setModelType("zipformer");
            model.setNumThreads(1);
            model.setProvider("cpu");
            OnlineRecognizerConfig config = new OnlineRecognizerConfig();
            config.setModelConfig(model);
            config.setFeatConfig(new FeatureConfig(SAMPLE_RATE, 80, 0f));
            config.setDecodingMethod("greedy_search");
            config.setEnableEndpoint(true);
            recognizer = new OnlineRecognizer(assets, config);
            try {
                stream = recognizer.createStream("");
            } catch (RuntimeException | LinkageError e) {
                recognizer.release();
                throw e;
            }
        }

        void accept(float[] samples, Listener listener) {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            String result = recognizer.getResult(stream).getText();
            if (!result.isEmpty() && !previous.equals(result)) {
                previous = result;
                listener.onText(result);
            }
            if (recognizer.isEndpoint(stream) || result.length() > 32) {
                recognizer.reset(stream);
                if (!previous.isEmpty()) listener.onSentenceEnd();
                previous = "";
            }
        }

        @Override public void close() {
            stream.release();
            recognizer.release();
        }
    }

    static final class Capture implements Runnable {
        private final AssetManager assets;
        private final Listener listener;
        private volatile boolean active = true;

        Capture(AssetManager assets, Listener listener) {
            this.assets = assets;
            this.listener = listener;
        }

        void stop() { active = false; }

        @SuppressLint("MissingPermission")
        @Override public void run() {
            if (!active) return;
            AudioRecord microphone = null;
            try (Decoder decoder = new Decoder(assets)) {
                if (!active) return;
                int bytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (bytes <= 0) throw new IllegalStateException("Audio format unavailable");
                microphone = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(bytes, 6400));
                if (microphone.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("Microphone was not initialized");
                }
                if (!active) return;
                microphone.startRecording();
                if (microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("Microphone did not start");
                }
                listener.onReady();
                short[] buffer = new short[1600];
                float[] full = new float[buffer.length];
                long nextAudioCheck = 0;
                boolean silenced = false;
                while (active) {
                    int count = microphone.read(buffer, 0, buffer.length);
                    if (count < 0) throw new IllegalStateException("AudioRecord.read returned " + count);
                    if (count == 0 || !active) continue;
                    long now = SystemClock.elapsedRealtime();
                    if (now >= nextAudioCheck) {
                        var recording = microphone.getActiveRecordingConfiguration();
                        if (recording != null && recording.isClientSilenced() != silenced) {
                            silenced = recording.isClientSilenced();
                            listener.onInputSilenced(silenced);
                        }
                        nextAudioCheck = now + 300;
                    }
                    float[] samples = count == full.length ? full : new float[count];
                    for (int i = 0; i < count; i++) samples[i] = silenced ? 0f : buffer[i] / 32768f;
                    decoder.accept(samples, listener);
                }
            } catch (RuntimeException | LinkageError e) {
                Log.w("ShunyanSpeech", "Could not run local speech recognizer", e);
                if (active) listener.onError();
            } finally {
                if (microphone != null) {
                    if (microphone.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) microphone.stop();
                    microphone.release();
                }
            }
        }
    }
}
