import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ClientHandler implements Runnable {

	public static final ArrayList<ClientHandler> clientHandlers = new ArrayList<>();
	private static final ReentrantLock handlersLock = new ReentrantLock();

	public static UserManager userManager;
	public static SessionManager sessionManager;
	public static RoomManager roomManager;

	private final BoundedBlockingQueue<String> outMessages = new BoundedBlockingQueue<>(200);

	private final Socket socket;
	private InputStream in;
	private OutputStream out;
	private String username;
	private String token;
	private Room currentRoom;

	public ClientHandler(Socket socket) {
		this.socket = socket;
		if (socket != null) {
			try {
				this.in = socket.getInputStream();
				this.out = socket.getOutputStream();
				startWriterThread();
			} catch (IOException e) {
				closeEverything();
			}
		}
	}

	public void enqueueMessage(String message) {
		if (!outMessages.offer(message)) {
			System.out.println("[!] Client " + username + " is too slow. Disconnecting.");
			closeEverything();
		}
	}

	private void startWriterThread() {
		Thread.ofVirtual().start(() -> {
			try {
				while (socket != null && !socket.isClosed()) {
					String msg = outMessages.take();
					Protocol.send(out, Protocol.BROADCAST, msg);
				}
			} catch (InterruptedException | IOException e) {
				closeEverything();
			}
		});
	}

	private void disconnectDuplicateUser(String targetUsername) {
		ClientHandler duplicate = null;
		handlersLock.lock();
		try {
			for (ClientHandler ch : clientHandlers) {
				if (targetUsername.equals(ch.username)) {
					duplicate = ch;
					break;
				}
			}
		} finally {
			handlersLock.unlock();
		}

		if (duplicate != null) {
			System.out.println("[*] Kicking old connection for user: " + targetUsername);
			try {
				Protocol.send(duplicate.getOutputStream(), Protocol.ERROR,
						"You logged in from another location. Disconnecting...");
				Protocol.send(duplicate.getOutputStream(), Protocol.LOGOUT_OK, "");
			} catch (Exception ignored) {
			}

			if (duplicate.token != null)
				sessionManager.removeSession(duplicate.token);
			duplicate.closeEverything();
		}
	}

	@Override
	public void run() {
		boolean networkDropped = false;
		try {
			if (!authenticate())
				return;

			handlersLock.lock();
			try {
				clientHandlers.add(this);
			} finally {
				handlersLock.unlock();
			}

			String authPayload = token + (currentRoom != null ? ":" + currentRoom.getName() : "");
			Protocol.send(out, Protocol.AUTH_OK, authPayload);

			while (socket.isConnected()) {
				Protocol.Message msg = Protocol.receive(in);
				switch (msg.type()) {
					case Protocol.HEARTBEAT -> Protocol.send(out, Protocol.HEARTBEAT, "");
					case Protocol.MSG -> handleMessage(msg.payload());
					case Protocol.JOIN -> handleJoin(msg.payload());
					case Protocol.LEAVE -> handleLeave();
					case Protocol.LOGOUT -> handleLogout();
					case Protocol.LIST_ROOMS -> handleListRooms();
					default -> Protocol.send(out, Protocol.ERROR, "Unknown command");
				}
			}
		} catch (IOException e) {
			networkDropped = true;
			handleNetworkDrop();
			return;
		} finally {
			if (!networkDropped) {
				closeEverything();
			}
		}
	}

	private boolean authenticate() throws IOException {
		Protocol.Message msg = Protocol.receive(in);

		if (msg.type() == Protocol.REGISTER) {
			String[] parts = msg.payload().split("\n", 2);
			if (parts.length < 2) {
				Protocol.send(out, Protocol.ERROR, "Invalid registration format");
				return false;
			}
			if (!userManager.register(parts[0], parts[1])) {
				Protocol.send(out, Protocol.ERROR,
						"Username '" + parts[0] + "' already exists. Please login instead.");
				return false;
			}
			username = parts[0];
			token = sessionManager.createSession(username);
			return true;
		}

		if (msg.type() == Protocol.AUTH) {
			String[] parts = msg.payload().split("\n", 2);
			if (parts.length < 2 || !userManager.authenticate(parts[0], parts[1])) {
				Protocol.send(out, Protocol.ERROR, "Invalid credentials");
				return false;
			}
			username = parts[0];
			disconnectDuplicateUser(username);
			token = sessionManager.createSession(username);
			return true;
		}

		if (msg.type() == Protocol.RECONNECT) {
			SessionManager.Session session = sessionManager.getSession(msg.payload());
			if (session == null) {
				Protocol.send(out, Protocol.ERROR, "Invalid token");
				return false;
			}
			username = session.username;
			token = msg.payload();
			if (session.currentRoom != null) {
				currentRoom = roomManager.getRoom(session.currentRoom);
				if (currentRoom != null)
					currentRoom.addParticipant(this);
			}
			return true;
		}

		Protocol.send(out, Protocol.ERROR, "Expected AUTH, REGISTER or RECONNECT");
		return false;
	}

	public void handleMessage(String text) throws IOException {
		if (currentRoom == null) {
			Protocol.send(out, Protocol.ERROR, "Not in a room");
			return;
		}

		String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
		String formattedMessage = "[" + time + "] " + username + ": " + text;

		if (currentRoom instanceof AIRoom aiRoom && text.startsWith("/")) {
			if (text.startsWith("/ask ") || text.startsWith("/summarize") || text.startsWith("/translate ")) {

				List<String> historySnapshot = currentRoom.getHistory();

				currentRoom.broadcast(formattedMessage, this);

				String response = aiRoom.handleAICommand(text, historySnapshot);
				if (response != null) {
					currentRoom.broadcast("[" + time + "] Bot: " + response, null);
				}
				return;
			}
		}

		currentRoom.broadcast(formattedMessage, this);
	}

	private void handleJoin(String roomName) throws IOException {
		if (currentRoom != null)
			handleLeave();

		Room room = roomManager.getRoom(roomName);

		if (room != null) {
			// ── Room exists: check password if private ──────────────────────
			if (room.isPrivate()) {
				Protocol.send(out, Protocol.ROOM_PWD_PROMPT, "Enter room password: ");
				Protocol.Message pwdMsg = receiveIgnoringHeartbeats(); // <--- UPDATED

				boolean correct = pwdMsg.type() == Protocol.ROOM_PWD
						&& room.checkPassword(pwdMsg.payload());

				if (!correct) {
					Protocol.send(out, Protocol.ROOM_PWD_FAIL,
							"Incorrect password. Access denied.");
					return;
				}
			}
			enterRoom(room, roomName);
			return;
		}

		List<String> existing = roomManager.listRooms();
		String activeList = existing.isEmpty() ? "none" : String.join(", ", existing);
		Protocol.send(out, Protocol.JOIN_FAIL,
				"Room '" + roomName + "' does not exist. Active rooms: [ " + activeList + " ]");

		Protocol.Message confirm = receiveIgnoringHeartbeats();
		if (confirm.type() != Protocol.CREATE_CONFIRM
				|| !confirm.payload().equalsIgnoreCase("yes")) {
			return;
		}

		Protocol.Message typeMsg = receiveIgnoringHeartbeats();
		if (typeMsg.type() != Protocol.CREATE_TYPE)
			return;
		boolean isAI = typeMsg.payload().trim().equalsIgnoreCase("ai");

		String systemPrompt = "";
		if (isAI) {
			Protocol.send(out, Protocol.AI_PROMPT,
					"Enter a system prompt for the AI bot (or press Enter for default): ");
			Protocol.Message promptMsg = receiveIgnoringHeartbeats();
			systemPrompt = promptMsg.payload().trim();
			if (systemPrompt.isEmpty())
				systemPrompt = AIRoom.DEFAULT_SYSTEM_PROMPT;
		}

		Protocol.send(out, Protocol.PRIVATE_PROMPT, "Make this room private? (yes/no): ");
		Protocol.Message privateMsg = receiveIgnoringHeartbeats();
		if (privateMsg.type() != Protocol.CREATE_PRIVATE)
			return;
		boolean isPrivate = privateMsg.payload().equalsIgnoreCase("yes");

		String passwordHash = null;
		if (isPrivate) {
			Protocol.send(out, Protocol.PWD_SET_PROMPT, "Set a password for this room: ");
			Protocol.Message setPwdMsg = receiveIgnoringHeartbeats();
			if (setPwdMsg.type() != Protocol.CREATE_PWD)
				return;

			String pwd = setPwdMsg.payload().trim();
			if (pwd.isEmpty()) {
				isPrivate = false;
			} else {
				passwordHash = Room.hash(pwd);
			}
		}

		roomManager.createRoom(roomName, isAI, systemPrompt, isPrivate, passwordHash);
		room = roomManager.getRoom(roomName);

		String typeLabel = isAI ? "AI" : "Normal";
		String privacyLabel = isPrivate ? " [Private]" : "";
		Protocol.send(out, Protocol.CREATE_OK,
				"Room '" + roomName + "' created. Type: " + typeLabel + privacyLabel);

		enterRoom(room, roomName);
	}

	private void enterRoom(Room room, String roomName) throws IOException {
		currentRoom = room;
		currentRoom.addParticipant(this);
		sessionManager.updateRoom(token, roomName);

		Protocol.send(out, Protocol.JOIN_OK, Ansi.CLEAR_SCREEN + roomName);

		for (String pastMsg : currentRoom.getHistory()) {
			enqueueMessage(pastMsg);
		}

		currentRoom.broadcast("[ " + username + " has joined the room ]", this, false);
	}

	private void handleLeave() {
		if (currentRoom == null)
			return;

		String roomName = currentRoom.getName(); // Save the name before we leave
		currentRoom.removeParticipant(this);
		currentRoom.broadcast("[ " + username + " has left the room ]", null, false);
		currentRoom = null;
		sessionManager.updateRoom(token, null);

		try {
			Protocol.send(out, Protocol.MSG, "You have left the room '" + roomName + "'.");
			handleListRooms(); // Automatically print the "after logging" page
		} catch (IOException e) {
		}
	}

	private void handleLogout() throws IOException {
		handleLeave();
		sessionManager.removeSession(token);
		token = null;
		Protocol.send(out, Protocol.LOGOUT_OK, "");
		closeEverything();
	}

	private void handleListRooms() throws IOException {
		List<String> rooms = roomManager.listRooms();

		String payload = rooms.isEmpty() ? "" : String.join("\n", rooms);;

		Protocol.send(out, Protocol.ROOM_LIST, payload);
	}

	public OutputStream getOutputStream() {
		return out;
	}

	private void closeEverything() {
		if (username != null) {
			handleLeave();
			handlersLock.lock();
			try {
				clientHandlers.remove(this);
			} finally {
				handlersLock.unlock();
			}
		}
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void handleNetworkDrop() {
		if (username != null) {
			if (currentRoom != null) {
				currentRoom.removeParticipant(this);
				currentRoom.broadcast("[ " + username + " is temporarily disconnected ]", null, false);
			}

			handlersLock.lock();
			try {
				clientHandlers.remove(this);
			} finally {
				handlersLock.unlock();
			}
		}

		if (socket != null) {
			try {
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private Protocol.Message receiveIgnoringHeartbeats() throws IOException {
		while (socket.isConnected()) {
			Protocol.Message msg = Protocol.receive(in);
			if (msg.type() == Protocol.HEARTBEAT) {
				// Echo it back immediately so the client doesn't drop the connection
				Protocol.send(out, Protocol.HEARTBEAT, "");
				continue;
			}
			return msg;
		}
		throw new EOFException("Connection closed");
	}
}
