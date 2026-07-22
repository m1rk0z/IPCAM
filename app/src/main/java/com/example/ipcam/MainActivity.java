package com.example.ipcam;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import android.graphics.Bitmap;
import android.widget.ImageView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements StreamingForegroundService.StreamingServiceListener {
    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 101;

    private TextureView cameraPreview;
    private TextView tvRtspUrl;
    private View vStatusIndicator;
    private TextView tvStatusText;
    private MaterialButton btnStartStop;
    private MaterialButton btnToggleCamera;
    private MaterialButton btnToggleAudio;
    private MaterialButton btnCopyUrl;
    private EditText etPort;
    private Spinner spResolution;
    private Spinner spFps;
    private Spinner spBitrate;
    private Spinner spRotation;
    private AutoCompleteTextView actvIpSelector;
    private TextInputLayout tilIpSelector;
    private NetworkInfoHelper.IpAddressInfo selectedIpAddress;
    private MaterialButton btnShareUrl;
    private MaterialButton btnQrCode;

    // Diagnostic views
    private TextView tvDiagResolution;
    private TextView tvDiagBitrate;
    private TextView tvDiagFps;
    private TextView tvDiagClients;
    private TextView tvDiagBufferDuration;
    private TextView tvDiagDiskUsage;
    private TextView tvDiagSegmentCount;

    private SettingsRepository settingsRepository;
    private StreamingForegroundService streamingService;
    private boolean isBound = false;
    private Surface previewSurface;
    private boolean isAutoChangingSettings = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            StreamingForegroundService.LocalBinder binder = (StreamingForegroundService.LocalBinder) service;
            streamingService = binder.getService();
            isBound = true;
            streamingService.setListener(MainActivity.this);
            streamingService.updateDiscoveryAdvertisement(Build.MODEL, settingsRepository.getRtspPort());

            // Sync UI with service's current running state
            updateUiState(streamingService.isStreaming());
            if (streamingService.isStreaming()) {
                Toast.makeText(MainActivity.this, "Reconnected to active stream session", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            streamingService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settingsRepository = new SettingsRepository(this);

        initViews();
        setupSpinner();
        setupListeners();

        // Bind to service immediately (starts service process but doesn't begin streaming)
        Intent intent = new Intent(this, StreamingForegroundService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void initViews() {
        cameraPreview = findViewById(R.id.cameraPreview);
        tvRtspUrl = findViewById(R.id.tvRtspUrl);
        vStatusIndicator = findViewById(R.id.vStatusIndicator);
        tvStatusText = findViewById(R.id.tvStatusText);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnToggleCamera = findViewById(R.id.btnToggleCamera);
        btnToggleAudio = findViewById(R.id.btnToggleAudio);
        btnCopyUrl = findViewById(R.id.btnCopyUrl);
        btnShareUrl = findViewById(R.id.btnShareUrl);
        btnQrCode = findViewById(R.id.btnQrCode);
        etPort = findViewById(R.id.etPort);
        spResolution = findViewById(R.id.spResolution);
        spFps = findViewById(R.id.spFps);
        spBitrate = findViewById(R.id.spBitrate);
        spRotation = findViewById(R.id.spRotation);
        actvIpSelector = findViewById(R.id.actvIpSelector);
        tilIpSelector = findViewById(R.id.tilIpSelector);

        tvDiagResolution = findViewById(R.id.tvDiagResolution);
        tvDiagBitrate = findViewById(R.id.tvDiagBitrate);
        tvDiagFps = findViewById(R.id.tvDiagFps);
        tvDiagClients = findViewById(R.id.tvDiagClients);
        tvDiagBufferDuration = findViewById(R.id.tvDiagBufferDuration);
        tvDiagDiskUsage = findViewById(R.id.tvDiagDiskUsage);
        tvDiagSegmentCount = findViewById(R.id.tvDiagSegmentCount);

        // Load persisted settings
        etPort.setText(String.valueOf(settingsRepository.getRtspPort()));
        
        // Setup initial audio toggle button state
        updateAudioButtonState(settingsRepository.isAudioEnabled());
    }

    private void setupSpinner() {
        // Resolution Spinner
        String[] resolutions = {"1080p", "4K", "720p"};
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spResolution.setAdapter(resAdapter);

        String savedRes = settingsRepository.getVideoResolution();
        for (int i = 0; i < resolutions.length; i++) {
            if (resolutions[i].equals(savedRes)) {
                spResolution.setSelection(i);
                break;
            }
        }

        // FPS Spinner
        String[] fpsOptions = {"30 FPS", "60 FPS", "120 FPS", "15 FPS", "45 FPS"};
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, fpsOptions);
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFps.setAdapter(fpsAdapter);

        int savedFps = settingsRepository.getVideoFps();
        String savedFpsStr = savedFps + " FPS";
        for (int i = 0; i < fpsOptions.length; i++) {
            if (fpsOptions[i].equals(savedFpsStr)) {
                spFps.setSelection(i);
                break;
            }
        }

        // Bitrate Spinner
        String[] bitrateOptions = {
            "1 Mbps", "2 Mbps", "4 Mbps", "6 Mbps (Predefinito)", "10 Mbps", "15 Mbps", "20 Mbps"
        };
        ArrayAdapter<String> bitrateAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, bitrateOptions);
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBitrate.setAdapter(bitrateAdapter);

        int savedBitrate = settingsRepository.getVideoBitrate();
        String savedBitrateStr = savedBitrate + " Mbps";
        if (savedBitrate == 6) {
            savedBitrateStr = "6 Mbps (Predefinito)";
        }
        for (int i = 0; i < bitrateOptions.length; i++) {
            if (bitrateOptions[i].equals(savedBitrateStr)) {
                spBitrate.setSelection(i);
                break;
            }
        }

        // Rotation Spinner
        String[] rotationOptions = {"0°", "90°"};
        ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, rotationOptions);
        rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRotation.setAdapter(rotationAdapter);

        int savedRotation = settingsRepository.getVideoRotation();
        String savedRotationStr = savedRotation + "°";
        for (int i = 0; i < rotationOptions.length; i++) {
            if (rotationOptions[i].equals(savedRotationStr)) {
                spRotation.setSelection(i);
                break;
            }
        }
    }

    private void setupListeners() {
        cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                previewSurface = new Surface(surfaceTexture);
                int savedRotation = settingsRepository.getVideoRotation();
                setPreviewRotation(savedRotation);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                if (previewSurface != null) {
                    previewSurface.release();
                    previewSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {}
        });

        btnStartStop.setOnClickListener(v -> toggleStreaming());
        btnToggleCamera.setOnClickListener(v -> toggleCameraDirection());
        btnToggleAudio.setOnClickListener(v -> toggleAudioMute());

        btnCopyUrl.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("RTSP URL", tvRtspUrl.getText().toString());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, "Link copiato negli appunti", Toast.LENGTH_SHORT).show();
            }
        });

        btnShareUrl.setOnClickListener(v -> {
            String rtspUrl = tvRtspUrl.getText().toString();
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, rtspUrl);
            startActivity(Intent.createChooser(shareIntent, "Condividi link RTSP"));
        });

        btnQrCode.setOnClickListener(v -> {
            String rtspUrl = tvRtspUrl.getText().toString();
            showQrCodeDialog(rtspUrl);
        });

        if (tilIpSelector != null) {
            tilIpSelector.setStartIconOnClickListener(v -> {
                updateAvailableIps();
                Toast.makeText(MainActivity.this, "Lista IP aggiornata", Toast.LENGTH_SHORT).show();
            });
        }

        // Keep the network discovery advertisement in sync whenever the RTSP port changes,
        // so the camera is found with the right port even before streaming is started.
        etPort.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            try {
                int port = Integer.parseInt(etPort.getText().toString());
                if (isBound && streamingService != null) {
                    streamingService.updateDiscoveryAdvertisement(Build.MODEL, port);
                }
            } catch (NumberFormatException ignored) {
                // Leave the previous advertisement in place until a valid port is entered
            }
        });

        spRotation.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (isAutoChangingSettings) return;
                String selected = parent.getItemAtPosition(position).toString();
                int degrees = 0;
                if (selected.equals("90°")) degrees = 90;

                int savedRotation = settingsRepository.getVideoRotation();
                if (degrees == savedRotation) return;

                settingsRepository.setVideoRotation(degrees);
                setPreviewRotation(degrees);

                if (isBound && streamingService != null && streamingService.isStreaming()) {
                    streamingService.changeRotation(degrees);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        spResolution.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (isAutoChangingSettings) return;
                String selectedRes = parent.getItemAtPosition(position).toString();
                String savedRes = settingsRepository.getVideoResolution();
                if (selectedRes.equals(savedRes)) return;

                // Determine default FPS and Bitrate based on the new resolution to fit hardware constraints
                int defaultFps = 30;
                int defaultBitrateMbps = 6;
                String fpsStr = "30 FPS";
                String bitrateStr = "6 Mbps (Predefinito)";

                if (selectedRes.equals("4K")) {
                    defaultFps = 30;
                    defaultBitrateMbps = 15;
                    fpsStr = "30 FPS";
                    bitrateStr = "15 Mbps";
                } else if (selectedRes.equals("720p")) {
                    defaultFps = 30;
                    defaultBitrateMbps = 4;
                    fpsStr = "30 FPS";
                    bitrateStr = "4 Mbps";
                }

                isAutoChangingSettings = true;
                
                settingsRepository.setVideoResolution(selectedRes);
                settingsRepository.setVideoFps(defaultFps);
                settingsRepository.setVideoBitrate(defaultBitrateMbps);

                // Update UI selections programmatically without triggering their listeners
                setSpinnerSelection(spFps, fpsStr);
                setSpinnerSelection(spBitrate, bitrateStr);

                isAutoChangingSettings = false;

                if (isBound && streamingService != null && streamingService.isStreaming()) {
                    int rotation = settingsRepository.getVideoRotation();
                    // Update bitrate first
                    streamingService.changeBitrate(defaultBitrateMbps);
                    // Then trigger hot-swap for resolution & FPS
                    streamingService.hotSwapVideoPipeline(selectedRes, defaultFps, rotation);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        spFps.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (isAutoChangingSettings) return;
                String fpsStr = parent.getItemAtPosition(position).toString();
                int fps = 30;
                try {
                    fps = Integer.parseInt(fpsStr.replace(" FPS", "").trim());
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing FPS setting", e);
                }
                int savedFps = settingsRepository.getVideoFps();
                if (fps == savedFps) return;

                settingsRepository.setVideoFps(fps);
                if (isBound && streamingService != null && streamingService.isStreaming()) {
                    String res = spResolution.getSelectedItem().toString();
                    int rotation = settingsRepository.getVideoRotation();
                    streamingService.hotSwapVideoPipeline(res, fps, rotation);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        spBitrate.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (isAutoChangingSettings) return;
                String bitrateStr = parent.getItemAtPosition(position).toString();
                int bitrateMbps = 6;
                try {
                    bitrateMbps = Integer.parseInt(bitrateStr.split(" ")[0].trim());
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing Bitrate setting", e);
                }
                int savedBitrate = settingsRepository.getVideoBitrate();
                if (bitrateMbps == savedBitrate) return;

                settingsRepository.setVideoBitrate(bitrateMbps);
                if (isBound && streamingService != null && streamingService.isStreaming()) {
                    streamingService.changeBitrate(bitrateMbps);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void updateRtspUrlDisplay() {
        String ip = (selectedIpAddress != null) ? selectedIpAddress.ipAddress : "127.0.0.1";
        int port = settingsRepository.getRtspPort();
        tvRtspUrl.setText("rtsp://" + ip + ":" + port + "/live");
    }

    private void updateAvailableIps() {
        if (actvIpSelector == null) return;
        List<NetworkInfoHelper.IpAddressInfo> ips = NetworkInfoHelper.getAvailableIpAddresses();
        ArrayAdapter<NetworkInfoHelper.IpAddressInfo> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_dropdown_item, ips);
        actvIpSelector.setAdapter(adapter);

        if (!ips.isEmpty()) {
            if (selectedIpAddress == null) {
                selectedIpAddress = ips.get(0);
            } else {
                boolean found = false;
                for (NetworkInfoHelper.IpAddressInfo ip : ips) {
                    if (ip.ipAddress.equals(selectedIpAddress.ipAddress)) {
                        selectedIpAddress = ip;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    selectedIpAddress = ips.get(0);
                }
            }
            actvIpSelector.setText(selectedIpAddress.toString(), false);
        }

        actvIpSelector.setOnItemClickListener((parent, view, position, id) -> {
            selectedIpAddress = (NetworkInfoHelper.IpAddressInfo) parent.getItemAtPosition(position);
            updateRtspUrlDisplay();
        });

        updateRtspUrlDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAvailableIps();
    }

    private void toggleStreaming() {
        if (!hasRequiredPermissions()) {
            requestPermissions();
            return;
        }

        if (isBound && streamingService != null) {
            if (streamingService.isStreaming()) {
                streamingService.stopStreaming();
            } else {
                // Save settings before start
                int port = 8554;
                try {
                    port = Integer.parseInt(etPort.getText().toString());
                } catch (NumberFormatException e) {
                    etPort.setText("8554");
                }
                settingsRepository.setRtspPort(port);
                streamingService.updateDiscoveryAdvertisement(Build.MODEL, port);

                String res = spResolution.getSelectedItem().toString();
                settingsRepository.setVideoResolution(res);

                // Get FPS
                String fpsStr = spFps.getSelectedItem().toString();
                int fps = 30;
                try {
                    fps = Integer.parseInt(fpsStr.replace(" FPS", "").trim());
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing FPS setting", e);
                }
                settingsRepository.setVideoFps(fps);

                // Get Bitrate
                String bitrateStr = spBitrate.getSelectedItem().toString();
                int bitrateMbps = 6;
                try {
                    bitrateMbps = Integer.parseInt(bitrateStr.split(" ")[0].trim());
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing Bitrate setting", e);
                }
                settingsRepository.setVideoBitrate(bitrateMbps);

                // Get Rotation
                String rotationStr = spRotation.getSelectedItem().toString();
                int rotation = 0;
                if (rotationStr.equals("90°")) rotation = 90;
                else if (rotationStr.equals("180°")) rotation = 180;
                else if (rotationStr.equals("270°")) rotation = 270;
                settingsRepository.setVideoRotation(rotation);

                updateRtspUrlDisplay();

                streamingService.startStreaming(
                        port,
                        res,
                        fps,
                        bitrateMbps,
                        rotation,
                        settingsRepository.isAudioEnabled(),
                        settingsRepository.useFrontCamera(),
                        previewSurface
                );
            }
        }
    }

    private void toggleCameraDirection() {
        boolean useFront = !settingsRepository.useFrontCamera();
        settingsRepository.setUseFrontCamera(useFront);
        
        if (isBound && streamingService != null && streamingService.isStreaming()) {
            streamingService.toggleCamera(previewSurface);
        } else {
            Toast.makeText(this, "Camera direction set to " + (useFront ? "Front" : "Rear"), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleAudioMute() {
        boolean audioEnabled = !settingsRepository.isAudioEnabled();
        settingsRepository.setAudioEnabled(audioEnabled);
        updateAudioButtonState(audioEnabled);
        
        if (isBound && streamingService != null && streamingService.isStreaming()) {
            streamingService.toggleAudio(audioEnabled);
        }
    }

    private void updateAudioButtonState(boolean enabled) {
        if (enabled) {
            btnToggleAudio.setText("AUDIO ON");
            btnToggleAudio.setIconResource(android.R.drawable.ic_lock_silent_mode_off);
        } else {
            btnToggleAudio.setText("AUDIO MUTED");
            btnToggleAudio.setIconResource(android.R.drawable.ic_lock_silent_mode);
        }
    }

    private void updateUiState(boolean active) {
        runOnUiThread(() -> {
            if (active) {
                btnStartStop.setText("STOP LIVE");
                btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.error));
                vStatusIndicator.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.success));
                tvStatusText.setText("SERVER ACTIVE");
                
                // Disable port setting changes during stream
                etPort.setEnabled(false);
                // Keep video settings enabled for real-time live updates
                spResolution.setEnabled(true);
                spFps.setEnabled(true);
                spBitrate.setEnabled(true);
            } else {
                btnStartStop.setText("START LIVE");
                btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.success));
                vStatusIndicator.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.error));
                tvStatusText.setText("OFFLINE");
                
                // Enable settings modification when offline
                etPort.setEnabled(true);
                spResolution.setEnabled(true);
                spFps.setEnabled(true);
                spBitrate.setEnabled(true);
                
                // Clear diagnostics
                tvDiagResolution.setText("Res: --");
                tvDiagBitrate.setText("Bitrate: -- Mbps");
                tvDiagFps.setText("Fps: --");
                tvDiagClients.setText("Clients: 0");
            }
        });
    }

    // SERVICE CALLBACK IMPLEMENTATIONS (Runs on service background thread or main thread depending on configuration)
    // We post to UI Thread inside the callbacks to ensure UI safety.

    @Override
    public void onStateChanged(boolean active) {
        updateUiState(active);
    }

    @Override
    public void onStatsUpdated(int fps, double bitrateMbps, int clientsCount) {
        runOnUiThread(() -> {
            tvDiagFps.setText(String.format("Fps: %d", fps));
            tvDiagBitrate.setText(String.format("Bitrate: %.2f Mbps", bitrateMbps));
            tvDiagClients.setText(String.format("Clients: %d", clientsCount));
        });
    }

    @Override
    public void onBufferStatsUpdated(long totalSec, long totalMb, int count) {
        runOnUiThread(() -> {
            tvDiagBufferDuration.setText(String.format("Buffer: %ds / 300s", totalSec));
            tvDiagDiskUsage.setText(String.format("Disk Size: %d MB", totalMb));
            tvDiagSegmentCount.setText(String.format("Segments: %d", count));
        });
    }

    @Override
    public void onActiveResolutionChanged(Size size) {
        runOnUiThread(() -> {
            tvDiagResolution.setText(String.format("Res: %dx%d", size.getWidth(), size.getHeight()));
        });
    }

    @Override
    public void onStreamingError(String message, boolean fallbackOccurred) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            if (fallbackOccurred) {
                // If fallback occurred, sync the resolution spinner selection
                spResolution.setSelection(0); // Index 0 represents 1080p
                settingsRepository.setVideoResolution("1080p");
            }
        });
    }

    // PERMISSIONS MANAGEMENT

    private boolean hasRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                toggleStreaming();
            } else {
                Toast.makeText(this, "Camera, microphone, and notification permissions are required to host the stream.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showQrCodeDialog(String url) {
        try {
            Bitmap bitmap = generateQrCode(url, 400, 400);
            
            // Inflate custom dialog layout
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_qr_code, null);

            ImageView ivQrCode = dialogView.findViewById(R.id.ivQrCode);
            TextView tvDialogRtspUrl = dialogView.findViewById(R.id.tvDialogRtspUrl);
            MaterialButton btnDialogCopy = dialogView.findViewById(R.id.btnDialogCopy);
            MaterialButton btnDialogShare = dialogView.findViewById(R.id.btnDialogShare);

            ivQrCode.setImageBitmap(bitmap);
            tvDialogRtspUrl.setText(url);

            androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

            btnDialogCopy.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("RTSP URL", url);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Link copiato negli appunti", Toast.LENGTH_SHORT).show();
                }
            });

            btnDialogShare.setOnClickListener(v -> {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, url);
                startActivity(Intent.createChooser(shareIntent, "Condividi link RTSP"));
            });

            dialog.show();
        } catch (Exception e) {
            Toast.makeText(this, "Errore nella generazione del QR Code", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error generating QR Code", e);
        }
    }

    private Bitmap generateQrCode(String text, int width, int height) throws com.google.zxing.WriterException {
        BitMatrix bitMatrix;
        try {
            bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height);
        } catch (IllegalArgumentException iae) {
            throw new com.google.zxing.WriterException(iae.getMessage());
        }
        int bitMatrixWidth = bitMatrix.getWidth();
        int bitMatrixHeight = bitMatrix.getHeight();
        int[] pixels = new int[bitMatrixWidth * bitMatrixHeight];
        for (int y = 0; y < bitMatrixHeight; y++) {
            int offset = y * bitMatrixWidth;
            for (int x = 0; x < bitMatrixWidth; x++) {
                pixels[offset + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(bitMatrixWidth, bitMatrixHeight, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, bitMatrixWidth, 0, 0, bitMatrixWidth, bitMatrixHeight);
        return bitmap;
    }

    @Override
    protected void onDestroy() {
        if (isBound) {
            if (streamingService != null) {
                streamingService.setListener(null);
            }
            unbindService(connection);
            isBound = false;
        }
        super.onDestroy();
    }

    private void setPreviewRotation(int degrees) {
        if (cameraPreview == null) return;
        runOnUiThread(() -> {
            cameraPreview.setRotation(degrees);
            int w = cameraPreview.getWidth();
            int h = cameraPreview.getHeight();
            if (w > 0 && h > 0) {
                if (degrees == 90 || degrees == 270) {
                    float ratio = (float) w / h;
                    cameraPreview.setScaleX(1.0f / ratio);
                    cameraPreview.setScaleY(ratio);
                } else {
                    cameraPreview.setScaleX(1.0f);
                    cameraPreview.setScaleY(1.0f);
                }
            }
        });
    }

    private void setSpinnerSelection(Spinner spinner, String value) {
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) spinner.getAdapter();
        if (adapter != null) {
            int pos = adapter.getPosition(value);
            if (pos >= 0) {
                spinner.setSelection(pos);
            }
        }
    }
}
