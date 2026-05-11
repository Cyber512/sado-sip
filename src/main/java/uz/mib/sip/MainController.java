package uz.mib.sip;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.util.Duration;

import java.util.LinkedList;
import java.util.Map;

public class MainController implements SipManager.SipListener {

    private static final Map<Integer,String> FAIL_MSG = Map.ofEntries(
        Map.entry(400, "❌ Noto'g'ri so'rov"),
        Map.entry(403, "🚫 Ruxsat berilmagan"),
        Map.entry(404, "❌ Bunday raqam mavjud emas"),
        Map.entry(408, "⏱️ Vaqt tugadi"),
        Map.entry(480, "📵 Abonent vaqtincha mavjud emas"),
        Map.entry(486, "📞 Abonent band"),
        Map.entry(487, "❌ Qo'ng'iroq bekor qilindi"),
        Map.entry(500, "⚠️ Server xatosi"),
        Map.entry(503, "⚠️ Xizmat mavjud emas"),
        Map.entry(603, "❌ Qo'ng'iroq rad etildi")
    );

    private final ScrollPane root;
    private final VBox       content;
    private final SipManager sip;
    private       RtpManager rtp;

    // UI refs
    private Label     statusLabel;
    private HBox      statusBox;
    private TextField dialInput;
    private Button    callBtn, cancelBtn;
    private VBox      incomingPanel, callPanel, transferPanel, failurePanel;
    private Label     callerLabel, timerLabel, activeCallerLabel, failureLabel, failureCodeLabel, waitingLabel;
    private TextField transferInput;
    private Button    doTransferBtn;
    private Label     transferStatusLabel;
    private TextArea  logArea;
    private VBox      waitingBox;

    private Timeline      timerTimeline;
    private long          callStartMs;
    private final LinkedList<String> logLines = new LinkedList<>();

    public MainController() {
        sip     = new SipManager(this);
        content = new VBox(12);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("main-bg");
        buildUI(content);
        root = new ScrollPane(content);
        root.setFitToWidth(true);
        root.getStyleClass().add("main-bg");
        root.setStyle("-fx-background-color: #1a1a2e;");
    }

    public ScrollPane getRoot() { return root; }

    public void initialize() { sip.connect(); }

    public void shutdown() {
        sip.disconnect();
        if (rtp != null) rtp.stop();
    }

    // ─── Build UI ─────────────────────────────────────────────────────────────

    private void buildUI(VBox box) {
        box.getChildren().addAll(
            buildHeader(),
            buildStatusBox(),
            buildFailurePanel(),
            buildWaitingBox(),
            buildDialer(),
            incomingPanel  = buildIncomingPanel(),
            callPanel      = buildCallPanel(),
            transferPanel  = buildTransferPanel(),
            buildLogArea()
        );
    }

    private Node buildHeader() {
        Label lbl = new Label("📞 MIB SIP Telefon");
        lbl.getStyleClass().add("header-title");
        HBox hb = new HBox(lbl);
        hb.setAlignment(Pos.CENTER);
        hb.setPadding(new Insets(0, 0, 5, 0));
        return hb;
    }

    private Node buildStatusBox() {
        statusLabel = new Label("Ulanmoqda...");
        statusLabel.getStyleClass().add("status-label");
        statusBox = new HBox(statusLabel);
        statusBox.setAlignment(Pos.CENTER);
        statusBox.setPadding(new Insets(12, 20, 12, 20));
        statusBox.getStyleClass().addAll("card", "status-connecting");
        return statusBox;
    }

    private VBox buildFailurePanel() {
        failurePanel = new VBox(4);
        failureLabel     = new Label("❌ Qo'ng'iroq amalga oshmadi");
        failureCodeLabel = new Label("");
        failureLabel.getStyleClass().add("failure-msg");
        failureCodeLabel.getStyleClass().add("failure-code");
        failurePanel.getChildren().addAll(failureLabel, failureCodeLabel);
        failurePanel.setAlignment(Pos.CENTER);
        failurePanel.setPadding(new Insets(12, 20, 12, 20));
        failurePanel.getStyleClass().addAll("card", "failure-panel");
        failurePanel.setVisible(false);
        failurePanel.setManaged(false);
        return failurePanel;
    }

