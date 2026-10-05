package brex.interview.transfer;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import static brex.interview.transfer.TransferTypes.*;
import static brex.interview.transfer.MoneyRequestTypes.*;

@Service
public class MoneyRequestService {
    private static final Set<String> STATUSES = Set.of("PENDING", "PAID", "DECLINED", "CANCELLED");
    private static final String SELECT =
            "SELECT r.*, rb.display_name requester_name, pb.display_name payer_name "
            + "FROM money_requests r "
            + "JOIN accounts ra ON ra.id=r.requester_account_id JOIN businesses rb ON rb.id=ra.business_id "
            + "JOIN accounts pa ON pa.id=r.payer_account_id JOIN businesses pb ON pb.id=pa.business_id ";
    private final JdbcTemplate db;
    private final TransferService transfers;
    private final TransactionTemplate tx;

    public MoneyRequestService(JdbcTemplate db, TransferService transfers, PlatformTransactionManager manager) {
        this.db = db;
        this.transfers = transfers;
        this.tx = new TransactionTemplate(manager);
    }

    public Outcome create(UUID business, String key, CreateMoneyRequest input) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (input == null) throw new Rejection(400, "INVALID_INPUT", "Request body is required.");
        if (input.payerAccountId() == null) fields.put("payerAccountId", "Choose a payer.");
        if (input.amountInCents() == null || input.amountInCents() < 1 || input.amountInCents() > TransferService.MAX_AMOUNT) {
            fields.put("amountInCents", "Enter integer cents between 1 and 1000000000.");
        }
        if (input.reason() != null && input.reason().length() > 280) fields.put("reason", "Reason must be at most 280 characters.");
        if (!fields.isEmpty()) throw new Rejection(400, "INVALID_INPUT", "Check the request details.", fields);
        Account actor = transfers.account(business);
        return idempotent(actor.id(), "create", key, transfers.write(input), () -> {
            if (actor.id().equals(input.payerAccountId())) throw new Rejection(422, "SELF_REQUEST", "Choose a different payer account.");
            List<String> currency = db.query("SELECT currency FROM accounts WHERE id=?", (r, n) -> r.getString(1), input.payerAccountId());
            if (currency.isEmpty()) throw new Rejection(404, "ACCOUNT_NOT_FOUND", "Payer account not found.");
            if (!actor.currency().equals(currency.get(0))) throw new Rejection(422, "UNSUPPORTED_CURRENCY", "Accounts must use the same currency.");
            UUID id = UUID.randomUUID();
            db.update("INSERT INTO money_requests(id,requester_account_id,payer_account_id,amount_in_cents,currency,reason,status,created_at) VALUES(?,?,?,?,?,?,'PENDING',?)",
                    id, actor.id(), input.payerAccountId(), input.amountInCents(), "USD", input.reason(), timestamp(now()));
            return new Outcome(201, transfers.write(find(actor.id(), id)));
        });
    }

    public MoneyRequest get(UUID business, UUID id) {
        return find(transfers.account(business).id(), id);
    }

    private MoneyRequest find(UUID actor, UUID id) {
        return db.query(SELECT + "WHERE r.id=? AND (r.requester_account_id=? OR r.payer_account_id=?)",
                (r, n) -> row(r), id, actor, actor).stream().findFirst()
                .orElseThrow(() -> new Rejection(404, "REQUEST_NOT_FOUND", "Money request not found."));
    }

    public Outcome action(UUID business, UUID id, String action, String body, String key) {
        Account actor = transfers.account(business);
        return idempotent(actor.id(), action, key, id + ":" + body, () -> {
            // Lock only the request row first; account joins must not acquire account locks out of order.
            List<UUID> locked = db.query("SELECT id FROM money_requests WHERE id=? AND (requester_account_id=? OR payer_account_id=?) FOR UPDATE",
                    (r, n) -> r.getObject(1, UUID.class), id, actor.id(), actor.id());
            if (locked.isEmpty()) throw new Rejection(404, "REQUEST_NOT_FOUND", "Money request not found.");
            MoneyRequest request = find(actor.id(), id);
            boolean allowed = action.equals("cancel") ? request.requesterAccountId().equals(actor.id()) : request.payerAccountId().equals(actor.id());
            if (!allowed) throw new Rejection(403, "FORBIDDEN_ACTION", "Your account cannot perform this action.");
            if (!request.status().equals("PENDING")) throw new Rejection(409, "REQUEST_NOT_PENDING", "This request has already been resolved.");
            UUID transferId = null;
            String terminal;
            switch (action) {
                case "pay" -> {
                    Transfer transfer = transfers.moveFunds(request.payerAccountId(), request.requesterAccountId(), request.amountInCents(), request.reason());
                    transferId = transfer.transferId();
                    terminal = "PAID";
                }
                case "decline" -> terminal = "DECLINED";
                case "cancel" -> terminal = "CANCELLED";
                default -> throw new Rejection(400, "INVALID_ACTION", "Invalid money request action.");
            }
            db.update("UPDATE money_requests SET status=?,transfer_id=?,resolved_at=? WHERE id=?", terminal, transferId, timestamp(now()), id);
            return new Outcome(200, transfers.write(find(actor.id(), id)));
        });
    }

    private Outcome idempotent(UUID actor, String action, String key, String input, Supplier<Outcome> work) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new Rejection(400, "INVALID_IDEMPOTENCY_KEY", "Provide a valid Idempotency-Key of 1–128 characters.");
        }
        String fingerprint = transfers.hash(input);
        try {
            return tx.execute(status -> {
                List<Outcome> previous = stored(actor, action, key, fingerprint);
                if (!previous.isEmpty()) return previous.get(0);
                db.update("INSERT INTO money_request_actions(id,actor_account_id,action,idempotency_key,request_fingerprint) VALUES(?,?,?,?,?)",
                        UUID.randomUUID(), actor, action, key, fingerprint);
                Outcome outcome;
                try {
                    outcome = work.get();
                } catch (Rejection rejected) {
                    outcome = new Outcome(rejected.status, transfers.write(rejected.error));
                }
                db.update("UPDATE money_request_actions SET http_status=?,response_payload=? WHERE actor_account_id=? AND action=? AND idempotency_key=?",
                        outcome.status(), outcome.payload(), actor, action, key);
                return outcome;
            });
        } catch (DuplicateKeyException duplicate) {
            return tx.execute(status -> stored(actor, action, key, fingerprint).stream().findFirst().orElseThrow(() -> duplicate));
        }
    }

    private List<Outcome> stored(UUID actor, String action, String key, String fingerprint) {
        return db.query("SELECT request_fingerprint,http_status,response_payload FROM money_request_actions WHERE actor_account_id=? AND action=? AND idempotency_key=?",
                (r, n) -> {
                    if (!fingerprint.equals(r.getString(1))) throw new Rejection(409, "IDEMPOTENCY_CONFLICT", "This key was already used for different request details.");
                    return new Outcome(r.getInt(2), r.getString(3));
                }, actor, action, key);
    }

    public Page<MoneyRequest> list(UUID business, String direction, String status, int limit, String cursor) {
        UUID actor = transfers.account(business).id();
        transfers.limit(limit);
        if (!Set.of("incoming", "outgoing").contains(direction)) throw new Rejection(400, "INVALID_DIRECTION", "Choose incoming or outgoing requests.");
        if (status != null && !STATUSES.contains(status)) throw new Rejection(400, "INVALID_STATUS", "Invalid request status.");
        String scope = "money-requests:" + actor + ":" + direction + ":" + status;
        String boundary = transfers.decode(cursor, scope);
        StringBuilder sql = new StringBuilder(SELECT + "WHERE r." + (direction.equals("incoming") ? "payer_account_id" : "requester_account_id") + "=?");
        List<Object> args = new ArrayList<>(List.of(actor));
        if (status != null) {
            sql.append(" AND r.status=?");
            args.add(status);
        }
        if (boundary != null) {
            String[] parts = boundary.split("\\|", -1);
            if (parts.length != 2) throw new Rejection(400, "INVALID_CURSOR", "Invalid cursor.");
            Instant time = transfers.date(parts[0]);
            UUID id = transfers.uuid(parts[1]);
            sql.append(" AND (r.created_at<? OR (r.created_at=? AND r.id<?))");
            args.add(timestamp(time));
            args.add(timestamp(time));
            args.add(id);
        }
        sql.append(" ORDER BY r.created_at DESC,r.id DESC LIMIT ?");
        args.add(limit + 1);
        List<MoneyRequest> rows = db.query(sql.toString(), (r, n) -> row(r), args.toArray());
        boolean more = rows.size() > limit;
        List<MoneyRequest> page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        MoneyRequest last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new Page<>(page, more ? transfers.encode(scope, last.createdAt() + "|" + last.requestId()) : null);
    }

    private MoneyRequest row(ResultSet r) throws SQLException {
        OffsetDateTime resolved = r.getObject("resolved_at", OffsetDateTime.class);
        return new MoneyRequest(r.getObject("id", UUID.class), r.getObject("requester_account_id", UUID.class),
                r.getObject("payer_account_id", UUID.class), r.getString("requester_name"), r.getString("payer_name"),
                r.getLong("amount_in_cents"), r.getString("currency"), r.getString("reason"), r.getString("status"),
                r.getObject("transfer_id", UUID.class), r.getObject("created_at", OffsetDateTime.class).toInstant(),
                resolved == null ? null : resolved.toInstant());
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
