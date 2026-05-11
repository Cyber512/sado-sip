package uz.mib.sip;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class MainApp extends Application {

    private MainController controller;

    @Override
    public void start(Stage primaryStage) {
        controller = new MainController();
        Scene scene = new Scene(controller.getRoot(), 460, 720);
        scene.getStylesheets().add(
            getClass().getResource("/uz/mib/sip/styles.css").toExternalForm()
        );
        primaryStage.setTitle("MIB SIP Telefon");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.setOnCloseRequest(e -> {
            controller.shutdown();
            Platform.exit();
            System.exit(0);
        });
        primaryStage.show();
        controller.initialize();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
