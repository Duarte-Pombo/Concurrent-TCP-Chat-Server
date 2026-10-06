import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class UserManager {

    private static final String USERS_FILE = "data/users.dat";

    private final HashMap<String, String> users = new HashMap<>(); // username -> sha256(password)
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public UserManager() {
        loadFromDisk();
    }

    public boolean register(String username, String password) {
        lock.writeLock().lock();
        try {
            if (users.containsKey(username)) return false;
            users.put(username, hash(password));
            persist();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean authenticate(String username, String password) {
        lock.readLock().lock();
        try {
            return hash(password).equals(users.get(username));
        } finally {
            lock.readLock().unlock();
        }
    }

    private void persist() {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(USERS_FILE))) {
            for (var entry : users.entrySet()) {
                w.write(entry.getKey() + ":" + entry.getValue());
                w.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadFromDisk() {
        File f = new File(USERS_FILE);
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) users.put(parts[0], parts[1]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String hash(String input) {
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
}