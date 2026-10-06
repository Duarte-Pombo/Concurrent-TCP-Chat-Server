public class Ansi {

    public static final String RESET   = "\033[0m";
    public static final String BOLD    = "\033[1m";
    public static final String RED     = "\033[31m";
    public static final String GREEN   = "\033[32m";
    public static final String YELLOW  = "\033[33m";
    public static final String CYAN    = "\033[36m";
    public static final String MAGENTA = "\033[35m";
    public static final String CLEAR_SCREEN = "\033[H\033[2J";

    public static String color(String text, String code) {
        return code + text + RESET;
    }
}
