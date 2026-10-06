import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

public class SessionManagerConcurrencyTest {

    @Test
    void concurrentCreateSession() throws InterruptedException {
        SessionManager sm = new SessionManager();
        int n = 20;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<String> tokens = new CopyOnWriteArrayList<>();
        List<String> violations = new CopyOnWriteArrayList<>();

        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    String token = sm.createSession("user" + idx);
                    tokens.add(token);
                } catch (Exception e) {
                    violations.add("createSession threw: " + e);
                }
            });
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread t : threads) t.join();

        assertEquals(n, tokens.size(), "should have 20 tokens");
        assertEquals(n, Set.copyOf(tokens).size(), "all tokens must be distinct");
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

    @Test
    void mixedCreateAndRemoveSession() throws InterruptedException {
        SessionManager sm = new SessionManager();
        int n = 10;

        // pre-create sessions to remove
        List<String> preTokens = new CopyOnWriteArrayList<>();
        for (int i = 0; i < n; i++) preTokens.add(sm.createSession("existing" + i));

        CountDownLatch ready = new CountDownLatch(n * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<String> violations = new CopyOnWriteArrayList<>();

        Thread[] threads = new Thread[n * 2];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { sm.createSession("newuser" + idx); }
                catch (Exception e) { violations.add("createSession threw: " + e); }
            });
            final String tokenToRemove = preTokens.get(i);
            threads[n + i] = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { sm.removeSession(tokenToRemove); }
                catch (Exception e) { violations.add("removeSession threw: " + e); }
            });
        }
        for (Thread t : threads) t.start();
        ready.await();
        start.countDown();
        for (Thread t : threads) t.join();

        assertTrue(violations.isEmpty(), "violations: " + violations);
    }
}
