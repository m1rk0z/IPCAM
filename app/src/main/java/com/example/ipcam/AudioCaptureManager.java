package com.example.ipcam;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioCaptureManager {
    private static final String TAG = "AudioCaptureManager";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_COUNT = 1;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BITRATE = 64000; // 64 kbps

    public interface AudioEncoderListener {
        void onAudioFormatChanged(MediaFormat format);
        void onAudioFrameEncoded(ByteBuffer buffer, MediaCodec.BufferInfo info);
        void onAudioEncoderError(Exception e);
    }

    private AudioRecord audioRecord;
    private MediaCodec encoder;
    private final AudioEncoderListener listener;
    
    private Thread recordThread;
    private Thread encoderThread;
    private volatile boolean isRunning = false;

    public AudioCaptureManager(AudioEncoderListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public synchronized void start() throws IOException {
        Log.d(TAG, "Starting Audio Capture & Encoder...");
        
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            throw new IOException("Unable to determine min buffer size for AudioRecord");
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize * 4
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("Failed to initialize AudioRecord");
        }

        MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize * 2);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        isRunning = true;
        
        audioRecord.startRecording();

        recordThread = new Thread(this::runRecordLoop, "AudioRecord-Worker");
        encoderThread = new Thread(this::runEncoderLoop, "AudioEncoder-Worker");
        
        recordThread.start();
        encoderThread.start();
        
        Log.d(TAG, "Audio Capture & Encoder started.");
    }

    public synchronized void stop() {
        Log.d(TAG, "Stopping Audio Capture & Encoder...");
        isRunning = false;
        
        if (recordThread != null) {
            recordThread.interrupt();
            try {
                recordThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordThread = null;
        }

        if (encoderThread != null) {
            encoderThread.interrupt();
            try {
                encoderThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            encoderThread = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping/releasing AudioRecord", e);
            }
            audioRecord = null;
        }

        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping/releasing Audio Encoder", e);
            }
            encoder = null;
        }
        Log.d(TAG, "Audio Capture & Encoder stopped.");
    }

    private void runRecordLoop() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
        byte[] tempBuffer = new byte[bufferSize];

        while (isRunning) {
            try {
                int readBytes = audioRecord.read(tempBuffer, 0, bufferSize);
                if (readBytes > 0 && isRunning) {
                    feedEncoder(tempBuffer, readBytes);
                } else if (readBytes < 0) {
                    Log.e(TAG, "AudioRecord error: " + readBytes);
                    break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in Audio Record loop", e);
                break;
            }
        }
    }

    private void feedEncoder(byte[] data, int length) {
        try {
            int inputBufferIndex = encoder.dequeueInputBuffer(10000); // 10ms timeout
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, 0, length);
                    
                    // Timestamp in microseconds
                    long pts = System.nanoTime() / 1000;
                    encoder.queueInputBuffer(inputBufferIndex, 0, length, pts, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to feed audio data to encoder", e);
        }
    }

    private void runEncoderLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (isRunning) {
            try {
                int outputBufferIndex = encoder.dequeueOutputBuffer(info, 10000); // 10ms timeout
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    Log.d(TAG, "Audio Encoder output format changed: " + newFormat);
                    if (listener != null) {
                        listener.onAudioFormatChanged(newFormat);
                    }
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && isRunning) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);

                        // Omit configuration buffers because they are parsed by the media formats
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            if (listener != null) {
                                ByteBuffer dup = outputBuffer.duplicate();
                                listener.onAudioFrameEncoded(dup, info);
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in audio encoder loop", e);
                if (listener != null && isRunning) {
                    listener.onAudioEncoderError(e);
                }
                break;
            }
        }
    }
}
