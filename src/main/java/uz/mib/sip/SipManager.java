package uz.mib.sip;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

public class SipManager {

    static final String SERVER   = "lite-sip.mib.uz";
    static final String WS_URL   = "wss://lite-sip.mib.uz:8089/ws";
    static final String USER     = "1700";
    static final String PASS     = "aa1700aa";
    static final String DOMAIN   = "lite-sip.mib.uz";
    static final String SIP_URI  = "sip:" + USER + "@" + DOMAIN;

    public interface SipListener {
        void onConnecting();
        void onRegistered();
        void onUnregistered();
        void onDisconnected(String reason);
        void onIncomingCall(String caller, CallInfo info);
        void onCallProgress(int code, String reason, String sdp);
        void onCallEstablished(CallInfo info);
        void onCallTerminated(int code, String reason);
        void onTransferResult(boolean ok, String msg);
        void onWaitingCallsChanged(int count);
        void onLog(String message);
    }

    public static class CallInfo {
        public final String callId, remoteNumber, remoteSdp;
        public final boolean outgoing;
        public CallInfo(String c, String n, String s, boolean o) {
            callId=c; remoteNumber=n; remoteSdp=s; outgoing=o;
        }
    }

    static class CallState {
        String callId, localTag, remoteTag;
        String remoteNumber;
        String fromHdr, toHdr, inviteVia, inviteContact;
        int localCseq;
        boolean established, outgoing;
        String localSdp, remoteSdp;
        int localRtpPort;
    }

    static class WaitingCall {
        CallState state;
        long ts;
    }

