package de.simplehardware.server;

/**
 * Parsing utilities for map lines and commands.
 */
public class MapParser {

    public static Integer parsePlayerId(String command) {
        try {
            return Integer.parseInt(command.substring(10).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the key (x,y) from a line like "x,y,TYPE,...". Returns null when not present.
     */
    public static String extractKey(String trimmedLine) {
        int comma1 = trimmedLine.indexOf(',');
        int comma2 = (comma1 != -1) ? trimmedLine.indexOf(',', comma1 + 1) : -1;
        if (comma1 != -1 && comma2 != -1) {
            return trimmedLine.substring(0, comma2);
        }
        return null;
    }

    public static int getCellTypePriority(String cellData, int typeStartIndex) {
        if (typeStartIndex == -1 || typeStartIndex >= cellData.length() - 1) return 0;
        String typePart = cellData.substring(typeStartIndex + 1);
        if (typePart.startsWith("FINISH")) return 4;
        if (typePart.startsWith("FORM")) return 3;
        if (typePart.startsWith("WALL")) return 2;
        if (typePart.startsWith("FLOOR")) return 1;
        return 0;
    }
}
