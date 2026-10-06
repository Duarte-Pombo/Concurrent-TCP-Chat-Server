import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import java.io.File;

public class RoomManagerConcurrencyTest {

    @Test
    void concurrentCreateSameName() throws InterruptedException {
        File saveFile = new File("rooms.dat");
        if (saveFile.exists()) {
            saveFile.delete();
        }

        RoomManager rm = new RoomManager();
        int n = 20;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<String> violations = new CopyOnWriteArrayList<>();

        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    // Just call it. It handles duplicates internally.
                    rm.createRoom("same-name", false, "", false, null);
                } catch (Exception e) {
                    violations.add("createRoom threw: " + e);
                }
            });
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread t : threads)
            t.join();

        // The real test: Did it only create exactly 1 room?
        assertEquals(1, rm.listRooms().size(), "Only 1 room should exist in the manager");
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }
}
