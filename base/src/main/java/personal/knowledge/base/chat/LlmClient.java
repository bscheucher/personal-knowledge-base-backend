package personal.knowledge.base.chat;

import java.util.List;

import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Thin wrapper around the Spring AI {@link ChatModel} that streams assistant tokens. */
@Service
public class LlmClient {

    private final ChatModel chatModel;

    public LlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Streams the assistant's reply as a sequence of text tokens, given a system prompt
     * (instructions + grounding context) and the user's message.
     */
    public Flux<String> stream(String systemPrompt, String userMessage) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)));
        // Use mapNotNull, not map+filter: Reactor's map throws if the mapper returns
        // null, and the final chunk of an OpenAI stream has a null result/text. With
        // plain map that NPE aborts the stream before it completes; mapNotNull drops
        // the null elements instead.
        return chatModel
                .stream(prompt)
                .mapNotNull(ChatResponse::getResult)
                .mapNotNull(Generation::getOutput)
                .mapNotNull(AbstractMessage::getText)
                .filter(text -> !text.isEmpty());
    }
}
