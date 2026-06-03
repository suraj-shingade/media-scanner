package com.mediascanner.ui;

import com.mediascanner.config.AppConfig;
import javafx.scene.Scene;

public class DarkModeManager {

    private static final String LIGHT_CSS = "/css/mediascanner-light.css";
    private static final String DARK_CSS = "/css/mediascanner-dark.css";

    private Scene scene;
    private final AppConfig config;

    public DarkModeManager(AppConfig config) {
        this.config = config;
    }

    public void setScene(Scene scene) {
        this.scene = scene;
    }

    public void apply(boolean dark) {
        if (scene == null) return;
        scene.getStylesheets().clear();
        String css = dark ? DARK_CSS : LIGHT_CSS;
        var url = getClass().getResource(css);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
        config.setDarkMode(dark);
    }

    public void toggle() {
        apply(!config.isDarkMode());
    }

    public boolean isDark() {
        return config.isDarkMode();
    }
}
