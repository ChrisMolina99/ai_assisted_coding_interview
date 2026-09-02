package brex.interview.dto;

import java.util.UUID;

public record MessageDTO(UUID id, String content, String createdAt) {
}
