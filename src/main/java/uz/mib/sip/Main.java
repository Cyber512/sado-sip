package uz.mib.sip;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        URL fxmlUrl = getClass().getResource("/uz/mib/sip/main.fxml");
        if (fxmlUrl == null) {
            throw new RuntimeException("Cannot find main.fxml resource");
        }
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        javafx.scene.Parent root = loader.load();

        Scene scene = new Scene(root, 480, 680);
        URL cssUrl = getClass().getResource("/uz/mib/sip/style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        primaryStage.setTitle("MIB SIP - Sado");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(400);
        primaryStage.setMinHeight(500);
        primaryStage.setOnCloseRequest(event -> {
            uz.mib.sip.ui.MainController controller = loader.getController();
            if (controller != null) {
                controller.shutdown();
            }
            javafx.application.Platform.exit();
            System.exit(0);
        });
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
