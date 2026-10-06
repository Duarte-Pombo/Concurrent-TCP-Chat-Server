import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class LogoutTest {

    private static ServerSocket serverSocket;
    private static final int PORT = 12346;

    @BeforeAll
    static void startServer() throws Exception {
        serverSocket = new ServerSocket(PORT);
        Server server = new Server(serverSocket);
        Thread t = new Thread(() -> {
            try { server.startServer(); } catch (IOException e) { /* server stopped */ }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(200);
    }

    @AfterAll
    static void stopServer() throws Exception {
        serverSocket.close();
        new File("data/users.dat").delete();
    }

    private Socket connectAndRegister(String user, String pass) throws IOException {
        Socket s = new Socket("localhost", PORT);
        Protocol.send(s.getOutputStream(), Protocol.REGISTER, user + "\n" + pass);
        Protocol.Message resp = Protocol.receive(s.getInputStream());
        if (resp.type() != Protocol.AUTH_OK) throw new IOException("register failed: " + resp.payload());
        return s;
    }

    private String getToken(Socket s) throws IOException {
        // After AUTH_OK the token was already consumed; we need it stored.
        // The connectAndRegister helper discards the payload — use raw here.
        throw new UnsupportedOperationException("use connectAndGetToken instead");
    }

    private static String[] connectAndGetToken(String user, String pass) throws IOException {
        Socket s = new Socket("localhost", PORT);
        Protocol.send(s.getOutputStream(), Protocol.REGISTER, user + "\n" + pass);
        Protocol.Message resp = Protocol.receive(s.getInputStream());
        if (resp.type() != Protocol.AUTH_OK) throw new IOException("register failed: " + resp.payload());
        return new String[]{ resp.payload() }; // [token]
    }

    @Test
    void logoutInvalidatesToken() throws Exception {
        // Register and obtain token
        Socket s = new Socket("localhost", PORT);
        Protocol.send(s.getOutputStream(), Protocol.REGISTER, "logoutuser1\npass");
        Protocol.Message authOk = Protocol.receive(s.getInputStream());
        assertEquals(Protocol.AUTH_OK, authOk.type());
        String token = authOk.payload();

        // Send LOGOUT
        Protocol.send(s.getOutputStream(), Protocol.LOGOUT, "");

        // Expect LOGOUT_OK back
        Protocol.Message logoutResp = Protocol.receive(s.getInputStream());
        assertEquals(Protocol.LOGOUT_OK, logoutResp.type());
        s.close();

        Thread.sleep(100); // let server process cleanup

        // Try to reconnect with invalidated token — should get ERROR
        Socket s2 = new Socket("localhost", PORT);
        Protocol.send(s2.getOutputStream(), Protocol.RECONNECT, token);
        Protocol.Message reconnectResp = Protocol.receive(s2.getInputStream());
        assertEquals(Protocol.ERROR, reconnectResp.type(), "reconnect with invalidated token should fail");
        s2.close();
    }

    @Test
    void logoutRemovesUserFromRoom() throws Exception {
        // Register, join room, then logout
        Socket s = new Socket("localhost", PORT);
        Protocol.send(s.getOutputStream(), Protocol.REGISTER, "logoutuser2\npass");
        Protocol.Message authOk = Protocol.receive(s.getInputStream());
        assertEquals(Protocol.AUTH_OK, authOk.type());

        Protocol.send(s.getOutputStream(), Protocol.JOIN, "logoutroom");
        Thread.sleep(100);

        // Second client to check room size
        Socket s2 = new Socket("localhost", PORT);
        Protocol.send(s2.getOutputStream(), Protocol.REGISTER, "logoutuser3\npass");
        Protocol.receive(s2.getInputStream()); // consume AUTH_OK
        Protocol.send(s2.getOutputStream(), Protocol.JOIN, "logoutroom");
        Thread.sleep(100);

        // Drain all pending messages (from own join + s2 join)
        s.setSoTimeout(200);
        try {
            while (true) Protocol.receive(s.getInputStream());
        } catch (java.net.SocketTimeoutException e) { /* buffer drained */ }
        s.setSoTimeout(0);

        // First client logs out
        Protocol.send(s.getOutputStream(), Protocol.LOGOUT, "");
        Protocol.Message logoutResp = Protocol.receive(s.getInputStream());
        assertEquals(Protocol.LOGOUT_OK, logoutResp.type());
        s.close();

        Thread.sleep(100);

        // Room should now have only 1 participant (logoutuser3)
        Room room = ClientHandler.roomManager.getRoom("logoutroom");
        assertNotNull(room);
        assertEquals(1, room.getParticipantCount(), "room should have 1 participant after logout");

        s2.close();
    }
}