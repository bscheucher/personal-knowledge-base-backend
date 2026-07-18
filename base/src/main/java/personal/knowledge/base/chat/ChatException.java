package personal.knowledge.base.chat;

/** Safe boundary exception for failures that occur before an SSE stream starts. */
public class ChatException extends RuntimeException {
    public ChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
