package com.mediascanner.config;

import com.mediascanner.model.IgnoreRule;
import com.mediascanner.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.mediascanner";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.properties";

    private static final List<String> DEFAULT_IGNORE_PATTERNS = Arrays.asList(
        "Thumbs.db", ".DS_Store", "desktop.ini", "._*", ".cache", ".tmp", ".temp"
    );

    private int workerThreadCount;
    private int imageSizeThresholdKb;
    private int videoSizeThresholdKb;
    private boolean highPriorityMode;
    private Job.FolderPattern folderPattern;
    private Job.DuplicatePolicy duplicatePolicy;
    private List<IgnoreRule> ignoreRules;

    private final Properties props = new Properties();

    public AppConfig() {
        ensureConfigDir();
        load();
    }

    private void ensureConfigDir() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private void load() {
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            } catch (IOException e) {
                log.warn("Failed to load config, using defaults: {}", e.getMessage());
            }
        }

        int cores = Runtime.getRuntime().availableProcessors();
        int rawCount = Integer.parseInt(props.getProperty("worker.thread.count", "0"));
        workerThreadCount = rawCount > 0 ? rawCount : cores * 2;

        imageSizeThresholdKb = Integer.parseInt(
            props.getProperty("validation.image.min.kb", "10"));
        videoSizeThresholdKb = Integer.parseInt(
            props.getProperty("validation.video.min.kb", "100"));
        highPriorityMode = Boolean.parseBoolean(
            props.getProperty("performance.high.priority", "false"));

        String patternStr = props.getProperty("folder.pattern", "YYYY_MMM");
        try {
            folderPattern = Job.FolderPattern.valueOf(patternStr);
        } catch (IllegalArgumentException e) {
            folderPattern = Job.FolderPattern.YYYY_MMM;
        }

        String policyStr = props.getProperty("duplicate.policy", "SKIP");
        try {
            duplicatePolicy = Job.DuplicatePolicy.valueOf(policyStr);
        } catch (IllegalArgumentException e) {
            duplicatePolicy = Job.DuplicatePolicy.SKIP;
        }

        ignoreRules = loadIgnoreRules();
    }

    private List<IgnoreRule> loadIgnoreRules() {
        List<IgnoreRule> rules = new ArrayList<>();
        for (String pattern : DEFAULT_IGNORE_PATTERNS) {
            rules.add(new IgnoreRule(pattern, IgnoreRule.Source.DEFAULT));
        }
        String userPatterns = props.getProperty("ignore.patterns.user", "");
        if (!userPatterns.isBlank()) {
            for (String pattern : userPatterns.split(",")) {
                String trimmed = pattern.trim();
                if (!trimmed.isEmpty()) {
                    rules.add(new IgnoreRule(trimmed, IgnoreRule.Source.USER_DEFINED));
                }
            }
        }
        return rules;
    }

    public void save() {
        props.setProperty("worker.thread.count", String.valueOf(workerThreadCount));
        props.setProperty("validation.image.min.kb", String.valueOf(imageSizeThresholdKb));
        props.setProperty("validation.video.min.kb", String.valueOf(videoSizeThresholdKb));
        props.setProperty("performance.high.priority", String.valueOf(highPriorityMode));
        props.setProperty("folder.pattern", folderPattern.name());
        props.setProperty("duplicate.policy", duplicatePolicy.name());

        String userPatterns = ignoreRules.stream()
            .filter(r -> r.getSource() == IgnoreRule.Source.USER_DEFINED)
            .map(IgnoreRule::getPattern)
            .collect(Collectors.joining(","));
        props.setProperty("ignore.patterns.user", userPatterns);

        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            props.store(fos, "MediaScanner Configuration");
        } catch (IOException e) {
            log.error("Failed to save config: {}", e.getMessage());
        }
    }

    public void addIgnorePattern(String pattern) {
        ignoreRules.add(new IgnoreRule(pattern, IgnoreRule.Source.USER_DEFINED));
        save();
    }

    public void removeIgnorePattern(String pattern) {
        ignoreRules.removeIf(r -> r.getPattern().equals(pattern)
            && r.getSource() == IgnoreRule.Source.USER_DEFINED);
        save();
    }

    public Path getDbPath() {
        return Paths.get(CONFIG_DIR, "mediascanner.db");
    }

    public Path getJobsDir() {
        return Paths.get(CONFIG_DIR, "jobs");
    }

    public int getWorkerThreadCount() { return workerThreadCount; }
    public void setWorkerThreadCount(int workerThreadCount) {
        this.workerThreadCount = workerThreadCount;
        save();
    }
    public int getImageSizeThresholdKb() { return imageSizeThresholdKb; }
    public void setImageSizeThresholdKb(int imageSizeThresholdKb) {
        this.imageSizeThresholdKb = imageSizeThresholdKb;
        save();
    }
    public int getVideoSizeThresholdKb() { return videoSizeThresholdKb; }
    public void setVideoSizeThresholdKb(int videoSizeThresholdKb) {
        this.videoSizeThresholdKb = videoSizeThresholdKb;
        save();
    }
    public boolean isHighPriorityMode() { return highPriorityMode; }
    public void setHighPriorityMode(boolean highPriorityMode) {
        this.highPriorityMode = highPriorityMode;
        save();
    }
    public Job.FolderPattern getFolderPattern() { return folderPattern; }
    public void setFolderPattern(Job.FolderPattern folderPattern) {
        this.folderPattern = folderPattern;
        save();
    }
    public Job.DuplicatePolicy getDuplicatePolicy() { return duplicatePolicy; }
    public void setDuplicatePolicy(Job.DuplicatePolicy duplicatePolicy) {
        this.duplicatePolicy = duplicatePolicy;
        save();
    }
    public List<IgnoreRule> getIgnoreRules() { return ignoreRules; }
}
