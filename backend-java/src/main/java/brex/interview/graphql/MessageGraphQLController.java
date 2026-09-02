package brex.interview.graphql;

import static brex.interview.converter.ModelConverters.toDTO;

import brex.interview.dto.MessageDTO;
import brex.interview.service.MessageService;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class MessageGraphQLController {

    private final MessageService messageService;

    public MessageGraphQLController(MessageService messageService) {
        this.messageService = messageService;
    }

    @QueryMapping
    public List<MessageDTO> latestMessages(@Argument("limit") Integer limit) {
        int requestedLimit = limit == null ? 10 : limit;
        return messageService.getLatestMessages(requestedLimit).stream()
                .map(message -> toDTO(message))
                .toList();
    }

    @MutationMapping
    public MessageDTO createMessage() {
        return toDTO(messageService.createMessage());
    }
}
