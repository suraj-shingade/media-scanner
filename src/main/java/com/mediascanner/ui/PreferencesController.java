package com.mediascanner.ui;

import com.mediascanner.config.AppConfig;
import com.mediascanner.model.IgnoreRule;
import com.mediascanner.model.Job;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.util.List;
import java.util.stream.Collectors;

public class PreferencesController {

    @FXML private TextField threadCountField;
    @FXML private CheckBox highPriorityCheck;
    @FXML private CheckBox deepValidationCheck;
    @FXML private TextField imageSizeField;
    @FXML private TextField videoSizeField;
    @FXML private ComboBox<String> folderPatternCombo;
    @FXML private ComboBox<String> duplicatePolicyCombo;
    @FXML private ListView<String> ignorePatternsListView;
    @FXML private TextField newPatternField;

    private AppConfig appConfig;

    public void init(AppConfig config) {
        this.appConfig = config;

        threadCountField.setText(String.valueOf(config.getWorkerThreadCount()));
        highPriorityCheck.setSelected(config.isHighPriorityMode());
        deepValidationCheck.setSelected(config.isDeepValidationEnabled());
        imageSizeField.setText(String.valueOf(config.getImageSizeThresholdKb()));
        videoSizeField.setText(String.valueOf(config.getVideoSizeThresholdKb()));

        folderPatternCombo.getItems().setAll("YYYY/MMM", "YYYY/MM", "YYYY/MMM/DD", "YYYY/MM/DD");
        folderPatternCombo.setValue(mapPatternToDisplay(config.getFolderPattern()));

        duplicatePolicyCombo.getItems().setAll("Skip", "Move to /_duplicates", "Keep Both");
        duplicatePolicyCombo.setValue(mapPolicyToDisplay(config.getDuplicatePolicy()));

        ignorePatternsListView.getItems().setAll(
            config.getIgnoreRules().stream()
                .filter(r -> r.getSource() == IgnoreRule.Source.USER_DEFINED)
                .map(IgnoreRule::getPattern)
                .collect(Collectors.toList()));
    }

    @FXML private void onOk() {
        if (!validate()) return;

        appConfig.setWorkerThreadCount(Integer.parseInt(threadCountField.getText().trim()));
        appConfig.setHighPriorityMode(highPriorityCheck.isSelected());
        appConfig.setDeepValidationEnabled(deepValidationCheck.isSelected());
        appConfig.setImageSizeThresholdKb(Integer.parseInt(imageSizeField.getText().trim()));
        appConfig.setVideoSizeThresholdKb(Integer.parseInt(videoSizeField.getText().trim()));
        appConfig.setFolderPattern(mapDisplayToPattern(folderPatternCombo.getValue()));
        appConfig.setDuplicatePolicy(mapDisplayToPolicy(duplicatePolicyCombo.getValue()));

        // Sync user ignore patterns
        List<String> uiPatterns = ignorePatternsListView.getItems();
        List<String> existing = appConfig.getIgnoreRules().stream()
            .filter(r -> r.getSource() == IgnoreRule.Source.USER_DEFINED)
            .map(IgnoreRule::getPattern)
            .collect(Collectors.toList());
        for (String p : existing) appConfig.removeIgnorePattern(p);
        for (String p : uiPatterns) appConfig.addIgnorePattern(p);

        appConfig.save();
        closeStage();
    }

    @FXML private void onCancel() {
        closeStage();
    }

    @FXML private void onAddPattern() {
        String pattern = newPatternField.getText().trim();
        if (!pattern.isEmpty() && !ignorePatternsListView.getItems().contains(pattern)) {
            ignorePatternsListView.getItems().add(pattern);
            newPatternField.clear();
        }
    }

    @FXML private void onRemovePattern() {
        String selected = ignorePatternsListView.getSelectionModel().getSelectedItem();
        if (selected != null) ignorePatternsListView.getItems().remove(selected);
    }

    private boolean validate() {
        boolean valid = true;

        try {
            int threads = Integer.parseInt(threadCountField.getText().trim());
            if (threads < 1) throw new NumberFormatException();
            threadCountField.setStyle("");
        } catch (NumberFormatException e) {
            threadCountField.setStyle("-fx-border-color: red;");
            valid = false;
        }

        try {
            int imgKb = Integer.parseInt(imageSizeField.getText().trim());
            if (imgKb < 1) throw new NumberFormatException();
            imageSizeField.setStyle("");
        } catch (NumberFormatException e) {
            imageSizeField.setStyle("-fx-border-color: red;");
            valid = false;
        }

        try {
            int vidKb = Integer.parseInt(videoSizeField.getText().trim());
            if (vidKb < 1) throw new NumberFormatException();
            videoSizeField.setStyle("");
        } catch (NumberFormatException e) {
            videoSizeField.setStyle("-fx-border-color: red;");
            valid = false;
        }

        return valid;
    }

    private void closeStage() {
        Stage stage = (Stage) threadCountField.getScene().getWindow();
        stage.close();
    }

    private String mapPatternToDisplay(Job.FolderPattern p) {
        return switch (p) {
            case YYYY_MM -> "YYYY/MM";
            case YYYY_MMM_DD -> "YYYY/MMM/DD";
            case YYYY_MM_DD -> "YYYY/MM/DD";
            default -> "YYYY/MMM";
        };
    }

    private Job.FolderPattern mapDisplayToPattern(String s) {
        return switch (s) {
            case "YYYY/MM" -> Job.FolderPattern.YYYY_MM;
            case "YYYY/MMM/DD" -> Job.FolderPattern.YYYY_MMM_DD;
            case "YYYY/MM/DD" -> Job.FolderPattern.YYYY_MM_DD;
            default -> Job.FolderPattern.YYYY_MMM;
        };
    }

    private String mapPolicyToDisplay(Job.DuplicatePolicy p) {
        return switch (p) {
            case MOVE_TO_BUCKET -> "Move to /_duplicates";
            case KEEP_BOTH -> "Keep Both";
            default -> "Skip";
        };
    }

    private Job.DuplicatePolicy mapDisplayToPolicy(String s) {
        if (s == null) return Job.DuplicatePolicy.SKIP;
        if (s.startsWith("Move")) return Job.DuplicatePolicy.MOVE_TO_BUCKET;
        if (s.startsWith("Keep")) return Job.DuplicatePolicy.KEEP_BOTH;
        return Job.DuplicatePolicy.SKIP;
    }
}
