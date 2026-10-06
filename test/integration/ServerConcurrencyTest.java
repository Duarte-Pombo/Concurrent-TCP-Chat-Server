import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerConcurrencyTest {

    private static ServerSocket serverSocket;
    private static Thread serverThread;
    private static final int PORT = 12345;

    @BeforeAll
    static void startServer() throws Exception {
        serverSocket = new ServerSocket(PORT);
        Server server = new Server(serverSocket);
        serverThread = new Thread(() -> {
            try { server.startServer(); } catch (IOException e) { /* server stopped */ }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(200); // let server socket settle
    }

    @AfterAll
    static void stopServer() throws Exception {
        serverSocket.close();
        new File("data/users.dat").delete();
    }

    // Helper: open a socket and send REGISTER, receive AUTH_OK token
    private static Socket connectAndRegister(String user, String pass) throws IOException {
        Socket s = new Socket("localhost", PORT);
        Protocol.send(s.getOutputStream(), Protocol.REGISTER, user + "\n" + pass);
        Protocol.Message resp = Protocol.receive(s.getInputStream());
        if (resp.type() != Protocol.AUTH_OK) throw new IOException("register failed for " + user + ": " + resp.payload());
        return s;
    }

    // Helper: send JOIN and drain any BROADCAST notifications until quiet
    private static void join(Socket s, String room) throws IOException {
        Protocol.send(s.getOutputStream(), Protocol.JOIN, room);
    }

    // Read one message with a short timeout (non-blocking drain)
    private static Protocol.Message tryReceive(Socket s, int timeoutMs) throws IOException {
        s.setSoTimeout(timeoutMs);
        try {
            return Protocol.receive(s.getInputStream());
        } catch (java.net.SocketTimeoutException e) {
            return null;
        } finally {
            s.setSoTimeout(0);
        }
    }

    @Test
    void concurrentJoin() throws Exception {
        int n = 10;
        String room = "joinroom";
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<String> violations = new CopyOnWriteArrayList<>();
        List<Socket> sockets = new CopyOnWriteArrayList<>();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    Socket s = connectAndRegister("joinuser" + idx, "pass");
                    sockets.add(s);
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    join(s, room);
                } catch (Exception e) {
                    violations.add("client " + idx + ": " + e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "clients did not connect in time");
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "clients did not finish joining in time");
        Thread.sleep(300); // let server process joins

        // Each client should receive at least one BROADCAST (entry notification from others)
        // and server room should have n participants — verify via getRoom
        RoomManager rm = ClientHandler.roomManager;
        Room r = rm.getRoom(room);
        assertNotNull(r, "room should exist");
        assertEquals(n, r.getParticipantCount(), "room should have " + n + " participants");
        assertTrue(violations.isEmpty(), "violations: " + violations);

        for (Socket s : sockets) { try { s.close(); } catch (IOException ignored) {} }
    }

    @Test
    void concurrentBroadcast() throws Exception {
        int n = 10;
        String room = "broadroom";
        List<Socket> sockets = new ArrayList<>();

        // connect + join sequentially so all are in room before test
        for (int i = 0; i < n; i++) {
            Socket s = connectAndRegister("broaduser" + i, "pass");
            join(s, room);
            sockets.add(s);
        }
        Thread.sleep(300); // drain entry notifications

        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<String> violations = new CopyOnWriteArrayList<>();
        List<AtomicInteger> receivedCounts = new ArrayList<>();
        for (int i = 0; i < n; i++) receivedCounts.add(new AtomicInteger(0));

        for (int i = 0; i < n; i++) {
            final Socket s = sockets.get(i);
            final AtomicInteger counter = receivedCounts.get(i);
            new Thread(() -> {
                try {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    Protocol.send(s.getOutputStream(), Protocol.MSG, "hello");
                    // read up to n-1 BROADCAST messages (with timeout)
                    for (int j = 0; j < n - 1; j++) {
                        Protocol.Message m = tryReceive(s, 3000);
                        if (m == null) break;
                        if (m.type() == Protocol.BROADCAST) counter.incrementAndGet();
                    }
                } catch (Exception e) {
                    violations.add("broadcast client: " + e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "broadcast test timed out");

        for (int i = 0; i < n; i++) {
            int got = receivedCounts.get(i).get();
            if (got != n - 1) violations.add("client " + i + " got " + got + " broadcasts, expected " + (n - 1));
        }
        assertTrue(violations.isEmpty(), "violations: " + violations);
        for (Socket s : sockets) { try { s.close(); } catch (IOException ignored) {} }
    }

    @Test
    void concurrentJoinAndLeave() throws Exception {
        int n = 5;
        String room = "jlroom";
        List<Socket> leavers = new ArrayList<>();

        // pre-join n clients that will leave
        for (int i = 0; i < n; i++) {
            Socket s = connectAndRegister("leaveuser" + i, "pass");
            join(s, room);
            leavers.add(s);
        }
        Thread.sleep(200);

        List<Socket> joiners = new ArrayList<>();
        for (int i = 0; i < n; i++) joiners.add(connectAndRegister("joiner2_" + i, "pass"));

        CountDownLatch ready = new CountDownLatch(n * 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n * 2);
        List<String> violations = new CopyOnWriteArrayList<>();

        for (int i = 0; i < n; i++) {
            final Socket joiner = joiners.get(i);
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    join(joiner, room);
                } catch (Exception e) { violations.add("join threw: " + e); }
                finally { done.countDown(); }
            }).start();

            final Socket leaver = leavers.get(i);
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    Protocol.send(leaver.getOutputStream(), Protocol.LEAVE, "");
                } catch (Exception e) { violations.add("leave threw: " + e); }
                finally { done.countDown(); }
            }).start();
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "join+leave test timed out");
        Thread.sleep(300);

        Room r = ClientHandler.roomManager.getRoom(room);
        if (r != null) {
            int count = r.getParticipantCount();
            if (count < 0) violations.add("participant count negative: " + count);
        }
        assertTrue(violations.isEmpty(), "violations: " + violations);

        for (Socket s : leavers) { try { s.close(); } catch (IOException ignored) {} }
        for (Socket s : joiners) { try { s.close(); } catch (IOException ignored) {} }
    }

    @Test
    void concurrentRegisterSameUsername() throws Exception {
        int n = 10;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger authOkCount = new AtomicInteger(0);
        List<String> violations = new CopyOnWriteArrayList<>();
        List<Socket> sockets = new CopyOnWriteArrayList<>();

        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                Socket s = null;
                try {
                    s = new Socket("localhost", PORT);
                    final Socket fs = s;
                    sockets.add(fs);
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    Protocol.send(fs.getOutputStream(), Protocol.REGISTER, "dupuser\npass");
                    Protocol.Message resp = Protocol.receive(fs.getInputStream());
                    if (resp.type() == Protocol.AUTH_OK) authOkCount.incrementAndGet();
                    else if (resp.type() != Protocol.ERROR) violations.add("unexpected response: " + resp.type());
                } catch (Exception e) {
                    violations.add("register threw: " + e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "register test timed out");

        assertEquals(1, authOkCount.get(), "exactly 1 client should get AUTH_OK");
        assertTrue(violations.isEmpty(), "violations: " + violations);
        for (Socket s : sockets) { try { s.close(); } catch (IOException ignored) {} }
    }
}
