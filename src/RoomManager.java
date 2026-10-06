import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class RoomManager {

    private final HashMap<String, Room> rooms = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private static final String ROOMS_FILE = "data/rooms.dat";

    public RoomManager() {
        Room.ROOMS_DIR.mkdirs();
        loadFromDisk();
    }

    public void createRoom(String name, boolean isAI, String systemPrompt, boolean isPrivate, String passwordHash) {
        lock.lock();
        try {
            if (rooms.containsKey(name)) return;

            Room room = isAI
                    ? new AIRoom(name, systemPrompt, AIRoom.DEFAULT_MODEL, isPrivate, passwordHash)
                    : new Room(name, isPrivate, passwordHash);

            rooms.put(name, room);

            if (isPrivate && passwordHash != null) {
                savePasswordToDisk(name, passwordHash);
            }
            saveToDisk();
        } finally {
            lock.unlock();
        }
    }

    public Room getRoom(String name) {
        lock.lock();
        try { return rooms.get(name); }
        finally { lock.unlock(); }
    }

    public List<String> listRooms() {
        lock.lock();
        try {
            List<String> result = new ArrayList<>();
            for (Map.Entry<String, Room> e : rooms.entrySet()) {
                Room r = e.getValue();
                StringBuilder sb = new StringBuilder(e.getKey());
                if (r instanceof AIRoom) sb.append(" [AI]");
                if (r.isPrivate())       sb.append(" [Private]");
                result.add(sb.toString());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void saveToDisk() {
       try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ROOMS_FILE), StandardCharsets.UTF_8))) {

            for (Map.Entry<String, Room> e : rooms.entrySet()) {
                Room r = e.getValue();
                String visTag = r.isPrivate() ? "private" : "public";

                if (r instanceof AIRoom ai) {
                    String encoded = encodePrompt(ai.getSystemPrompt());
                    bw.write(e.getKey() + ":" + visTag + ":ai:" + encoded);
                } else {
                    bw.write(e.getKey() + ":" + visTag + ":normal");
                }
                bw.newLine();
            }
        } catch (IOException ignored) {}
    }

    private void loadFromDisk() {
        File file = new File(ROOMS_FILE);
        if (!file.exists()) return;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                parseLine(line);
            }
        } catch (IOException ignored) {}
    }

    private void parseLine(String line) {
        // Split into at most 4 tokens so the encoded prompt (field 4) is kept whole.
        String[] parts = line.split(":", 4);
        if (parts.length < 2) return;

        String roomName = parts[0];

        // Detect legacy format: second field is "ai" or "normal"
        boolean legacy = parts[1].equals("ai") || parts[1].equals("normal");

        boolean isPrivate;
        String  typeField;   // "normal" or "ai"
        String  promptEncoded = "";

        if (legacy) {
            // Old format has no visibility tag — treat as public
            isPrivate  = false;
            typeField  = parts[1];
            if (typeField.equals("ai") && parts.length >= 3) {
                promptEncoded = parts[2];
            }
        } else {
            // New format
            isPrivate = parts[1].equals("private");
            if (parts.length < 3) return;
            typeField = parts[2];                          // "normal" or "ai"
            if (typeField.equals("ai") && parts.length >= 4) {
                promptEncoded = parts[3];
            }
        }

        boolean isAI = typeField.equals("ai");

        String systemPrompt  = decodePrompt(promptEncoded);
        String passwordHash  = isPrivate ? loadPasswordFromDisk(roomName) : null;

        Room room = isAI
                ? new AIRoom(roomName, systemPrompt, AIRoom.DEFAULT_MODEL, isPrivate, passwordHash)
                : new Room(roomName, isPrivate, passwordHash);

        rooms.put(roomName, room);
    }

    private void savePasswordToDisk(String roomName, String passwordHash) {
        File f = new File(Room.ROOMS_DIR, roomName + ".pwd");
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8))) {
            bw.write(passwordHash);
            bw.newLine();
        } catch (IOException ignored) {}
    }

    private String loadPasswordFromDisk(String roomName) {
        File f = new File(Room.ROOMS_DIR, roomName + ".pwd");
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String encodePrompt(String prompt) {
        return prompt
                .replace("\\", "\\\\")
                .replace(":",  "\\:")
                .replace("\n", "\\n");
    }

    private static String decodePrompt(String encoded) {
        return encoded
                .replace("\\n",  "\n")
                .replace("\\:",  ":")
                .replace("\\\\", "\\");
    }
}
