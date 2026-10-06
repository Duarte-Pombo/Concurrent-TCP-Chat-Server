import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.UUID;

public class RoomConcurrencyTest {

    // Minimal stub: only overrides getOutputStream() — no real socket needed
    static class StubHandler extends ClientHandler {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StubHandler() {
            super(null); // socket unused in unit tests
        }

        @Override
        public OutputStream getOutputStream() {
            return buf;
        }
    }

    @Test
    void concurrentAddParticipant() throws InterruptedException {
        Room room = new Room("test");
        int n = 20;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<StubHandler> handlers = new ArrayList<>();
        for (int i = 0; i < n; i++)
            handlers.add(new StubHandler());

        List<String> violations = new CopyOnWriteArrayList<>();
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final StubHandler h = handlers.get(i);
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    room.addParticipant(h);
                } catch (Exception e) {
                    violations.add("addParticipant threw: " + e);
                }
            });
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread t : threads)
            t.join();

        assertEquals(n, room.getParticipantCount(), "participant count should be 20");
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

    @Test
    void concurrentAddAndRemove() throws InterruptedException {
        Room room = new Room("test");
        int n = 10;
        List<StubHandler> handlers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StubHandler h = new StubHandler();
            handlers.add(h);
            room.addParticipant(h);
        }

        CountDownLatch ready = new CountDownLatch(n * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<String> violations = new CopyOnWriteArrayList<>();

        List<StubHandler> newHandlers = new ArrayList<>();
        for (int i = 0; i < n; i++)
            newHandlers.add(new StubHandler());

        Thread[] threads = new Thread[n * 2];
        for (int i = 0; i < n; i++) {
            final StubHandler toAdd = newHandlers.get(i);
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    room.addParticipant(toAdd);
                } catch (Exception e) {
                    violations.add("add threw: " + e);
                }
            });
            final StubHandler toRemove = handlers.get(i);
            threads[n + i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    room.removeParticipant(toRemove);
                } catch (Exception e) {
                    violations.add("remove threw: " + e);
                }
            });
        }
        for (Thread t : threads)
            t.start();
        ready.await();
        start.countDown();
        for (Thread t : threads)
            t.join();

        int count = room.getParticipantCount();
        assertTrue(count >= 0, "participant count must not be negative, was: " + count);
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

    @Test
    void concurrentBroadcast() throws InterruptedException {
        String uniqueRoomName = "test-" + UUID.randomUUID().toString();
        Room room = new Room(uniqueRoomName);

        int n = 20;
        StubHandler sender = new StubHandler();
        room.addParticipant(sender);

        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<String> violations = new CopyOnWriteArrayList<>();

        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final String msg = "msg-" + i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    room.broadcast(msg, sender);
                } catch (Exception e) {
                    violations.add("broadcast threw: " + e);
                }
            });
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread t : threads)
            t.join();

        assertEquals(n, room.getHistory().size(), "messageHistory size should be 20 (no lost writes)");
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }
}
