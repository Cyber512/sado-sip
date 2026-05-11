package uz.mib.sip;

import javax.sound.sampled.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RtpManager {

    private static final int RATE    = 8000;
    private static final int PTIME   = 20;
    private static final int SAMPLES = RATE * PTIME / 1000;

    private DatagramSocket socket;
    private Thread sendThread, recvThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private TargetDataLine  mic;
    private SourceDataLine  spk;
    private InetAddress remoteAddr;
    private int remotePort;
    private int seq = 0;
    private long ts  = 0;
    private int ssrc = (int)(Math.random() * Integer.MAX_VALUE);
    private final Listener listener;

    public interface Listener { void onError(String msg); }

    public RtpManager(Listener l) { this.listener = l; }

    public void start(int localPort, String remoteHost, int remotePort) {
        try {
            this.remoteAddr = InetAddress.getByName(remoteHost);
            this.remotePort = remotePort;
            socket = new DatagramSocket(localPort);
            socket.setSoTimeout(50);
            AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, RATE, 16, 1, 2, RATE, false);
            DataLine.Info mi = new DataLine.Info(TargetDataLine.class, fmt);
            DataLine.Info si = new DataLine.Info(SourceDataLine.class, fmt);
            mic = (TargetDataLine)  AudioSystem.getLine(mi); mic.open(fmt, SAMPLES * 4); mic.start();
            spk = (SourceDataLine)  AudioSystem.getLine(si); spk.open(fmt, SAMPLES * 4); spk.start();
            running.set(true);
            sendThread = new Thread(this::sendLoop, "rtp-send"); sendThread.setDaemon(true); sendThread.start();
            recvThread = new Thread(this::recvLoop, "rtp-recv"); recvThread.setDaemon(true); recvThread.start();
        } catch (Exception e) { listener.onError("RTP: " + e.getMessage()); }
    }

    public void stop() {
        running.set(false);
        if (mic != null) { mic.stop(); mic.close(); }
        if (spk != null) { spk.stop(); spk.close(); }
        if (socket != null && !socket.isClosed()) socket.close();
    }

    private void sendLoop() {
        byte[] pcm  = new byte[SAMPLES * 2];
        byte[] ulaw = new byte[SAMPLES];
        while (running.get()) {
            try {
                int rd = 0;
                while (rd < pcm.length) { int n = mic.read(pcm, rd, pcm.length - rd); if (n < 0) break; rd += n; }
                for (int i = 0; i < SAMPLES; i++) {
                    int s = (short)((pcm[i*2] & 0xFF) | (pcm[i*2+1] << 8));
                    ulaw[i] = encode(s);
                }
                byte[] pkt = buildRtp(ulaw);
                socket.send(new DatagramPacket(pkt, pkt.length, remoteAddr, remotePort));
                seq++; ts += SAMPLES;
            } catch (Exception e) { if (running.get()) listener.onError("Send: " + e.getMessage()); }
        }
    }

    private void recvLoop() {
        byte[] buf    = new byte[512];
        byte[] pcmOut = new byte[SAMPLES * 2];
        while (running.get()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                byte[] data = pkt.getData();
                int len = pkt.getLength();
                if (len < 13) continue;
                int pt = data[1] & 0x7F;
                if (pt == 0 || pt == 8) {
                    int pLen = len - 12;
                    for (int i = 0; i < pLen && i < SAMPLES; i++) {
                        int s = (pt == 0) ? decode(data[12+i]) : decodePcma(data[12+i]);
                        pcmOut[i*2]   = (byte)(s & 0xFF);
                        pcmOut[i*2+1] = (byte)((s >> 8) & 0xFF);
                    }
                    spk.write(pcmOut, 0, pLen * 2);
                }
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) { if (running.get()) listener.onError("Recv: " + e.getMessage()); }
        }
    }

    private byte[] buildRtp(byte[] payload) {
        byte[] pkt = new byte[12 + payload.length];
        pkt[0] = (byte)0x80; pkt[1] = 0;
        pkt[2] = (byte)((seq >> 8) & 0xFF); pkt[3] = (byte)(seq & 0xFF);
        pkt[4] = (byte)((ts >> 24)&0xFF); pkt[5] = (byte)((ts>>16)&0xFF); pkt[6] = (byte)((ts>>8)&0xFF); pkt[7] = (byte)(ts&0xFF);
        pkt[8] = (byte)((ssrc>>24)&0xFF); pkt[9] = (byte)((ssrc>>16)&0xFF); pkt[10] = (byte)((ssrc>>8)&0xFF); pkt[11] = (byte)(ssrc&0xFF);
        System.arraycopy(payload, 0, pkt, 12, payload.length);
        return pkt;
    }

    private static byte encode(int s) {
        int sign = (s < 0) ? 0x80 : 0;
        if (s < 0) s = -s;
        if (s > 32767) s = 32767;
        s += 132;
        int exp = 7;
        for (int m = 0x4000; (s & m) == 0 && exp > 0; exp--, m >>= 1);
        int mantissa = (s >> (exp + 3)) & 0x0F;
        return (byte)(~(sign | (exp << 4) | mantissa) & 0xFF);
    }

    private static int decode(byte ulaw) {
        int u = (~ulaw) & 0xFF;
        int sign = u & 0x80, exp = (u >> 4) & 7, man = u & 0x0F;
        int s = ((man << 3) + 132) << exp;
        s -= 132;
        return sign != 0 ? -s : s;
    }

    private static int decodePcma(byte alaw) {
        int a = alaw ^ 0x55;
        int sign = a & 0x80, exp = (a >> 4) & 7, man = a & 0x0F;
        int s = (exp == 0) ? (man << 1) | 1 : ((man | 0x10) << (exp - 1)) | (1 << (exp - 2));
        s <<= 3;
        return sign != 0 ? s : -s;
    }
}
