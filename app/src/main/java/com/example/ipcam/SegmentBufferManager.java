package com.example.ipcam;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

public class SegmentBufferManager {
    private static final String TAG = "SegmentBufferManager";
    private static final long SEGMENT_DURATION_US = 5_000_000L; // 5 seconds in microseconds
    private static final long MAX_BUFFER_DURATION_US = 300_000_000L; // 300 seconds in microseconds

    private static class SegmentInfo {
        final File file;
        final long startPtsUs;
        long durationUs;
        long sizeBytes;

        SegmentInfo(File file, long startPtsUs) {
            this.file = file;
            this.startPtsUs = startPtsUs;
            this.durationUs = 0;
            this.sizeBytes = 0;
        }
    }

    private static class EncodedFrame {
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;
        final boolean isVideo;

        EncodedFrame(ByteBuffer data, MediaCodec.BufferInfo info, boolean isVideo) {
            // Duplicate and copy to ensure memory safety in asynchronous queue
            ByteBuffer dup = ByteBuffer.allocateDirect(info.size);
            data.position(info.offset);
            data.limit(info.offset + info.size);
            dup.put(data);
            dup.flip();
            
            this.data = dup;
            this.info = new MediaCodec.BufferInfo();
            this.info.set(0, info.size, info.presentationTimeUs, info.flags);
            this.isVideo = isVideo;
        }
    }

    public interface SegmentStatsListener {
        void onStatsUpdated(long totalDurationSec, long totalSizeBytes, int segmentCount);
    }

    private final Context context;
    private final File bufferDir;
    private final LinkedBlockingQueue<EncodedFrame> frameQueue = new LinkedBlockingQueue<>(120); // Bounds queue to prevent OOM
    private final List<SegmentInfo> segments = new ArrayList<>();
    private final SegmentStatsListener statsListener;

    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private boolean isAudioEnabled = true;

    private Thread writerThread;
    private volatile boolean isRunning = false;

    private MediaMuxer currentMuxer;
    private SegmentInfo currentSegment;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private int segmentCounter = 0;
    
    private long lastVideoPtsUs = -1;

    public SegmentBufferManager(Context context, SegmentStatsListener statsListener) {
        this.context = context.getApplicationContext();
        this.statsListener = statsListener;
        this.bufferDir = new File(context.getExternalCacheDir(), "ipcam_replay");
        if (!bufferDir.exists()) {
            bufferDir.mkdirs();
        }
        clearExistingSegments();
    }

    public synchronized void start(boolean isAudioEnabled) {
        Log.d(TAG, "Starting SegmentBufferManager...");
        this.isAudioEnabled = isAudioEnabled;
        this.videoFormat = null;
        this.audioFormat = null;
        this.videoTrackIndex = -1;
        this.audioTrackIndex = -1;
        this.lastVideoPtsUs = -1;
        this.segments.clear();
        this.frameQueue.clear();

        isRunning = true;
        writerThread = new Thread(this::runWriterLoop, "SegmentWriter-Worker");
        writerThread.start();
    }

