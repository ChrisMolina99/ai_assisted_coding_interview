package brex.interview.rest;

import static brex.interview.converter.ModelConverters.toDTO;

import brex.interview.dto.MessageDTO;
import brex.interview.service.MessageService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
public class MessageRestController {

    private final MessageService messageService;

    public MessageRestController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/latest")
    public List<MessageDTO> getLatestMessages(@RequestParam(defaultValue = "10") int limit) {
        return messageService.getLatestMessages(limit).stream()
                .map(message -> toDTO(message))
                .toList();
    }

    @PostMapping
    public MessageDTO createMessage() {
        return toDTO(messageService.createMessage());
    }
}
