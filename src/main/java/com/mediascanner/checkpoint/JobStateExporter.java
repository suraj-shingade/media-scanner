package com.mediascanner.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mediascanner.model.CheckpointState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JobStateExporter {

    private static final Logger log = LoggerFactory.getLogger(JobStateExporter.class);

    private final ObjectMapper mapper;

    public JobStateExporter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void export(CheckpointState state, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        mapper.writeValue(targetPath.toFile(), state);
        log.info("Job state exported to {}", targetPath);
    }

    /**
     * Deserializes and validates that the paths in the checkpoint are accessible.
     * Returns null if the state is invalid (paths inaccessible).
     */
    public CheckpointState importFrom(Path sourcePath) throws IOException {
        if (!Files.exists(sourcePath)) {
            throw new IOException("Checkpoint file not found: " + sourcePath);
        }
        CheckpointState state = mapper.readValue(sourcePath.toFile(), CheckpointState.class);

        if (state.getSourcePath() == null || state.getTargetPath() == null) {
            log.warn("Invalid checkpoint: missing source or target path");
            return null;
        }

        Path source = Paths.get(state.getSourcePath());
        Path target = Paths.get(state.getTargetPath());

        if (!Files.isDirectory(source)) {
            log.warn("Source path not accessible: {}", state.getSourcePath());
            return null;
        }
        if (!Files.isDirectory(target) && !Files.exists(target.getParent())) {
            log.warn("Target path not accessible: {}", state.getTargetPath());
            return null;
        }

        log.info("Loaded checkpoint for job {} with {} processed files",
            state.getJobId(), state.getProcessedFiles());
        return state;
    }
}
