package uz.mib.sip.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import uz.mib.sip.SipEngine;

import java.net.URL;
import java.util.ResourceBundle;

public class MainController implements Initializable {

    // --- SIP Config ---
    static final String SIP_USER   = "1700";
    static final String SIP_PASS   = "aa1700aa";
    static final String SIP_DOMAIN = "lite-sip.mib.uz";
    static final String SIP_HOST   = "lite-sip.mib.uz";
    static final int    SIP_PORT   = 5060;

    // --- FXML bindings ---
    @FXML private Label statusBadge;
    @FXML private Label statusLabel;
    @FXML private HBox  failureBanner;
    @FXML private Label failureMessage;
    @FXML private Label waitingCallsLabel;

    @FXML private TextField dialNumber;
    @FXML private Button    callBtn;
    @FXML private Button    cancelBtn;

    @FXML private VBox  currentCallPane;
    @FXML private Label callTimerLabel;
    @FXML private Label currentNumberLabel;

    @FXML private VBox  incomingCallPane;
    @FXML private Label callerLabel;
    @FXML private Button answerBtn;
    @FXML private Button rejectBtn;

    @FXML private HBox   callControlsPane;
    @FXML private Button hangupBtn;
    @FXML private Button transferBtn;

    @FXML private VBox   transferPane;
    @FXML private TextField transferNumberField;
    @FXML private Button    doTransferBtn;
    @FXML private Label     transferStatus;

    @FXML private VBox debugLog;
    @FXML private ScrollPane debugScrollPane;

    // --- State ---
    private SipEngine engine;
    private Timeline  callTimer;
    private int       callSeconds = 0;
    private String    currentCallee = "";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Initial UI state
        failureBanner.setVisible(false);
        failureBanner.setManaged(false);
        waitingCallsLabel.setVisible(false);
        waitingCallsLabel.setManaged(false);
        cancelBtn.setVisible(false);
        cancelBtn.setManaged(false);
        currentCallPane.setVisible(false);
        currentCallPane.setManaged(false);
        incomingCallPane.setVisible(false);
        incomingCallPane.setManaged(false);
        callControlsPane.setVisible(false);
        callControlsPane.setManaged(false);
        transferPane.setVisible(false);
        transferPane.setManaged(false);

        setStatus("IDLE", "Ulanilmagan");

