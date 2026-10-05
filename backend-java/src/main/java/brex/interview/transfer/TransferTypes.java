package brex.interview.transfer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
public final class TransferTypes {
    private TransferTypes() {
    }

    public record Business(UUID id, String displayName) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    public record Account(UUID id, UUID businessId, String displayName, String currency, long balanceInCents) {
    }

    public record Recipient(UUID id, UUID businessId, String displayName, String currency) {
    }

    public record CreateTransfer(UUID recipientAccountId,
                                 @JsonDeserialize(using = IntegerCentsDeserializer.class) Long amountInCents,
                                 String reason) {
    }

    /** Money must be an integer JSON number; strings and fractional numbers are invalid. */
    public static class IntegerCentsDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }

    public record Transfer(UUID transferId, UUID senderAccountId, UUID recipientAccountId, String senderDisplayName, String recipientDisplayName, long amountInCents, String currency, String reason, String status, Instant createdAt) {
    }

    public record ApiError(String code, String message, Map<String,String> fieldErrors) {
    }

    public record Outcome(int status, String payload) {
    }

    public static class Rejection extends RuntimeException {
        final int status;
        final ApiError error;
        public Rejection(int status, String code, String message) {
            this(status, code, message, Map.of());
        }
        public Rejection(int status, String code, String message, Map<String,String> fields) {
            super(message);
            this.status=status;
            this.error=new ApiError(code,message,fields);
        }
    }
}