    private VBox buildWaitingBox() {
        waitingLabel = new Label("🕐 Kutayotgan: 0 ta");
        waitingLabel.getStyleClass().add("waiting-label");
        waitingBox = new VBox(waitingLabel);
        waitingBox.setAlignment(Pos.CENTER);
        waitingBox.setPadding(new Insets(10));
        waitingBox.getStyleClass().addAll("card", "waiting-card");
        waitingBox.setVisible(false);
        waitingBox.setManaged(false);
        return waitingBox;
    }

    private Node buildDialer() {
        dialInput = new TextField();
        dialInput.setPromptText("Raqamni kiriting (masalan: 1701)");
        dialInput.getStyleClass().add("dialer-input");
        dialInput.setOnAction(e -> onCall());

        callBtn   = makeBtn("📞 Qo'ng'iroq", "btn-call");
        cancelBtn = makeBtn("✖ Bekor",        "btn-cancel");
        cancelBtn.setVisible(false);
        cancelBtn.setManaged(false);

        callBtn.setOnAction(e -> onCall());
        cancelBtn.setOnAction(e -> onCancelCall());

        HBox row = new HBox(8, dialInput, callBtn, cancelBtn);
        HBox.setHgrow(dialInput, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(row);
        card.setPadding(new Insets(16));
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildIncomingPanel() {
        callerLabel = new Label("📞 Kiruvchi qo'ng'iroq...");
        callerLabel.getStyleClass().add("caller-label");

        Button ansBtn = makeBtn("✅ Javob", "btn-answer");
        Button rejBtn = makeBtn("❌ Rad",   "btn-reject");
        ansBtn.setOnAction(e -> onAnswer());
        rejBtn.setOnAction(e -> onReject());

        HBox btns = new HBox(10, ansBtn, rejBtn);
        btns.setAlignment(Pos.CENTER);

        VBox panel = new VBox(12, callerLabel, btns);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(20));
        panel.getStyleClass().addAll("card", "incoming-card");
        panel.setVisible(false);
        panel.setManaged(false);
        return panel;
    }

    private VBox buildCallPanel() {
        timerLabel       = new Label("00:00");
        activeCallerLabel = new Label("");
        timerLabel.getStyleClass().add("timer-label");
        activeCallerLabel.getStyleClass().add("active-caller-label");

        Button hangBtn      = makeBtn("📵 Tugatish",  "btn-hangup");
        Button transferToggle = makeBtn("🔀 O'tkazish", "btn-transfer");
        hangBtn.setOnAction(e -> onHangup());
        transferToggle.setOnAction(e -> toggleTransfer());

        HBox btns = new HBox(10, hangBtn, transferToggle);
        btns.setAlignment(Pos.CENTER);

        VBox panel = new VBox(8, timerLabel, activeCallerLabel, btns);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(20));
        panel.getStyleClass().addAll("card", "active-call-card");
        panel.setVisible(false);
        panel.setManaged(false);
        return panel;
    }

    private VBox buildTransferPanel() {
        transferInput = new TextField();
        transferInput.setPromptText("O'tkazish raqami (masalan: 1702)");
        transferInput.getStyleClass().add("dialer-input");
        transferInput.setOnAction(e -> onTransfer());

        doTransferBtn = makeBtn("🔀 O'tkazish", "btn-transfer");
        doTransferBtn.setOnAction(e -> onTransfer());

        HBox row = new HBox(8, transferInput, doTransferBtn);
        HBox.setHgrow(transferInput, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);

        transferStatusLabel = new Label("");
        transferStatusLabel.getStyleClass().add("transfer-status");
        transferStatusLabel.setVisible(false);
        transferStatusLabel.setManaged(false);

        VBox panel = new VBox(10, row, transferStatusLabel);
        panel.setPadding(new Insets(16));
        panel.getStyleClass().addAll("card", "transfer-card");
        panel.setVisible(false);
        panel.setManaged(false);
        return panel;
    }

    private Node buildLogArea() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(6);
        logArea.getStyleClass().add("log-area");
        logArea.setWrapText(false);