    private WebSocketClient ws;
    private final SipListener listener;
    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);

    private String regCallId = genCallId();
    private final String regTag = genTag();
    private int regCseq = 0;
    private ScheduledFuture<?> reregTask;

    private CallState activeCall;
    private final Queue<WaitingCall> waiting = new ConcurrentLinkedQueue<>();
    private final String instanceId = genTag();

    public SipManager(SipListener l) { this.listener = l; }

    public void connect() {
        listener.onConnecting();
        buildWs();
        try {
            ws.setSocketFactory(trustAllCtx().getSocketFactory());
        } catch (Exception e) { listener.onLog("SSL: " + e.getMessage()); }
        ws.connect();
    }

    public void disconnect() {
        if (reregTask != null) reregTask.cancel(false);
        if (ws != null && ws.isOpen()) {
            sendUnregister();
            ws.close();
        }
    }

    private void buildWs() {
        try {
            URI uri = new URI(WS_URL);
            ws = new WebSocketClient(uri, Map.of("Sec-WebSocket-Protocol", "sip")) {
                @Override public void onOpen(ServerHandshake h) {
                    listener.onLog("WS ulandi");
                    sendRegister(null);
                }
                @Override public void onMessage(String m) { handleRaw(m); }
                @Override public void onClose(int c, String r, boolean remote) {
                    listener.onLog("WS yopildi: " + r);
                    listener.onDisconnected(r);
                    if (reregTask != null) reregTask.cancel(false);
                    sched.schedule(() -> {
                        listener.onConnecting();
                        buildWs();
                        try { ws.setSocketFactory(trustAllCtx().getSocketFactory()); } catch(Exception e){}
                        ws.connect();
                    }, 3, TimeUnit.SECONDS);
                }
                @Override public void onError(Exception e) { listener.onLog("WS xatosi: " + e.getMessage()); }
            };
        } catch (URISyntaxException e) { listener.onLog("URI xatosi: " + e.getMessage()); }
    }

    // ── Register ──────────────────────────────────────────────────────

    private void sendRegister(Map<String,String> auth) {
        regCseq++;
        String contact = "sip:" + USER + "@" + SERVER + ";transport=ws";
        StringBuilder b = new StringBuilder();
        b.append("REGISTER sip:").append(DOMAIN).append(" SIP/2.0\r\n");
        b.append("Via: SIP/2.0/WSS ").append(SERVER).append(";branch=").append(branch()).append(";rport\r\n");
        b.append("Max-Forwards: 70\r\n");
        b.append("From: <").append(SIP_URI).append(">;tag=").append(regTag).append("\r\n");
        b.append("To: <").append(SIP_URI).append(">\r\n");
        b.append("Call-ID: ").append(regCallId).append("\r\n");
        b.append("CSeq: ").append(regCseq).append(" REGISTER\r\n");
        b.append("Contact: <").append(contact).append(">;+sip.instance=\"<urn:uuid:").append(instanceId).append(">\"\r\n");
        b.append("Expires: 600\r\n");
        b.append("Allow: INVITE,ACK,BYE,CANCEL,OPTIONS,REFER,NOTIFY\r\n");
        b.append("Supported: replaces,100rel,timer\r\n");
        b.append("User-Agent: SadoSIP/1.0 JavaFX\r\n");
        if (auth != null) b.append("Authorization: ").append(digest("REGISTER", "sip:" + DOMAIN, auth)).append("\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
    }

    private void sendUnregister() {
        regCseq++;
        StringBuilder b = new StringBuilder();
        b.append("REGISTER sip:").append(DOMAIN).append(" SIP/2.0\r\n");
        b.append("Via: SIP/2.0/WSS ").append(SERVER).append(";branch=").append(branch()).append(";rport\r\n");
        b.append("Max-Forwards: 70\r\n");
        b.append("From: <").append(SIP_URI).append(">;tag=").append(regTag).append("\r\n");
        b.append("To: <").append(SIP_URI).append(">\r\n");
        b.append("Call-ID: ").append(regCallId).append("\r\n");
        b.append("CSeq: ").append(regCseq).append(" REGISTER\r\n");
        b.append("Contact: *\r\nExpires: 0\r\nContent-Length: 0\r\n\r\n");
        send(b.toString());
    }

    // ── Outgoing call ──────────────────────────────────────────────────

    public void makeCall(String number) {
        if (activeCall != null) return;
        CallState c = new CallState();
        c.callId       = genCallId();
        c.localTag     = genTag();
        c.outgoing     = true;
        c.remoteNumber = number;
        c.localCseq    = 1;
        c.localRtpPort = freePort();
        c.localSdp     = buildSdp(c.localRtpPort);
        activeCall     = c;

        String target  = "sip:" + number + "@" + DOMAIN;
        String contact = "sip:" + USER + "@" + SERVER + ";transport=ws";
        c.fromHdr = "<" + SIP_URI + ">;tag=" + c.localTag;
        c.toHdr   = "<" + target + ">";

        sendInvite(null);
        listener.onLog("INVITE → " + number);
    }

    private void sendInvite(Map<String,String> authChallenge) {
        if (activeCall == null) return;
        String target  = "sip:" + activeCall.remoteNumber + "@" + DOMAIN;
        String contact = "sip:" + USER + "@" + SERVER + ";transport=ws";

        StringBuilder b = new StringBuilder();
        b.append("INVITE ").append(target).append(" SIP/2.0\r\n");
        b.append("Via: SIP/2.0/WSS ").append(SERVER).append(";branch=").append(branch()).append(";rport\r\n");
        b.append("Max-Forwards: 70\r\n");
        b.append("From: ").append(activeCall.fromHdr).append("\r\n");
        // For re-INVITE after 401, To must NOT contain remote tag (still no dialog)
        b.append("To: ").append(activeCall.toHdr).append("\r\n");
        b.append("Call-ID: ").append(activeCall.callId).append("\r\n");
        b.append("CSeq: ").append(activeCall.localCseq).append(" INVITE\r\n");
        b.append("Contact: <").append(contact).append(">\r\n");
        b.append("Allow: INVITE,ACK,BYE,CANCEL,OPTIONS,REFER\r\n");
        b.append("Supported: replaces,100rel\r\n");
        if (authChallenge != null) {
            b.append("Authorization: ").append(digest("INVITE", target, authChallenge)).append("\r\n");
        }
        b.append("Content-Type: application/sdp\r\n");
        b.append("Content-Length: ").append(activeCall.localSdp.length()).append("\r\n\r\n");
        b.append(activeCall.localSdp);
        send(b.toString());
    }

    public void cancelOutgoing() {
        if (activeCall == null || !activeCall.outgoing || activeCall.established) return;
        String target = "sip:" + activeCall.remoteNumber + "@" + DOMAIN;
        StringBuilder b = new StringBuilder();
        b.append("CANCEL ").append(target).append(" SIP/2.0\r\n");
        b.append("Via: SIP/2.0/WSS ").append(SERVER).append(";branch=").append(branch()).append(";rport\r\n");
        b.append("Max-Forwards: 70\r\n");
        b.append("From: ").append(activeCall.fromHdr).append("\r\n");
        b.append("To: ").append(activeCall.toHdr).append("\r\n");
        b.append("Call-ID: ").append(activeCall.callId).append("\r\n");
        b.append("CSeq: ").append(activeCall.localCseq).append(" CANCEL\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
    }

    // ── Incoming call ──────────────────────────────────────────────────

    public void answer() {
        if (activeCall == null || activeCall.outgoing) return;
        activeCall.localRtpPort = freePort();
        activeCall.localSdp     = buildSdp(activeCall.localRtpPort);
        send200Ok(activeCall);
    }

    public void reject() {
        if (activeCall == null || activeCall.outgoing || activeCall.established) return;
        StringBuilder b = new StringBuilder();
        b.append("SIP/2.0 603 Decline\r\n");
        b.append("Via: ").append(activeCall.inviteVia).append("\r\n");
        b.append("From: ").append(activeCall.fromHdr).append("\r\n");
        b.append("To: ").append(activeCall.toHdr).append(";tag=").append(activeCall.localTag).append("\r\n");
        b.append("Call-ID: ").append(activeCall.callId).append("\r\n");
        b.append("CSeq: ").append(activeCall.localCseq).append(" INVITE\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
        activeCall = null;
        listener.onCallTerminated(603, "Declined");
        sched.schedule(this::processWaiting, 200, TimeUnit.MILLISECONDS);
    }

    public void hangup() {
        if (activeCall == null) return;
        if (activeCall.established) {
            sendBye();
        } else if (activeCall.outgoing) {
            cancelOutgoing();
        } else {
            reject();
        }
    }

    public void transfer(String number) {
        if (activeCall == null || !activeCall.established) return;
        activeCall.localCseq++;
        String targetUri = "sip:" + number + "@" + DOMAIN;
        String reqUri    = activeCall.inviteContact != null ? activeCall.inviteContact : "sip:" + activeCall.remoteNumber + "@" + DOMAIN;
        StringBuilder b  = new StringBuilder();
        b.append("REFER ").append(reqUri).append(" SIP/2.0\r\n");
        appendDialogHdrs(b);
        b.append("CSeq: ").append(activeCall.localCseq).append(" REFER\r\n");
        b.append("Refer-To: <").append(targetUri).append(">\r\n");
        b.append("Referred-By: <").append(SIP_URI).append(">\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
    }

    // ── Handle incoming messages ─────────────────────────────────────────────

    private void handleRaw(String raw) {
        int nl = raw.indexOf("\r\n");
        listener.onLog("← " + (nl > 0 ? raw.substring(0, Math.min(nl, 100)) : raw.substring(0, Math.min(100, raw.length()))));
        SipMessage msg = SipMessage.parse(raw);
        if (msg == null) return;
        if (msg.isRequest()) handleRequest(msg);
        else                 handleResponse(msg);
    }

    private void handleRequest(SipMessage msg) {
        switch (msg.getMethod()) {
            case "INVITE"  -> onInvite(msg);
            case "ACK"     -> onAck(msg);
            case "BYE"     -> onBye(msg);
            case "CANCEL"  -> onCancel(msg);
            case "NOTIFY"  -> simpleReply(msg, 200, "OK");
            case "OPTIONS" -> optionsReply(msg);
            default        -> simpleReply(msg, 405, "Method Not Allowed");
        }
    }

    private void handleResponse(SipMessage msg) {
        String cseqHdr = msg.getHeader("cseq");
        if (cseqHdr == null) return;
        String[] p = cseqHdr.split(" ", 2);
        String method = p.length > 1 ? p[1].trim() : "";
        switch (method) {
            case "REGISTER" -> onRegResponse(msg);
            case "INVITE"   -> onInviteResponse(msg);
            case "REFER"    -> onReferResponse(msg);
        }
    }

    private void onRegResponse(SipMessage msg) {
        int code = msg.getStatusCode();
        if (code == 401 || code == 407) {
            String hdr = msg.getHeader(code == 401 ? "www-authenticate" : "proxy-authenticate");
            if (hdr != null) sendRegister(parseChallenge(hdr));
        } else if (code >= 200 && code < 300) {
            listener.onRegistered();
            if (reregTask != null) reregTask.cancel(false);
            reregTask = sched.schedule(() -> sendRegister(null), 550, TimeUnit.SECONDS);
        } else {
            listener.onUnregistered();
        }
    }

    private void onInvite(SipMessage msg) {
        String from    = msg.getHeader("from");
        String callId  = msg.getHeader("call-id");
        String cseq    = msg.getHeader("cseq");
        String via     = msg.getHeader("via");
        String contact = msg.getHeader("contact");
        String body    = msg.getBody();
        String caller  = extractNum(from);
        String cseqN   = cseq != null ? cseq.split(" ")[0] : "1";

        simpleReply(msg, 100, "Trying");

        CallState c = new CallState();
        c.callId       = callId;
        c.localTag     = genTag();
        c.remoteNumber = caller;
        c.localCseq    = Integer.parseInt(cseqN);
        c.outgoing     = false;
        c.inviteVia    = via;
        c.fromHdr      = from;
        c.toHdr        = "<" + SIP_URI + ">";
        c.remoteSdp    = body;
        if (contact != null) c.inviteContact = extractUri(contact);

        if (activeCall != null) {
            WaitingCall wc = new WaitingCall();
            wc.state = c; wc.ts = System.currentTimeMillis();
            waiting.add(wc);
            sendRinging(c);
            listener.onWaitingCallsChanged(waiting.size());
            return;
        }

        activeCall = c;
        sendRinging(c);
        listener.onIncomingCall(caller, new CallInfo(callId, caller, body, false));
    }

    private void onAck(SipMessage msg) {
        if (activeCall != null && !activeCall.outgoing && !activeCall.established) {
            activeCall.established = true;
            listener.onCallEstablished(new CallInfo(activeCall.callId, activeCall.remoteNumber, activeCall.remoteSdp, false));
        }
    }

    private void onBye(SipMessage msg) {
        simpleReply(msg, 200, "OK");
        activeCall = null;
        listener.onCallTerminated(0, "BYE");
        sched.schedule(this::processWaiting, 200, TimeUnit.MILLISECONDS);
    }

    private void onCancel(SipMessage msg) {
        simpleReply(msg, 200, "OK");
        if (activeCall != null) {
            StringBuilder b = new StringBuilder();
            b.append("SIP/2.0 487 Request Terminated\r\n");
            b.append("Via: ").append(activeCall.inviteVia).append("\r\n");
            b.append("From: ").append(activeCall.fromHdr).append("\r\n");
            b.append("To: ").append(activeCall.toHdr).append(";tag=").append(activeCall.localTag).append("\r\n");
            b.append("Call-ID: ").append(activeCall.callId).append("\r\n");
            b.append("CSeq: ").append(activeCall.localCseq).append(" INVITE\r\n");
            b.append("Content-Length: 0\r\n\r\n");
            send(b.toString());
            activeCall = null;
            listener.onCallTerminated(487, "Request Terminated");
            sched.schedule(this::processWaiting, 200, TimeUnit.MILLISECONDS);
        }
    }

    private void onInviteResponse(SipMessage msg) {
        if (activeCall == null || !activeCall.outgoing) return;
        int code = msg.getStatusCode();

        // Update remote tag and contact from every response
        String toHdr = msg.getHeader("to");
        if (toHdr != null) { String t = extractTag(toHdr); if (t != null) activeCall.remoteTag = t; }
        String contactHdr = msg.getHeader("contact");
        if (contactHdr != null) activeCall.inviteContact = extractUri(contactHdr);

        if (code == 100) return;
        if (code == 180) { listener.onCallProgress(180, "Ringing", null); return; }
        if (code == 183) { listener.onCallProgress(183, "Session Progress", msg.getBody()); return; }

        // ── 401/407: digest auth challenge for INVITE ──────────────────────────────
        if (code == 401 || code == 407) {
            // RFC 3261: ACK must be sent for any non-2xx final response to INVITE
            sendAckForError();

            String hdrName = (code == 401) ? "www-authenticate" : "proxy-authenticate";
            String authHdr  = msg.getHeader(hdrName);
            if (authHdr == null) {
                listener.onLog("401 geldi lekin WWW-Authenticate yo'q");
                activeCall = null;
                listener.onCallTerminated(code, msg.getReasonPhrase());
                return;
            }

            // Increment CSeq and re-send INVITE with Authorization
            activeCall.localCseq++;
            // Fresh RTP port for the re-INVITE
            activeCall.localRtpPort = freePort();
            activeCall.localSdp     = buildSdp(activeCall.localRtpPort);
            // Clear remote tag so To header in re-INVITE stays tag-free
            activeCall.remoteTag    = null;

            listener.onLog("INVITE 401 → auth bilan qayta yuborilmoqda...");
            sendInvite(parseChallenge(authHdr));
            return;
        }

        // ── 2xx: success ─────────────────────────────────────────────────────────────────
        if (code >= 200 && code < 300) {
            sendAck();
            activeCall.remoteSdp   = msg.getBody();
            activeCall.established = true;
            listener.onCallEstablished(new CallInfo(activeCall.callId, activeCall.remoteNumber, activeCall.remoteSdp, true));
            return;
        }

        // ── 3xx-6xx: failure ───────────────────────────────────────────────────────────
        if (code >= 300) {
            sendAckForError();
            int failCode = code;
            String failReason = msg.getReasonPhrase();
            activeCall = null;
            listener.onCallTerminated(failCode, failReason);
        }
    }

    private void onReferResponse(SipMessage msg) {
        int code = msg.getStatusCode();
        if (code >= 200 && code < 300) listener.onTransferResult(true, "O'tkazildi");
        else listener.onTransferResult(false, "Xato: " + code);
    }

    // ── SIP message helpers ──────────────────────────────────────────────────

    private void sendBye() {
        if (activeCall == null) return;
        activeCall.localCseq++;
        String reqUri = activeCall.inviteContact != null ? activeCall.inviteContact : "sip:" + activeCall.remoteNumber + "@" + DOMAIN;
        StringBuilder b = new StringBuilder();
        b.append("BYE ").append(reqUri).append(" SIP/2.0\r\n");
        appendDialogHdrs(b);
        b.append("CSeq: ").append(activeCall.localCseq).append(" BYE\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
        activeCall = null;
        listener.onCallTerminated(0, "BYE sent");
        sched.schedule(this::processWaiting, 200, TimeUnit.MILLISECONDS);
    }

    private void sendRinging(CallState c) {
        StringBuilder b = new StringBuilder();
        b.append("SIP/2.0 180 Ringing\r\n");
        b.append("Via: ").append(c.inviteVia).append("\r\n");
        b.append("From: ").append(c.fromHdr).append("\r\n");
        b.append("To: ").append(c.toHdr).append(";tag=").append(c.localTag).append("\r\n");
        b.append("Call-ID: ").append(c.callId).append("\r\n");
        b.append("CSeq: ").append(c.localCseq).append(" INVITE\r\n");
        b.append("Contact: <sip:").append(USER).append("@").append(SERVER).append(";transport=ws>\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
    }

    private void send200Ok(CallState c) {
        String contact = "sip:" + USER + "@" + SERVER + ";transport=ws";
        StringBuilder b = new StringBuilder();
        b.append("SIP/2.0 200 OK\r\n");
        b.append("Via: ").append(c.inviteVia).append("\r\n");
        b.append("From: ").append(c.fromHdr).append("\r\n");
        b.append("To: ").append(c.toHdr).append(";tag=").append(c.localTag).append("\r\n");
        b.append("Call-ID: ").append(c.callId).append("\r\n");
        b.append("CSeq: ").append(c.localCseq).append(" INVITE\r\n");
        b.append("Contact: <").append(contact).append(">\r\n");
        b.append("Allow: INVITE,ACK,BYE,CANCEL,OPTIONS,REFER\r\n");
        b.append("Content-Type: application/sdp\r\n");
        b.append("Content-Length: ").append(c.localSdp.length()).append("\r\n\r\n");
        b.append(c.localSdp);
        send(b.toString());
    }

    /** ACK for 2xx responses (dialog-establishing) */
    private void sendAck() {
        if (activeCall == null) return;
        String reqUri = activeCall.inviteContact != null ? activeCall.inviteContact
                      : "sip:" + activeCall.remoteNumber + "@" + DOMAIN;
        StringBuilder b = new StringBuilder();
        b.append("ACK ").append(reqUri).append(" SIP/2.0\r\n");
        b.append("Via: SIP/2.0/WSS ").append(SERVER).append(";branch=").append(branch()).append(";rport\r\n");
        b.append("Max-Forwards: 70\r\n");
        b.append("From: ").append(activeCall.fromHdr).append("\r\n");
        String to = activeCall.toHdr;
        if (activeCall.remoteTag != null && !to.contains("tag=")) to += ";tag=" + activeCall.remoteTag;
        b.append("To: ").append(to).append("\r\n");
        b.append("Call-ID: ").append(activeCall.callId).append("\r\n");
        b.append("CSeq: ").append(activeCall.localCseq).append(" ACK\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
    }

    /** ACK for non-2xx responses (401, 403, 486, etc.) — same transaction, original Request-URI */
    private void sendAckForError() {
        if (activeCall == null) return;
        String reqUri = "sip:" + activeCall.remoteNumber + "@" + DOMAIN;
        StringBuilder b = new StringBuilder();
        b.append("ACK ").append(reqUri).append(" SIP/2.0\r\n");
        b.append("Via: SIP/2.0/WSS ").append(SERVER).append(";branch=").append(branch()).append(";rport\r\n");
        b.append("Max-Forwards: 70\r\n");
        b.append("From: ").append(activeCall.fromHdr).append("\r\n");
        String to = activeCall.toHdr;
        if (activeCall.remoteTag != null && !to.contains("tag=")) to += ";tag=" + activeCall.remoteTag;
        b.append("To: ").append(to).append("\r\n");
        b.append("Call-ID: ").append(activeCall.callId).append("\r\n");
        b.append("CSeq: ").append(activeCall.localCseq).append(" ACK\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
    }

    private void appendDialogHdrs(StringBuilder b) {
        b.append("Via: SIP/2.0/WSS ").append(SERVER).append(";branch=").append(branch()).append(";rport\r\n");
        b.append("Max-Forwards: 70\r\n");
        if (activeCall.outgoing) {
            b.append("From: ").append(activeCall.fromHdr).append("\r\n");
            String to = activeCall.toHdr;
            if (activeCall.remoteTag != null && !to.contains("tag=")) to += ";tag=" + activeCall.remoteTag;
            b.append("To: ").append(to).append("\r\n");
        } else {
            b.append("From: ").append(activeCall.toHdr).append(";tag=").append(activeCall.localTag).append("\r\n");
            b.append("To: ").append(activeCall.fromHdr).append("\r\n");
        }
        b.append("Call-ID: ").append(activeCall.callId).append("\r\n");
        b.append("Contact: <sip:").append(USER).append("@").append(SERVER).append(";transport=ws>\r\n");
    }

    private void simpleReply(SipMessage msg, int code, String reason) {
        StringBuilder b = new StringBuilder();
        b.append("SIP/2.0 ").append(code).append(" ").append(reason).append("\r\n");
        b.append("Via: ").append(msg.getHeader("via")).append("\r\n");
        b.append("From: ").append(msg.getHeader("from")).append("\r\n");
        b.append("To: ").append(msg.getHeader("to")).append("\r\n");
        b.append("Call-ID: ").append(msg.getHeader("call-id")).append("\r\n");
        b.append("CSeq: ").append(msg.getHeader("cseq")).append("\r\n");
        b.append("Content-Length: 0\r\n\r\n");
        send(b.toString());
    }

    private void optionsReply(SipMessage msg) {
        StringBuilder b = new StringBuilder();
        b.append("SIP/2.0 200 OK\r\n");
        b.append("Via: ").append(msg.getHeader("via")).append("\r\n");
        b.append("From: ").append(msg.getHeader("from")).append("\r\n");
        b.append("To: ").append(msg.getHeader("to")).append("\r\n");
        b.append("Call-ID: ").append(msg.getHeader("call-id")).append("\r\n");
        b.append("CSeq: ").append(msg.getHeader("cseq")).append("\r\n");
        b.append("Allow: INVITE,ACK,BYE,CANCEL,OPTIONS,REFER\r\nContent-Length: 0\r\n\r\n");
        send(b.toString());
    }

    private void processWaiting() {
        if (activeCall != null) return;
        WaitingCall wc = waiting.poll();
        if (wc != null) {
            listener.onWaitingCallsChanged(waiting.size());
            activeCall = wc.state;
            listener.onIncomingCall(wc.state.remoteNumber,
                new CallInfo(wc.state.callId, wc.state.remoteNumber, wc.state.remoteSdp, false));
        } else {
            listener.onWaitingCallsChanged(0);
        }
    }

    // ── SDP ──────────────────────────────────────────────────────────────

    public static String buildSdp(int port) {
        String ip = localIp();
        return "v=0\r\n" +
               "o=sado-sip " + System.currentTimeMillis() + " " + (System.currentTimeMillis()+1) + " IN IP4 " + ip + "\r\n" +
               "s=SadoSIP\r\nc=IN IP4 " + ip + "\r\nt=0 0\r\n" +
               "m=audio " + port + " RTP/AVP 0 8 101\r\n" +
               "a=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\n" +
               "a=rtpmap:101 telephone-event/8000\r\na=fmtp:101 0-15\r\na=sendrecv\r\n";
    }

    public static String parseSdpIp(String sdp) {
        if (sdp == null) return null;
        for (String l : sdp.split("\r?\n")) if (l.startsWith("c=IN IP4 ")) return l.substring(9).trim();
        return null;
    }

    public static int parseSdpPort(String sdp) {
        if (sdp == null) return 0;
        for (String l : sdp.split("\r?\n")) {
            if (l.startsWith("m=audio ")) {
                String[] p = l.split(" ");
                if (p.length > 1) try { return Integer.parseInt(p[1]); } catch(NumberFormatException e){}
            }
        }
        return 0;
    }

    public int getLocalRtpPort() { return activeCall != null ? activeCall.localRtpPort : 0; }
    public boolean isCallActive() { return activeCall != null; }
    public boolean isEstablished() { return activeCall != null && activeCall.established; }
    public SipManager.CallState getActiveCall() { return activeCall; }

    // ── Digest auth ───────────────────────────────────────────────────────────

    private String digest(String method, String uri, Map<String,String> ch) {
        String realm  = ch.getOrDefault("realm","");
        String nonce  = ch.getOrDefault("nonce","");
        String qop    = ch.get("qop");
        String opaque = ch.get("opaque");
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String ha1 = hex(md.digest((USER + ":" + realm + ":" + PASS).getBytes())); md.reset();
            String ha2 = hex(md.digest((method + ":" + uri).getBytes())); md.reset();
            StringBuilder a = new StringBuilder("Digest username=\"" ).append(USER)
                .append("\",realm=\"" ).append(realm).append("\",nonce=\"" ).append(nonce)
                .append("\",uri=\""   ).append(uri  ).append("\",");
            String resp;
            if (qop != null && qop.contains("auth")) {
                String nc = "00000001", cn = genTag().substring(0,8);
                resp = hex(md.digest((ha1+":"+nonce+":"+nc+":"+cn+":auth:"+ha2).getBytes()));
                a.append("qop=auth,nc=").append(nc).append(",cnonce=\"" ).append(cn).append("\",");
            } else {
                resp = hex(md.digest((ha1+":"+nonce+":"+ha2).getBytes()));
            }
            a.append("response=\"" ).append(resp).append("\"");
            if (opaque != null) a.append(",opaque=\"" ).append(opaque).append("\"");
            return a.toString();
        } catch (Exception e) { return ""; }
    }

    private Map<String,String> parseChallenge(String hdr) {
        Map<String,String> m = new HashMap<>();
        int sp = hdr.indexOf(' ');
        if (sp > 0) hdr = hdr.substring(sp+1);
        Matcher mat = Pattern.compile("(\\w+)\\s*=\\s*\"?([^\",]*)\"?").matcher(hdr);
        while (mat.find()) m.put(mat.group(1).toLowerCase(), mat.group(2));
        return m;
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private void send(String msg) {
        if (ws != null && ws.isOpen()) ws.send(msg);
        else listener.onLog("⚠️ WS yopiq");
    }

    private static String genCallId() { return UUID.randomUUID().toString().replace("-","") + "@sado"; }
    private static String genTag()    { return Long.toHexString(new SecureRandom().nextLong() & 0xFFFFFFFFL); }
    private static String branch()    { return "z9hG4bK" + Long.toHexString(new SecureRandom().nextLong() & 0xFFFFFFFFL); }
    private static int freePort()     { return (new Random().nextInt(4000) * 2) + 10000; }

    private static String localIp() {
        try { return InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception e) { return "127.0.0.1"; }
    }

    private String extractNum(String hdr) {
        if (hdr == null) return "Unknown";
        Matcher m = Pattern.compile("sip:([^@>\\s;]+)@").matcher(hdr);
        return m.find() ? m.group(1) : hdr;
    }

    private String extractUri(String hdr) {
        Matcher m = Pattern.compile("<([^>]+)>").matcher(hdr);
        if (m.find()) return m.group(1);
        return hdr.trim().split("[\\s;]")[0];
    }

    private String extractTag(String hdr) {
        Matcher m = Pattern.compile(";tag=([^;\\s,]+)").matcher(hdr);
        return m.find() ? m.group(1) : null;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static SSLContext trustAllCtx() throws Exception {
        TrustManager[] tm = { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new SecureRandom());
        return ctx;
    }
}
