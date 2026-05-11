package uz.mib.sip;

import javafx.application.Platform;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SipEngine {

    public enum State {
        IDLE, CONNECTING, REGISTERING, REGISTERED, CALLING, RINGING_IN, ACTIVE
    }

    public enum Transport { UDP, TCP }

    public interface Listener {
        void onState(State s, String detail);
        void onIncomingCall(String from, String callId);
        void onCallConnected(String remoteIp, int remotePort);
        void onCallEnded(int code, String reason);
        void onLog(String msg);
    }

    // Config
    private final String user;
    private final String pass;
    private final String domain;
    private final String serverHost;
    private final int    serverPort;
    private final Transport transport;
    private Listener listener;

    // Network
    private DatagramSocket udpSocket;   // UDP mode
    private Socket         tcpSocket;   // TCP mode
    private OutputStream   tcpOut;
    private String  localIp;
    private int     localSipPort;
    private InetAddress serverAddr;

    // State
    private volatile State state = State.IDLE;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Registration
    private final String regCallId   = uuid();
    private final String regFromTag  = hexRandom(8);
    private int          regCSeq     = 1;

    // Current call dialog
    private volatile String  callId;
    private volatile String  localTag;
    private volatile String  remoteTag;
    private volatile String  remoteTarget;
    private volatile int     callCSeq    = 1;
    private volatile boolean isOutgoing;
    private volatile int     inviteCSeq;
    private volatile String  inviteBranch;

    // Pending incoming call
    private volatile String pendingFromHeader;
    private volatile String pendingToHeader;
    private volatile String pendingCallId;
    private volatile String pendingViaHeader;
    private volatile String pendingContact;
    private volatile String pendingSdp;

    // RTP
    private volatile RtpSession rtpSession;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sip-scheduler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> reregisterTask;

    // ---- Constructors ----

    public SipEngine(String user, String pass, String domain,
                     String serverHost, int serverPort, Transport transport) {
        this.user       = user;
        this.pass       = pass;
        this.domain     = domain;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.transport  = transport;
    }

    /** UDP shortcut */
    public SipEngine(String user, String pass, String domain, String serverHost, int serverPort) {
        this(user, pass, domain, serverHost, serverPort, Transport.UDP);
    }

    public void setListener(Listener l) { this.listener = l; }
    public State getState()             { return state; }

    // ---- Connect ----

    public void connect() {
        changeState(State.CONNECTING, "Ulanmoqda...");
        try {
            serverAddr = InetAddress.getByName(serverHost);
            localIp    = detectLocalIp(serverAddr);

            if (transport == Transport.UDP) {
                udpSocket     = new DatagramSocket();
                localSipPort  = udpSocket.getLocalPort();
                running.set(true);
                startUdpReceiver();
            } else {
                tcpSocket    = new Socket(serverHost, serverPort);
                tcpSocket.setSoTimeout(0);
                tcpOut       = tcpSocket.getOutputStream();
                localSipPort = tcpSocket.getLocalPort();
                localIp      = tcpSocket.getLocalAddress().getHostAddress();
                running.set(true);
                startTcpReceiver();
            }

            log("Transport: " + transport + " | Local: " + localIp + ":" + localSipPort
                + " | Server: " + serverHost + ":" + serverPort);
            sendRegister(null);

        } catch (Exception e) {
            log("Ulanish xatosi: " + e.getMessage());
            changeState(State.IDLE, "Ulanish xatosi: " + e.getMessage());
        }
    }

    public void disconnect() {
        running.set(false);
        cancelReregister();
        if (state == State.ACTIVE || state == State.CALLING) hangup();
        closeSocket();
        changeState(State.IDLE, "Uzildi");
    }

    private void closeSocket() {
        try { if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close(); } catch (Exception ignored) {}
        try { if (tcpSocket != null && !tcpSocket.isClosed()) tcpSocket.close(); } catch (Exception ignored) {}
    }

    // ---- Receiver threads ----

    private void startUdpReceiver() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (running.get() && !udpSocket.isClosed()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    if (!msg.trim().isEmpty()) handleSipMessage(msg);
                } catch (SocketException e) {
                    if (running.get()) log("UDP qabul xatosi: " + e.getMessage());
                    break;
                } catch (Exception e) {
                    if (running.get()) log("UDP xatosi: " + e.getMessage());
                }
            }
        }, "sip-udp-receiver");
        t.setDaemon(true);
        t.start();
    }

    private void startTcpReceiver() {
        Thread t = new Thread(() -> {
            try {
                InputStream in = tcpSocket.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while (running.get() && (n = in.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    // Split on double CRLF + Content-Length boundary
                    String s = sb.toString();
                    int processed = extractMessages(s, sb);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log("TCP uzildi: " + e.getMessage());
                    changeState(State.IDLE, "Ulanish uzildi");
                }
            }
        }, "sip-tcp-receiver");
        t.setDaemon(true);
        t.start();
    }

    private int extractMessages(String s, StringBuilder sb) {
        while (true) {
            int headerEnd = s.indexOf("\r\n\r\n");
            if (headerEnd < 0) break;

            String headers = s.substring(0, headerEnd);
            String rest    = s.substring(headerEnd + 4);

            int contentLength = 0;
            for (String line : headers.split("\r\n")) {
                String low = line.toLowerCase();
                if (low.startsWith("content-length:") || low.startsWith("l:")) {
                    try { contentLength = Integer.parseInt(line.split(":", 2)[1].trim()); } catch (Exception ignored) {}
                    break;
                }
            }

            if (rest.length() < contentLength) break; // wait for more data

            String body = rest.substring(0, contentLength);
            String full = headers + "\r\n\r\n" + body;
            handleSipMessage(full);

            s = rest.substring(contentLength);
            sb.setLength(0);
            sb.append(s);
        }
        return 0;
    }

    // ---- Send ----

    private void send(String message) {
        log("> " + firstLine(message));
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            if (transport == Transport.UDP) {
                DatagramPacket pkt = new DatagramPacket(data, data.length, serverAddr, serverPort);
                udpSocket.send(pkt);
            } else {
                tcpOut.write(data);
                tcpOut.flush();
            }
        } catch (Exception e) {
            log("Yuborish xatosi: " + e.getMessage());
        }
    }

    // ---- REGISTER ----

    private void sendRegister(String wwwAuth) {
        changeState(State.REGISTERING, "Ro'yxatdan o'tmoqda...");
        String branch    = "z9hG4bK" + hexRandom(8);
        String sipUri    = "sip:" + domain;
        String userUri   = "sip:" + user + "@" + domain;
        String transport = via();

        StringBuilder sb = new StringBuilder();
        sb.append("REGISTER ").append(sipUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/").append(transport).append(" ").append(localIp).append(":").append(localSipPort)
          .append(";branch=").append(branch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userUri).append(">;tag=").append(regFromTag).append("\r\n");
        sb.append("To: <").append(userUri).append(">\r\n");
        sb.append("Call-ID: ").append(regCallId).append("\r\n");
        sb.append("CSeq: ").append(regCSeq).append(" REGISTER\r\n");
        sb.append("Contact: <sip:").append(user).append("@").append(localIp).append(":").append(localSipPort)
          .append(";transport=").append(transport.toLowerCase()).append(">\r\n");
        sb.append("Expires: 300\r\n");
        sb.append("Allow: INVITE, ACK, CANCEL, BYE, REFER, OPTIONS\r\n");
        sb.append("User-Agent: SadoSIP/1.0\r\n");

        if (wwwAuth != null) {
            String auth = DigestAuth.buildAuthorization(user, pass, "REGISTER", sipUri, wwwAuth);
            if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n");
        }

        sb.append("Content-Length: 0\r\n\r\n");
        send(sb.toString());
        regCSeq++;
    }

    private void scheduleReregister() {
        cancelReregister();
        reregisterTask = scheduler.schedule(() -> {
            if (running.get()) {
                log("Qayta ro'yxatdan o'tmoqda...");
                sendRegister(null);
            }
        }, 250, TimeUnit.SECONDS);
    }

    private void cancelReregister() {
        if (reregisterTask != null && !reregisterTask.isDone()) reregisterTask.cancel(false);
    }

    // ---- Make call ----

    public void makeCall(String number) {
        if (state != State.REGISTERED) { log("Holat: " + state + " — qo'ng'iroq mumkin emas"); return; }

        callId       = uuid();
        localTag     = hexRandom(8);
        callCSeq     = 1;
        inviteCSeq   = 1;
        inviteBranch = "z9hG4bK" + hexRandom(8);
        isOutgoing   = true;
        remoteTag    = null;

        String targetUri = "sip:" + number + "@" + domain;
        remoteTarget = targetUri;

        RtpSession rtp = new RtpSession();
        try { rtp.bind(); } catch (Exception e) { log("RTP xatosi: " + e.getMessage()); return; }
        rtpSession = rtp;

        String sdp       = buildSdp(localIp, rtp.getLocalPort());
        String userUri   = "sip:" + user + "@" + domain;
        String transport = via();

        StringBuilder sb = new StringBuilder();
        sb.append("INVITE ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/").append(transport).append(" ").append(localIp).append(":").append(localSipPort)
          .append(";branch=").append(inviteBranch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: <").append(targetUri).append(">\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(callCSeq).append(" INVITE\r\n");
        sb.append("Contact: <sip:").append(user).append("@").append(localIp).append(":").append(localSipPort)
          .append(";transport=").append(transport.toLowerCase()).append(">\r\n");
        sb.append("Allow: INVITE, ACK, CANCEL, BYE, REFER, OPTIONS\r\n");
        sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ").append(sdp.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n");
        sb.append(sdp);

        changeState(State.CALLING, "Qo'ng'iroq: " + number);
        send(sb.toString());
    }

    // ---- Answer ----

    public void answerCall() {
        if (state != State.RINGING_IN) return;

        RtpSession rtp = new RtpSession();
        try { rtp.bind(); } catch (Exception e) { log("RTP xatosi: " + e.getMessage()); return; }
        rtpSession = rtp;

        String sdp       = buildSdp(localIp, rtp.getLocalPort());
        String transport = via();
        localTag = hexRandom(8);

        String toHeader = pendingToHeader != null ? pendingToHeader : "<sip:" + user + "@" + domain + ">";
        if (!toHeader.contains(";tag=")) toHeader = toHeader + ";tag=" + localTag;

        StringBuilder sb = new StringBuilder();
        sb.append("SIP/2.0 200 OK\r\n");
        sb.append("Via: ").append(pendingViaHeader).append("\r\n");
        sb.append("From: ").append(pendingFromHeader).append("\r\n");
        sb.append("To: ").append(toHeader).append("\r\n");
        sb.append("Call-ID: ").append(pendingCallId).append("\r\n");
        sb.append("CSeq: 1 INVITE\r\n");
        sb.append("Contact: <sip:").append(user).append("@").append(localIp).append(":").append(localSipPort)
          .append(";transport=").append(transport.toLowerCase()).append(">\r\n");
        sb.append("Allow: INVITE, ACK, CANCEL, BYE, REFER, OPTIONS\r\n");
        sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ").append(sdp.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n");
        sb.append(sdp);

        callId = pendingCallId;
        send(sb.toString());
        changeState(State.ACTIVE, "Faol qo'ng'iroq");

        if (pendingSdp != null) startRtp(rtp, pendingSdp);
    }

    // ---- Reject ----

    public void rejectCall() {
        if (state != State.RINGING_IN) return;

        StringBuilder sb = new StringBuilder();
        sb.append("SIP/2.0 603 Decline\r\n");
        sb.append("Via: ").append(pendingViaHeader).append("\r\n");
        sb.append("From: ").append(pendingFromHeader).append("\r\n");
        sb.append("To: ").append(pendingToHeader).append(";tag=").append(hexRandom(8)).append("\r\n");
        sb.append("Call-ID: ").append(pendingCallId).append("\r\n");
        sb.append("CSeq: 1 INVITE\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        send(sb.toString());
        clearPendingCall();
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
    }

    // ---- Hangup ----

    public void hangup() {
        if (state == State.CALLING)    sendCancel();
        else if (state == State.ACTIVE || state == State.RINGING_IN) sendBye();
    }

    private void sendCancel() {
        if (callId == null) return;
        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        String userUri   = "sip:" + user + "@" + domain;
        String transport = via();

        StringBuilder sb = new StringBuilder();
        sb.append("CANCEL ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/").append(transport).append(" ").append(localIp).append(":").append(localSipPort)
          .append(";branch=").append(inviteBranch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: <").append(targetUri).append(">").append(remoteTag != null ? ";tag=" + remoteTag : "").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(inviteCSeq).append(" CANCEL\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        send(sb.toString());
        stopCall();
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
    }

    private void sendBye() {
        if (callId == null) return;
        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        String userUri   = "sip:" + user + "@" + domain;
        String transport = via();
        int    byeCSeq   = callCSeq++;

        StringBuilder sb = new StringBuilder();
        sb.append("BYE ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/").append(transport).append(" ").append(localIp).append(":").append(localSipPort)
          .append(";branch=z9hG4bK").append(hexRandom(8)).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: <").append(targetUri).append(">").append(remoteTag != null ? ";tag=" + remoteTag : "").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(byeCSeq).append(" BYE\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        send(sb.toString());
        stopCall();
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
    }

    // ---- Transfer ----

    public void transfer(String targetNumber) {
        if (state != State.ACTIVE || callId == null) return;
        String referTo   = "sip:" + targetNumber + "@" + domain;
        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        String userUri   = "sip:" + user + "@" + domain;
        String transport = via();
        int    refCSeq   = callCSeq++;

        StringBuilder sb = new StringBuilder();
        sb.append("REFER ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/").append(transport).append(" ").append(localIp).append(":").append(localSipPort)
          .append(";branch=z9hG4bK").append(hexRandom(8)).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: <").append(targetUri).append(">").append(remoteTag != null ? ";tag=" + remoteTag : "").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(refCSeq).append(" REFER\r\n");
        sb.append("Refer-To: <").append(referTo).append(">\r\n");
        sb.append("Referred-By: <").append(userUri).append(">\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        send(sb.toString());
        log("Transfer yuborildi: " + targetNumber);
    }

    // ---- Message handling ----

    private void handleSipMessage(String raw) {
        raw = raw.trim();
        if (raw.isEmpty()) return;
        log("< " + firstLine(raw));

        SipMessage msg = SipMessage.parse(raw);
        if (msg == null) return;

        if (msg.isResponse) handleResponse(msg);
        else                handleRequest(msg);
    }

    private void handleResponse(SipMessage msg) {
        String cseq = msg.cseq();
        if (cseq == null) return;
        String cu = cseq.toUpperCase();

        if      (cu.contains("REGISTER")) handleRegisterResponse(msg);
        else if (cu.contains("INVITE"))   handleInviteResponse(msg);
        else if (cu.contains("REFER"))    log("REFER javobi: " + msg.statusCode + " " + msg.reasonPhrase);
    }

    private void handleRegisterResponse(SipMessage msg) {
        int code = msg.statusCode;
        if (code == 401 || code == 407) {
            String authHeader = code == 401
                ? msg.wwwAuthenticate()
                : msg.getHeader("proxy-authenticate");
            if (authHeader == null) {
                changeState(State.IDLE, "Auth header yo'q");
                return;
            }
            log("Auth so'raldi...");
            sendRegister(authHeader);
        } else if (code == 200) {
            log("Ro'yxatdan o'tish muvaffaqiyatli");
            changeState(State.REGISTERED, "Tayyor");
            scheduleReregister();
        } else if (code >= 400) {
            log("Ro'yxatdan o'tish rad etildi: " + code);
            changeState(State.IDLE, "Ro'yxatdan o'tish xatosi: " + code);
        }
    }

    private void handleInviteResponse(SipMessage msg) {
        int    code = msg.statusCode;
        String cid  = msg.callId();
        if (cid == null || !cid.equals(callId)) return;

        String toTag = msg.extractTag(msg.to());
        if (toTag != null) remoteTag = toTag;

        String contact = msg.contact();
        if (contact != null) {
            String uri = extractAngle(contact);
            if (uri != null) remoteTarget = uri;
        }

        if (code == 100) {
            log("Urinilmoqda...");
        } else if (code == 180 || code == 183) {
            changeState(State.CALLING, "Jiringlayapti...");
        } else if (code == 200) {
            sendAck(msg, false);
            String[] rtp = parseSdpRtp(msg.body);
            if (rtp != null) startRtp(rtpSession, msg.body);
            changeState(State.ACTIVE, "Faol");
            String ip   = rtp != null ? rtp[0] : "?";
            int    port = rtp != null ? Integer.parseInt(rtp[1]) : 0;
            if (listener != null) Platform.runLater(() -> listener.onCallConnected(ip, port));
        } else if (code >= 300) {
            sendAck(msg, true);
            final int c = code; final String r = msg.reasonPhrase;
            stopCall();
            changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
            if (listener != null) Platform.runLater(() -> listener.onCallEnded(c, r));
        }
    }

    private void sendAck(SipMessage inviteResp, boolean forError) {
        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        String userUri   = "sip:" + user + "@" + domain;
        String transport = via();

        StringBuilder sb = new StringBuilder();
        sb.append("ACK ").append(targetUri).append(" SIP/2.0\r\n");
        String branch = forError ? inviteBranch : "z9hG4bK" + hexRandom(8);
        sb.append("Via: SIP/2.0/").append(transport).append(" ").append(localIp).append(":").append(localSipPort)
          .append(";branch=").append(branch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userUri).append(">;tag=").append(localTag).append("\r\n");
        String toH = inviteResp.to();
        sb.append("To: ").append(toH != null ? toH : "<" + targetUri + ">").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(inviteCSeq).append(" ACK\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        send(sb.toString());
    }

    private void handleRequest(SipMessage msg) {
        switch (msg.method.toUpperCase()) {
            case "INVITE":  handleIncomingInvite(msg);   break;
            case "BYE":     handleIncomingBye(msg);      break;
            case "CANCEL":  handleIncomingCancel(msg);   break;
            case "OPTIONS": sendSimpleResponse(msg, 200, "OK"); break;
            case "NOTIFY":  sendSimpleResponse(msg, 200, "OK"); log("NOTIFY: " + msg.body); break;
            default:        sendSimpleResponse(msg, 405, "Method Not Allowed"); break;
        }
    }

    private void handleIncomingInvite(SipMessage msg) {
        String fromHeader   = msg.from();
        String callerUser   = msg.extractUser(fromHeader);
        String displayName  = msg.extractDisplayName(fromHeader);
        String callerDisplay = (displayName != null && !displayName.isEmpty()) ? displayName : callerUser;

        pendingFromHeader = fromHeader;
        pendingToHeader   = msg.to();
        pendingCallId     = msg.callId();
        pendingViaHeader  = msg.via();
        pendingContact    = msg.contact();
        pendingSdp        = msg.body;
        localTag          = hexRandom(8);
        isOutgoing        = false;

        sendProvisional(msg, 100, "Trying");
        sendProvisional(msg, 180, "Ringing");

        changeState(State.RINGING_IN, "Kiruvchi: " + callerDisplay);
        final String cd = callerDisplay; final String cid = pendingCallId;
        if (listener != null) Platform.runLater(() -> listener.onIncomingCall(cd, cid));
    }

    private void sendProvisional(SipMessage invite, int code, String reason) {
        String transport = via();
        String toHeader  = invite.to();
        if (code > 100 && !toHeader.contains(";tag=")) toHeader = toHeader + ";tag=" + localTag;

        StringBuilder sb = new StringBuilder();
        sb.append("SIP/2.0 ").append(code).append(" ").append(reason).append("\r\n");
        sb.append("Via: ").append(invite.via()).append("\r\n");
        sb.append("From: ").append(invite.from()).append("\r\n");
        sb.append("To: ").append(toHeader).append("\r\n");
        sb.append("Call-ID: ").append(invite.callId()).append("\r\n");
        sb.append("CSeq: ").append(invite.cseq()).append("\r\n");
        if (code == 180) {
            sb.append("Contact: <sip:").append(user).append("@").append(localIp).append(":").append(localSipPort)
              .append(";transport=").append(transport.toLowerCase()).append(">\r\n");
        }
        sb.append("Content-Length: 0\r\n\r\n");
        send(sb.toString());
    }

    private void handleIncomingBye(SipMessage msg) {
        sendSimpleResponse(msg, 200, "OK");
        stopCall();
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
        if (listener != null) Platform.runLater(() -> listener.onCallEnded(0, "BYE"));
    }

    private void handleIncomingCancel(SipMessage msg) {
        sendSimpleResponse(msg, 200, "OK");
        if (pendingCallId != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("SIP/2.0 487 Request Terminated\r\n");
            sb.append("Via: ").append(pendingViaHeader).append("\r\n");
            sb.append("From: ").append(pendingFromHeader).append("\r\n");
            sb.append("To: ").append(pendingToHeader).append(";tag=").append(localTag != null ? localTag : hexRandom(8)).append("\r\n");
            sb.append("Call-ID: ").append(pendingCallId).append("\r\n");
            sb.append("CSeq: 1 INVITE\r\n");
            sb.append("Content-Length: 0\r\n\r\n");
            send(sb.toString());
        }
        clearPendingCall();
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
        if (listener != null) Platform.runLater(() -> listener.onCallEnded(0, "CANCEL"));
    }

    private void sendSimpleResponse(SipMessage req, int code, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("SIP/2.0 ").append(code).append(" ").append(reason).append("\r\n");
        if (req.via()    != null) sb.append("Via: ").append(req.via()).append("\r\n");
        if (req.from()   != null) sb.append("From: ").append(req.from()).append("\r\n");
        String to = req.to();
        if (to != null) {
            if (!to.contains(";tag=")) to = to + ";tag=" + hexRandom(8);
            sb.append("To: ").append(to).append("\r\n");
        }
        if (req.callId() != null) sb.append("Call-ID: ").append(req.callId()).append("\r\n");
        if (req.cseq()   != null) sb.append("CSeq: ").append(req.cseq()).append("\r\n");
        sb.append("Content-Length: 0\r\n\r\n");
        send(sb.toString());
    }

    // ---- Helpers ----

    private void startRtp(RtpSession rtp, String sdp) {
        String[] addr = parseSdpRtp(sdp);
        if (addr == null || rtp == null) return;
        try {
            String remoteIp   = addr[0];
            int    remotePort = Integer.parseInt(addr[1]);
            rtp.start(remoteIp, remotePort, 0);
            if (listener != null) {
                Platform.runLater(() -> listener.onCallConnected(remoteIp, remotePort));
            }
        } catch (Exception e) {
            log("RTP boshlash xatosi: " + e.getMessage());
        }
    }

    private void stopCall() {
        if (rtpSession != null) { rtpSession.stop(); rtpSession = null; }
        callId = null; localTag = null; remoteTag = null; remoteTarget = null;
        clearPendingCall();
    }

    private void clearPendingCall() {
        pendingFromHeader = null; pendingToHeader = null; pendingCallId = null;
        pendingViaHeader  = null; pendingContact  = null; pendingSdp    = null;
    }

    private String buildSdp(String ip, int rtpPort) {
        long ts = System.currentTimeMillis() / 1000L;
        return "v=0\r\n" +
               "o=- " + ts + " " + ts + " IN IP4 " + ip + "\r\n" +
               "s=SIP Call\r\n" +
               "c=IN IP4 " + ip + "\r\n" +
               "t=0 0\r\n" +
               "m=audio " + rtpPort + " RTP/AVP 0 8\r\n" +
               "a=rtpmap:0 PCMU/8000\r\n" +
               "a=rtpmap:8 PCMA/8000\r\n" +
               "a=sendrecv\r\n";
    }

    private String[] parseSdpRtp(String sdp) {
        if (sdp == null || sdp.isEmpty()) return null;
        String ip = null; int port = -1;
        for (String line : sdp.split("[\r\n]+")) {
            line = line.trim();
            if (line.startsWith("c=IN IP4 "))  ip   = line.substring(9).trim();
            else if (line.startsWith("m=audio ")) {
                String[] p = line.split("\\s+");
                if (p.length >= 2) try { port = Integer.parseInt(p[1]); } catch (Exception ignored) {}
            }
        }
        return (ip != null && port > 0) ? new String[]{ip, String.valueOf(port)} : null;
    }

    private String detectLocalIp(InetAddress serverAddr) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(serverAddr, serverPort);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            try {
                Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                while (ifaces.hasMoreElements()) {
                    NetworkInterface iface = ifaces.nextElement();
                    if (iface.isLoopback() || !iface.isUp()) continue;
                    Enumeration<InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                    }
                }
            } catch (Exception ignored) {}
            return "127.0.0.1";
        }
    }

    private String extractAngle(String header) {
        int lt = header.indexOf('<'), gt = header.indexOf('>');
        if (lt >= 0 && gt > lt) return header.substring(lt + 1, gt).trim();
        int semi = header.indexOf(';');
        return semi >= 0 ? header.substring(0, semi).trim() : header.trim();
    }

    /** Returns "UDP" or "TCP" for Via/Contact headers */
    private String via() {
        return transport == Transport.TCP ? "TCP" : "UDP";
    }

    private String firstLine(String msg) {
        int idx = msg.indexOf("\r\n");
        return idx > 0 ? msg.substring(0, idx) : msg.substring(0, Math.min(120, msg.length()));
    }

    private void changeState(State s, String detail) {
        state = s;
        if (listener != null) Platform.runLater(() -> listener.onState(s, detail));
    }

    private void log(String msg) {
        if (listener != null) Platform.runLater(() -> listener.onLog(msg));
    }

    public void shutdown() {
        cancelReregister();
        scheduler.shutdownNow();
        disconnect();
    }

    private static String uuid()            { return UUID.randomUUID().toString(); }
    private static String hexRandom(int n)  {
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < n; i++) sb.append(Integer.toHexString(r.nextInt(16)));
        return sb.toString();
    }
}
