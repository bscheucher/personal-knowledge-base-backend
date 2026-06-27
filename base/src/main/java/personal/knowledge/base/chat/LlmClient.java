package personal.knowledge.base.chat;

import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
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
        return chatModel
                .stream(prompt)
                .map(response -> response.getResult())
                .filter(Objects::nonNull)
                .map(Generation::getOutput)
                .map(message -> message.getText())
                .filter(text -> text != null && !text.isEmpty());
    }
}
