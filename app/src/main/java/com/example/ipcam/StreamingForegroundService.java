package com.example.ipcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.nio.ByteBuffer;

public class StreamingForegroundService extends Service {
    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "IPCAM_Streaming_Channel";
    private static final int NOTIFICATION_ID = 420;
    private static final String DISCOVERY_RTSP_PATH = "/live";

    public interface StreamingServiceListener {
        void onStateChanged(boolean active);
        void onStatsUpdated(int fps, double bitrateMbps, int clientsCount);
        void onBufferStatsUpdated(long totalSec, long totalMb, int count);
        void onActiveResolutionChanged(Size size);
        void onStreamingError(String message, boolean fallbackOccurred);
    }

    public class LocalBinder extends Binder {
        public StreamingForegroundService getService() {
            return StreamingForegroundService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private StreamingServiceListener listener;

    private CameraCaptureManager cameraManager;
    private VideoEncoderManager videoEncoder;
    private AudioCaptureManager audioManager;
    private RtspServerManager rtspServer;
    private SegmentBufferManager segmentBuffer;
    private NsdAdvertiser nsdAdvertiser;

    private boolean isStreaming = false;
    private boolean audioEnabled = true;
    private boolean useFrontCamera = false;
    private String resolutionSetting = "1080p";
    private int rtspPort = 8554;
    private int fpsSetting = 30;
    private int bitrateSetting = 6_000_000;
    private int rotationSetting = 0;

    private Surface lastPreviewSurface;

    // Real-time metric variables
    private int frameCounter = 0;
    private long byteCounter = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable metricsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isStreaming) {
                int fps = frameCounter;
                double mbps = (byteCounter * 8.0) / (1024.0 * 1024.0);
                frameCounter = 0;
                byteCounter = 0;

                int clients = (rtspServer != null) ? rtspServer.getClientCount() : 0;
                
                if (listener != null) {
                    listener.onStatsUpdated(fps, mbps, clients);
                }
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        nsdAdvertiser = new NsdAdvertiser(this);
    }

    public void setListener(StreamingServiceListener listener) {
        this.listener = listener;
    }

    /**
     * Publishes (or refreshes) the NSD discovery record so companion apps can find this
     * camera on the local network automatically. Safe to call any time, including before
     * streaming has started, so the device is discoverable as soon as the app is open.
     */
    public void updateDiscoveryAdvertisement(String cameraName, int port) {
        if (nsdAdvertiser != null) {
            nsdAdvertiser.register(cameraName, port, DISCOVERY_RTSP_PATH);
        }
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    public synchronized void startStreaming(int port, String resolution, int fps, int bitrateMbps, int rotation, boolean audio, boolean frontCam, Surface previewSurface) {
        if (isStreaming) {
            Log.w(TAG, "Already streaming.");
            return;
        }

        this.rtspPort = port;
        this.resolutionSetting = resolution;
        this.fpsSetting = fps;
        this.bitrateSetting = bitrateMbps * 1_000_000;
        this.rotationSetting = rotation;
        this.audioEnabled = audio;
        this.useFrontCamera = frontCam;
        this.lastPreviewSurface = previewSurface;

        startForegroundNotification();

        try {
            initPipeline();
            isStreaming = true;
            if (listener != null) {
                listener.onStateChanged(true);
            }
            // Start real-time metrics thread
            mainHandler.postDelayed(metricsRunnable, 1000);
        } catch (Exception e) {
            Log.e(TAG, "Error starting stream pipeline", e);
            if (resolutionSetting.equals("4K")) {
                // Automatic fallback: If 4K fails, retry at 1080p
                Log.w(TAG, "4K Start failed. Attempting fallback to 1080p...");
                if (listener != null) {
                    listener.onStreamingError("4K initialization failed. Falling back to 1080p...", true);
                }
                stopStreamingInternal();
                this.resolutionSetting = "1080p";
                this.bitrateSetting = 6_000_000; // Reset bitrate fallback
                try {
                    startForegroundNotification();
                    initPipeline();
                    isStreaming = true;
                    if (listener != null) {
                        listener.onStateChanged(true);
                    }
                    mainHandler.postDelayed(metricsRunnable, 1000);
                } catch (Exception ex) {
                    Log.e(TAG, "Fallback pipeline also failed", ex);
                    if (listener != null) {
                        listener.onStreamingError("Fallback to 1080p also failed: " + ex.getMessage(), false);
                    }
                    stopStreaming();
                }
            } else {
                if (listener != null) {
                    listener.onStreamingError("Pipeline start failed: " + e.getMessage(), false);
                }
                stopStreaming();
            }
        }
    }

    public synchronized void stopStreaming() {
        if (!isStreaming) return;
        stopStreamingInternal();
        stopForeground(true);
        isStreaming = false;
        if (listener != null) {
            listener.onStateChanged(false);
        }
    }

    private void stopStreamingInternal() {
        mainHandler.removeCallbacks(metricsRunnable);

        if (cameraManager != null) {
            cameraManager.stop();
            cameraManager = null;
        }

        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder = null;
        }

        if (audioManager != null) {
            audioManager.stop();
            audioManager = null;
        }

        if (rtspServer != null) {
            rtspServer.stop();
            rtspServer = null;
        }

        if (segmentBuffer != null) {
            segmentBuffer.stop();
            segmentBuffer = null;
        }

        lastPreviewSurface = null;
        Log.d(TAG, "Pipeline shut down.");
    }

