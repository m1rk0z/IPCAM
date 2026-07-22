# CLAUDE.md - Developer Guide & Codebase Rules

This file outlines build commands, testing procedures, architecture patterns, and code style rules for the **IPCAM Pro** Android project, tailored for Claude Code agents.

---

## 1. Build and Test Commands

### Compilation and Build
- **Build Debug APK**: `.\gradlew.bat assembleDebug`
- **Build Release APK** (Signed with debug keystore): `.\gradlew.bat assembleRelease`
- **Clean Build Directory**: `.\gradlew.bat clean`
- **Verify Lint/Checks**: `.\gradlew.bat lint`

### Testing
- **Run Unit Tests**: `.\gradlew.bat test`
- **Run Instrumentation Tests**: `.\gradlew.bat connectedAndroidTest`

---

## 2. Project Architecture & Codebase Layout

### Key Paths
- **Java Sources**: `app/src/main/java/com/example/ipcam/`
- **Layout XMLs**: `app/src/main/res/layout/`
- **Drawables**: `app/src/main/res/drawable/`
- **Colors & Themes**: `app/src/main/res/values/` and `app/src/main/res/values-night/`
- **Manifest**: `app/src/main/AndroidManifest.xml`

### Source Files & Roles
1. **`MainActivity.java`**: Handles the UI controller, IP address bindings, QR code generation, and configuration inputs.
2. **`StreamingForegroundService.java`**: Binds all streaming managers and maintains runtime persistence as an Android Foreground Service.
3. **`CameraCaptureManager.java`**: Orchestrates `Camera2` sessions to output to both the UI TextureView preview and the encoder surface.
4. **`VideoEncoderManager.java`**: Uses hardware `MediaCodec` to encode camera frames to raw H.264 elementary stream.
5. **`AudioCaptureManager.java`**: Records PCM audio and encodes it into AAC ADTS frames.
6. **`RtspServerManager.java`**: Pure-Java RTSP server supporting UDP and TCP (interleaved) transmission, packetizing H.264 and AAC into RTP.
7. **`SegmentBufferManager.java`**: Implements a local replay buffer by writing stream chunks into 5-second cyclical `.mp4` cache files.

---

## 3. Critical Code Style & Architectural Constraints

### ⚠️ Dynamic Parameter Hot-Swapping
- **Rule**: Changing video parameters (resolution, fps, rotation, camera facing) **must not stop the RTSP server socket** or disconnect network clients.
- **Implementation**: `StreamingForegroundService.hotSwapVideoPipeline(...)` shuts down only the `CameraCaptureManager` and `VideoEncoderManager`, then re-configures and restarts them. The server socket in `RtspServerManager` continues running.
- **Bitrate Adaptation**: Modify bitrate on-the-fly without pipeline re-initialization by passing dynamic parameters to the running `MediaCodec` instance:
  ```java
  Bundle params = new Bundle();
  params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate);
  videoEncoder.setParameters(params);
  ```

### 🎨 UI/UX & Layout Constraints (Cyber Teal Theme)
- **Edge-to-Edge Compatibility**: The application target is Android 15+ (`targetSdk = 36`).
  - The root layout of `activity_main.xml` MUST have `android:fitsSystemWindows="true"` to prevent overlapping the system status and navigation bars.
- **ConstraintLayout Dimensions**:
  - Never use `android:layout_width="match_parent"` for direct children inside `ConstraintLayout`.
  - Instead, use `android:layout_width="0dp"` with explicit start/end constraints:
    ```xml
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent"
    ```
  - This ensures proper padding, margins, and prevents elements from overflowing device screen edges.
- **Design Tokens**:
  - Background color: `@color/background` (`#0F172A` Slate Dark)
  - Card background: `@color/surface` (`#192134` Slate Blue)
  - Accent colors: `@color/primary` (`#06B6D4` Cyan) and `@color/accent` (`#F97316` Orange)
  - Status indicators: Programmatic changes to text color/status pill background should use theme resources `R.color.success` and `R.color.error` instead of system default colors.