    public synchronized void stop() {
        Log.d(TAG, "Stopping SegmentBufferManager...");
        isRunning = false;
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writerThread = null;
        }
        closeCurrentMuxer();
        clearExistingSegments();
        Log.d(TAG, "SegmentBufferManager stopped.");
    }

    private volatile boolean pendingFormatChange = false;

    public synchronized void setVideoFormat(MediaFormat format) {
        if (this.videoFormat != null && !format.toString().equals(this.videoFormat.toString())) {
            pendingFormatChange = true;
        }
        this.videoFormat = format;
    }

    public synchronized void setAudioFormat(MediaFormat format) {
        this.audioFormat = format;
    }

    public void addVideoFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (!isRunning) return;
        frameQueue.offer(new EncodedFrame(buffer, info, true));
    }

    public void addAudioFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (!isRunning || !isAudioEnabled) return;
        frameQueue.offer(new EncodedFrame(buffer, info, false));
    }

    private void runWriterLoop() {
        while (isRunning) {
            try {
                if (pendingFormatChange) {
                    pendingFormatChange = false;
                    closeCurrentMuxer();
                }

                EncodedFrame frame = frameQueue.take();
                
                // Wait until we have the required format descriptions to build the MP4 container
                if (videoFormat == null || (isAudioEnabled && audioFormat == null)) {
                    continue;
                }

                if (currentMuxer == null) {
                    // Muxer needs to start on a keyframe to prevent corrupted MP4 playbacks
                    if (frame.isVideo && (frame.info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        startNewSegment(frame.info.presentationTimeUs);
                    } else {
                        continue; // Skip non-keyframes until we start a new segment
                    }
                }

                // Check segment duration and split if we hit the duration limit on a Keyframe
                if (frame.isVideo && (frame.info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    long duration = frame.info.presentationTimeUs - currentSegment.startPtsUs;
                    if (duration >= SEGMENT_DURATION_US) {
                        closeCurrentMuxer();
                        startNewSegment(frame.info.presentationTimeUs);
                    }
                }

                writeFrame(frame);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Exception in segment writer loop", e);
            }
        }
    }

    private void startNewSegment(long ptsUs) {
        try {
            segmentCounter++;
            File file = new File(bufferDir, "segment_" + System.currentTimeMillis() + "_" + segmentCounter + ".mp4");
            
            currentSegment = new SegmentInfo(file, ptsUs);
            currentMuxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            videoTrackIndex = currentMuxer.addTrack(videoFormat);
            if (isAudioEnabled && audioFormat != null) {
                audioTrackIndex = currentMuxer.addTrack(audioFormat);
            }
            
            currentMuxer.start();
            Log.d(TAG, "Started segment file: " + file.getName());
        } catch (Exception e) {
            Log.e(TAG, "Error starting new segment", e);
            currentMuxer = null;
            currentSegment = null;
        }
    }

    private void writeFrame(EncodedFrame frame) {
        if (currentMuxer == null || currentSegment == null) return;
        
        try {
            int trackIndex = frame.isVideo ? videoTrackIndex : audioTrackIndex;
            if (trackIndex < 0) return;

            // MediaMuxer requires presentation timestamps to start at or near 0 for each file.
            // Adjust the absolute timestamp of the frame relative to the start of this segment.
            long relativePtsUs = frame.info.presentationTimeUs - currentSegment.startPtsUs;
            if (relativePtsUs < 0) {
                relativePtsUs = 0;
            }

            MediaCodec.BufferInfo adjustedInfo = new MediaCodec.BufferInfo();
            adjustedInfo.set(0, frame.info.size, relativePtsUs, frame.info.flags);

            currentMuxer.writeSampleData(trackIndex, frame.data, adjustedInfo);

            if (frame.isVideo) {
                lastVideoPtsUs = frame.info.presentationTimeUs;
                currentSegment.durationUs = relativePtsUs;
            }
            currentSegment.sizeBytes += frame.info.size;

        } catch (Exception e) {
            Log.e(TAG, "Error writing frame to muxer", e);
        }
    }

    private void closeCurrentMuxer() {
        if (currentMuxer != null) {
            try {
                currentMuxer.stop();
                currentMuxer.release();
                Log.d(TAG, "Closed segment file: " + currentSegment.file.getName() 
                      + ", duration: " + (currentSegment.durationUs / 1_000_000.0) + "s, size: " + currentSegment.sizeBytes + " bytes");
                
                synchronized (segments) {
                    segments.add(currentSegment);
                    pruneOldSegments();
                    updateStats();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping/releasing muxer", e);
                // If muxer failed, delete the partial/corrupted file
                if (currentSegment != null && currentSegment.file.exists()) {
                    currentSegment.file.delete();
                }
            }
            currentMuxer = null;
            currentSegment = null;
            videoTrackIndex = -1;
            audioTrackIndex = -1;
        }
    }

    private void pruneOldSegments() {
        long totalDurationUs = 0;
        for (SegmentInfo seg : segments) {
            totalDurationUs += seg.durationUs;
        }

        while (totalDurationUs > MAX_BUFFER_DURATION_US && !segments.isEmpty()) {
            SegmentInfo oldest = segments.remove(0);
            totalDurationUs -= oldest.durationUs;
            if (oldest.file.exists()) {
                boolean deleted = oldest.file.delete();
                Log.d(TAG, "Pruned/Deleted oldest segment: " + oldest.file.getName() + " (" + deleted + ")");
            }
        }
    }

    private void updateStats() {
        long totalDurationSec = 0;
        long totalSizeBytes = 0;
        int count = 0;
        
        synchronized (segments) {
            count = segments.size();
            for (SegmentInfo seg : segments) {
                totalDurationSec += (seg.durationUs / 1_000_000L);
                totalSizeBytes += seg.sizeBytes;
            }
        }
        
        if (statsListener != null) {
            statsListener.onStatsUpdated(totalDurationSec, totalSizeBytes, count);
        }
    }

    private void clearExistingSegments() {
        try {
            File[] files = bufferDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".mp4")) {
                        f.delete();
                    }
                }
            }
            Log.d(TAG, "Cleared any existing segment files.");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing old segments from disk", e);
        }
    }
}
