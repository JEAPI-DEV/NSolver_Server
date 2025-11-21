package de.simplehardware.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Responsible for loading and persisting player maps to disk.
 */
public class IO {
    private final Path storageDir;
    private final ConsoleLogger logger;

    public IO(Path storageDir, ConsoleLogger logger) {
        this.storageDir = storageDir;
        this.logger = logger;
        ensureDirectoryExists(this.storageDir);
    }

    public Map<String, Map<Integer, Map<String, String>>> loadAll() {
        Map<String, Map<Integer, Map<String, String>>> mapsByMaze = new ConcurrentHashMap<>();

        if (!Files.exists(storageDir) || !Files.isDirectory(storageDir)) return mapsByMaze;

        try (Stream<Path> mazeDirs = Files.list(storageDir)) {
            mazeDirs.filter(Files::isDirectory).forEach(mazePath -> {
                String mazeId = mazePath.getFileName().toString();
                Map<Integer, Map<String, String>> perPlayer = mapsByMaze.computeIfAbsent(mazeId, k -> new ConcurrentHashMap<>());

                try (Stream<Path> playerFiles = Files.list(mazePath)) {
                    playerFiles
                            .filter(p -> p.getFileName().toString().matches("player_\\d+\\.map"))
                            .forEach(file -> {
                                String fileName = file.getFileName().toString();
                                int playerId;
                                try {
                                    playerId = Integer.parseInt(fileName.substring(7, fileName.indexOf('.')));
                                } catch (NumberFormatException e) {
                                    logger.warn("Invalid player file name", "file=" + fileName);
                                    return;
                                }

                                try {
                                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                                    Map<String, String> cells = perPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
    
                                    for (String line : lines) {
                                        String trimmed = line.trim();
                                        if (trimmed.isEmpty()) continue;
                                        String key = MapParser.extractKey(trimmed);
                                        if (key != null) cells.put(key, trimmed);
                                    }
    
                                    int finishCount = 0;
                                    int formCount = 0;
                                    for (String cellLine : cells.values()) {
                                        int firstComma = cellLine.indexOf(',');
                                        if (firstComma != -1) {
                                            int secondComma = cellLine.indexOf(',', firstComma + 1);
                                            if (secondComma != -1) {
                                                int pri = MapParser.getCellTypePriority(cellLine, secondComma);
                                                if (pri == 4) finishCount++;
                                                else if (pri == 3) formCount++;
                                            }
                                        }
                                    }
                                    logger.info("Loaded player map from disk", "mazeId=" + mazeId, "playerId=" + playerId, "total=" + cells.size(), "finishes=" + finishCount, "forms=" + formCount);
                                } catch (IOException e) {
                                    logger.warn("Failed to load player map", "file=" + file + " err=" + e.getMessage());
                                }
                            });
                } catch (IOException e) {
                    logger.warn("Failed to list player files", "maze=" + mazePath + " err=" + e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.warn("Failed to list maze directories", "err=" + e.getMessage());
        }

        return mapsByMaze;
    }

    public void persistPlayerMap(String mazeId, int playerId, Map<String, String> cells) {
        Path mazePath = storageDir.resolve(mazeId);
        ensureDirectoryExists(mazePath);
        Path file = mazePath.resolve("player_" + playerId + ".map");

        try {
            List<String> sortedLines = new ArrayList<>(cells.values());
            Collections.sort(sortedLines);
            Files.write(file, sortedLines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Failed to persist map", "maze=" + mazeId, "player=" + playerId, "err=" + e.getMessage());
        }
    }

    /**
     * Read mazeId from a simple JSON config file. Returns "default_maze" on error.
     */
    public String readMazeId(Path mazeConfigPath) {
        try {
            if (Files.exists(mazeConfigPath)) {
                String content = Files.readString(mazeConfigPath, StandardCharsets.UTF_8);
                int startIndex = content.indexOf("\"mazeId\"");
                if (startIndex != -1) {
                    startIndex = content.indexOf('"', startIndex + 8);
                    if (startIndex != -1) {
                        int endIndex = content.indexOf('"', startIndex + 1);
                        if (endIndex != -1) {
                            return content.substring(startIndex + 1, endIndex);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read maze config", "err=" + e.getMessage());
        }
        return "default_maze";
    }

    private void ensureDirectoryExists(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.warn("failed to create directory", "dir=" + dir, "err=" + e.getMessage());
        }
    }
}
