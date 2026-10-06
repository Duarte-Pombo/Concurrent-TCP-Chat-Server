import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class Room {

    /** Directory that holds per-room .history and .pwd files. */
    static final File ROOMS_DIR = new File("data/rooms");

    private static volatile int MAX_HISTORY = 100;

    /** Called by Server at startup */
    public static void setMaxHistory(int max) {
        if (max > 0) MAX_HISTORY = max;
    }

    private final String name;
    private final boolean isPrivate;
    private final String passwordHash; // SHA-256 hex; null when public
    private final ArrayList<ClientHandler> participants = new ArrayList<>();
    private final ArrayList<String> messageHistory   = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor();
    public Room(String name) {
        this(name, false, null);
    }

    /**
     * Room Constructor used by Room Manager
     *
     * @param name         room name
     * @param isPrivate    true if a password is required to join
     * @param passwordHash SHA-256 hex of the room password, or null if public
     */
    public Room(String name, boolean isPrivate, String passwordHash) {
        this.name         = name;
        this.isPrivate    = isPrivate;
        this.passwordHash = passwordHash;
        ROOMS_DIR.mkdirs();
        loadHistoryFromDisk();
    }

    public boolean isPrivate() { return isPrivate; }

    public boolean checkPassword(String plainPassword) {
        if (!isPrivate || passwordHash == null) return true;
        return hash(plainPassword).equals(passwordHash);
    }

    public static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public void addParticipant(ClientHandler handler) {
        lock.lock();
        try {
            participants.add(handler);
        } finally {
            lock.unlock();
        }
    }

    public void removeParticipant(ClientHandler handler) {
        lock.lock();
        try {
            participants.remove(handler);
        } finally {
            lock.unlock();
        }
    }

    public void broadcast(String message, ClientHandler sender) {
        broadcast(message, sender, true);
    }

    public void broadcast(String message, ClientHandler sender, boolean saveToHistory) {
        List<String> snapshotForDisk = null;

        lock.lock();
        try {
            if (saveToHistory) {
                messageHistory.add(message);
                while (messageHistory.size() > MAX_HISTORY) {
                    messageHistory.remove(0);
                }
                snapshotForDisk = new ArrayList<>(messageHistory);
            }

            List<ClientHandler> snapshot = new ArrayList<>(participants);
            for (ClientHandler p : snapshot) {
                if (p != sender) {
                    try {
                        p.enqueueMessage(message);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        if (saveToHistory && snapshotForDisk != null) {
            List<String> finalSnapshotForDisk = snapshotForDisk;
            diskWriter.submit(() -> saveHistoryToDisk(finalSnapshotForDisk));
        }
    }

    private File historyFile() {
        return new File(ROOMS_DIR, name + ".history");
    }

    private void loadHistoryFromDisk() {
        File f = historyFile();
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                messageHistory.add(line);
            }
            while (messageHistory.size() > MAX_HISTORY) {
                messageHistory.remove(0);
            }
        } catch (IOException ignored) {}
    }

    private void saveHistoryToDisk(List<String> historySnapshot) {
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(historyFile()), StandardCharsets.UTF_8))) {
            for (String msg : historySnapshot) {
                bw.write(msg);
                bw.newLine();
            }
        } catch (IOException e) {
            System.err.println("[!] Failed to save history for room " + name + ": " + e.getMessage());
        }
    }

    public String getName() { return name; }

    public int getParticipantCount() {
        lock.lock();
        try { return participants.size(); }
        finally { lock.unlock(); }
    }

    public List<String> getHistory() {
        lock.lock();
        try { return new ArrayList<>(messageHistory); }
        finally { lock.unlock(); }
    }
}
