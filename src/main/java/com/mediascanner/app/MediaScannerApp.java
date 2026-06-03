package com.mediascanner.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;

public class MediaScannerApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MediaScannerApp.class);

    @Override
    public void start(Stage primaryStage) throws IOException {
        log.info("MediaScanner starting");
        URL fxmlUrl = getClass().getResource("/fxml/main.fxml");
        if (fxmlUrl == null) {
            throw new IOException("Cannot find /fxml/main.fxml on classpath");
        }
        Parent root = FXMLLoader.load(fxmlUrl);
        Scene scene = new Scene(root, 1200, 800);
        URL cssUrl = getClass().getResource("/css/mediascanner.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        primaryStage.setTitle("MediaScanner");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.show();
        log.info("MediaScanner UI ready");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
