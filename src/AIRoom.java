import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class AIRoom extends Room {

    public static final String DEFAULT_MODEL = "gemma3:1b";
    public static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant in a group chat. Keep responses concise.";

    private static final String OLLAMA_URL = System.getProperty("ollama.url", "http://localhost:11434");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final String FALLBACK = "Bot: [unavailable]";
    private static final int CONTEXT_MESSAGES = 10;

    private final String systemPrompt;
    private final String model;

    public AIRoom(String name, String systemPrompt, String model,
            boolean isPrivate, String passwordHash) {
        super(name, isPrivate, passwordHash);
        this.systemPrompt = systemPrompt;
        this.model = model;
    }

    public AIRoom(String name, String systemPrompt, String model) {
        this(name, systemPrompt, model, false, null);
    }

    public AIRoom(String name) {
        this(name, DEFAULT_SYSTEM_PROMPT, DEFAULT_MODEL, false, null);
    }

    private String callOllama(String prompt) {
        try {
            String escaped = prompt
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n");

            String body = "{\"model\":\"" + model + "\",\"prompt\":\""
                    + escaped + "\",\"stream\":false}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL + "/api/generate"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "[error: Ollama returned HTTP " + response.statusCode() + "]";
            }

            return extractJsonField(response.body(), "response");
        } catch (Exception e) {
            return FALLBACK;
        }
    }

    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);

        if (start == -1)
            return "[parse error]";

        start += key.length();
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\')
                break;
            end++;
        }

        return json.substring(start, end)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private int parseN(String s, int defaultVal) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String handleAICommand(String text, List<String> history) {
        if (text.startsWith("/ask ")) {
            String question = text.substring(5).trim();
            return callOllama(systemPrompt + "\n\nAnswer this question: " + question);
        }

        if (text.equals("/summarize") || text.startsWith("/summarize ")) {
            int n = CONTEXT_MESSAGES;
            if (text.startsWith("/summarize ")) {
                n = parseN(text.substring(11).trim(), CONTEXT_MESSAGES);
            }

            if (history.isEmpty()) {
                return "There is no conversation history to summarize yet!";
            }

            List<String> last = history.subList(Math.max(0, history.size() - n), history.size());
            String conversationToSummarize = String.join("\n", last);

            String promptInstruction;
            if (last.size() < 4) {
                promptInstruction = "The conversation history below is very brief. Provide a single, short sentence explaining what has happened so far.";
            } else {
                promptInstruction = "Summarize the following conversation logs concisely in 2-3 sentences.";
            }

            String fullPrompt = systemPrompt + "\n\n" + promptInstruction
                    + "\n\nCONVERSATION LOGS:\n---\n" + conversationToSummarize + "\n---\n\nSummary:";

            return callOllama(fullPrompt);
        }

        if (text.startsWith("/translate ")) {
            String lang = text.substring(11).trim();
            String lastMsg = history.isEmpty() ? "" : history.getLast();
            return callOllama("Translate the following message to " + lang + ":\n" + lastMsg);
        }

        return null;
    }
}
