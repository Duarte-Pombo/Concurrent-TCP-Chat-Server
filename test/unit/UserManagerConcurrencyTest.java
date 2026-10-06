import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class UserManagerConcurrencyTest {

    @AfterEach
    void cleanup() {
        new File("data/users.dat").delete();
    }

    @Test
    void concurrentRegisterSameUsername() throws InterruptedException {
        UserManager um = new UserManager();
        int n = 20;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> violations = new CopyOnWriteArrayList<>();

        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    if (um.register("sameuser", "pass")) successCount.incrementAndGet();
                } catch (Exception e) {
                    violations.add("register threw: " + e);
                }
            });
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread t : threads) t.join();

        assertEquals(1, successCount.get(), "exactly 1 register should return true");
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

    @Test
    void mixedRegisterAndAuthenticate() throws InterruptedException {
        UserManager um = new UserManager();
        // pre-register so authenticate has something to find
        um.register("existinguser", "pass");

        int n = 10;
        CountDownLatch ready = new CountDownLatch(n * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<String> violations = new CopyOnWriteArrayList<>();

        Thread[] threads = new Thread[n * 2];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { um.register("newuser" + idx, "pass"); }
                catch (Exception e) { violations.add("register threw: " + e); }
            });
            threads[n + i] = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { um.authenticate("existinguser", "pass"); }
                catch (Exception e) { violations.add("authenticate threw: " + e); }
            });
        }
        for (Thread t : threads) t.start();
        ready.await();
        start.countDown();
        for (Thread t : threads) t.join();

        assertTrue(violations.isEmpty(), "violations: " + violations);
    }
}
