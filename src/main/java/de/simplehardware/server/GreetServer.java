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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class GreetServer {
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;

    // mapsByMaze: mazeId -> (playerId -> (posKey -> serializedCell))
    private final Map<String, Map<Integer, Map<String, String>>> mapsByMaze = new HashMap<>();
    private Integer currentPlayer = null;
    private String currentMaze = null;
    private final Path storageDir;
    private final Path mazeConfigPath;

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
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                // determine current maze id from config file at connection time
                currentMaze = readMazeIdFromConfig();
                String greeting;
                while ((greeting = in.readLine()) != null) {
                    if (greeting.startsWith("PLAYER_ID ")) {
                        currentPlayer = Integer.parseInt(greeting.substring(10));
                        out.println("OK");
                    } else if ("GET_MAP".equals(greeting)) {
                        // refresh maze id in case file changed between runs
                        if (currentMaze == null) currentMaze = readMazeIdFromConfig();
                        if (currentPlayer != null && currentMaze != null) {
                            Map<Integer, Map<String, String>> perPlayer = mapsByMaze.getOrDefault(currentMaze, Map.of());
                            Map<String, String> cells = perPlayer.getOrDefault(currentPlayer, Map.of());
                            if (cells.isEmpty()) {
                                out.println("NO_MAP");
                            } else {
                                List<String> vals = cells.values().stream().collect(Collectors.toList());
                                out.println(String.join(";", vals));
                            }
                        } else {
                            out.println("NO_PLAYER_OR_MAZE");
                        }
                    } else if (greeting.startsWith("UPDATE_MAP ")) {
                        // refresh maze id in case file changed between runs
                        if (currentMaze == null) currentMaze = readMazeIdFromConfig();
                        if (currentPlayer != null && currentMaze != null) {
                            String data = greeting.substring(11);
                            Map<Integer, Map<String, String>> perPlayer = mapsByMaze.computeIfAbsent(currentMaze, k -> new HashMap<>());
                            Map<String, String> cells = perPlayer.computeIfAbsent(currentPlayer, k -> new HashMap<>());
                            String[] updates = data.split(";");
                            for (String update : updates) {
                                if (update == null) continue;
                                update = update.trim();
                                if (update.isEmpty()) continue;
                                String[] parts = update.split(",");
                                if (parts.length < 3) continue;
                                String key = parts[0].trim() + "," + parts[1].trim();
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
                            try {
                                saveMapToDisk(currentMaze, currentPlayer, cells);
                            } catch (IOException e) {
                                System.err.println("Failed to persist map: " + e.getMessage());
                            }
                            out.println("UPDATED");
                        } else {
                            out.println("NO_PLAYER_OR_MAZE");
                        }
                    } else {
                        out.println("hallo back");
                    }
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
                    try (var files = Files.list(mazePath)) {
                        files.filter(p -> p.getFileName().toString().startsWith("player_")).forEach(playerFile -> {
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
                    }
                } catch (Exception ex) {
                    System.err.println("Failed to load maze dir " + mazePath + ": " + ex.getMessage());
                }
            });
        }
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