    public synchronized void toggleCamera(Surface previewSurface) {
        if (!isStreaming) return;
        this.useFrontCamera = !useFrontCamera;
        this.lastPreviewSurface = previewSurface;

        // In alto frame rate (high-speed) la dimensione dell'encoder dipende dalla camera scelta,
        // che può differire tra fronte e retro: ricrea l'intera pipeline invece del quick-swap.
        if (fpsSetting > 30) {
            hotSwapVideoPipeline(resolutionSetting, fpsSetting, rotationSetting);
            return;
        }

        // Quick hot swap of camera: Stop camera, switch direction, start camera again
        if (cameraManager != null) {
            cameraManager.stop();
        }

        try {
            Surface encoderSurface = videoEncoder.getInputSurface(); // Reuse the existing running encoder's surface
            CameraCaptureManager.VideoConfig vc = CameraCaptureManager.resolveConfig(this, useFrontCamera, resolutionSetting, fpsSetting);
            cameraManager = new CameraCaptureManager(this, cameraListener);
            cameraManager.start(useFrontCamera, vc.size, vc.highSpeed, vc.fps, vc.hsRange, lastPreviewSurface, encoderSurface);
        } catch (Exception e) {
            Log.e(TAG, "Error toggling camera", e);
            if (listener != null) {
                listener.onStreamingError("Failed to re-initialize camera: " + e.getMessage(), false);
            }
            stopStreaming();
        }
    }

    public synchronized void toggleAudio(boolean enabled) {
        this.audioEnabled = enabled;
        if (!isStreaming) return;
        
        // Re-start or stop audio managers
        if (audioEnabled) {
            try {
                audioManager = new AudioCaptureManager(audioListener);
                audioManager.start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start audio encoder", e);
                if (listener != null) {
                    listener.onStreamingError("Failed to activate audio: " + e.getMessage(), false);
                }
            }
        } else {
            if (audioManager != null) {
                audioManager.stop();
                audioManager = null;
            }
        }
        
        if (segmentBuffer != null) {
            segmentBuffer.stop();
            segmentBuffer.start(audioEnabled);
        }
        if (rtspServer != null) {
            rtspServer.stop();
            try {
                rtspServer.start(audioEnabled);
            } catch (IOException e) {
                Log.e(TAG, "Failed to restart RTSP server on audio toggle", e);
            }
        }
    }

    public synchronized void changeRotation(int degrees) {
        if (this.rotationSetting == degrees) return;
        this.rotationSetting = degrees;
        if (isStreaming) {
            hotSwapVideoPipeline(resolutionSetting, fpsSetting, degrees);
        }
    }

