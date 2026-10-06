import javax.net.ssl.SSLServerSocketFactory;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {

	private ServerSocket serverSocket;
	private volatile boolean running = true;

	public Server(ServerSocket serverSocket) {
		this.serverSocket = serverSocket;
	}

	public void startServer() throws IOException {
		int maxHistory = Integer.getInteger("room.history.size", 100);
		Room.setMaxHistory(maxHistory);
		System.out.println("[*] Room history cap set to " + maxHistory + " messages per room.");

		ClientHandler.userManager = new UserManager();
		ClientHandler.sessionManager = new SessionManager();
		ClientHandler.roomManager = new RoomManager();

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			while (running) {
				Socket socket = serverSocket.accept();
				System.out.println("[A new client has connected]");

				ClientHandler clientHandler = new ClientHandler(socket);
				executor.submit(clientHandler);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static ServerSocket createServerSocket(int port) throws IOException {
		if (Boolean.getBoolean("ssl")) {
			System.setProperty("javax.net.ssl.keyStore", "keystore.jks");
			System.setProperty("javax.net.ssl.keyStorePassword", "changeit");
			return SSLServerSocketFactory.getDefault().createServerSocket(port);
		}
		return new ServerSocket(port);
	}

	public static void main(String[] args) throws IOException {
		ServerSocket serverSocket = createServerSocket(1234);
		Server server = new Server(serverSocket);
		server.startServer();
	}
}
