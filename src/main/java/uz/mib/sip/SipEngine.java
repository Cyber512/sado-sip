package uz.mib.sip;

import javafx.application.Platform;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SipEngine {

    public enum State {
        IDLE, CONNECTING, REGISTERING, REGISTERED, CALLING, RINGING_IN, ACTIVE
    }

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
    private final String wsUrl;
    private Listener listener;

    // WebSocket
    private HttpClient httpClient;
    private WebSocket ws;
    private final StringBuilder wsBuffer = new StringBuilder();

    // State
    private volatile State state = State.IDLE;

    // Registration
    private final String regCallId = uuid();
    private final String regFromTag = hexRandom(8);
    private int regCSeq = 1;
    private String regWwwAuth = null;

    // Client identifier
    private final String clientId = "sado" + hexRandom(6);

    // Current call dialog
    private volatile String callId;
    private volatile String localTag;
    private volatile String remoteTag;
    private volatile String remoteTarget;
    private volatile int callCSeq = 1;
    private volatile boolean isOutgoing;

    // Pending incoming call info
    private volatile String pendingFromHeader;
    private volatile String pendingToHeader;
    private volatile String pendingCallId;
    private volatile String pendingViaBranch;
    private volatile String pendingViaHeader;
    private volatile String pendingContact;
    private volatile String pendingSdp;
    private volatile String pendingRemoteTag;

    // Outgoing INVITE tracking
    private volatile int inviteCSeq;
    private volatile String inviteBranch;

    // RTP
    private volatile RtpSession rtpSession;

    // Scheduler for re-registration, keepalives
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sip-scheduler");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> reregisterTask;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public SipEngine(String user, String pass, String domain, String wsUrl) {
        this.user = user;
        this.pass = pass;
        this.domain = domain;
        this.wsUrl = wsUrl;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    // ---- Connection ----

    public void connect() {
        changeState(State.CONNECTING, "WebSocket ulanmoqda...");
        log("WebSocket ulanmoqda: " + wsUrl);

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        URI uri;
        try {
            uri = URI.create(wsUrl);
        } catch (Exception e) {
            changeState(State.IDLE, "URL xatosi: " + e.getMessage());
            return;
        }

        WebSocket.Listener wsListener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                connected.set(true);
                log("WebSocket ulandi");
                ws = webSocket;
                webSocket.request(1);
                sendRegister(null);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                wsBuffer.append(data);
                webSocket.request(1);
                if (last) {
                    String msg = wsBuffer.toString();
                    wsBuffer.setLength(0);
                    handleSipMessage(msg);
                }
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                connected.set(false);
                log("WebSocket yopildi: " + statusCode + " " + reason);
                if (state != State.IDLE) {
                    changeState(State.IDLE, "Ulanish uzildi");
                    stopCall(0, "Ulanish uzildi");
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log("WebSocket xatosi: " + error.getMessage());
                connected.set(false);
                changeState(State.IDLE, "Xato: " + error.getMessage());
                stopCall(500, "Ulanish xatosi");
            }
        };

        try {
            httpClient.newWebSocketBuilder()
                    .subprotocols("sip")
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, wsListener)
                    .whenComplete((webSocket, error) -> {
                        if (error != null) {
                            log("WebSocket ulanish xatosi: " + error.getMessage());
                            changeState(State.IDLE, "Ulanish xatosi: " + error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log("WebSocket yaratish xatosi: " + e.getMessage());
            changeState(State.IDLE, "Xato: " + e.getMessage());
        }
    }

    public void disconnect() {
        if (state == State.ACTIVE || state == State.CALLING) {
            hangup();
        }
        cancelReregister();
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {}
        }
        connected.set(false);
        changeState(State.IDLE, "Uzildi");
    }

    // ---- SIP REGISTER ----

    private void sendRegister(String wwwAuthHeader) {
        changeState(State.REGISTERING, "Ro'yxatdan o'tmoqda...");
        String branch = "z9hG4bK" + hexRandom(8);
        String sipUri = "sip:" + domain;
        String userSipUri = "sip:" + user + "@" + domain;

        StringBuilder sb = new StringBuilder();
        sb.append("REGISTER ").append(sipUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/WSS ").append(clientId).append(";branch=").append(branch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userSipUri).append(">;tag=").append(regFromTag).append("\r\n");
        sb.append("To: <").append(userSipUri).append(">\r\n");
        sb.append("Call-ID: ").append(regCallId).append("\r\n");
        sb.append("CSeq: ").append(regCSeq).append(" REGISTER\r\n");
        sb.append("Contact: <sip:").append(user).append("@").append(clientId).append(";transport=wss>\r\n");
        sb.append("Expires: 300\r\n");
        sb.append("Allow: INVITE, ACK, CANCEL, BYE, REFER, OPTIONS\r\n");
        sb.append("User-Agent: SadoSIP/1.0\r\n");

        if (wwwAuthHeader != null) {
            String auth = DigestAuth.buildAuthorization(user, pass, "REGISTER", sipUri, wwwAuthHeader);
            if (auth != null) {
                sb.append("Authorization: ").append(auth).append("\r\n");
            }
        }

        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");

        send(sb.toString());
        regCSeq++;
    }

    private void scheduleReregister() {
        cancelReregister();
        reregisterTask = scheduler.schedule(() -> {
            if (connected.get()) {
                log("Qayta ro'yxatdan o'tmoqda...");
                regWwwAuth = null;
                sendRegister(null);
            }
        }, 250, TimeUnit.SECONDS);
    }

    private void cancelReregister() {
        if (reregisterTask != null && !reregisterTask.isDone()) {
            reregisterTask.cancel(false);
        }
    }

    // ---- Make call ----

    public void makeCall(String number) {
        if (state != State.REGISTERED) {
            log("Qo'ng'iroq qilish mumkin emas: " + state);
            return;
        }

        callId = uuid();
        localTag = hexRandom(8);
        callCSeq = 1;
        inviteCSeq = 1;
        inviteBranch = "z9hG4bK" + hexRandom(8);
        isOutgoing = true;
        remoteTag = null;

        String targetUri = "sip:" + number + "@" + domain;
        remoteTarget = targetUri;

        RtpSession rtp = new RtpSession();
        try {
            rtp.bind();
        } catch (Exception e) {
            log("RTP bağlanma xatosi: " + e.getMessage());
            return;
        }
        rtpSession = rtp;

        String localIp = getLocalIp();
        int rtpPort = rtp.getLocalPort();
        String sdp = buildSdp(localIp, rtpPort);

        String userSipUri = "sip:" + user + "@" + domain;

        StringBuilder sb = new StringBuilder();
        sb.append("INVITE ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/WSS ").append(clientId).append(";branch=").append(inviteBranch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userSipUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: <").append(targetUri).append(">\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(callCSeq).append(" INVITE\r\n");
        sb.append("Contact: <sip:").append(user).append("@").append(clientId).append(";transport=wss>\r\n");
        sb.append("Allow: INVITE, ACK, CANCEL, BYE, REFER, OPTIONS\r\n");
        sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ").append(sdp.getBytes().length).append("\r\n");
        sb.append("\r\n");
        sb.append(sdp);

        changeState(State.CALLING, "Qo'ng'iroq qilinmoqda: " + number);
        send(sb.toString());
    }

    // ---- Answer incoming ----

    public void answerCall() {
        if (state != State.RINGING_IN) return;

        String localIp = getLocalIp();
        RtpSession rtp = new RtpSession();
        try {
            rtp.bind();
        } catch (Exception e) {
            log("RTP bağlanma xatosi: " + e.getMessage());
            return;
        }
        rtpSession = rtp;
        int rtpPort = rtp.getLocalPort();
        String sdp = buildSdp(localIp, rtpPort);

        // Build 200 OK
        StringBuilder sb = new StringBuilder();
        sb.append("SIP/2.0 200 OK\r\n");
        sb.append("Via: ").append(pendingViaHeader).append("\r\n");
        sb.append("From: ").append(pendingFromHeader).append("\r\n");

        String toHeader = pendingToHeader;
        if (toHeader != null && !toHeader.contains(";tag=")) {
            toHeader = toHeader + ";tag=" + localTag;
        } else if (toHeader == null) {
            toHeader = "<sip:" + user + "@" + domain + ">;tag=" + localTag;
        }
        sb.append("To: ").append(toHeader).append("\r\n");
        sb.append("Call-ID: ").append(pendingCallId).append("\r\n");
        sb.append("CSeq: 1 INVITE\r\n");
        sb.append("Contact: <sip:").append(user).append("@").append(clientId).append(";transport=wss>\r\n");
        sb.append("Allow: INVITE, ACK, CANCEL, BYE, REFER, OPTIONS\r\n");
        sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ").append(sdp.getBytes().length).append("\r\n");
        sb.append("\r\n");
        sb.append(sdp);

        callId = pendingCallId;
        localTag = extractTagFromHeader(toHeader);
        isOutgoing = false;

        send(sb.toString());
        changeState(State.ACTIVE, "Faol qo'ng'iroq");

        // Parse remote SDP and start RTP
        if (pendingSdp != null) {
            String[] rtpAddr = parseSdpForRtp(pendingSdp);
            if (rtpAddr != null && rtpAddr.length == 2) {
                try {
                    int remotePort = Integer.parseInt(rtpAddr[1]);
                    String remoteIp = rtpAddr[0];
                    rtp.start(remoteIp, remotePort, 0);
                    if (listener != null) {
                        final String ip = remoteIp;
                        final int port = remotePort;
                        Platform.runLater(() -> listener.onCallConnected(ip, port));
                    }
                } catch (Exception e) {
                    log("RTP boshlash xatosi: " + e.getMessage());
                }
            }
        }
    }

    // ---- Reject incoming ----

    public void rejectCall() {
        if (state != State.RINGING_IN) return;

        StringBuilder sb = new StringBuilder();
        sb.append("SIP/2.0 603 Decline\r\n");
        sb.append("Via: ").append(pendingViaHeader).append("\r\n");
        sb.append("From: ").append(pendingFromHeader).append("\r\n");
        sb.append("To: ").append(pendingToHeader).append(";tag=").append(hexRandom(8)).append("\r\n");
        sb.append("Call-ID: ").append(pendingCallId).append("\r\n");
        sb.append("CSeq: 1 INVITE\r\n");
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");

        send(sb.toString());
        clearPendingCall();
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
    }

    // ---- Hangup ----

    public void hangup() {
        if (state == State.CALLING) {
            sendCancel();
        } else if (state == State.ACTIVE || state == State.RINGING_IN) {
            sendBye();
        }
    }

    private void sendCancel() {
        if (callId == null) return;
        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        String userSipUri = "sip:" + user + "@" + domain;

        StringBuilder sb = new StringBuilder();
        sb.append("CANCEL ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/WSS ").append(clientId).append(";branch=").append(inviteBranch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userSipUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: <").append(targetUri).append(">").append(remoteTag != null ? ";tag=" + remoteTag : "").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(inviteCSeq).append(" CANCEL\r\n");
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");

        send(sb.toString());
        stopCall(0, "Bekor qilindi");
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
    }

    private void sendBye() {
        if (callId == null) return;

        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        String userSipUri = "sip:" + user + "@" + domain;
        int byeCSeq = callCSeq++;

        StringBuilder sb = new StringBuilder();
        sb.append("BYE ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/WSS ").append(clientId).append(";branch=z9hG4bK").append(hexRandom(8)).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userSipUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: <").append(targetUri).append(">").append(remoteTag != null ? ";tag=" + remoteTag : "").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(byeCSeq).append(" BYE\r\n");
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");

        send(sb.toString());
        stopCall(0, "BYE");
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
    }

    // ---- Transfer (REFER) ----

    public void transfer(String targetNumber) {
        if (state != State.ACTIVE || callId == null) return;

        String referTo = "sip:" + targetNumber + "@" + domain;
        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        String userSipUri = "sip:" + user + "@" + domain;
        int referCSeq = callCSeq++;

        StringBuilder sb = new StringBuilder();
        sb.append("REFER ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/WSS ").append(clientId).append(";branch=z9hG4bK").append(hexRandom(8)).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userSipUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: <").append(targetUri).append(">").append(remoteTag != null ? ";tag=" + remoteTag : "").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(referCSeq).append(" REFER\r\n");
        sb.append("Refer-To: <").append(referTo).append(">\r\n");
        sb.append("Referred-By: <").append(userSipUri).append(">\r\n");
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");

        send(sb.toString());
        log("Transfer so'rovi yuborildi: " + targetNumber);
    }

    // ---- Message handling ----

    private void handleSipMessage(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;

        // Handle OPTIONS (keepalive) ping/pong
        raw = raw.trim();
        if (raw.equals("\r\n") || raw.isEmpty()) return;

        log("< " + raw.substring(0, Math.min(200, raw.length())).replace("\r\n", " | "));

        SipMessage msg = SipMessage.parse(raw);
        if (msg == null) return;

        if (msg.isResponse) {
            handleResponse(msg);
        } else {
            handleRequest(msg);
        }
    }

    private void handleResponse(SipMessage msg) {
        String cseq = msg.cseq();
        if (cseq == null) return;

        String cseqUpper = cseq.toUpperCase();

        if (cseqUpper.contains("REGISTER")) {
            handleRegisterResponse(msg);
        } else if (cseqUpper.contains("INVITE")) {
            handleInviteResponse(msg);
        } else if (cseqUpper.contains("BYE") || cseqUpper.contains("CANCEL")) {
            // Ignore BYE/CANCEL responses
            log("BYE/CANCEL javobi: " + msg.statusCode);
        } else if (cseqUpper.contains("REFER")) {
            log("REFER javobi: " + msg.statusCode + " " + msg.reasonPhrase);
        }
    }

    private void handleRegisterResponse(SipMessage msg) {
        int code = msg.statusCode;
        if (code == 401 || code == 407) {
            String authHeader = code == 401 ? msg.wwwAuthenticate() : msg.getHeader("proxy-authenticate");
            if (authHeader == null) {
                log("401/407 javobi lekin auth header yo'q");
                changeState(State.IDLE, "Autentifikatsiya xatosi");
                return;
            }
            log("Autentifikatsiya so'raldi, javob berilmoqda...");
            regWwwAuth = authHeader;
            sendRegister(authHeader);
        } else if (code == 200) {
            log("Ro'yxatdan o'tish muvaffaqiyatli!");
            changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
            scheduleReregister();
        } else if (code >= 400) {
            log("Ro'yxatdan o'tish muvaffaqiyatsiz: " + code + " " + msg.reasonPhrase);
            changeState(State.IDLE, "Ro'yxatdan o'tish xatosi: " + code);
        }
    }

    private void handleInviteResponse(SipMessage msg) {
        int code = msg.statusCode;
        String cid = msg.callId();
        if (cid == null || !cid.equals(callId)) return;

        String toTag = msg.extractTag(msg.to());
        if (toTag != null) remoteTag = toTag;

        // Update remote target from Contact
        String contact = msg.contact();
        if (contact != null) {
            String contactUri = extractContactUri(contact);
            if (contactUri != null) remoteTarget = contactUri;
        }

        if (code == 100) {
            log("Ulanmoqda...");
        } else if (code == 180 || code == 183) {
            log("Jiringlayapti...");
            changeState(State.CALLING, "Jiringlayapti...");
        } else if (code == 200) {
            // Send ACK
            sendAck(msg);
            // Parse SDP and start RTP
            String sdp = msg.body;
            String[] rtpAddr = parseSdpForRtp(sdp);
            if (rtpAddr != null && rtpAddr.length == 2) {
                try {
                    String remoteIp = rtpAddr[0];
                    int remotePort = Integer.parseInt(rtpAddr[1]);
                    if (rtpSession != null) {
                        rtpSession.start(remoteIp, remotePort, 0);
                    }
                    changeState(State.ACTIVE, "Faol");
                    final String ip = remoteIp;
                    final int port = remotePort;
                    if (listener != null) Platform.runLater(() -> listener.onCallConnected(ip, port));
                } catch (Exception e) {
                    log("RTP boshlash xatosi: " + e.getMessage());
                }
            } else {
                changeState(State.ACTIVE, "Faol (SDP xatosi)");
                if (listener != null) Platform.runLater(() -> listener.onCallConnected("unknown", 0));
            }
        } else if (code >= 300) {
            log("INVITE rad etildi: " + code + " " + msg.reasonPhrase);
            // Send ACK for final responses
            sendAckForError(msg);
            final int c = code;
            final String r = msg.reasonPhrase;
            stopCall(c, r);
            changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
            if (listener != null) Platform.runLater(() -> listener.onCallEnded(c, r));
        }
    }

    private void sendAck(SipMessage inviteResponse) {
        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        if (targetUri.contains(";")) {
            // Use full contact URI
        }
        String userSipUri = "sip:" + user + "@" + domain;
        String toHeader = inviteResponse.to();

        StringBuilder sb = new StringBuilder();
        sb.append("ACK ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/WSS ").append(clientId).append(";branch=z9hG4bK").append(hexRandom(8)).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userSipUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: ").append(toHeader != null ? toHeader : "<" + targetUri + ">").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(inviteCSeq).append(" ACK\r\n");
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");

        send(sb.toString());
    }

    private void sendAckForError(SipMessage inviteResponse) {
        String targetUri = remoteTarget != null ? remoteTarget : "sip:" + domain;
        if (targetUri.startsWith("sip:") == false) targetUri = "sip:" + targetUri;
        String userSipUri = "sip:" + user + "@" + domain;
        String toHeader = inviteResponse.to();

        StringBuilder sb = new StringBuilder();
        sb.append("ACK ").append(targetUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/WSS ").append(clientId).append(";branch=").append(inviteBranch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("From: <").append(userSipUri).append(">;tag=").append(localTag).append("\r\n");
        sb.append("To: ").append(toHeader != null ? toHeader : "<" + targetUri + ">").append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(inviteCSeq).append(" ACK\r\n");
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");

        send(sb.toString());
    }

    private void handleRequest(SipMessage msg) {
        String method = msg.method;
        if (method == null) return;

        switch (method.toUpperCase()) {
            case "INVITE":
                handleIncomingInvite(msg);
                break;
            case "BYE":
                handleIncomingBye(msg);
                break;
            case "CANCEL":
                handleIncomingCancel(msg);
                break;
            case "OPTIONS":
                handleOptions(msg);
                break;
            case "NOTIFY":
                handleNotify(msg);
                break;
            default:
                log("Noma'lum SIP metod: " + method);
                sendResponse(msg, 405, "Method Not Allowed");
        }
    }

    private void handleIncomingInvite(SipMessage msg) {
        String fromHeader = msg.from();
        String fromTag = msg.extractTag(fromHeader);
        String callerUser = msg.extractUser(fromHeader);
        String displayName = msg.extractDisplayName(fromHeader);
        String callerDisplay = (displayName != null && !displayName.isEmpty()) ? displayName : callerUser;

        pendingFromHeader = fromHeader;
        pendingToHeader = msg.to();
        pendingCallId = msg.callId();
        pendingViaHeader = msg.via();
        pendingContact = msg.contact();
        pendingSdp = msg.body;
        pendingRemoteTag = fromTag;
        localTag = hexRandom(8);
        callCSeq = 2;
        isOutgoing = false;

        // Send 100 Trying
        sendProvisional(msg, 100, "Trying");
        // Send 180 Ringing
        sendProvisional(msg, 180, "Ringing");

        changeState(State.RINGING_IN, "Kiruvchi qo'ng'iroq: " + callerDisplay);
        final String caller = callerDisplay;
        final String cid = pendingCallId;
        if (listener != null) Platform.runLater(() -> listener.onIncomingCall(caller, cid));
    }

    private void sendProvisional(SipMessage invite, int code, String reason) {
        String toHeader = invite.to();
        if (code > 100 && !toHeader.contains(";tag=")) {
            toHeader = toHeader + ";tag=" + localTag;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("SIP/2.0 ").append(code).append(" ").append(reason).append("\r\n");
        sb.append("Via: ").append(invite.via()).append("\r\n");
        sb.append("From: ").append(invite.from()).append("\r\n");
        sb.append("To: ").append(toHeader).append("\r\n");
        sb.append("Call-ID: ").append(invite.callId()).append("\r\n");
        sb.append("CSeq: ").append(invite.cseq()).append("\r\n");
        if (code == 180) {
            sb.append("Contact: <sip:").append(user).append("@").append(clientId).append(";transport=wss>\r\n");
        }
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");

        send(sb.toString());
    }

    private void handleIncomingBye(SipMessage msg) {
        // Send 200 OK
        sendResponse(msg, 200, "OK");
        stopCall(0, "BYE");
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
        if (listener != null) Platform.runLater(() -> listener.onCallEnded(0, "BYE"));
    }

    private void handleIncomingCancel(SipMessage msg) {
        // Send 200 OK for CANCEL
        sendResponse(msg, 200, "OK");
        // Send 487 for original INVITE
        if (pendingCallId != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("SIP/2.0 487 Request Terminated\r\n");
            sb.append("Via: ").append(pendingViaHeader).append("\r\n");
            sb.append("From: ").append(pendingFromHeader).append("\r\n");
            sb.append("To: ").append(pendingToHeader).append(";tag=").append(localTag != null ? localTag : hexRandom(8)).append("\r\n");
            sb.append("Call-ID: ").append(pendingCallId).append("\r\n");
            sb.append("CSeq: 1 INVITE\r\n");
            sb.append("Content-Length: 0\r\n");
            sb.append("\r\n");
            send(sb.toString());
        }
        clearPendingCall();
        changeState(State.REGISTERED, "Ro'yxatdan o'tgan");
        if (listener != null) Platform.runLater(() -> listener.onCallEnded(0, "CANCEL"));
    }

    private void handleOptions(SipMessage msg) {
        sendResponse(msg, 200, "OK");
    }

    private void handleNotify(SipMessage msg) {
        sendResponse(msg, 200, "OK");
        log("NOTIFY: " + msg.body);
    }

    private void sendResponse(SipMessage request, int code, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("SIP/2.0 ").append(code).append(" ").append(reason).append("\r\n");
        if (request.via() != null) sb.append("Via: ").append(request.via()).append("\r\n");
        if (request.from() != null) sb.append("From: ").append(request.from()).append("\r\n");
        String toHeader = request.to();
        if (toHeader != null && !toHeader.contains(";tag=")) {
            toHeader = toHeader + ";tag=" + hexRandom(8);
        }
        if (toHeader != null) sb.append("To: ").append(toHeader).append("\r\n");
        if (request.callId() != null) sb.append("Call-ID: ").append(request.callId()).append("\r\n");
        if (request.cseq() != null) sb.append("CSeq: ").append(request.cseq()).append("\r\n");
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");
        send(sb.toString());
    }

    // ---- Helpers ----

    private void stopCall(int code, String reason) {
        if (rtpSession != null) {
            rtpSession.stop();
            rtpSession = null;
        }
        callId = null;
        localTag = null;
        remoteTag = null;
        remoteTarget = null;
        clearPendingCall();
    }

    private void clearPendingCall() {
        pendingFromHeader = null;
        pendingToHeader = null;
        pendingCallId = null;
        pendingViaHeader = null;
        pendingContact = null;
        pendingSdp = null;
        pendingRemoteTag = null;
    }

    private String buildSdp(String localIp, int rtpPort) {
        long ts = System.currentTimeMillis() / 1000L;
        return "v=0\r\n" +
               "o=- " + ts + " " + ts + " IN IP4 " + localIp + "\r\n" +
               "s=SIP Call\r\n" +
               "c=IN IP4 " + localIp + "\r\n" +
               "t=0 0\r\n" +
               "m=audio " + rtpPort + " RTP/AVP 0 8\r\n" +
               "a=rtpmap:0 PCMU/8000\r\n" +
               "a=rtpmap:8 PCMA/8000\r\n" +
               "a=sendrecv\r\n";
    }

    private String[] parseSdpForRtp(String sdp) {
        if (sdp == null || sdp.isEmpty()) return null;
        String ip = null;
        int port = -1;

        for (String line : sdp.split("[\r\n]+")) {
            line = line.trim();
            if (line.startsWith("c=IN IP4 ")) {
                ip = line.substring("c=IN IP4 ".length()).trim();
            } else if (line.startsWith("m=audio ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (ip != null && port > 0) {
            return new String[]{ip, String.valueOf(port)};
        }
        return null;
    }

    private String getLocalIp() {
        try {
            // Try to find a non-loopback address
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            // Fallback
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private String extractContactUri(String contactHeader) {
        if (contactHeader == null) return null;
        int lt = contactHeader.indexOf('<');
        int gt = contactHeader.indexOf('>');
        if (lt >= 0 && gt > lt) {
            return contactHeader.substring(lt + 1, gt).trim();
        }
        // No angle brackets
        int semi = contactHeader.indexOf(';');
        return semi >= 0 ? contactHeader.substring(0, semi).trim() : contactHeader.trim();
    }

    private String extractTagFromHeader(String header) {
        if (header == null) return null;
        int idx = header.toLowerCase().indexOf(";tag=");
        if (idx < 0) return null;
        String rest = header.substring(idx + 5);
        int semi = rest.indexOf(';');
        return semi < 0 ? rest.trim() : rest.substring(0, semi).trim();
    }

    private void send(String message) {
        if (ws == null) {
            log("WebSocket null, xabar yuborib bo'lmaydi");
            return;
        }
        log("> " + message.substring(0, Math.min(200, message.length())).replace("\r\n", " | "));
        ws.sendText(message, true).exceptionally(e -> {
            log("Yuborish xatosi: " + e.getMessage());
            return null;
        });
    }

    private void changeState(State newState, String detail) {
        state = newState;
        if (listener != null) {
            final State s = newState;
            final String d = detail;
            Platform.runLater(() -> listener.onState(s, d));
        }
    }

    private void log(String msg) {
        if (listener != null) {
            Platform.runLater(() -> listener.onLog(msg));
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String hexRandom(int n) {
        StringBuilder sb = new StringBuilder();
        Random rand = new Random();
        for (int i = 0; i < n; i++) {
            sb.append(Integer.toHexString(rand.nextInt(16)));
        }
        return sb.toString();
    }

    public void shutdown() {
        cancelReregister();
        scheduler.shutdownNow();
        disconnect();
    }
}
