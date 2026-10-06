import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class SessionManager {

    private final HashMap<String, Session> sessions = new HashMap<>(); // token -> session
    private final ReentrantLock lock = new ReentrantLock();
    private static final long TOKEN_TTL_MS = 24L * 60 * 60 * 1000; // 24 hours
    private static final String TOKENS_FILE = "data/tokens.dat";

    public SessionManager() {
        loadFromDisk();
        startCleanupThread();
    }

    public String createSession(String username) {
        String token = UUID.randomUUID().toString();
        lock.lock();
        try {
            sessions.put(token, new Session(username));
            saveToDisk();
        } finally {
            lock.unlock();
        }
        return token;
    }

    public Session getSession(String token) {
        lock.lock();
        try {
            Session s = sessions.get(token);
            if (s != null && s.isExpired()) {
                sessions.remove(token);
                saveToDisk();
                return null;
            }
            return s;
        } finally {
            lock.unlock();
        }
    }

    public void removeSession(String token) {
        lock.lock();
        try {
            sessions.remove(token);
            saveToDisk();
        } finally {
            lock.unlock();
        }
    }

    public void updateRoom(String token, String roomName) {
        lock.lock();
        try {
            Session s = sessions.get(token);
            if (s != null) {
                s.currentRoom = roomName;
                saveToDisk();
            }
        } finally {
            lock.unlock();
        }
    }

    private void cleanupExpired() {
        lock.lock();
        try {
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                if (entry.getValue().isExpired()) {
                    expired.add(entry.getKey());
                }
            }
            if (!expired.isEmpty()) {
                expired.forEach(sessions::remove);
                saveToDisk();
            }
        } finally {
            lock.unlock();
        }
    }

    private void saveToDisk() {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(TOKENS_FILE), StandardCharsets.UTF_8))){
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                String room = e.getValue().currentRoom != null ? e.getValue().currentRoom : "";
                bw.write(e.getKey() + ":" + e.getValue().username + ":" + e.getValue().createdAt + ":" + room);
                bw.newLine();
            }
        } catch (IOException ignored) {}
    }

    private void loadFromDisk() {
        File file = new File(TOKENS_FILE);
        if (!file.exists()) return;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":", 4);
                if (parts.length < 4) continue;
                String token = parts[0];
                String username = parts[1];
                long createdAt;
                try {
                    createdAt = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                String room = parts[3].isEmpty() ? null : parts[3];
                Session s = new Session(username, createdAt);
                s.currentRoom = room;
                if (!s.isExpired()) sessions.put(token, s);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void startCleanupThread() {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(this::cleanupExpired, 5, 5, TimeUnit.MINUTES);
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(5 * 60000);
                } catch (InterruptedException e) {
                    return;
                }
                cleanupExpired();
            }
        });
    }

    public static class Session {
        public final String username;
        public String currentRoom;
        final long createdAt;

        public Session(String username) {
            this.username = username;
            this.createdAt = System.currentTimeMillis();
        }

        Session(String username, long createdAt) {
            this.username = username;
            this.createdAt = createdAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > TOKEN_TTL_MS;
        }
    }
}
