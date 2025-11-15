package de.simplehardware.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class GreetServer {
    private final Map<String, Map<Integer, Map<String, String>>> mapsByMaze = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;
    private final Path storageDir;
    private final Path mazeConfigPath;
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "map-disk-writer");
        t.setDaemon(true);
        return t;
    });
    private static final Pattern COMMA_PATTERN = Pattern.compile(",");

    public GreetServer() {
        String dir = System.getenv().getOrDefault("MAP_STORAGE_DIR", "./maps");
        storageDir = Paths.get(dir).toAbsolutePath().normalize();
        ensureDirectoryExists(storageDir);

        // Use the exact absolute path you provided
        String cfg = "/home/jeapi/Desktop/Studium/Semester3/SoftwareEntwurf/Env/FHMaze/gameSettings.json";
        mazeConfigPath = Paths.get(cfg).toAbsolutePath().normalize();
    }

    private void ensureDirectoryExists(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("Warning: failed to create directory " + dir + ": " + e.getMessage());
        }
    }

    private String readMazeIdFromConfig() {
        try {
            if (Files.exists(mazeConfigPath)) {
                String content = Files.readString(mazeConfigPath, StandardCharsets.UTF_8);
                // Simple JSON extraction for "mazeId"
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
            System.err.println("Failed to read maze config: " + e.getMessage());
        }
        return "default_maze"; // Fallback to default maze ID
    }

    public void start(int port) throws IOException {
        loadMapsFromStorage();
        serverSocket = new ServerSocket(port);
        System.out.println("Server started on port " + port);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            handleClient(clientSocket);
        }
    }

    private void handleClient(Socket clientSocket) {
        String currentMaze = readMazeIdFromConfig();
        Integer currentPlayer = null;

        try (clientSocket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String command;
            while ((command = in.readLine()) != null) {
                if (command.startsWith("PLAYER_ID ")) {
                    currentPlayer = parsePlayerId(command);
                    out.println("OK");
                } else if ("GET_MAP".equals(command)) {
                    handleGetMap(out, currentMaze, currentPlayer);
                } else if (command.startsWith("UPDATE_MAP ")) {
                    handleUpdateMap(command, currentMaze, currentPlayer);
                    out.println("UPDATED");
                } else {
                    out.println("UNKNOWN_COMMAND");
                }
            }
        } catch (IOException e) {
            System.err.println("Client connection error: " + e.getMessage());
        }
    }

    private Integer parsePlayerId(String command) {
        try {
            return Integer.parseInt(command.substring(10).trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid player ID format: " + command);
            return null;
        }
    }

    private void handleGetMap(PrintWriter out, String mazeId, Integer playerId) {
        if (playerId == null) {
            out.println("NO_PLAYER");
            return;
        }

        Map<String, String> cells = mapsByMaze
                .getOrDefault(mazeId, Collections.emptyMap())
                .getOrDefault(playerId, Collections.emptyMap());

        if (cells.isEmpty()) {
            out.println("NO_MAP");
        } else {
            // Join values directly without creating intermediate list
            StringBuilder response = new StringBuilder(cells.size() * 30);
            boolean first = true;
            for (String cell : cells.values()) {
                if (!first) response.append(';');
                response.append(cell);
                first = false;
            }
            out.println(response.toString());
        }
    }

    private void handleUpdateMap(String command, String mazeId, Integer playerId) {
        if (playerId == null) return;

        String data = command.substring(11);
        if (data.isEmpty()) return;

        Map<Integer, Map<String, String>> perPlayer = mapsByMaze.computeIfAbsent(mazeId, k -> new ConcurrentHashMap<>());
        Map<String, String> cells = perPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());

        String[] updates = data.split(";");
        for (String update : updates) {
            if (update == null || update.isEmpty()) continue;
            update = update.trim();

            // Manual parsing for better performance
            int comma1 = update.indexOf(',');
            int comma2 = (comma1 != -1) ? update.indexOf(',', comma1 + 1) : -1;

            if (comma1 == -1 || comma2 == -1) continue;

            String key = update.substring(0, comma2); // x,y as key
            String existing = cells.get(key);

            if (existing == null) {
                cells.put(key, update);
            } else {
                int newPriority = getCellTypePriority(update, comma2);
                int existingPriority = getCellTypePriority(existing, existing.indexOf(',', existing.indexOf(',') + 1));
                if (newPriority >= existingPriority) {
                    cells.put(key, update);
                }
            }
        }

        // Async persistence
        final Map<String, String> snapshot = new HashMap<>(cells);
        diskWriter.execute(() -> persistPlayerMap(mazeId, playerId, snapshot));
    }

    private int getCellTypePriority(String cellData, int typeStartIndex) {
        if (typeStartIndex == -1 || typeStartIndex >= cellData.length() - 1) return 1;

        // Extract the type part (after the second comma)
        String typePart = cellData.substring(typeStartIndex + 1);
        if (typePart.startsWith("FINISH")) return 4;
        if (typePart.startsWith("FORM")) return 3;
        if (typePart.startsWith("WALL")) return 2;
        return 1;  // EMPTY or unknown
    }

    private void loadMapsFromStorage() {
        if (!Files.exists(storageDir) || !Files.isDirectory(storageDir)) return;

        try (Stream<Path> mazeDirs = Files.list(storageDir)) {
            mazeDirs.filter(Files::isDirectory).forEach(this::loadMazeDirectory);
        } catch (IOException e) {
            System.err.println("Failed to list maze directories: " + e.getMessage());
        }
    }

    private void loadMazeDirectory(Path mazePath) {
        String mazeId = mazePath.getFileName().toString();
        Map<Integer, Map<String, String>> perPlayer = mapsByMaze.computeIfAbsent(mazeId, k -> new ConcurrentHashMap<>());

        try (Stream<Path> playerFiles = Files.list(mazePath)) {
            playerFiles
                    .filter(p -> p.getFileName().toString().matches("player_\\d+\\.map"))
                    .forEach(file -> loadPlayerMap(file, perPlayer, mazeId));
        } catch (IOException e) {
            System.err.println("Failed to list player files in " + mazePath + ": " + e.getMessage());
        }
    }

    private void loadPlayerMap(Path file, Map<Integer, Map<String, String>> perPlayer, String mazeId) {
        String fileName = file.getFileName().toString();
        int playerId;
        try {
            playerId = Integer.parseInt(fileName.substring(7, fileName.indexOf('.')));
        } catch (NumberFormatException e) {
            System.err.println("Invalid player file name: " + fileName);
            return;
        }

        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, String> cells = perPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                int comma1 = trimmed.indexOf(',');
                int comma2 = (comma1 != -1) ? trimmed.indexOf(',', comma1 + 1) : -1;

                if (comma1 != -1 && comma2 != -1) {
                    String key = trimmed.substring(0, comma2); // x,y
                    cells.put(key, trimmed);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load player map " + file + ": " + e.getMessage());
        }
    }

    private void persistPlayerMap(String mazeId, int playerId, Map<String, String> cells) {
        Path mazePath = storageDir.resolve(mazeId);
        ensureDirectoryExists(mazePath);
        Path file = mazePath.resolve("player_" + playerId + ".map");

        try {
            // Sort by key for deterministic output
            List<String> sortedLines = new ArrayList<>(cells.values());
            Collections.sort(sortedLines);
            Files.write(file, sortedLines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to persist map for maze " + mazeId + ", player " + playerId + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;
            GreetServer server = new GreetServer();
            server.start(port);
        } catch (Exception e) {
            System.err.println("Server startup failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}