    public synchronized void changeBitrate(int bitrateMbps) {
        this.bitrateSetting = bitrateMbps * 1_000_000;
        if (isStreaming && videoEncoder != null) {
            videoEncoder.setBitrate(this.bitrateSetting);
        }
    }

    public synchronized void hotSwapVideoPipeline(String newRes, int newFps, int newRotation) {
        if (!isStreaming) return;
        Log.d(TAG, "Hot-swapping video pipeline: resolution=" + newRes + ", fps=" + newFps + ", rotation=" + newRotation);

        // 1. Stop camera capture manager
        if (cameraManager != null) {
            cameraManager.stop();
            cameraManager = null;
        }

        // 2. Stop video encoder manager
        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder = null;
        }

        // 3. Update configuration variables
        this.resolutionSetting = newRes;
        this.fpsSetting = newFps;
        this.rotationSetting = newRotation;

        // Risolvi dimensioni/fps reali del device (high-speed adattivo per fps > 30). L'encoder
        // deve usare esattamente la size che la camera produrrà, altrimenti la sessione fallisce.
        CameraCaptureManager.VideoConfig vc = CameraCaptureManager.resolveConfig(this, useFrontCamera, resolutionSetting, fpsSetting);
        int width = vc.size.getWidth();
        int height = vc.size.getHeight();

        try {
            // 4. Start new Video Encoder Manager
            videoEncoder = new VideoEncoderManager(
                    width,
                    height,
                    bitrateSetting,
                    vc.fps,
                    rotationSetting,
                    videoListener
            );
            Surface encoderSurface = videoEncoder.start();

            // 5. Restart Camera Capture Manager with new surface
            cameraManager = new CameraCaptureManager(this, cameraListener);
            cameraManager.start(useFrontCamera, vc.size, vc.highSpeed, vc.fps, vc.hsRange, lastPreviewSurface, encoderSurface);


            
            Log.d(TAG, "Video pipeline hot-swap completed successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Error during video pipeline hot-swap", e);
            if (listener != null) {
                listener.onStreamingError("Failed to re-initialize streaming pipeline: " + e.getMessage(), false);
            }
            stopStreaming();
        }
    }

    private void initPipeline() throws Exception {
        Log.d(TAG, "Initializing pipeline components...");
        
        // 1. Start RTSP server
        rtspServer = new RtspServerManager(rtspPort, rtspListener);
        rtspServer.start(audioEnabled);

        // 2. Start Segmented Buffer
        segmentBuffer = new SegmentBufferManager(this, segmentStatsListener);
        segmentBuffer.start(audioEnabled);

        // 3. Start Video Encoder. Dimensioni/fps risolti in base alle capacità del device:
        // high-speed adattivo per fps > 30 (può ridurre la risoluzione se il device supporta
        // gli alti frame rate solo a risoluzioni inferiori). La rotazione è applicata come hint
        // all'encoder in VideoEncoderManager, non scambiando width/height.
        CameraCaptureManager.VideoConfig vc = CameraCaptureManager.resolveConfig(this, useFrontCamera, resolutionSetting, fpsSetting);
        int width = vc.size.getWidth();
        int height = vc.size.getHeight();

        videoEncoder = new VideoEncoderManager(
                width,
                height,
                bitrateSetting,
                vc.fps,
                rotationSetting,
                videoListener
        );
        Surface encoderSurface = videoEncoder.start();

        // 4. Start Audio (if enabled)
        if (audioEnabled) {
            audioManager = new AudioCaptureManager(audioListener);
            audioManager.start();
        }

        // 5. Open Camera
        cameraManager = new CameraCaptureManager(this, cameraListener);
        cameraManager.start(useFrontCamera, vc.size, vc.highSpeed, vc.fps, vc.hsRange, lastPreviewSurface, encoderSurface);
    }

    // LISTENER IMPLEMENTATIONS
    
