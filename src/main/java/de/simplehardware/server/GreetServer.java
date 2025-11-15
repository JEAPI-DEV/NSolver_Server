package de.simplehardware.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class GreetServer {
    private final Map<String, Map<Integer, Map<String, String>>> mapsByMaze = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;
    private final Path storageDir;
    private final Path mazeConfigPath;
    private final IO io;
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "map-disk-writer");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService clientPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "client-worker");
        t.setDaemon(true);
        return t;
    });
    private final ConsoleLogger logger = new ConsoleLogger("GreetServer");

    public GreetServer() {
        String dir = System.getenv().getOrDefault("MAP_STORAGE_DIR", "./maps");
        storageDir = Paths.get(dir).toAbsolutePath().normalize();
        // IO will ensure storage directory exists and handle persistence
        io = new IO(storageDir, logger);

        // Use the exact absolute path you provided
        String cfg = "/home/jeapi/Desktop/Studium/Semester3/SoftwareEntwurf/Env/FHMaze/gameSettings.json";
        mazeConfigPath = Paths.get(cfg).toAbsolutePath().normalize();
    }

    // ensureDirectoryExists moved to IO

    // maze id reading delegated to IO.readMazeId

    public void start(int port) throws IOException {
        // load persisted maps from disk via IO
        mapsByMaze.putAll(io.loadAll());
        serverSocket = new ServerSocket(port);
        logger.info("Server started", "port=" + port, "storage=" + storageDir);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown requested - stopping server");
            try {
                if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
            } catch (IOException e) {
                logger.warn("Error while closing server socket", "err=" + e.getMessage());
            }
            diskWriter.shutdown();
            clientPool.shutdown();
        }));

        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                logger.info("Accepted connection", "remote=" + clientSocket.getRemoteSocketAddress());
                clientPool.submit(() -> handleClient(clientSocket));
            } catch (SocketException se) {
                logger.info("Server socket closed, stopping accept loop");
                break;
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        String currentMaze = io.readMazeId(mazeConfigPath);
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
            logger.warn("Client connection error", "err=" + e.getMessage());
        }
    }

    private Integer parsePlayerId(String command) {
        return MapParser.parsePlayerId(command);
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

            String key = MapParser.extractKey(update);
            if (key == null) continue;

            String existing = cells.get(key);
            if (existing == null) {
                cells.put(key, update);
            } else {
                int newPriority = MapParser.getCellTypePriority(update, update.indexOf(',', update.indexOf(',') + 1));
                int existingPriority = MapParser.getCellTypePriority(existing, existing.indexOf(',', existing.indexOf(',') + 1));
                if (newPriority >= existingPriority) {
                    cells.put(key, update);
                }
            }
        }

        // Async persistence
        final Map<String, String> snapshot = new HashMap<>(cells);
        diskWriter.execute(() -> io.persistPlayerMap(mazeId, playerId, snapshot));
    }

    // persistence and loading moved to IO

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