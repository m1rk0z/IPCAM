package com.example.ipcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoderManager {
    private static final String TAG = "VideoEncoderManager";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    public interface VideoEncoderListener {
        void onVideoFormatChanged(MediaFormat format);
        void onVideoFrameEncoded(ByteBuffer buffer, MediaCodec.BufferInfo info, boolean isKeyFrame);
        void onSpsPpsReady(byte[] sps, byte[] pps, String spsPpsBase64);
        void onEncoderError(Exception e);
    }

    private MediaCodec encoder;
    private Surface inputSurface;
    private VideoEncoderListener listener;
    private Thread workerThread;
    private volatile boolean isRunning = false;

    private int width;
    private int height;
    private int bitrate;
    private int fps;
    private int rotation = 0;

    private byte[] sps;
    private byte[] pps;
    private String spsPpsBase64;

    public VideoEncoderManager(int width, int height, int bitrate, int fps, int rotation, VideoEncoderListener listener) {
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.fps = fps;
        this.rotation = rotation;
        this.listener = listener;
    }

    public Surface start() throws IOException {
        Log.d(TAG, "Starting VideoEncoder: " + width + "x" + height + " @ " + fps + " fps, rotation: " + rotation + ", bitrate: " + bitrate);
        
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        // 1 keyframe/sec: allinea il GOP ai segmenti HLS da 1s del player, che con "-c copy"
        // può tagliare un segmento solo su un keyframe. Con GOP=2s i segmenti diventavano ~2s
        // reali invece di 1s, raddoppiando inutilmente la latenza end-to-end.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        // Use Baseline profile for maximum RTSP player compatibility
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        // AVCLevel4 basta per 1080p@30; per frame rate alti (60/120fps) serve un level più alto
        // (Level 5.2 copre 1080p@120 e 720p@240), altrimenti l'encoder può rifiutare il formato.
        format.setInteger(MediaFormat.KEY_LEVEL, fps > 30
                ? MediaCodecInfo.CodecProfileLevel.AVCLevel52
                : MediaCodecInfo.CodecProfileLevel.AVCLevel4);

        // La rotazione va applicata qui (hint per il GraphicBufferSource del Surface-input
        // encoder), NON scambiando width/height: la camera cattura sempre nella sua
        // dimensione nativa (es. 1920x1080) e la CameraCaptureSession rifiuta una Surface
        // dichiarata a dimensioni diverse (es. 1080x1920) perché non è tra le size supportate
        // dallo StreamConfigurationMap, causando onConfigureFailed e lo streaming che non parte.
        if (rotation != 0) {
            format.setInteger(MediaFormat.KEY_ROTATION, rotation);
        }

        try {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start encoder with Baseline profile, retrying with defaults...", e);
            // Fallback retry with default settings if baseline profile is not supported
            format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (rotation != 0) {
                format.setInteger(MediaFormat.KEY_ROTATION, rotation);
            }
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();
        }

        isRunning = true;
        workerThread = new Thread(this::runEncoderLoop, "VideoEncoder-Worker");
        workerThread.start();

        return inputSurface;
    }

    public void stop() {
        Log.d(TAG, "Stopping VideoEncoder...");
        isRunning = false;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }

        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing video encoder", e);
            }
            encoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        Log.d(TAG, "VideoEncoder stopped.");
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
                    Log.d(TAG, "Encoder output format changed: " + newFormat);
                    if (listener != null) {
                        listener.onVideoFormatChanged(newFormat);
                    }
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);

                        boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        if (isConfig) {
                            Log.d(TAG, "Codec config buffer received (SPS/PPS), length: " + info.size);
                            extractSpsPps(outputBuffer, info.size);
                            if (listener != null) {
                                ByteBuffer dup = outputBuffer.duplicate();
                                listener.onVideoFrameEncoded(dup, info, false);
                            }
                        } else {
                            if (listener != null) {
                                // Pass a duplicate buffer to ensure thread safety
                                ByteBuffer dup = outputBuffer.duplicate();
                                listener.onVideoFrameEncoded(dup, info, isKeyFrame);
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in video encoder loop", e);
                if (listener != null && isRunning) {
                    listener.onEncoderError(e);
                }
                break;
            }
        }
    }

    private void extractSpsPps(ByteBuffer buffer, int size) {
        byte[] csd = new byte[size];
        buffer.get(csd);
        
        int spsStart = -1, spsEnd = -1;
        int ppsStart = -1, ppsEnd = -1;
        
        // Scan for NAL units start code 0x00000001 (4 bytes) or 0x000001 (3 bytes)
        int i = 0;
        while (i < size - 4) {
            if (csd[i] == 0 && csd[i+1] == 0 && (csd[i+2] == 1 || (csd[i+2] == 0 && csd[i+3] == 1))) {
                int startCodeLen = (csd[i+2] == 1) ? 3 : 4;
                int nalType = csd[i + startCodeLen] & 0x1F;
                
                if (nalType == 7) { // SPS
                    spsStart = i + startCodeLen;
                } else if (nalType == 8) { // PPS
                    ppsStart = i + startCodeLen;
                    if (spsStart != -1 && spsEnd == -1) {
                        spsEnd = i; // SPS ends where PPS begins
                    }
                } else {
                    if (spsStart != -1 && spsEnd == -1) spsEnd = i;
                    if (ppsStart != -1 && ppsEnd == -1) ppsEnd = i;
                }
                i += startCodeLen;
            } else {
                i++;
            }
        }
        
        if (spsStart != -1 && spsEnd == -1) spsEnd = (ppsStart != -1) ? ppsStart - 4 : size;
        if (ppsStart != -1 && ppsEnd == -1) ppsEnd = size;

        if (spsStart != -1 && ppsStart != -1 && spsEnd > spsStart && ppsEnd > ppsStart) {
            sps = new byte[spsEnd - spsStart];
            pps = new byte[ppsEnd - ppsStart];
            System.arraycopy(csd, spsStart, sps, 0, sps.length);
            System.arraycopy(csd, ppsStart, pps, 0, pps.length);

            String spsBase64 = Base64.encodeToString(sps, Base64.NO_WRAP);
            String ppsBase64 = Base64.encodeToString(pps, Base64.NO_WRAP);
            spsPpsBase64 = spsBase64 + "," + ppsBase64;
            
            Log.d(TAG, "SPS/PPS successfully parsed!");
            if (listener != null) {
                listener.onSpsPpsReady(sps, pps, spsPpsBase64);
            }
        } else {
            Log.e(TAG, "Could not extract SPS/PPS from config buffer. spsStart=" + spsStart + ", ppsStart=" + ppsStart);
        }
    }

    public String getSpsPpsBase64() {
        return spsPpsBase64;
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public void setRotation(int degrees) {
        this.rotation = degrees;
        if (encoder != null && isRunning) {
            try {
                android.os.Bundle b = new android.os.Bundle();
                b.putInt("rotation-degrees", degrees);
                encoder.setParameters(b);
                Log.d(TAG, "Sent dynamic rotation-degrees parameter to encoder: " + degrees);
            } catch (Exception e) {
                Log.w(TAG, "Dynamic rotation update not supported by encoder", e);
            }
        }
    }

    public void setBitrate(int bitrateBps) {
        this.bitrate = bitrateBps;
        if (encoder != null && isRunning) {
            try {
                android.os.Bundle b = new android.os.Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps);
                encoder.setParameters(b);
                Log.d(TAG, "Sent dynamic video-bitrate parameter to encoder: " + bitrateBps);
            } catch (Exception e) {
                Log.w(TAG, "Dynamic bitrate update not supported by encoder", e);
            }
        }
    }
}
