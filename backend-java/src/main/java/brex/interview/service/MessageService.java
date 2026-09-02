package brex.interview.service;

import brex.interview.model.Message;
import brex.interview.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class MessageService {

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public List<Message> getLatestMessages(int limit) {
        PageRequest page = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return messageRepository.findAll(page).getContent();
    }

    public Message createMessage() {
        Message message = new Message(UUID.randomUUID(), "Hello World", LocalDateTime.now());
        return messageRepository.save(message);
    }
}
