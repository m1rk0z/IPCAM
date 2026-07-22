package com.example.ipcam;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsRepository {
    private static final String PREFS_NAME = "ipcam_settings";
    
    private static final String KEY_PORT = "rtsp_port";
    private static final String KEY_RESOLUTION = "video_resolution";
    private static final String KEY_AUDIO_ENABLED = "audio_enabled";
    private static final String KEY_USE_FRONT_CAMERA = "use_front_camera";
    private static final String KEY_BUFFER_DURATION = "buffer_duration";
    private static final String KEY_FPS = "video_fps";
    private static final String KEY_BITRATE = "video_bitrate";
    private static final String KEY_ROTATION = "video_rotation";

    private final SharedPreferences prefs;

    public SettingsRepository(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getRtspPort() {
        return prefs.getInt(KEY_PORT, 8554);
    }

    public void setRtspPort(int port) {
        prefs.edit().putInt(KEY_PORT, port).apply();
    }

    public String getVideoResolution() {
        return prefs.getString(KEY_RESOLUTION, "1080p");
    }

    public void setVideoResolution(String resolution) {
        prefs.edit().putString(KEY_RESOLUTION, resolution).apply();
    }

    public boolean isAudioEnabled() {
        return prefs.getBoolean(KEY_AUDIO_ENABLED, true);
    }

    public void setAudioEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUDIO_ENABLED, enabled).apply();
    }

    public boolean useFrontCamera() {
        return prefs.getBoolean(KEY_USE_FRONT_CAMERA, false);
    }

    public void setUseFrontCamera(boolean useFront) {
        prefs.edit().putBoolean(KEY_USE_FRONT_CAMERA, useFront).apply();
    }

    public int getBufferDurationSeconds() {
        return prefs.getInt(KEY_BUFFER_DURATION, 300);
    }

    public void setBufferDurationSeconds(int seconds) {
        prefs.edit().putInt(KEY_BUFFER_DURATION, seconds).apply();
    }

    public int getVideoFps() {
        return prefs.getInt(KEY_FPS, 30);
    }

    public void setVideoFps(int fps) {
        prefs.edit().putInt(KEY_FPS, fps).apply();
    }

    public int getVideoBitrate() {
        return prefs.getInt(KEY_BITRATE, 6); // 6 Mbps default
    }

    public void setVideoBitrate(int bitrate) {
        prefs.edit().putInt(KEY_BITRATE, bitrate).apply();
    }

    public int getVideoRotation() {
        return prefs.getInt(KEY_ROTATION, 0);
    }

    public void setVideoRotation(int rotation) {
        prefs.edit().putInt(KEY_ROTATION, rotation).apply();
    }
}
