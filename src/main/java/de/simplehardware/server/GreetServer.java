package de.simplehardware.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class GreetServer {
    private final Map<String, Map<String, String>> mapsByMaze = new ConcurrentHashMap<>();
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
        try (clientSocket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
            logger.info("New client handler started");
            String command;
            while ((command = in.readLine()) != null) {
                logger.info("Received command", "cmd=" + command);
                if (command.startsWith("MAZE_ID ")) {
                    currentMaze = command.substring(7).trim();
                    logger.info("Client session set mazeId", "mazeId=" + currentMaze);
                    out.println("OK");
                    logger.info("Sent OK for MAZE_ID");
                } else if ("GET_MAP".equals(command)) {
                    logger.info("Handling GET_MAP", "mazeId=" + currentMaze);
                    handleGetMap(out, currentMaze);
                    logger.info("GET_MAP handled");
                } else if (command.startsWith("UPDATE_MAP ")) {
                    logger.info("Handling UPDATE_MAP", "mazeId=" + currentMaze);
                    handleUpdateMap(command, currentMaze);
                    out.println("UPDATED");
                    logger.info("Sent UPDATED for UPDATE_MAP");
                } else {
                    logger.info("Unknown command", "cmd=" + command);
                    out.println("UNKNOWN_COMMAND");
                }
            }
            logger.info("Client handler finished (connection closed)");
        } catch (IOException e) {
            logger.warn("Client connection error", "err=" + e.getMessage());
        }
    }


    private void handleGetMap(PrintWriter out, String mazeId) {
        Map<String, String> cells = mapsByMaze.getOrDefault(mazeId, Collections.emptyMap());
        if (cells.isEmpty()) {
            out.println("NO_MAP");
        } else {
            out.println(String.join(";", cells.values()));
        }
    }

    private void handleUpdateMap(String command, String mazeId) {
        String data = command.substring(11);
        if (data.isEmpty()) return;
        Map<String, String> cells = mapsByMaze.computeIfAbsent(mazeId, k -> new ConcurrentHashMap<>());
        String[] updatesArray = data.split(";");
        for (String update : updatesArray) {
            if (update == null || update.isEmpty()) continue;
            update = update.trim();
            cells.put(update, update);
        }
        final Map<String, String> snapshot = new HashMap<>(cells);
        diskWriter.execute(() -> io.persistMap(mazeId, snapshot));
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