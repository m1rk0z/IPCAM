package com.example.ipcam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class CameraCaptureManager {
    private static final String TAG = "CameraCaptureManager";

    public interface CameraListener {
        void onCameraOpened(Size activeSize);
        void onCameraClosed();
        void onCameraError(Exception e);
    }

    /**
     * Configurazione video risolta in base alle reali capacità del device.
     * Per fps > 30 tenta una sessione high-speed alla risoluzione più alta supportata a quel
     * frame rate (che su molti telefoni è inferiore a quella richiesta, es. 720p per i 120fps).
     */
    public static class VideoConfig {
        public final Size size;
        public final boolean highSpeed;
        public final int fps;
        public final Range<Integer> hsRange; // range FPS per la sessione high-speed (null se normale)

        VideoConfig(Size size, boolean highSpeed, int fps, Range<Integer> hsRange) {
            this.size = size;
            this.highSpeed = highSpeed;
            this.fps = fps;
            this.hsRange = hsRange;
        }
    }

    private final Context context;
    private final CameraListener listener;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder recordRequestBuilder;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private Size activeSize;
    private boolean useFrontCamera = false;

    // Configurazione risolta passata a start()
    private Size captureSize;
    private boolean useHighSpeed = false;
    private int targetFps = 30;
    private Range<Integer> highSpeedRange;

    public CameraCaptureManager(Context context, CameraListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public Size getActiveSize() {
        return activeSize;
    }

    public synchronized void start(boolean useFrontCamera, Size captureSize, boolean highSpeed,
                                   int fps, Range<Integer> hsRange,
                                   Surface previewSurface, Surface encoderSurface) {
        this.useFrontCamera = useFrontCamera;
        this.captureSize = captureSize;
        this.useHighSpeed = highSpeed;
        this.targetFps = fps;
        this.highSpeedRange = hsRange;

        startBackgroundThread();
        openCamera(previewSurface, encoderSurface);
    }

    public synchronized void stop() {
        closeCamera();
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        cameraThread = new HandlerThread("CameraBackground");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera(Surface previewSurface, Surface encoderSurface) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = getCameraId(manager, useFrontCamera);
            if (cameraId == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED, "No suitable camera found");
            }

            activeSize = (captureSize != null) ? captureSize : new Size(1920, 1080);
            Log.d(TAG, "Opening camera: size=" + activeSize + ", highSpeed=" + useHighSpeed
                    + ", fps=" + targetFps + ", range=" + highSpeedRange);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    synchronized (CameraCaptureManager.this) {
                        cameraDevice = camera;
                        createCaptureSession(previewSurface, encoderSurface);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    synchronized (CameraCaptureManager.this) {
                        closeCamera();
                        if (listener != null) {
                            listener.onCameraClosed();
                        }
                    }
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    synchronized (CameraCaptureManager.this) {
                        closeCamera();
                        if (listener != null) {
                            listener.onCameraError(new Exception("Camera open error: " + error));
                        }
                    }
                }
            }, cameraHandler);

        } catch (Exception e) {
            Log.e(TAG, "Error opening camera", e);
            if (listener != null) {
                listener.onCameraError(e);
            }
        }
    }

    private void createCaptureSession(Surface previewSurface, Surface encoderSurface) {
        if (cameraDevice == null) return;
        if (useHighSpeed) {
            createHighSpeedSession(previewSurface, encoderSurface);
        } else {
            createNormalSession(previewSurface, encoderSurface);
        }
    }

    /**
     * Sessione high-speed (>30fps). Per rispettare i vincoli Camera2, la sessione high-speed
     * renderizza SOLO sulla surface dell'encoder (dimensioni garantite corrette): la preview
     * locale resta sull'ultimo frame durante l'alto frame rate. Per il caso d'uso IPCAM→player
     * questo va bene, perché la visione avviene sull'app player, non sull'anteprima del telefono.
     * In caso di fallimento si ricade automaticamente sulla sessione normale a 30fps.
     */
    private void createHighSpeedSession(Surface previewSurface, Surface encoderSurface) {
        try {
            recordRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            List<Surface> surfaces = new ArrayList<>();
            if (encoderSurface != null) {
                surfaces.add(encoderSurface);
                recordRequestBuilder.addTarget(encoderSurface);
            }
            if (highSpeedRange != null) {
                recordRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, highSpeedRange);
            }

            cameraDevice.createConstrainedHighSpeedCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            synchronized (CameraCaptureManager.this) {
                                if (cameraDevice == null) return;
                                captureSession = session;
                                try {
                                    CameraConstrainedHighSpeedCaptureSession hsSession =
                                            (CameraConstrainedHighSpeedCaptureSession) session;
                                    List<CaptureRequest> burst =
                                            hsSession.createHighSpeedRequestList(recordRequestBuilder.build());
                                    hsSession.setRepeatingBurst(burst, null, cameraHandler);
                                    Log.d(TAG, "High-speed session running at " + highSpeedRange + " on " + activeSize);
                                    if (listener != null) {
                                        listener.onCameraOpened(activeSize);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "High-speed burst start failed, falling back to normal 30fps", e);
                                    fallbackToNormal(previewSurface, encoderSurface);
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "High-speed session config failed, falling back to normal 30fps");
                            synchronized (CameraCaptureManager.this) {
                                fallbackToNormal(previewSurface, encoderSurface);
                            }
                        }
                    }, cameraHandler);

        } catch (Exception e) {
            Log.e(TAG, "createHighSpeedSession error, falling back to normal", e);
            synchronized (CameraCaptureManager.this) {
                fallbackToNormal(previewSurface, encoderSurface);
            }
        }
    }

    private void fallbackToNormal(Surface previewSurface, Surface encoderSurface) {
        if (cameraDevice == null) return;
        this.useHighSpeed = false;
        this.highSpeedRange = null;
        createNormalSession(previewSurface, encoderSurface);
    }

    private void createNormalSession(Surface previewSurface, Surface encoderSurface) {
        if (cameraDevice == null) return;
        try {
            recordRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Imposta il range FPS target anche in sessione normale, altrimenti il template RECORD
            // resta al default (spesso fisso a 30fps) ignorando l'fps richiesto dall'utente.
            Range<Integer> normalRange = selectBestNormalFpsRange(targetFps);
            if (normalRange != null) {
                recordRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, normalRange);
                Log.d(TAG, "Normal session FPS range: " + normalRange + " (target " + targetFps + ")");
            }

            List<Surface> surfaces = new ArrayList<>();
            if (previewSurface != null) {
                surfaces.add(previewSurface);
                recordRequestBuilder.addTarget(previewSurface);
            }
            if (encoderSurface != null) {
                surfaces.add(encoderSurface);
                recordRequestBuilder.addTarget(encoderSurface);
            }

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    synchronized (CameraCaptureManager.this) {
                        if (cameraDevice == null) return;
                        captureSession = session;
                        try {
                            recordRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                            captureSession.setRepeatingRequest(recordRequestBuilder.build(), null, cameraHandler);
                            Log.d(TAG, "Normal CameraCaptureSession configured and running.");
                            if (listener != null) {
                                listener.onCameraOpened(activeSize);
                            }
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Failed to start camera preview", e);
                            if (listener != null) {
                                listener.onCameraError(e);
                            }
                        }
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Camera configuration failed");
                    if (listener != null) {
                        listener.onCameraError(new Exception("Camera capture session configuration failed."));
                    }
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session", e);
            if (listener != null) {
                listener.onCameraError(e);
            }
        }
    }

    private synchronized void closeCamera() {
        Log.d(TAG, "Closing camera device and session...");
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing capture session", e);
            }
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        activeSize = null;
    }

    private String getCameraId(CameraManager manager, boolean front) throws CameraAccessException {
        return staticGetCameraId(manager, front);
    }

    private static String staticGetCameraId(CameraManager manager, boolean front) throws CameraAccessException {
        int targetFacing = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == targetFacing) {
                return id;
            }
        }
        // Fallback to the first available camera
        String[] list = manager.getCameraIdList();
        return list.length > 0 ? list[0] : null;
    }

    /**
     * Sceglie il range FPS più adatto per una sessione NORMALE (<=30fps richiesti), preferendo a
     * parità di distanza i range fissi (lower == upper) per evitare che la camera cali l'fps da sola.
     */
    private Range<Integer> selectBestNormalFpsRange(int fps) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = staticGetCameraId(manager, useFrontCamera);
            if (cameraId == null) return null;
            CameraCharacteristics ch = manager.getCameraCharacteristics(cameraId);
            Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return null;

            Range<Integer> best = null;
            int bestScore = Integer.MAX_VALUE;
            for (Range<Integer> r : ranges) {
                int diff = Math.abs(r.getUpper() - fps) + Math.abs(r.getLower() - fps);
                boolean isFixed = r.getLower().equals(r.getUpper());
                int score = diff * 10 - (isFixed ? 1 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = r;
                }
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "selectBestNormalFpsRange error", e);
            return null;
        }
    }

    // ──────────────────────────── Static config resolver ───────────────────────

    private static Size desiredSize(String resolutionSetting) {
        switch (resolutionSetting) {
            case "4K":
                return new Size(3840, 2160);
            case "720p":
                return new Size(1280, 720);
            case "1080p":
            default:
                return new Size(1920, 1080);
        }
    }

    private static Size pickClosest(Size[] sizes, Size desired) {
        if (sizes == null || sizes.length == 0) return null;
        for (Size s : sizes) {
            if (s.getWidth() == desired.getWidth() && s.getHeight() == desired.getHeight()) {
                return s;
            }
        }
        Size best = null;
        int minDiff = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int diff = Math.abs(s.getWidth() - desired.getWidth()) + Math.abs(s.getHeight() - desired.getHeight());
            if (diff < minDiff) {
                minDiff = diff;
                best = s;
            }
        }
        return best;
    }

    /** Prefer un range high-speed che raggiunga targetFps, preferendo i fissi [f,f] più vicini. */
    private static Range<Integer> pickHighSpeedRange(Range<Integer>[] ranges, int targetFps) {
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Range<Integer> r : ranges) {
            if (r.getUpper() < targetFps) continue; // deve raggiungere il target
            boolean fixed = r.getLower().equals(r.getUpper());
            int score = (r.getUpper() - targetFps) * 10 + (fixed ? 0 : 5);
            if (score < bestScore) {
                bestScore = score;
                best = r;
            }
        }
        if (best == null) {
            // nessun range raggiunge il target: prendi quello con l'upper più alto disponibile
            int bestUpper = -1;
            for (Range<Integer> r : ranges) {
                if (r.getUpper() > bestUpper) {
                    bestUpper = r.getUpper();
                    best = r;
                }
            }
        }
        return best;
    }

    /**
     * Risolve la configurazione video reale in base alle capacità del device. Per fps > 30
     * cerca la migliore coppia (size, range) high-speed che raggiunge il target senza superare
     * la risoluzione richiesta; se il device non offre high-speel utile, ricade su 30fps normali.
     */
    public static VideoConfig resolveConfig(Context context, boolean front, String resolutionSetting, int targetFps) {
        Size desired = desiredSize(resolutionSetting);
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = staticGetCameraId(manager, front);
            if (cameraId == null) {
                return new VideoConfig(desired, false, Math.min(targetFps, 30), null);
            }
            CameraCharacteristics ch = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return new VideoConfig(desired, false, Math.min(targetFps, 30), null);
            }

            Size[] encSizes = map.getOutputSizes(MediaCodec.class);
            Size normalSize = pickClosest(encSizes, desired);
            if (normalSize == null) normalSize = desired;

            if (targetFps <= 30) {
                return new VideoConfig(normalSize, false, targetFps, null);
            }

            // Ricerca high-speed
            Size[] hsSizes = map.getHighSpeedVideoSizes();
            if (hsSizes != null && hsSizes.length > 0) {
                Size best = null;
                Range<Integer> bestRange = null;
                int bestArea = -1;
                int desiredArea = desired.getWidth() * desired.getHeight();
                for (Size s : hsSizes) {
                    // Non superare la risoluzione richiesta dall'utente
                    int area = s.getWidth() * s.getHeight();
                    if (area > desiredArea) continue;
                    Range<Integer>[] hsRanges = map.getHighSpeedVideoFpsRangesFor(s);
                    Range<Integer> r = pickHighSpeedRange(hsRanges, targetFps);
                    if (r == null) continue;
                    // Preferisci la risoluzione più alta (area maggiore) che raggiunge il target
                    if (r.getUpper() >= targetFps && area > bestArea) {
                        bestArea = area;
                        best = s;
                        bestRange = r;
                    }
                }
                if (best != null && bestRange != null) {
                    int effFps = Math.min(targetFps, bestRange.getUpper());
                    Log.d(TAG, "resolveConfig HIGH-SPEED: " + best + " @ " + bestRange + " (eff " + effFps + ")");
                    return new VideoConfig(best, true, effFps, bestRange);
                }
            }

            // Nessun high-speed utilizzabile → 30fps normali alla risoluzione richiesta
            Log.d(TAG, "resolveConfig: high-speed non disponibile per " + targetFps + "fps, uso 30fps normali");
            return new VideoConfig(normalSize, false, 30, null);
        } catch (Exception e) {
            Log.e(TAG, "resolveConfig error", e);
            return new VideoConfig(desired, false, Math.min(targetFps, 30), null);
        }
    }
}