    private final VideoEncoderManager.VideoEncoderListener videoListener = new VideoEncoderManager.VideoEncoderListener() {
        @Override
        public void onVideoFormatChanged(MediaFormat format) {
            if (segmentBuffer != null) {
                segmentBuffer.setVideoFormat(format);
            }
        }

        @Override
        public void onVideoFrameEncoded(ByteBuffer buffer, MediaCodec.BufferInfo info, boolean isKeyFrame) {
            frameCounter++;
            byteCounter += info.size;

            if (rtspServer != null) {
                rtspServer.sendVideoFrame(buffer, info, isKeyFrame);
            }
            if (segmentBuffer != null) {
                // Skip config buffers (SPS/PPS) for segment buffering as MediaMuxer handles it out-of-band
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    segmentBuffer.addVideoFrame(buffer, info);
                }
            }
        }

        @Override
        public void onSpsPpsReady(byte[] sps, byte[] pps, String spsPpsBase64) {
            if (rtspServer != null) {
                rtspServer.setSpsPps(spsPpsBase64);
            }
        }

        @Override
        public void onEncoderError(Exception e) {
            Log.e(TAG, "Video encoder error callback", e);
            if (listener != null) {
                listener.onStreamingError("Video encoder error: " + e.getMessage(), false);
            }
            stopStreaming();
        }
    };

    private final AudioCaptureManager.AudioEncoderListener audioListener = new AudioCaptureManager.AudioEncoderListener() {
        @Override
        public void onAudioFormatChanged(MediaFormat format) {
            if (segmentBuffer != null) {
                segmentBuffer.setAudioFormat(format);
            }
        }

        @Override
        public void onAudioFrameEncoded(ByteBuffer buffer, MediaCodec.BufferInfo info) {
            if (rtspServer != null) {
                rtspServer.sendAudioFrame(buffer, info);
            }
            if (segmentBuffer != null) {
                segmentBuffer.addAudioFrame(buffer, info);
            }
        }

        @Override
        public void onAudioEncoderError(Exception e) {
            Log.e(TAG, "Audio encoder error callback", e);
            if (listener != null) {
                listener.onStreamingError("Audio capturing error: " + e.getMessage(), false);
            }
            // Non-fatal error, toggle audio off
            toggleAudio(false);
        }
    };

    private final CameraCaptureManager.CameraListener cameraListener = new CameraCaptureManager.CameraListener() {
        @Override
        public void onCameraOpened(Size size) {
            if (listener != null) {
                listener.onActiveResolutionChanged(size);
            }
        }

        @Override
        public void onCameraClosed() {}

        @Override
        public void onCameraError(Exception e) {
            Log.e(TAG, "Camera error callback", e);
            if (listener != null) {
                listener.onStreamingError("Camera device error: " + e.getMessage(), false);
            }
            stopStreaming();
        }
    };

    private final RtspServerManager.RtspServerListener rtspListener = new RtspServerManager.RtspServerListener() {
        @Override
        public void onClientCountChanged(int count) {
            // Metrics runnable will pick up the count, but we can push updates if needed
        }

        @Override
        public void onServerError(Exception e) {
            Log.e(TAG, "RTSP server error callback", e);
            if (listener != null) {
                listener.onStreamingError("RTSP Server error: " + e.getMessage(), false);
            }
            stopStreaming();
        }
    };

    private final SegmentBufferManager.SegmentStatsListener segmentStatsListener = new SegmentBufferManager.SegmentStatsListener() {
        @Override
        public void onStatsUpdated(long totalDurationSec, long totalSizeBytes, int segmentCount) {
            long mb = totalSizeBytes / (1024L * 1024L);
            if (listener != null) {
                listener.onBufferStatsUpdated(totalDurationSec, mb, segmentCount);
            }
        }
    };

    // FOREGROUND SERVICE DECORATIONS
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "IPCAM Streaming Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void startForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("IPCAM Active")
                .setContentText("RTSP Server is active on port " + rtspPort)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(pendingIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        if (nsdAdvertiser != null) {
            nsdAdvertiser.unregister();
        }
        super.onDestroy();
    }
}
