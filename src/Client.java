import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class Client {
	private Socket socket;
	private InputStream in;
	private OutputStream out;
	private String username;
	private String token;
	private String host;
	private int port;
	private boolean isReconnecting = false;

	private volatile boolean running = true;
	private volatile boolean isCreationDialog = false;
	private volatile DialogStep dialogStep = DialogStep.NONE;

	private enum DialogStep {
		NONE,
		CONFIRM, // "Create this room? (yes/no)"
		TYPE, // "Add AI bot? (yes/no)"
		AI_PROMPT, // "Enter system prompt:"
		PRIVATE_CONFIRM, // "Make this room private? (yes/no)"
		PWD_SET, // "Set a password for this room:"
		ROOM_PWD // "Enter room password:" — joining an existing private room
	}

	public Client(Socket socket, String username, String host, int port) throws IOException {
		this.socket = socket;
		this.username = username;
		this.host = host;
		this.port = port;
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
	}

	public void connect(boolean register, String password) throws IOException {
		byte type = register ? Protocol.REGISTER : Protocol.AUTH;
		Protocol.send(out, type, username + "\n" + password);

		Protocol.Message response = Protocol.receive(in);
		if (response.type() == Protocol.AUTH_OK) {
			this.token = response.payload();
			this.socket.setSoTimeout(2500);
			System.out.print("\033[H\033[2J");
			System.out.flush();
			System.out.println(Ansi.color("=== Main Lobby | User: " + this.username + " ===", Ansi.CYAN));
			System.out.println(Ansi.color("[+] Authenticated. Fetching rooms...", Ansi.GREEN));
			Protocol.send(out, Protocol.LIST_ROOMS, "");
		} else {
			System.out.println(Ansi.color("[!] Error: " + response.payload(), Ansi.RED));
			running = false;
			socket.close();
		}
	}

	public void listenForMessages() {
		Thread t = new Thread(() -> {
			int missedHeartbeats = 0; // Tracks silence
			while (running) {
				try {
					Protocol.Message msg = Protocol.receive(in);
					missedHeartbeats = 0; // Reset counter the moment WE HEAR ANYTHING from the server

					switch (msg.type()) {
						case Protocol.HEARTBEAT -> {
							// Just a background ping acknowledgment. Do nothing!
						}
						case Protocol.BROADCAST -> {
							String line = msg.payload();
							if (line.startsWith("Bot: ")) {
								line = Ansi.color(line, Ansi.CYAN);
							}
							System.out.println("\n" + line);
							System.out.print(Ansi.color("> ", Ansi.BOLD));
						}
						case Protocol.ROOM_LIST -> printRoomList(msg.payload());
						case Protocol.JOIN_OK -> {
							System.out.print("\033[H\033[2J");
							System.out.flush();
							String header = "=== Room: " + msg.payload().replace(Ansi.CLEAR_SCREEN, "") + " | User: " + this.username + " ===";
							System.out.println(Ansi.color(header, Ansi.CYAN));
							System.out.print(Ansi.color("> ", Ansi.BOLD));
						}
						case Protocol.JOIN_FAIL -> {
							System.out.println(Ansi.color("[!] " + msg.payload(), Ansi.YELLOW));
							System.out.print(Ansi.color("Create this room? (yes/no): ", Ansi.BOLD));
							isCreationDialog = true;
							dialogStep = DialogStep.CONFIRM;
						}
						case Protocol.CREATE_OK -> {
							System.out.println(Ansi.color("[+] " + msg.payload(), Ansi.GREEN));
							isCreationDialog = false;
							dialogStep = DialogStep.NONE;
							System.out.print(Ansi.color("> ", Ansi.BOLD));
						}
						case Protocol.LOGOUT_OK -> {
							System.out.println(Ansi.color("Logged out.", Ansi.YELLOW));
							running = false;
						}
						case Protocol.ERROR -> System.out.println(Ansi.color("[!] " + msg.payload(), Ansi.RED));
						case Protocol.AI_PROMPT -> {
							System.out.print(Ansi.color(msg.payload(), Ansi.BOLD));
							isCreationDialog = true;
							dialogStep = DialogStep.AI_PROMPT;
						}
						case Protocol.PRIVATE_PROMPT -> {
							System.out.print(Ansi.color(msg.payload(), Ansi.BOLD));
							isCreationDialog = true;
							dialogStep = DialogStep.PRIVATE_CONFIRM;
						}
						case Protocol.PWD_SET_PROMPT -> {
							System.out.print(Ansi.color(msg.payload(), Ansi.BOLD));
							isCreationDialog = true;
							dialogStep = DialogStep.PWD_SET;
						}
						case Protocol.ROOM_PWD_PROMPT -> {
							System.out.print(Ansi.color(msg.payload(), Ansi.BOLD));
							isCreationDialog = true;
							dialogStep = DialogStep.ROOM_PWD;
						}
						case Protocol.ROOM_PWD_FAIL -> {
							System.out.println(Ansi.color("[!] " + msg.payload(), Ansi.RED));
							isCreationDialog = false;
							dialogStep = DialogStep.NONE;
							System.out.print(Ansi.color("> ", Ansi.BOLD));
						}
						default -> System.out.println("[server] " + msg.payload());
					}
				} catch (java.net.SocketTimeoutException e) {
					missedHeartbeats++;
					// 3 missed heartbeats (2.5s x 3 = 7.5 seconds) = Server is completely dead.
					if (missedHeartbeats > 2) {
						if (running) {
							reconnect();
							missedHeartbeats = 0;
						}
						continue;
					}
					// Otherwise, ping the server to demand a response.
					try {
						Protocol.send(out, Protocol.HEARTBEAT, "");
					} catch (IOException ex) {
						if (running) {
							reconnect();
							missedHeartbeats = 0;
						}
					}
				} catch (IOException e) {
					if (running) {
						reconnect();
						missedHeartbeats = 0;
					}
				}
			}
		});
		t.setDaemon(true);
		t.start();
	}

	public void sendMessages(Scanner scanner) {
		while (running) {
			try {
				if (System.in.available() > 0) {
					String line = scanner.nextLine().trim();

					if (!running) continue;

					if (line.isEmpty() && dialogStep != DialogStep.AI_PROMPT)
						continue;

					if (isCreationDialog) {
						handleDialogInput(line);
						continue;
					}

					if (line.startsWith("/")) {
						String[] parts = line.split(" ", 2);
						String coloredLine = Ansi.color(parts[0], Ansi.CYAN)
								+ (parts.length > 1 ? " " + parts[1] : "");
						System.out.print("\033[1A\033[2K");
						System.out.println(Ansi.color("> ", Ansi.BOLD) + coloredLine);
						handleCommand(line);
					} else {
						Protocol.send(out, Protocol.MSG, line);
						System.out.print(Ansi.color("> ", Ansi.BOLD));
					}
				} else {
					Thread.sleep(50);
				}
			} catch (Exception e) {
				System.out.println("[!] Network error. Message failed to send. Wait for reconnect...");
			}
		}
	}

	private void handleDialogInput(String input) throws IOException {
		switch (dialogStep) {

			case CONFIRM -> {
				if (input.equalsIgnoreCase("yes")) {
					Protocol.send(out, Protocol.CREATE_CONFIRM, "yes");
					System.out.print(Ansi.color("Add AI bot? (yes/no): ", Ansi.BOLD));
					dialogStep = DialogStep.TYPE;
				} else {
					// Notify server so it stops waiting for CREATE_CONFIRM.
					Protocol.send(out, Protocol.CREATE_CONFIRM, "no");
					System.out.println(Ansi.color("Room creation cancelled.", Ansi.YELLOW));
					System.out.print(Ansi.color("> ", Ansi.BOLD));
					isCreationDialog = false;
					dialogStep = DialogStep.NONE;
				}
			}

			case TYPE -> {
				boolean isAI = input.equalsIgnoreCase("yes");
				Protocol.send(out, Protocol.CREATE_TYPE, isAI ? "ai" : "normal");
				isCreationDialog = false;
				dialogStep = DialogStep.NONE;
			}

			// User typed the AI system prompt (or pressed Enter for the default).
			case AI_PROMPT -> {
				Protocol.send(out, Protocol.MSG, input);
				isCreationDialog = false;
				dialogStep = DialogStep.NONE;
			}

			// "Make this room private? (yes/no)"
			case PRIVATE_CONFIRM -> {
				Protocol.send(out, Protocol.CREATE_PRIVATE,
						input.equalsIgnoreCase("yes") ? "yes" : "no");
				// If yes, server will send PWD_SET_PROMPT; dialog continues.
				// If no, server proceeds to create the room.
				isCreationDialog = false;
				dialogStep = DialogStep.NONE;
			}

			// "Set a password for this room:"
			case PWD_SET -> {
				Protocol.send(out, Protocol.CREATE_PWD, input);
				isCreationDialog = false;
				dialogStep = DialogStep.NONE;
			}

			// "Enter room password:" — joining an existing private room
			case ROOM_PWD -> {
				Protocol.send(out, Protocol.ROOM_PWD, input);
				isCreationDialog = false;
				dialogStep = DialogStep.NONE;
			}
		}
	}

	private void handleCommand(String line) throws IOException {
		String[] parts = line.split(" ", 2);
		String cmd = parts[0].toLowerCase();
		String arg = parts.length > 1 ? parts[1].trim() : "";

		switch (cmd) {
			case "/list" -> Protocol.send(out, Protocol.LIST_ROOMS, "");
			case "/join" -> {
				if (arg.isEmpty())
					System.out.println("Usage: /join <room>");
				else
					Protocol.send(out, Protocol.JOIN, arg);
			}
			case "/leave" -> {
				// Clear the screen and print the Lobby banner immediately!
				System.out.print("\033[H\033[2J");
				System.out.flush();
				System.out.println(Ansi.color("=== Main Lobby | User: " + this.username + " ===", Ansi.CYAN));

				Protocol.send(out, Protocol.LEAVE, "");
			}			case "/logout" -> {
				try { Protocol.send(out, Protocol.LOGOUT, ""); } catch (Exception ignored) {}
				running = false;
				System.out.println(Ansi.color("Logging out...", Ansi.YELLOW));
			}
			case "/quit" -> {
				try { Protocol.send(out, Protocol.LOGOUT, ""); } catch (Exception ignored) {}
				System.out.println(Ansi.color("Exiting application...", Ansi.YELLOW));
				System.exit(0);
			}
			case "/help" -> printHelp();
			default -> Protocol.send(out, Protocol.MSG, line);
		}
	}

	private void printHelp() {
		System.out.println(Ansi.color("--- Available Commands ---", Ansi.BOLD));
		System.out.println(Ansi.color(" /help", Ansi.CYAN) + "              Show this help message");
		System.out.println(Ansi.color(" /list", Ansi.CYAN) + "              List all active rooms");
		System.out.println(Ansi.color(" /join <room>", Ansi.CYAN) + "         Join or create a room");
		System.out.println(Ansi.color(" /leave", Ansi.CYAN) + "             Leave the current room");
		System.out.println(Ansi.color(" /logout", Ansi.CYAN) + "            Logout and return to auth screen");
		System.out.println(Ansi.color(" /quit", Ansi.CYAN) + "              Close the application entirely");
		System.out.println(Ansi.color("--- AI Room Commands ---", Ansi.BOLD));
		System.out.println(Ansi.color(" /ask <question>", Ansi.MAGENTA) + "      Ask the AI a question");
		System.out.println(Ansi.color(" /summarize [n]", Ansi.MAGENTA)
				+ "       Summarize the last N messages (default 10)");
		System.out.println(Ansi.color(" /translate <lang>", Ansi.MAGENTA) + "    Translate the last message");
		System.out.print(Ansi.color("> ", Ansi.BOLD));
	}

	private void printRoomList(String payload) {
		if (payload.isEmpty()) {
			System.out.println("No active rooms. Use /join <name> to create one.");
		} else {
			System.out.println(Ansi.color("--- Active Rooms ---", Ansi.BOLD));
			for (String room : payload.split("\n")) {
				String display = room
						.replace("[AI]", Ansi.color("[AI]", Ansi.MAGENTA))
						.replace("[Private]", Ansi.color("[Private]", Ansi.YELLOW));
				System.out.println(" " + display);
			}
			System.out.println("Use " + Ansi.color("/join <name>", Ansi.CYAN) + " to enter a room");
			System.out.print(Ansi.color("> ", Ansi.BOLD));
		}
	}

	public void closeEverything() throws IOException {
		running = false;
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {}
		}
	}

	private synchronized void reconnect() {
		if (isReconnecting) return;
		isReconnecting = true;

		try {
			if (socket != null)
				socket.close();
		} catch (IOException ignored) {}

		System.out.println("[*] Connection lost. Commencing recovery loop...");

		while (running) {
			try {
				System.out.println("[*] Attempting to reconnect...");

				// 1. Create an un-connected socket instance using your helper logic
				socket = buildUnconnectedSocket();

				// 2. Bind and attempt connection with a strict 1-second timeout limit
				java.net.InetSocketAddress endpoint = new java.net.InetSocketAddress(this.host, this.port);
				socket.connect(endpoint, 1000); // 1000ms = 1 second timeout limit

				// 3. Re-establish streams if connection succeeds
				in = socket.getInputStream();
				out = socket.getOutputStream();

				Protocol.send(out, Protocol.RECONNECT, token);
				Protocol.Message response = Protocol.receive(in);

				if (response.type() == Protocol.AUTH_OK) {
					String payload = response.payload();
					String[] parts = payload.split(":", 2);
					this.token = parts[0];

					this.socket.setSoTimeout(2500);

					System.out.print("\033[H\033[2J");
					System.out.flush();

					if (parts.length > 1) {
						String activeRoomName = parts[1];
						System.out.println(Ansi.color("=== Room: " + activeRoomName + " | User: " + this.username + " ===", Ansi.CYAN));
					} else {
						System.out.println(Ansi.color("=== Main Lobby | User: " + this.username + " ===", Ansi.CYAN));
					}

					System.out.println(Ansi.color("[+] Reconnected successfully! Resuming session...", Ansi.GREEN));
					System.out.print(Ansi.color("> ", Ansi.BOLD));

					isReconnecting = false;
					return;
				} else {
					System.out.println("[!] Session expired or invalid: " + response.payload());
					running = false;
					isReconnecting = false;
					return;
				}
			} catch (Exception e) {
				// 4. Connection failed fast or timed out.
				System.out.println("[-] Server unreachable. Retrying in 3 seconds...");
				try {
					Thread.sleep(3000); // Guarantees a flat 3-second pause before looping back
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					isReconnecting = false;
					return;
				}
			}
		}
	}

	private static Socket createSocket(String host, int port) throws IOException {
		if (Boolean.getBoolean("ssl")) {
			System.setProperty("javax.net.ssl.trustStore", "keystore.jks");
			System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
			return (Socket) SSLSocketFactory.getDefault().createSocket(host, port);
		}
		return new Socket(host, port);
	}

	public static void main(String[] args) {
		Scanner scanner = new Scanner(System.in);
		String host = "localhost";
		int port = 1234;

		if (args.length > 0) {
			host = args[0];
		}
		if (args.length > 1) {
			try {
				port = Integer.parseInt(args[1]);
			} catch (NumberFormatException e) {
				System.out.println("[-] Invalid port provided. Defaulting to 1234.");
			}
		}

		while (true) {
			System.out.println(Ansi.color("\n=== Application Auth ===", Ansi.CYAN));
			System.out.print(Ansi.color("Username: ", Ansi.BOLD));

			if (!scanner.hasNextLine()) break;

			String username = scanner.nextLine().trim();

			System.out.print(Ansi.color("Register or Login? (r/l): ", Ansi.BOLD));
			boolean register = scanner.nextLine().trim().equalsIgnoreCase("r");

			System.out.print(Ansi.color("Password: ", Ansi.BOLD));
			String password = scanner.nextLine();

			System.out.println("[*] Connecting to server at " + host + ":" + port + "...");

			try {
				Socket socket = createSocket(host, port);
				Client client = new Client(socket, username, host, port);

				client.connect(register, password);

				if (client.running) {
					client.listenForMessages();
					client.sendMessages(scanner);
				}

				client.closeEverything();

			} catch (IOException e) {
				System.out.println(Ansi.color("[!] Connection failed: " + e.getMessage(), Ansi.RED));
				System.out.println("Retrying in 3 seconds...");
				try { Thread.sleep(3000); } catch (InterruptedException ie) {}
			}
		}
		scanner.close();
	}

	private Socket buildUnconnectedSocket() throws IOException {
		if (Boolean.getBoolean("ssl")) {
			System.setProperty("javax.net.ssl.trustStore", "keystore.jks");
			System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
			return SSLSocketFactory.getDefault().createSocket();
		}
		return new Socket();
	}
}
