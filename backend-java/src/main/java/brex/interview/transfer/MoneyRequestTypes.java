package brex.interview.transfer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.UUID;

public final class MoneyRequestTypes {
    private MoneyRequestTypes() {}

    public record CreateMoneyRequest(UUID payerAccountId,
            @JsonDeserialize(using = TransferTypes.IntegerCentsDeserializer.class) Long amountInCents,
            String reason) {}

    public record MoneyRequest(UUID requestId, UUID requesterAccountId, UUID payerAccountId,
            String requesterDisplayName, String payerDisplayName, long amountInCents, String currency,
            String reason, String status, UUID transferId, Instant createdAt, Instant resolvedAt) {}
}
