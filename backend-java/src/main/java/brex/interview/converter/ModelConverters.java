package brex.interview.converter;

import brex.interview.dto.MessageDTO;
import brex.interview.model.Message;
import java.time.format.DateTimeFormatter;

public final class ModelConverters {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private ModelConverters() {
    }

    public static MessageDTO toDTO(Message message) {
        if (message.getId() == null) {
            throw new IllegalStateException("Message ID cannot be null");
        }

        String createdAt = message.getCreatedAt() == null
                ? ""
                : message.getCreatedAt().format(DATE_TIME_FORMATTER);
        return new MessageDTO(message.getId(), message.getContent(), createdAt);
    }
}