        // Create engine and connect
        engine = new SipEngine(SIP_USER, SIP_PASS, SIP_DOMAIN, SIP_HOST, SIP_PORT);
        engine.setListener(new SipEngine.Listener() {
            @Override
            public void onState(SipEngine.State s, String detail) {
                handleStateChange(s, detail);
            }

            @Override
            public void onIncomingCall(String from, String callId) {
                showIncomingCall(from);
            }

            @Override
            public void onCallConnected(String remoteIp, int remotePort) {
                startCallTimer();
            }

            @Override
            public void onCallEnded(int code, String reason) {
                handleCallEnded(code, reason);
            }

            @Override
            public void onLog(String msg) {
                appendLog(msg);
            }
        });
        engine.connect();
    }

    // ---- Event handlers ----

    @FXML
    private void onCallBtn() {
        String number = dialNumber.getText().trim();
        if (number.isEmpty()) return;
        currentCallee = number;
        engine.makeCall(number);
        hideBanner();
    }

    @FXML
    private void onCancelBtn() {
        engine.hangup();
    }

    @FXML
    private void onAnswerBtn() {
        engine.answerCall();
        hideIncomingPane();
    }

    @FXML
    private void onRejectBtn() {
        engine.rejectCall();
        hideIncomingPane();
    }

    @FXML
    private void onHangupBtn() {
        engine.hangup();
    }

    @FXML
    private void onTransferBtn() {
        boolean show = !transferPane.isVisible();
        transferPane.setVisible(show);
        transferPane.setManaged(show);
        if (!show) {
            transferStatus.setText("");
        }
    }

    @FXML
    private void onDoTransferBtn() {
        String target = transferNumberField.getText().trim();
        if (target.isEmpty()) {
            transferStatus.setText("Raqam kiriting");
            return;
        }
        engine.transfer(target);
        transferStatus.setText("Transfer yuborildi: " + target);
        transferNumberField.clear();
    }

    // ---- State/UI updates ----

    private void handleStateChange(SipEngine.State s, String detail) {
        statusLabel.setText(detail);
        switch (s) {
            case IDLE:
                setStatus("IDLE", "Ulanilmagan");
                hideCallControls();
                hideIncomingPane();
                break;
            case CONNECTING:
                setStatus("CONNECTING", "Ulanmoqda...");
                break;
            case REGISTERING:
                setStatus("REGISTERING", "Ro'yxatdan o'tmoqda...");
                break;
            case REGISTERED:
                setStatus("REGISTERED", "Tayyor");
                hideCallControls();
                hideIncomingPane();
                break;
            case CALLING:
                setStatus("CALLING", "Jiringlayapti...");
                cancelBtn.setVisible(true);
                cancelBtn.setManaged(true);
                callBtn.setVisible(false);
                callBtn.setManaged(false);
                break;
            case RINGING_IN:
                setStatus("RINGING_IN", "Kiruvchi qo'ng'iroq");
                break;
            case ACTIVE:
                setStatus("ACTIVE", "Faol qo'ng'iroq");
                showCallControls();
                break;
        }
    }

    private void showIncomingCall(String from) {
        callerLabel.setText(from);
        incomingCallPane.setVisible(true);
        incomingCallPane.setManaged(true);
    }

    private void hideIncomingPane() {
        incomingCallPane.setVisible(false);
        incomingCallPane.setManaged(false);
    }

    private void showCallControls() {
        cancelBtn.setVisible(false);
        cancelBtn.setManaged(false);
        callBtn.setVisible(true);
        callBtn.setManaged(true);

        currentNumberLabel.setText(currentCallee.isEmpty() ? "Noma'lum" : currentCallee);
        currentCallPane.setVisible(true);
        currentCallPane.setManaged(true);
        callControlsPane.setVisible(true);
        callControlsPane.setManaged(true);
    }

    private void hideCallControls() {
        cancelBtn.setVisible(false);
        cancelBtn.setManaged(false);
        callBtn.setVisible(true);
        callBtn.setManaged(true);

        currentCallPane.setVisible(false);
        currentCallPane.setManaged(false);
        callControlsPane.setVisible(false);
        callControlsPane.setManaged(false);
        transferPane.setVisible(false);
        transferPane.setManaged(false);
        stopCallTimer();
    }

    private void handleCallEnded(int code, String reason) {
        hideCallControls();
        hideIncomingPane();
        stopCallTimer();
        if (code != 0) {
            showBanner("Qo'ng'iroq xatosi: " + code + " " + reason);
        }
    }

    private void startCallTimer() {
        stopCallTimer();
        callSeconds = 0;
        callTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            callSeconds++;
            int h = callSeconds / 3600;
            int m = (callSeconds % 3600) / 60;
            int s = callSeconds % 60;
            if (h > 0) {
                callTimerLabel.setText(String.format("%d:%02d:%02d", h, m, s));
            } else {
                callTimerLabel.setText(String.format("%02d:%02d", m, s));
            }
        }));
        callTimer.setCycleCount(Timeline.INDEFINITE);
        callTimer.play();
    }

    private void stopCallTimer() {
        if (callTimer != null) {
            callTimer.stop();
            callTimer = null;
        }
        callTimerLabel.setText("00:00");
    }

    private void setStatus(String cssState, String text) {
        statusBadge.getStyleClass().removeAll(
            "status-connecting", "status-registered", "status-calling", "status-error", "status-active"
        );
        switch (cssState) {
            case "IDLE":
                statusBadge.getStyleClass().add("status-error");
                statusBadge.setText("Offline");
                break;
            case "CONNECTING":
            case "REGISTERING":
                statusBadge.getStyleClass().add("status-connecting");
                statusBadge.setText("Ulanmoqda...");
                break;
            case "REGISTERED":
                statusBadge.getStyleClass().add("status-registered");
                statusBadge.setText("Online");
                break;
            case "CALLING":
            case "RINGING_IN":
                statusBadge.getStyleClass().add("status-calling");
                statusBadge.setText("Qo'ng'iroq");
                break;
            case "ACTIVE":
                statusBadge.getStyleClass().add("status-registered");
                statusBadge.setText("Faol");
                break;
            default:
                statusBadge.getStyleClass().add("status-error");
                statusBadge.setText("Xato");
        }
    }

    private void showBanner(String message) {
        failureMessage.setText(message);
        failureBanner.setVisible(true);
        failureBanner.setManaged(true);
        // Auto-hide after 5 seconds
        Timeline hide = new Timeline(new KeyFrame(Duration.seconds(5), e -> hideBanner()));
        hide.play();
    }

    private void hideBanner() {
        failureBanner.setVisible(false);
        failureBanner.setManaged(false);
    }

    private void appendLog(String msg) {
        Label lbl = new Label(msg);
        lbl.getStyleClass().add("debug-line");
        lbl.setWrapText(true);
        lbl.setMaxWidth(Double.MAX_VALUE);
        debugLog.getChildren().add(0, lbl);
        // Keep max 100 entries
        if (debugLog.getChildren().size() > 100) {
            debugLog.getChildren().remove(debugLog.getChildren().size() - 1);
        }
    }

    public void shutdown() {
        if (engine != null) {
            engine.shutdown();
        }
        if (callTimer != null) {
            callTimer.stop();
        }
    }
}
