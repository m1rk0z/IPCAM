package com.example.ipcam;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RtspServerManager {
    private static final String TAG = "RtspServerManager";
    
    private static final int PAYLOAD_TYPE_VIDEO = 96;
    private static final int PAYLOAD_TYPE_AUDIO = 97;
    private static final int MTU = 1400;

    public interface RtspServerListener {
        void onClientCountChanged(int count);
        void onServerError(Exception e);
    }

    private final int port;
    private final RtspServerListener listener;
    
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;
    
    private final List<ClientSession> clients = Collections.synchronizedList(new ArrayList<>());
    
    private String spsPpsBase64 = "";
    private boolean isAudioEnabled = true;

    // Track sequence numbers and SSRC for RTP
    private int videoSeq = 0;
    private int audioSeq = 0;
    private final int videoSsrc;
    private final int audioSsrc;

    public RtspServerManager(int port, RtspServerListener listener) {
        this.port = port;
        this.listener = listener;
        Random r = new Random();
        this.videoSsrc = r.nextInt();
        this.audioSsrc = r.nextInt();
    }


    public synchronized void setSpsPps(String spsPpsBase64) {
        this.spsPpsBase64 = spsPpsBase64;
    }

    public synchronized void start(boolean isAudioEnabled) throws IOException {
        Log.d(TAG, "Starting RTSP Server on port: " + port);
        this.isAudioEnabled = isAudioEnabled;
        this.clients.clear();
        this.videoSeq = 0;
        this.audioSeq = 0;

        serverSocket = new ServerSocket(port, 10, InetAddress.getByName("0.0.0.0"));
        isRunning = true;

        serverThread = new Thread(this::runServerLoop, "RtspServer-Listener");
        serverThread.start();
        Log.d(TAG, "RTSP Server listening on port " + port);
    }

    public synchronized void stop() {
        Log.d(TAG, "Stopping RTSP Server...");
        isRunning = false;
        
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
            serverSocket = null;
        }

        if (serverThread != null) {
            serverThread.interrupt();
            try {
                serverThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverThread = null;
        }

        // Close all client sessions
        synchronized (clients) {
            for (ClientSession client : clients) {
                client.close();
            }
            clients.clear();
        }
        
        if (listener != null) {
            listener.onClientCountChanged(0);
        }
        
        Log.d(TAG, "RTSP Server stopped.");
    }

    public int getClientCount() {
        return clients.size();
    }

    private void runServerLoop() {
        while (isRunning) {
            try {
                Socket clientSocket = serverSocket.accept();
                Log.d(TAG, "New client connected from " + clientSocket.getRemoteSocketAddress());
                ClientSession session = new ClientSession(clientSocket);
                clients.add(session);
                session.start();
                
                if (listener != null) {
                    listener.onClientCountChanged(clients.size());
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Exception in RTSP server accept loop", e);
                    if (listener != null) {
                        listener.onServerError(e);
                    }
                }
                break;
            }
        }
    }

    private void removeClient(ClientSession session) {
        if (clients.remove(session)) {
            session.close();
            Log.d(TAG, "Client disconnected. Remaining: " + clients.size());
            if (listener != null) {
                listener.onClientCountChanged(clients.size());
            }
        }
    }

    // Packetize H.264 video frame and send
    public void sendVideoFrame(ByteBuffer buffer, MediaCodec.BufferInfo info, boolean isKeyFrame) {
        if (clients.isEmpty()) return;

        long ptsUs = info.presentationTimeUs;
        long rtpTimestamp = (ptsUs * 90000L) / 1000000L; // 90kHz clock


        // Parse NAL units. The encoder output buffer can contain multiple start-code separated NALs
        int limit = info.offset + info.size;
        int position = info.offset;

        List<Integer> startIndices = new ArrayList<>();
        // Scan for start codes [0, 0, 0, 1] or [0, 0, 1]
        for (int i = position; i < limit - 3; i++) {
            if (buffer.get(i) == 0 && buffer.get(i + 1) == 0 && buffer.get(i + 2) == 1) {
                startIndices.add(i);
            } else if (i < limit - 4 && buffer.get(i) == 0 && buffer.get(i + 1) == 0 && buffer.get(i + 2) == 0 && buffer.get(i + 3) == 1) {
                startIndices.add(i);
            }
        }

        for (int idx = 0; idx < startIndices.size(); idx++) {
            int start = startIndices.get(idx);
            int nextStart = (idx + 1 < startIndices.size()) ? startIndices.get(idx + 1) : limit;
            
            // Determine start code length (3 or 4 bytes)
            int startCodeLen = 3;
            if (buffer.get(start + 3) == 1) {
                startCodeLen = 4;
            }

            int nalOffset = start + startCodeLen;
            int nalSize = nextStart - nalOffset;
            if (nalSize <= 0) continue;

            byte[] nalData = new byte[nalSize];
            buffer.position(nalOffset);
            buffer.get(nalData);

            boolean isLastNal = (idx == startIndices.size() - 1);
            packetizeVideoNal(nalData, rtpTimestamp, isLastNal);
        }
    }

    private void packetizeVideoNal(byte[] nal, long timestamp, boolean isLastNal) {
        int nalType = nal[0] & 0x1F;
        
        // Skip SEI NALs for bandwidth optimization if needed, but SPS/PPS/IDR/Coded slices are mandatory
        if (nalType == 6) return;

        if (nal.length <= MTU) {
            // Send Single NAL Unit Packet
            byte[] packet = new byte[12 + nal.length];
            writeRtpHeader(packet, PAYLOAD_TYPE_VIDEO, videoSeq++, timestamp, videoSsrc, isLastNal);
            System.arraycopy(nal, 0, packet, 12, nal.length);
            broadcastPacket(packet, true);
        } else {
            // Fragment NAL Unit into FU-A packets
            int payloadOffset = 1; // Skip original NAL header byte in fragment payload
            int remaining = nal.length - 1;
            int fragmentCount = (remaining + MTU - 1) / MTU;
            byte nalHeader = nal[0];

            for (int i = 0; i < fragmentCount; i++) {
                int chunkSize = Math.min(remaining, MTU);
                byte[] packet = new byte[12 + 2 + chunkSize];
                
                boolean isFirst = (i == 0);
                boolean isLast = (i == fragmentCount - 1);
                
                writeRtpHeader(packet, PAYLOAD_TYPE_VIDEO, videoSeq++, timestamp, videoSsrc, isLast && isLastNal);
                
                // FU Indicator: NRI + Type 28
                packet[12] = (byte) ((nalHeader & 0x60) | 28);
                
                // FU Header: S + E + R + Type
                byte s = isFirst ? (byte) 0x80 : 0;
                byte e = isLast ? (byte) 0x40 : 0;
                packet[13] = (byte) (s | e | (nalHeader & 0x1F));
                
                System.arraycopy(nal, payloadOffset, packet, 14, chunkSize);
                broadcastPacket(packet, true);
                
                payloadOffset += chunkSize;
                remaining -= chunkSize;
            }
        }
    }

    // Packetize AAC audio frame and send
    public void sendAudioFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (clients.isEmpty() || !isAudioEnabled) return;

        long ptsUs = info.presentationTimeUs;
        long rtpTimestamp = (ptsUs * 44100L) / 1000000L; // 44.1kHz audio sampling clock

        byte[] frame = new byte[info.size];
        buffer.position(info.offset);
        buffer.get(frame);

        // RTP Payload for AAC-hbr (RFC 3640 / RFC 6416)
        // 12 bytes RTP header + 4 bytes AU Header section + AAC raw frame
        byte[] packet = new byte[12 + 4 + frame.length];
        
        writeRtpHeader(packet, PAYLOAD_TYPE_AUDIO, audioSeq++, rtpTimestamp, audioSsrc, true);

        // AU Headers section:
        // AU-headers-length: 16 bits = 0x0010 (size of AU-headers in bits = 16 bits)
        packet[12] = 0x00;
        packet[13] = 0x10;
        
        // AU-Header: 13 bits size + 3 bits index (0)
        int size = frame.length;
        packet[14] = (byte) ((size >> 5) & 0xFF);
        packet[15] = (byte) ((size & 0x1F) << 3);

        System.arraycopy(frame, 0, packet, 16, frame.length);
        broadcastPacket(packet, false);
    }

    private void writeRtpHeader(byte[] packet, int pt, int seq, long timestamp, int ssrc, boolean marker) {
        packet[0] = (byte) 0x80; // V=2, P=0, X=0, CC=0
        packet[1] = (byte) ((pt & 0x7F) | (marker ? 0x80 : 0x00));
        packet[2] = (byte) ((seq >> 8) & 0xFF);
        packet[3] = (byte) (seq & 0xFF);
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        packet[8] = (byte) ((ssrc >> 24) & 0xFF);
        packet[9] = (byte) ((ssrc >> 16) & 0xFF);
        packet[10] = (byte) ((ssrc >> 8) & 0xFF);
        packet[11] = (byte) (ssrc & 0xFF);
    }

    private void broadcastPacket(byte[] packet, boolean isVideo) {
        synchronized (clients) {
            for (ClientSession client : clients) {
                if (client.isPlaying) {
                    client.sendRtp(packet, isVideo);
                }
            }
        }
    }

    // Individual Client Connection Handler
    private class ClientSession extends Thread {
        private final Socket socket;
        private OutputStream os;
        private BufferedReader reader;
        private volatile boolean isConnected = true;
        
        private boolean isPlaying = false;
        private String sessionId;
        
        // Streaming Transport Options
        private boolean useTcpInterleaved = false;
        private int videoInterleavedChannel = 0;
        private int audioInterleavedChannel = 2;
        
        // UDP destination configuration
        private InetAddress clientIp;
        private int videoClientPort = -1;
        private int audioClientPort = -1;
        private DatagramSocket rtpVideoSocket;
        private DatagramSocket rtpAudioSocket;

        ClientSession(Socket socket) {
            this.socket = socket;
            this.sessionId = String.format("%08X", new Random().nextInt());
        }

        @Override
        public void run() {
            try {
                // Disabilita l'algoritmo di Nagle: senza questo, il kernel può accodare i
                // pacchetti video/RTSP piccoli in attesa di riempire il buffer TCP, aggiungendo
                // fino a qualche centinaio di ms di latenza extra sullo streaming interleaved.
                try {
                    socket.setTcpNoDelay(true);
                } catch (Exception e) {
                    Log.w(TAG, "Impossibile disabilitare Nagle (TCP_NODELAY) sul socket client", e);
                }

                os = socket.getOutputStream();
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                clientIp = socket.getInetAddress();

                while (isConnected) {
                    String line = reader.readLine();
                    if (line == null) break; // Client closed connection
                    
                    if (line.trim().isEmpty()) continue;

                    // Parse request headers (e.g. METHOD rtsp://... RTSP/1.0)
                    String[] requestLine = line.split(" ");
                    if (requestLine.length < 3) continue;
                    
                    String method = requestLine[0];
                    String url = requestLine[1];
                    
                    int cseq = 0;
                    String transport = "";
                    
                    // Read request headers
                    String headerLine;
                    while ((headerLine = reader.readLine()) != null && !headerLine.trim().isEmpty()) {
                        String lower = headerLine.toLowerCase();
                        if (lower.startsWith("cseq:")) {
                            cseq = Integer.parseInt(headerLine.substring(5).trim());
                        } else if (lower.startsWith("transport:")) {
                            transport = headerLine.substring(10).trim();
                        }
                    }

                    handleRtspRequest(method, url, cseq, transport);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling RTSP client session", e);
            } finally {
                removeClient(this);
            }
        }

        private void handleRtspRequest(String method, String url, int cseq, String transport) throws IOException {
            Log.d(TAG, "RTSP Request: " + method + ", CSeq: " + cseq + ", URL: " + url);
            
            switch (method) {
                case "OPTIONS":
                    sendResponse(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n");
                    break;
                    
                case "DESCRIBE":
                    String sdp = getSdpDescription();
                    sendResponse(cseq, 
                        "Content-Base: " + url + "/\r\n" +
                        "Content-Type: application/sdp\r\n" +
                        "Content-Length: " + sdp.getBytes().length + "\r\n" +
                        "\r\n" + sdp);
                    break;
                    
                case "SETUP":
                    handleSetup(url, cseq, transport);
                    break;
                    
                case "PLAY":
                    isPlaying = true;
                    sendResponse(cseq, 
                        "Range: npt=0.000-\r\n" +
                        "Session: " + sessionId + "\r\n");
                    Log.d(TAG, "Client started streaming play.");
                    break;
                    
                case "TEARDOWN":
                    sendResponse(cseq, "Session: " + sessionId + "\r\n");
                    isConnected = false;
                    break;
                    
                default:
                    sendErrorResponse(cseq, "501 Not Implemented");
                    break;
            }
        }

        private void handleSetup(String url, int cseq, String transport) throws IOException {
            boolean isVideoTrack = url.toLowerCase().contains("trackid=0");
            boolean isAudioTrack = url.toLowerCase().contains("trackid=1");

            if (!isVideoTrack && !isAudioTrack) {
                sendErrorResponse(cseq, "404 Not Found");
                return;
            }

            String responseTransport;
            if (transport.toLowerCase().contains("rtp/avp/tcp") || transport.toLowerCase().contains("interleaved")) {
                // TCP Interleaved streaming
                useTcpInterleaved = true;
                
                // Parse interleaved channels e.g. interleaved=0-1
                Pattern p = Pattern.compile("interleaved=(\\d+)-(\\d+)");
                Matcher m = p.matcher(transport);
                int rtpChannel = isVideoTrack ? 0 : 2;
                if (m.find()) {
                    rtpChannel = Integer.parseInt(m.group(1));
                }
                
                if (isVideoTrack) {
                    videoInterleavedChannel = rtpChannel;
                } else {
                    audioInterleavedChannel = rtpChannel;
                }

                responseTransport = "RTP/AVP/TCP;unicast;interleaved=" + rtpChannel + "-" + (rtpChannel + 1);
            } else {
                // UDP streaming
                useTcpInterleaved = false;
                
                // Parse client ports e.g. client_port=5000-5001
                Pattern p = Pattern.compile("client_port=(\\d+)-(\\d+)");
                Matcher m = p.matcher(transport);
                int clientRtpPort = 5000;
                if (m.find()) {
                    clientRtpPort = Integer.parseInt(m.group(1));
                }

                DatagramSocket socket;
                try {
                    // Open UDP socket on even ephemeral port
                    socket = new DatagramSocket(0);
                    socket.setSendBufferSize(512 * 1024);
                } catch (SocketException se) {
                    sendErrorResponse(cseq, "500 Internal Server Error");
                    return;
                }

                int localPort = socket.getLocalPort();
                if (isVideoTrack) {
                    videoClientPort = clientRtpPort;
                    rtpVideoSocket = socket;
                } else {
                    audioClientPort = clientRtpPort;
                    rtpAudioSocket = socket;
                }

                responseTransport = "RTP/AVP/UDP;unicast;client_port=" + clientRtpPort + "-" + (clientRtpPort + 1) +
                        ";server_port=" + localPort + "-" + (localPort + 1);
            }

            sendResponse(cseq, 
                "Transport: " + responseTransport + "\r\n" +
                "Session: " + sessionId + "\r\n");
        }

        private String getSdpDescription() {
            String ip = NetworkInfoHelper.getLocalIpAddress();
            
            // Format Audio configuration hex string (AAC config)
            // Mono LC AAC 44100Hz = 1208 in Hex
            String audioSdpSection = "";
            if (isAudioEnabled) {
                audioSdpSection = 
                    "m=audio 0 RTP/AVP " + PAYLOAD_TYPE_AUDIO + "\r\n" +
                    "a=rtpmap:" + PAYLOAD_TYPE_AUDIO + " mpeg4-generic/44100/1\r\n" +
                    "a=fmtp:" + PAYLOAD_TYPE_AUDIO + " streamtype=5;profile-level-id=15;mode=AAC-hbr;config=1208;SizeLength=13;IndexLength=3;IndexDeltaLength=3;Profile=1\r\n" +
                    "a=control:trackID=1\r\n";
            }

            return "v=0\r\n" +
                    "o=- 0 0 IN IP4 " + ip + "\r\n" +
                    "s=AndroidIPCamLive\r\n" +
                    "c=IN IP4 " + ip + "\r\n" +
                    "t=0 0\r\n" +
                    "m=video 0 RTP/AVP " + PAYLOAD_TYPE_VIDEO + "\r\n" +
                    "a=rtpmap:" + PAYLOAD_TYPE_VIDEO + " H264/90000\r\n" +
                    "a=fmtp:" + PAYLOAD_TYPE_VIDEO + " packetization-mode=1;profile-level-id=42e01f;sprop-parameter-sets=" + spsPpsBase64 + "\r\n" +
                    "a=control:trackID=0\r\n" +
                    audioSdpSection;
        }

        private void sendResponse(int cseq, String headers) throws IOException {
            String response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: " + cseq + "\r\n" +
                    headers + "\r\n";
            os.write(response.getBytes());
            os.flush();
        }

        private void sendErrorResponse(int cseq, String code) throws IOException {
            String response = "RTSP/1.0 " + code + "\r\n" +
                    "CSeq: " + cseq + "\r\n" +
                    "\r\n";
            os.write(response.getBytes());
            os.flush();
        }

        void sendRtp(byte[] rtpPacket, boolean isVideo) {
            try {
                if (useTcpInterleaved) {
                    // TCP Interleaved formatting:
                    // '$' (0x24) + 1-byte channel + 2-byte size + RTP data
                    int channel = isVideo ? videoInterleavedChannel : audioInterleavedChannel;
                    int len = rtpPacket.length;
                    byte[] header = new byte[4];
                    header[0] = 0x24; // '$'
                    header[1] = (byte) (channel & 0xFF);
                    header[2] = (byte) ((len >> 8) & 0xFF);
                    header[3] = (byte) (len & 0xFF);
                    
                    synchronized (os) {
                        os.write(header);
                        os.write(rtpPacket);
                        os.flush();
                    }
                } else {
                    // UDP streaming
                    DatagramSocket socket = isVideo ? rtpVideoSocket : rtpAudioSocket;
                    int port = isVideo ? videoClientPort : audioClientPort;
                    if (socket != null && port > 0) {
                        DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, clientIp, port);
                        socket.send(packet);
                    }
                }
            } catch (Exception e) {
                // If writing fails, disconnect client
                Log.e(TAG, "Failed sending RTP packet to client, disconnecting...", e);
                isConnected = false;
            }
        }

        void close() {
            isConnected = false;
            isPlaying = false;
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
            if (rtpVideoSocket != null) {
                rtpVideoSocket.close();
                rtpVideoSocket = null;
            }
            if (rtpAudioSocket != null) {
                rtpAudioSocket.close();
                rtpAudioSocket = null;
            }
        }
    }
}
