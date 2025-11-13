package de.simplehardware.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GreetServer {
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;

    // mapsByMaze: mazeId -> (playerId -> (posKey -> serializedCell))
    private final Map<String, Map<Integer, Map<String, String>>> mapsByMaze = new HashMap<>();
    // visited positions: mazeId -> (playerId -> set(posKey))
    private final Map<String, Map<Integer, Set<String>>> visitedByMaze = new HashMap<>();
    private Integer currentPlayer = null;
    private String currentMaze = null;
    private final Path storageDir;
    private final Path mazeConfigPath;
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "map-disk-writer");
        t.setDaemon(true);
        return t;
    });

    public GreetServer() {
        String dir = System.getenv().getOrDefault("MAP_STORAGE_DIR", "./maps");
        storageDir = Paths.get(dir).toAbsolutePath();
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create storage dir: " + storageDir, e);
        }
        String cfg = System.getenv().getOrDefault("MAZE_CONFIG_PATH", "/home/jeapi/Desktop/Studium/Semester3/SoftwareEntwurf/Env/FHMaze/gameSettings.json");
        mazeConfigPath = Paths.get(cfg).toAbsolutePath();
    }

    private String readMazeIdFromConfig() {
        try {
            if (Files.exists(mazeConfigPath)) {
                String content = Files.readString(mazeConfigPath, StandardCharsets.UTF_8);
                // very small and forgiving JSON extraction for "mazeId" : "..."
                int idx = content.indexOf("\"mazeId\"");
                if (idx >= 0) {
                    int colon = content.indexOf(':', idx);
                    if (colon > 0) {
                        int firstQuote = content.indexOf('"', colon);
                        if (firstQuote >= 0) {
                            int secondQuote = content.indexOf('"', firstQuote + 1);
                            if (secondQuote > firstQuote) {
                                return content.substring(firstQuote + 1, secondQuote);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read maze config " + mazeConfigPath + ": " + e.getMessage());
        }
        return null;
    }

    public void start(int port) {
        try {
            loadMapsFromStorage();
        } catch (IOException e) {
            System.err.println("Warning: failed to load maps: " + e.getMessage());
        }

        try {
            serverSocket = new ServerSocket(port);
            while (true) {
                clientSocket = serverSocket.accept();
                try {
                    out = new PrintWriter(clientSocket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    currentMaze = readMazeIdFromConfig();
                    String greeting;
                    while ((greeting = in.readLine()) != null) {
                        if (greeting.startsWith("PLAYER_ID ")) {
                            currentPlayer = Integer.parseInt(greeting.substring(10));
                            out.println("OK");
                        } else if ("GET_MAP".equals(greeting)) {
                            if (currentMaze == null) currentMaze = readMazeIdFromConfig();
                            if (currentPlayer != null && currentMaze != null) {
                                Map<Integer, Map<String, String>> perPlayer = mapsByMaze.getOrDefault(currentMaze, Map.of());
                                Map<String, String> cells = perPlayer.getOrDefault(currentPlayer, Map.of());
                                List<String> vals = new ArrayList<>(cells.values());
                                Map<Integer, Set<String>> perVisited = visitedByMaze.getOrDefault(currentMaze, Map.of());
                                Set<String> visited = perVisited.getOrDefault(currentPlayer, Set.of());
                                for (String v : visited) {
                                    vals.add(v + ",VISITED");
                                }
                                if (vals.isEmpty()) {
                                    out.println("NO_MAP");
                                } else {
                                    out.println(String.join(";", vals));
                                }
                            } else {
                                out.println("NO_PLAYER_OR_MAZE");
                            }
                        } else if (greeting.startsWith("UPDATE_MAP ")) {
                            if (currentMaze == null) currentMaze = readMazeIdFromConfig();
                            if (currentPlayer != null && currentMaze != null) {
                                String data = greeting.substring(11);
                                Map<Integer, Map<String, String>> perPlayer = mapsByMaze.computeIfAbsent(currentMaze, k -> new HashMap<>());
                                Map<String, String> cells = perPlayer.computeIfAbsent(currentPlayer, k -> new HashMap<>());
                                String[] updates = data.split(";");
                                Set<String> visitedSet = visitedByMaze.computeIfAbsent(currentMaze, k -> new HashMap<>()).computeIfAbsent(currentPlayer, k -> new HashSet<>());
                                for (String update : updates) {
                                    if (update == null) continue;
                                    update = update.trim();
                                    if (update.isEmpty()) continue;
                                    String[] parts = update.split(",");
                                    if (parts.length < 3) continue;
                                    String key = parts[0].trim() + "," + parts[1].trim();
                                    String type = parts[2].trim().toUpperCase();
                                    if ("VISITED".equals(type)) {
                                        visitedSet.add(key);
                                        continue;
                                    }
                                    String existing = cells.get(key);
                                    if (existing == null) {
                                        cells.put(key, update);
                                    } else {
                                        int pNew = priorityOf(update);
                                        int pExist = priorityOf(existing);
                                        if (pNew >= pExist) {
                                            cells.put(key, update);
                                        }
                                    }
                                }
                                // persist asynchronously to avoid blocking the request thread
                                Map<String, String> snapshot = new HashMap<>(cells);
                                Set<String> visitedSnapshot = new HashSet<>(visitedByMaze.getOrDefault(currentMaze, Map.of()).getOrDefault(currentPlayer, Set.of()));
                                diskWriter.submit(() -> {
                                    try {
                                        saveMapToDisk(currentMaze, currentPlayer, snapshot);
                                    } catch (IOException e) {
                                        System.err.println("Failed to persist map asynchronously: " + e.getMessage());
                                    }
                                    try {
                                        saveVisitedToDisk(currentMaze, currentPlayer, visitedSnapshot);
                                    } catch (IOException e) {
                                        System.err.println("Failed to persist visited asynchronously: " + e.getMessage());
                                    }
                                });
                                out.println("UPDATED");
                            } else {
                                out.println("NO_PLAYER_OR_MAZE");
                            }
                        } else {
                            out.println("hallo back");
                        }
                    }
                } catch (IOException e) {
                    // Log and continue accepting new clients instead of crashing the server
                    System.err.println("Client connection error: " + e.getMessage());
                } finally {
                    // Clean up client resources and reset per-connection state
                    try {
                        if (in != null) in.close();
                    } catch (IOException ignored) {}
                    if (out != null) out.close();
                    try {
                        if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
                    } catch (IOException ignored) {}
                    in = null;
                    out = null;
                    clientSocket = null;
                    currentPlayer = null;
                    currentMaze = null;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadMapsFromStorage() throws IOException {
        if (!Files.exists(storageDir) || !Files.isDirectory(storageDir)) return;
        try (var mazeDirs = Files.list(storageDir)) {
            mazeDirs.filter(Files::isDirectory).forEach(mazePath -> {
                String mazeId = mazePath.getFileName().toString();
                try {
                    Map<Integer, Map<String, String>> perPlayer = mapsByMaze.computeIfAbsent(mazeId, k -> new HashMap<>());
                    Map<Integer, Set<String>> perVisited = visitedByMaze.computeIfAbsent(mazeId, k -> new HashMap<>());
                    List<Path> fileList = Files.list(mazePath).collect(Collectors.toList());
                    fileList.stream().filter(p -> p.getFileName().toString().startsWith("player_") && p.getFileName().toString().endsWith(".map")).forEach(playerFile -> {
                        String fname = playerFile.getFileName().toString();
                        try {
                            int playerId = Integer.parseInt(fname.substring(7, fname.indexOf('.')));
                            List<String> lines = Files.readAllLines(playerFile, StandardCharsets.UTF_8);
                            Map<String, String> cells = perPlayer.computeIfAbsent(playerId, k -> new HashMap<>());
                            for (String line : lines) {
                                String s = line.trim();
                                if (s.isEmpty()) continue;
                                String[] parts = s.split(",");
                                if (parts.length < 2) continue;
                                String key = parts[0].trim() + "," + parts[1].trim();
                                cells.put(key, s);
                            }
                        } catch (Exception ex) {
                            System.err.println("Failed to load player file " + playerFile + ": " + ex.getMessage());
                        }
                    });
                    // also look for visited files player_<id>.visited
                    fileList.stream().filter(p -> p.getFileName().toString().startsWith("player_") && p.getFileName().toString().endsWith(".visited")).forEach(visitedFile -> {
                        String fname = visitedFile.getFileName().toString();
                        try {
                            int playerId = Integer.parseInt(fname.substring(7, fname.indexOf('.')));
                            List<String> lines = Files.readAllLines(visitedFile, StandardCharsets.UTF_8);
                            Set<String> visited = perVisited.computeIfAbsent(playerId, k -> new HashSet<>());
                            for (String line : lines) {
                                String s = line.trim();
                                if (s.isEmpty()) continue;
                                String[] parts = s.split(",");
                                if (parts.length < 2) continue;
                                String key = parts[0].trim() + "," + parts[1].trim();
                                visited.add(key);
                            }
                        } catch (Exception ex) {
                            System.err.println("Failed to load visited file " + visitedFile + ": " + ex.getMessage());
                        }
                    });
                } catch (Exception ex) {
                    System.err.println("Failed to load maze dir " + mazePath + ": " + ex.getMessage());
                }
            });
        }
    }

    private void saveVisitedToDisk(String mazeId, int playerId, Set<String> visited) throws IOException {
        Path mazePath = storageDir.resolve(mazeId);
        Files.createDirectories(mazePath);
        Path file = mazePath.resolve("player_" + playerId + ".visited");
        List<String> lines = visited.stream().sorted().collect(Collectors.toList());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private void saveMapToDisk(String mazeId, int playerId, Map<String, String> cells) throws IOException {
        Path mazePath = storageDir.resolve(mazeId);
        Files.createDirectories(mazePath);
        Path file = mazePath.resolve("player_" + playerId + ".map");
        // write deterministic order by sorting keys
        List<String> lines = cells.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static int priorityOf(String serialized) {
        if (serialized == null) return 0;
        String[] p = serialized.split(",");
        if (p.length < 3) return 0;
        String type = p[2].trim().toUpperCase();
        return switch (type) {
            case "FINISH" -> 4;
            case "FORM" -> 3;
            case "WALL" -> 2;
            default -> 1; // EMPTY or unknown
        };
    }

    public static void main(String[] args) {
        GreetServer server = new GreetServer();
        server.start(5555);
    }
}