package de.simplehardware.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

public class GreetServer {
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;
    // For each player id keep a map from position "x,y" -> serialized cell string
    private Map<Integer, Map<String, String>> playerCellSets = new HashMap<>();
    private Integer currentPlayer = null;

    public void start(int port) {
        try {
            serverSocket = new ServerSocket(port);
            while (true) {
                clientSocket = serverSocket.accept();
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                String greeting;
                while ((greeting = in.readLine()) != null) {
                    System.out.println(greeting);
                    if (greeting.startsWith("PLAYER_ID ")) {
                        currentPlayer = Integer.parseInt(greeting.substring(10));
                        out.println("OK");
                    } else if ("GET_MAP".equals(greeting)) {
                        if (currentPlayer != null) {
                            Map<String, String> cells = playerCellSets.getOrDefault(currentPlayer, Map.of());
                            if (cells.isEmpty()) {
                                out.println("NO_MAP");
                            } else {
                                // return deterministic ordering by key
                                List<String> vals = new ArrayList<>(cells.values());
                                out.println(String.join(";", vals));
                            }
                        } else {
                            out.println("NO_PLAYER");
                        }
                    } else if (greeting.startsWith("UPDATE_MAP ")) {
                        if (currentPlayer != null) {
                            String data = greeting.substring(11);
                            Map<String, String> cells = playerCellSets.computeIfAbsent(currentPlayer, k -> new HashMap<>());
                            String[] updates = data.split(";");
                            for (String update : updates) {
                                if (update == null) continue;
                                update = update.trim();
                                if (update.isEmpty()) continue;
                                String[] parts = update.split(",");
                                if (parts.length < 3) continue;
                                String key = parts[0].trim() + "," + parts[1].trim();
                                String existing = cells.get(key);
                                // Merge by priority: FINISH > FORM > WALL > EMPTY
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
                            out.println("UPDATED");
                        } else {
                            out.println("NO_PLAYER");
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
        GreetServer server=new GreetServer();
        server.start(5555);
    }
}