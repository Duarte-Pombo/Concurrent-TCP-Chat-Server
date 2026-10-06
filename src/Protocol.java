import java.io.*;
import java.nio.charset.StandardCharsets;

public class Protocol {

    // Shared Opcodes
    public static final byte MSG            = 0x01;
    public static final byte BROADCAST      = 0x02;
    public static final byte AUTH           = 0x03;
    public static final byte AUTH_OK        = 0x04;
    public static final byte REGISTER       = 0x05;
    public static final byte JOIN           = 0x06;
    public static final byte LEAVE          = 0x07;
    public static final byte RECONNECT      = 0x08;
    public static final byte HEARTBEAT      = 0x09;
    public static final byte ERROR          = 0x0A;
    public static final byte LOGOUT         = 0x0B;
    public static final byte LOGOUT_OK      = 0x0C;
    public static final byte LIST_ROOMS     = 0x12;
    public static final byte ROOM_LIST      = 0x13;
    public static final byte JOIN_OK        = 0x14;
    public static final byte JOIN_FAIL      = 0x15;
    public static final byte CREATE_CONFIRM = 0x16;
    public static final byte CREATE_TYPE    = 0x17;
    public static final byte CREATE_OK      = 0x18;
    public static final byte AI_PROMPT      = 0x19;

    public static final byte ROOM_PWD_PROMPT = 0x1A;
    public static final byte ROOM_PWD        = 0x1B;
    public static final byte ROOM_PWD_FAIL   = 0x1C;
    public static final byte PRIVATE_PROMPT  = 0x1D;
    public static final byte CREATE_PRIVATE  = 0x1E;
    public static final byte PWD_SET_PROMPT  = 0x1F;
    public static final byte CREATE_PWD      = 0x20;

    public static void send(OutputStream out, byte type, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(body.length);
        dos.writeByte(type);
        dos.write(body);
        dos.flush();
    }

    public static Message receive(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int length = dis.readInt();
        byte type = dis.readByte();
        byte[] body = dis.readNBytes(length);
        return new Message(type, new String(body, StandardCharsets.UTF_8));
    }

    public record Message(byte type, String payload) {}
}
