package uz.mib.sip;

import javax.sound.sampled.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class RtpSession {
    private DatagramSocket socket;
    private int localPort;
    private Thread sendThread;
    private Thread receiveThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private int ssrc;
    private int seqNum;
    private long timestamp;

    // ULAW audio format: 8000 Hz, 8-bit, mono, PCM signed, ULAW encoding
    private static final AudioFormat ULAW_FORMAT =
            new AudioFormat(AudioFormat.Encoding.ULAW, 8000, 8, 1, 1, 8000, false);
    // PCM format for intermediate conversion
    private static final AudioFormat PCM_FORMAT =
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 8000, 16, 1, 2, 8000, false);

    private static final int SAMPLES_PER_PACKET = 160;
    private static final int RTP_HEADER_SIZE = 12;

    public RtpSession() {
        Random rand = new Random();
        ssrc = rand.nextInt();
        seqNum = rand.nextInt(65536);
        timestamp = rand.nextInt(100000);
    }

    public void bind() throws Exception {
        socket = new DatagramSocket(0);
        socket.setSoTimeout(100);
        localPort = socket.getLocalPort();
    }

    public int getLocalPort() {
        return localPort;
    }

    public void start(String remoteIp, int remotePort, int payloadType) {
        if (running.getAndSet(true)) return;

        InetAddress remoteAddr;
        try {
            remoteAddr = InetAddress.getByName(remoteIp);
        } catch (Exception e) {
            System.err.println("[RTP] Cannot resolve remote IP: " + remoteIp);
            running.set(false);
            return;
        }

        sendThread = new Thread(() -> runSend(remoteAddr, remotePort, payloadType), "rtp-send");
        sendThread.setDaemon(true);
        sendThread.start();

        receiveThread = new Thread(this::runReceive, "rtp-recv");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    private void runSend(InetAddress remoteAddr, int remotePort, int payloadType) {
        TargetDataLine micLine = null;
        try {
            // Try to get ULAW line directly, else get PCM and convert
            DataLine.Info ulawInfo = new DataLine.Info(TargetDataLine.class, ULAW_FORMAT);
            DataLine.Info pcmInfo = new DataLine.Info(TargetDataLine.class, PCM_FORMAT);

            if (AudioSystem.isLineSupported(ulawInfo)) {
                micLine = (TargetDataLine) AudioSystem.getLine(ulawInfo);
                micLine.open(ULAW_FORMAT);
            } else {
                // Use PCM and convert
                micLine = (TargetDataLine) AudioSystem.getLine(pcmInfo);
                micLine.open(PCM_FORMAT);
            }
            micLine.start();

            boolean isUlaw = micLine.getFormat().getEncoding() == AudioFormat.Encoding.ULAW;
            AudioInputStream rawStream = new AudioInputStream(micLine);
            AudioInputStream ulawStream;

            if (isUlaw) {
                ulawStream = rawStream;
            } else {
                ulawStream = AudioSystem.getAudioInputStream(ULAW_FORMAT, rawStream);
            }

            byte[] audioData = new byte[SAMPLES_PER_PACKET];
            byte[] rtpPacket = new byte[RTP_HEADER_SIZE + SAMPLES_PER_PACKET];

            while (running.get()) {
                int read = 0;
                while (read < SAMPLES_PER_PACKET && running.get()) {
                    int n = ulawStream.read(audioData, read, SAMPLES_PER_PACKET - read);
                    if (n < 0) break;
                    read += n;
                }
                if (!running.get()) break;

                // Build RTP header
                rtpPacket[0] = (byte) 0x80; // V=2, P=0, X=0, CC=0
                rtpPacket[1] = (byte) (payloadType & 0x7F);
                rtpPacket[2] = (byte) ((seqNum >> 8) & 0xFF);
                rtpPacket[3] = (byte) (seqNum & 0xFF);
                rtpPacket[4] = (byte) ((timestamp >> 24) & 0xFF);
                rtpPacket[5] = (byte) ((timestamp >> 16) & 0xFF);
                rtpPacket[6] = (byte) ((timestamp >> 8) & 0xFF);
                rtpPacket[7] = (byte) (timestamp & 0xFF);
                rtpPacket[8] = (byte) ((ssrc >> 24) & 0xFF);
                rtpPacket[9] = (byte) ((ssrc >> 16) & 0xFF);
                rtpPacket[10] = (byte) ((ssrc >> 8) & 0xFF);
                rtpPacket[11] = (byte) (ssrc & 0xFF);

                System.arraycopy(audioData, 0, rtpPacket, RTP_HEADER_SIZE, read);

                DatagramPacket pkt = new DatagramPacket(rtpPacket, RTP_HEADER_SIZE + read, remoteAddr, remotePort);
                try {
                    socket.send(pkt);
                } catch (Exception e) {
                    if (running.get()) {
                        System.err.println("[RTP] Send error: " + e.getMessage());
                    }
                }

                seqNum = (seqNum + 1) & 0xFFFF;
                timestamp += SAMPLES_PER_PACKET;
            }
        } catch (Exception e) {
            if (running.get()) {
                System.err.println("[RTP] Send thread error: " + e.getMessage());
            }
        } finally {
            if (micLine != null) {
                micLine.stop();
                micLine.close();
            }
        }
    }

    private void runReceive() {
        SourceDataLine speakerLine = null;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, ULAW_FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                // Try PCM
                info = new DataLine.Info(SourceDataLine.class, PCM_FORMAT);
                speakerLine = (SourceDataLine) AudioSystem.getLine(info);
                speakerLine.open(PCM_FORMAT);
            } else {
                speakerLine = (SourceDataLine) AudioSystem.getLine(info);
                speakerLine.open(ULAW_FORMAT);
            }
            speakerLine.start();

            byte[] recvBuf = new byte[4096];
            DatagramPacket pkt = new DatagramPacket(recvBuf, recvBuf.length);
            boolean speakerIsUlaw = speakerLine.getFormat().getEncoding() == AudioFormat.Encoding.ULAW;

            while (running.get()) {
                try {
                    socket.receive(pkt);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (Exception e) {
                    if (running.get()) {
                        System.err.println("[RTP] Receive error: " + e.getMessage());
                    }
                    break;
                }

                int dataLen = pkt.getLength();
                if (dataLen <= RTP_HEADER_SIZE) continue;

                byte[] rtp = pkt.getData();
                int audioLen = dataLen - RTP_HEADER_SIZE;
                byte[] audioData = new byte[audioLen];
                System.arraycopy(rtp, RTP_HEADER_SIZE, audioData, 0, audioLen);

                if (speakerIsUlaw) {
                    speakerLine.write(audioData, 0, audioLen);
                } else {
                    // Convert ULAW to PCM for playback
                    try {
                        AudioInputStream ulawStream = new AudioInputStream(
                                new java.io.ByteArrayInputStream(audioData),
                                ULAW_FORMAT, audioLen);
                        AudioInputStream pcmStream = AudioSystem.getAudioInputStream(PCM_FORMAT, ulawStream);
                        byte[] pcmData = pcmStream.readAllBytes();
                        speakerLine.write(pcmData, 0, pcmData.length);
                    } catch (Exception e) {
                        // fallback: write raw
                        speakerLine.write(audioData, 0, audioLen);
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                System.err.println("[RTP] Receive thread error: " + e.getMessage());
            }
        } finally {
            if (speakerLine != null) {
                speakerLine.drain();
                speakerLine.stop();
                speakerLine.close();
            }
        }
    }

    public void stop() {
        running.set(false);
        if (sendThread != null) sendThread.interrupt();
        if (receiveThread != null) receiveThread.interrupt();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