        Label lbl = new Label("Log");
        lbl.getStyleClass().add("log-label");

        VBox card = new VBox(6, lbl, logArea);
        card.setPadding(new Insets(12));
        card.getStyleClass().add("card");
        return card;
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    private void onCall() {
        String num = dialInput.getText().trim().replaceAll("[^0-9]","");
        if (num.isEmpty()) { showAlert("Raqam kiriting"); return; }
        if (sip.isCallActive()) { showAlert("Faol qo'ng'iroq bor"); return; }
        hideFailure();
        sip.makeCall(num);
        setVisible(cancelBtn, true);
        callBtn.setDisable(true);
    }

    private void onCancelCall() {
        sip.cancelOutgoing();
        setVisible(cancelBtn, false);
        callBtn.setDisable(false);
    }

    private void onAnswer() {
        sip.answer();
        setVisible(incomingPanel, false);
    }

    private void onReject() {
        sip.reject();
        setVisible(incomingPanel, false);
    }

    private void onHangup() {
        sip.hangup();
        stopTimer();
        setVisible(callPanel, false);
        setVisible(transferPanel, false);
        if (rtp != null) { rtp.stop(); rtp = null; }
    }

    private void onTransfer() {
        String num = transferInput.getText().trim().replaceAll("[^0-9]","");
        if (num.isEmpty()) { showAlert("Raqam kiriting"); return; }
        doTransferBtn.setDisable(true);
        showTransferStatus("⏳ O'tkazilmoqda...", "ts-progress");
        sip.transfer(num);
    }

    private void toggleTransfer() {
        boolean vis = transferPanel.isVisible();
        setVisible(transferPanel, !vis);
        if (!vis) {
            transferInput.clear();
            hideTransferStatus();
            doTransferBtn.setDisable(false);
            Platform.runLater(transferInput::requestFocus);
        }
    }

    // ─── SipListener ─────────────────────────────────────────────────────────

    @Override public void onConnecting() {
        ui(() -> setStatus("⏳ Ulanmoqda...", "status-connecting"));
    }

    @Override public void onRegistered() {
        ui(() -> setStatus("✅ Tayyor — Qo'ng'iroq kutilmoqda", "status-connected"));
    }

    @Override public void onUnregistered() {
        ui(() -> setStatus("❌ Ro'yxatdan o'tishda xatolik", "status-error"));
    }

    @Override public void onDisconnected(String reason) {
        ui(() -> setStatus("❌ Ulanish uzildi", "status-error"));
    }

    @Override public void onIncomingCall(String caller, SipManager.CallInfo info) {
        ui(() -> {
            callerLabel.setText("📞 Kiruvchi: " + caller);
            setVisible(incomingPanel, true);
            setStatus("📞 Kiruvchi qo'ng'iroq...", "status-calling");
            pulseNode(incomingPanel);
        });
    }

    @Override public void onCallProgress(int code, String reason, String sdp) {
        ui(() -> {
            if (code == 180) setStatus("📞 Jiringlamoqda...", "status-calling");
            else if (code == 183) {
                setStatus("📞 Ulanmoqda... (ovoz eshitilmoqda)", "status-calling");
                if (sdp != null) startRtp(sdp);
            }
        });
    }

    @Override public void onCallEstablished(SipManager.CallInfo info) {
        ui(() -> {
            setVisible(incomingPanel, false);
            setVisible(callPanel, true);
            setVisible(cancelBtn, false);
            callBtn.setDisable(false);
            activeCallerLabel.setText("📞 " + info.remoteNumber);
            startTimer();
            setStatus("✅ Qo'ng'iroq faol", "status-connected");
            if (info.remoteSdp != null) startRtp(info.remoteSdp);
        });
    }

    @Override public void onCallTerminated(int code, String reason) {
        ui(() -> {
            stopTimer();
            setVisible(callPanel, false);
            setVisible(incomingPanel, false);
            setVisible(transferPanel, false);
            setVisible(cancelBtn, false);
            callBtn.setDisable(false);
            if (rtp != null) { rtp.stop(); rtp = null; }
            if (code > 0 && code != 200) {
                showFailure(code, reason);
            } else {
                setStatus("✅ Tayyor — Qo'ng'iroq kutilmoqda", "status-connected");
            }
        });
    }

