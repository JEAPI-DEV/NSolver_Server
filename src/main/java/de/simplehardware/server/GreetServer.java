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

        // Dynamic mazeId set by client
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
        String currentMaze = "default_maze";
        Integer currentPlayer = null;

        try (clientSocket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String command;
            while ((command = in.readLine()) != null) {
                if (command.startsWith("PLAYER_ID ")) {
                    currentPlayer = parsePlayerId(command);
                    out.println("OK");
                } else if (command.startsWith("MAZE_ID ")) {
                    currentMaze = command.substring(7).trim();
                    logger.info("Client session set mazeId", "mazeId=" + currentMaze, "playerId=" + currentPlayer);
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
        logger.info("Sending map to player", "playerId="+playerId, "mazeId="+mazeId, "total="+cells.size(), "finishes="+finishCount, "forms="+formCount);

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

        String[] updatesArray = data.split(";");
        int updateCount = 0;
        for (String update : updatesArray) {
            if (update == null || update.isEmpty()) continue;
            update = update.trim();
            updateCount++;

            String key = MapParser.extractKey(update);
            if (key == null) continue;

            String existing = cells.get(key);
            if (existing == null) {
                cells.put(key, update);
            } else {
                int firstComma = update.indexOf(',');
                int secondComma = update.indexOf(',', firstComma + 1);
                int newPriority = MapParser.getCellTypePriority(update, secondComma);
                firstComma = existing.indexOf(',');
                secondComma = existing.indexOf(',', firstComma + 1);
                int existingPriority = MapParser.getCellTypePriority(existing, secondComma);
                if (newPriority >= existingPriority) {
                    cells.put(key, update);
                }
            }
            // Log special updates
            int typeStart = update.indexOf(',', update.indexOf(',') + 1);
            if (typeStart != -1) {
                int pri = MapParser.getCellTypePriority(update, typeStart);
                if (pri == 3 || pri == 4) {
                    logger.info("Updated special cell", "playerId=" + playerId, "key=" + key, "priority=" + pri, "data=" + update);
                }
            }
        }
        logger.info("Processed map updates", "playerId=" + playerId, "mazeId=" + mazeId, "count=" + updateCount);

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