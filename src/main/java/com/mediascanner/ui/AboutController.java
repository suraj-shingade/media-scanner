package com.mediascanner.ui;

import com.mediascanner.config.AppConfig;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.stage.Stage;

public class AboutController {

    @FXML private Label versionLabel;

    public void init(AppConfig config) {
        versionLabel.setText("Version " + config.getAppVersion());
    }

    @FXML private void onClose() {
        Stage stage = (Stage) versionLabel.getScene().getWindow();
        stage.close();
    }
}