    @Override public void onTransferResult(boolean ok, String msg) {
        ui(() -> {
            doTransferBtn.setDisable(false);
            showTransferStatus(ok ? "✅ " + msg : "❌ " + msg, ok ? "ts-success" : "ts-failed");
            if (ok) {
                onHangup();
                Platform.runLater(() -> setVisible(transferPanel, false));
            }
        });
    }

    @Override public void onWaitingCallsChanged(int count) {
        ui(() -> {
            waitingLabel.setText("🕐 Kutayotgan: " + count + " ta");
            setVisible(waitingBox, count > 0);
        });
    }

    @Override public void onLog(String message) {
        ui(() -> {
            logLines.addFirst(java.time.LocalTime.now().toString().substring(0,8) + " " + message);
            if (logLines.size() > 50) logLines.removeLast();
            logArea.setText(String.join("\n", logLines));
            logArea.positionCaret(0);
        });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void startRtp(String remoteSdp) {
        String host = SipManager.parseSdpIp(remoteSdp);
        int    port = SipManager.parseSdpPort(remoteSdp);
        int localPort = sip.getLocalRtpPort();
        if (host == null || port == 0 || localPort == 0) return;
        if (rtp != null) rtp.stop();
        rtp = new RtpManager(msg -> onLog("RTP: " + msg));
        rtp.start(localPort, host, port);
    }

    private void startTimer() {
        callStartMs = System.currentTimeMillis();
        timerTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long s = (System.currentTimeMillis() - callStartMs) / 1000;
            timerLabel.setText(String.format("%02d:%02d", s/60, s%60));
        }));
        timerTimeline.setCycleCount(Animation.INDEFINITE);
        timerTimeline.play();
    }

    private void stopTimer() {
        if (timerTimeline != null) { timerTimeline.stop(); timerTimeline = null; }
        timerLabel.setText("00:00");
    }

    private void setStatus(String msg, String cssClass) {
        statusLabel.setText(msg);
        statusBox.getStyleClass().removeAll("status-connecting","status-connected","status-error","status-calling","status-failed");
        statusBox.getStyleClass().add(cssClass);
    }

    private void showFailure(int code, String reason) {
        String msg = FAIL_MSG.getOrDefault(code, "❌ Qo'ng'iroq amalga oshmadi");
        failureLabel.setText(msg);
        failureCodeLabel.setText("[" + code + " " + (reason != null ? reason : "") + "]");
        setVisible(failurePanel, true);
        setStatus(msg, "status-failed");
        new Timeline(new KeyFrame(Duration.seconds(5), e -> ui(() -> {
            hideFailure();
            if (sip.isCallActive()) setStatus("✅ Tayyor — Qo'ng'iroq kutilmoqda", "status-connected");
        }))).play();
    }

    private void hideFailure() { setVisible(failurePanel, false); }

    private void showTransferStatus(String msg, String css) {
        transferStatusLabel.setText(msg);
        transferStatusLabel.getStyleClass().removeAll("ts-progress","ts-success","ts-failed");
        transferStatusLabel.getStyleClass().add(css);
        setVisible(transferStatusLabel, true);
        if ("ts-success".equals(css)) new Timeline(new KeyFrame(Duration.seconds(3), e -> ui(() -> hideTransferStatus()))).play();
    }

    private void hideTransferStatus() { setVisible(transferStatusLabel, false); }

    private static void setVisible(Node n, boolean v) { n.setVisible(v); n.setManaged(v); }
    private static void ui(Runnable r) { if (javafx.application.Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r); }

    private static Button makeBtn(String text, String css) {
        Button b = new Button(text);
        b.getStyleClass().addAll("btn", css);
        return b;
    }

    private static void showAlert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private static void pulseNode(Node n) {
        ScaleTransition st = new ScaleTransition(Duration.millis(300), n);
        st.setFromX(0.97); st.setToX(1.0); st.setFromY(0.97); st.setToY(1.0);
        st.setCycleCount(3); st.setAutoReverse(true); st.play();
    }
}
