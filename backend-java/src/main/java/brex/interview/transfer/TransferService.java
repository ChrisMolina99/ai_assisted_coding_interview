package brex.interview.transfer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import static brex.interview.transfer.TransferTypes.*;
@Service
public class TransferService {
    public static final long MAX_AMOUNT=1_000_000_000L;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public TransferService(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json) {
        this.db=db;
        this.tx=new TransactionTemplate(manager);
        this.json=json;
    }

    public Page<Business> businesses() {
        return new Page<>(db.query("SELECT id,display_name FROM businesses ORDER BY display_name,id",(r,n)->new Business(r.getObject(1,UUID.class),r.getString(2))),null);
    }

    public Account account(UUID business) {
        return db.query("SELECT a.*,b.display_name FROM accounts a JOIN businesses b ON b.id=a.business_id WHERE business_id=?",(r,n)->accountRow(r),business).stream().findFirst().orElseThrow(()->new Rejection(404,"ACCOUNT_NOT_FOUND","Business account not found."));
    }

    private Account accountRow(ResultSet r) throws SQLException {
        return new Account(r.getObject("id",UUID.class),r.getObject("business_id",UUID.class),r.getString("display_name"),r.getString("currency"),r.getLong("balance_in_cents"));
    }

    public Page<Recipient> recipients(UUID business,int limit,String cursor) {
        Account own=account(business);
        limit=limit(limit);
        String scope="recipients:"+own.id();
        String boundary=decode(cursor,scope);
        List<Recipient> rows=db.query("SELECT a.*,b.display_name FROM accounts a JOIN businesses b ON b.id=a.business_id WHERE a.id<>? AND CAST(a.id AS VARCHAR)>? ORDER BY a.id LIMIT ?",(r,n)->new Recipient(r.getObject("id",UUID.class),r.getObject("business_id",UUID.class),r.getString("display_name"),r.getString("currency")),own.id(),boundary==null?"":uuid(boundary).toString(),limit+1);
        boolean more=rows.size()>limit;
        List<Recipient> page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new Page<>(page,more?encode(scope,page.get(page.size()-1).id().toString()):null);
    }

    public Outcome create(UUID business,String key,CreateTransfer request) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new Rejection(400,"INVALID_IDEMPOTENCY_KEY","Provide an Idempotency-Key of 1–128 letters, digits, dots, underscores, colons or hyphens.");
        if(request==null) throw new Rejection(400,"INVALID_INPUT","Transfer body is required.");
        Map<String,String> fields=new LinkedHashMap<>();
        if(request.recipientAccountId()==null) fields.put("recipientAccountId","Choose a recipient.");
        if(request.amountInCents()==null || request.amountInCents()<1 || request.amountInCents()>MAX_AMOUNT) fields.put("amountInCents","Enter integer cents between 1 and 1000000000.");
        if(request.reason()!=null && request.reason().length()>280) fields.put("reason","Reason must be at most 280 characters.");
        if(!fields.isEmpty()) throw new Rejection(400,"INVALID_INPUT","Check the transfer details.",fields);
        Account sender=account(business);
        String fingerprint=hash(write(request));
        try {
            return tx.execute(status -> {
                List<Outcome> prior=stored(sender.id(),key,fingerprint);
                if(!prior.isEmpty()) return prior.get(0);
                db.update("INSERT INTO transfer_requests(id,sender_account_id,idempotency_key,request_fingerprint) VALUES(?,?,?,?)",UUID.randomUUID(),sender.id(),key,fingerprint);
                Outcome outcome;
                UUID transferId=null;
                try {
                    Transfer transfer = moveFunds(sender.id(), request.recipientAccountId(), request.amountInCents(), request.reason());
                    transferId = transfer.transferId();
                    outcome = new Outcome(201, write(transfer));
                }
                catch(Rejection rejection) {
                    outcome=new Outcome(rejection.status,write(rejection.error));
                }
                db.update("UPDATE transfer_requests SET outcome=?,transfer_id=?,http_status=?,response_payload=? WHERE sender_account_id=? AND idempotency_key=?",outcome.status()==201?"COMPLETED":"REJECTED",transferId,outcome.status(),outcome.payload(),sender.id(),key);
                return outcome;
            });
        }
        catch(DuplicateKeyException duplicate) {
            // The losing transaction has rolled back; the winner's committed response is read in a fresh transaction.
            return tx.execute(status -> stored(sender.id(),key,fingerprint).stream().findFirst().orElseThrow(()->duplicate));
        }
    }


    /** Caller owns the transaction; account locks and money movement participate in it. */
    Transfer moveFunds(UUID senderId, UUID recipientId, long amount, String reason) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Money movement requires an active transaction.");
        }
        if (senderId.equals(recipientId)) {
            throw new Rejection(422, "SELF_TRANSFER", "Choose a different recipient account.");
        }
        List<UUID> ids = new ArrayList<>(List.of(senderId, recipientId));
        ids.sort(Comparator.comparing(UUID::toString));
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : ids) {
            List<Account> found = db.query(
                    "SELECT a.*,b.display_name FROM accounts a JOIN businesses b ON b.id=a.business_id WHERE a.id=? FOR UPDATE",
                    (r, n) -> accountRow(r), id);
            if (found.isEmpty()) {
                throw new Rejection(404, "ACCOUNT_NOT_FOUND", "Recipient account not found.");
            }
            locked.put(id, found.get(0));
        }
        Account from = locked.get(senderId);
        Account to = locked.get(recipientId);
        if (!from.currency().equals(to.currency())) {
            throw new Rejection(422, "UNSUPPORTED_CURRENCY", "Accounts must use the same currency.");
        }
        if (from.balanceInCents() < amount) {
            throw new Rejection(409, "INSUFFICIENT_FUNDS", "Your account has insufficient funds.");
        }
        if (to.balanceInCents() > Long.MAX_VALUE - amount) {
            throw new Rejection(422, "BALANCE_LIMIT", "Recipient balance limit exceeded.");
        }
        UUID transferId = UUID.randomUUID();
        Instant created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update("UPDATE accounts SET balance_in_cents=balance_in_cents-? WHERE id=?", amount, from.id());
        db.update("UPDATE accounts SET balance_in_cents=balance_in_cents+? WHERE id=?", amount, to.id());
        db.update("INSERT INTO transfers(id,sender_account_id,recipient_account_id,amount_in_cents,currency,reason,created_at) VALUES(?,?,?,?,?,?,?)",
                transferId, from.id(), to.id(), amount, "USD", reason,
                OffsetDateTime.ofInstant(created, java.time.ZoneOffset.UTC));
        return new Transfer(transferId, from.id(), to.id(), from.displayName(), to.displayName(),
                amount, "USD", reason, "COMPLETED", created);
    }

    private List<Outcome> stored(UUID sender,String key,String fingerprint) {
        return db.query("SELECT request_fingerprint,http_status,response_payload FROM transfer_requests WHERE sender_account_id=? AND idempotency_key=?",(r,n)-> {
            if(!r.getString(1).equals(fingerprint)) throw new Rejection(409,"IDEMPOTENCY_CONFLICT","This key was already used for different transfer details.");
            return new Outcome(r.getInt(2),r.getString(3));
        }, sender, key);
    }

    private static final String TRANSFER_SELECT =
            "SELECT t.*, sb.display_name sender_name, rb.display_name recipient_name "
            + "FROM transfers t "
            + "JOIN accounts sa ON sa.id=t.sender_account_id "
            + "JOIN businesses sb ON sb.id=sa.business_id "
            + "JOIN accounts ra ON ra.id=t.recipient_account_id "
            + "JOIN businesses rb ON rb.id=ra.business_id ";
    private Transfer transferRow(ResultSet r) throws SQLException {
        return new Transfer(r.getObject("id",UUID.class),r.getObject("sender_account_id",UUID.class),r.getObject("recipient_account_id",UUID.class),r.getString("sender_name"),r.getString("recipient_name"),r.getLong("amount_in_cents"),r.getString("currency"),r.getString("reason"),"COMPLETED",r.getObject("created_at",OffsetDateTime.class).toInstant());
    }

    public Transfer transfer(UUID business,UUID id) {
        Account own=account(business);
        return db.query(TRANSFER_SELECT+"WHERE t.id=? AND (t.sender_account_id=? OR t.recipient_account_id=?)",(r,n)->transferRow(r),id,own.id(),own.id()).stream().findFirst().orElseThrow(()->new Rejection(404,"TRANSFER_NOT_FOUND","Transfer not found."));
    }

    public Page<Transfer> history(UUID business,String startDate,String endDate,int limit,String cursor) {
        Account own=account(business);
        limit=limit(limit);
        Instant start=date(startDate),end=date(endDate);
        if(start!=null && end!=null && !start.isBefore(end)) throw new Rejection(400,"INVALID_DATE_RANGE","startDate must be before endDate.");
        String scope="history:"+own.id()+":"+start+":"+end;
        String boundary=decode(cursor,scope);
        StringBuilder sql=new StringBuilder(TRANSFER_SELECT+"WHERE (t.sender_account_id=? OR t.recipient_account_id=?)");
        List<Object> args=new ArrayList<>(List.of(own.id(),own.id()));
        if(start!=null) {
            sql.append(" AND t.created_at>=?");
            args.add(OffsetDateTime.ofInstant(start,java.time.ZoneOffset.UTC));
        }
        if(end!=null) {
            sql.append(" AND t.created_at<?");
            args.add(OffsetDateTime.ofInstant(end,java.time.ZoneOffset.UTC));
        }
        if(boundary!=null) {
            String[] parts=boundary.split("\\|",-1);
            if(parts.length!=2) throw new Rejection(400,"INVALID_CURSOR","Invalid cursor.");
            Instant time=date(parts[0]);
            UUID id=uuid(parts[1]);
            sql.append(" AND (t.created_at<? OR (t.created_at=? AND t.id<?))");
            args.add(OffsetDateTime.ofInstant(time,java.time.ZoneOffset.UTC));
            args.add(OffsetDateTime.ofInstant(time,java.time.ZoneOffset.UTC));
            args.add(id);
        }
        sql.append(" ORDER BY t.created_at DESC,t.id DESC LIMIT ?");
        args.add(limit+1);
        List<Transfer> rows=db.query(sql.toString(),(r,n)->transferRow(r),args.toArray());
        boolean more=rows.size()>limit;
        List<Transfer> page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        Transfer last=page.isEmpty()?null:page.get(page.size()-1);
        return new Page<>(page,more?encode(scope,last.createdAt()+"|"+last.transferId()):null);
    }

    int limit(int limit) {
        if(limit<1||limit>100) throw new Rejection(400,"INVALID_LIMIT","limit must be between 1 and 100.");
        return limit;
    }

    Instant date(String date) {
        if(date==null) return null;
        try {
            Instant parsed = Instant.parse(date);
            // Instant's supported years exceed OffsetDateTime's JDBC representation.
            OffsetDateTime.ofInstant(parsed, java.time.ZoneOffset.UTC);
            return parsed;
        }
        catch(Exception e) {
            throw new Rejection(400,"INVALID_DATE","Dates must be ISO timestamps within the supported date range.");
        }
    }

    UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        }
        catch(Exception e) {
            throw new Rejection(400,"INVALID_CURSOR","Invalid cursor.");
        }
    }

    String encode(String scope,String boundary) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((scope+"\n"+boundary).getBytes(StandardCharsets.UTF_8));
    }

    String decode(String cursor,String scope) {
        if(cursor==null) return null;
        try {
            String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
            String[] parts=decoded.split("\n",-1);
            if(parts.length!=2 || !parts[0].equals(scope)) throw new IllegalArgumentException();
            return parts[1];
        }
        catch(Exception e) {
            throw new Rejection(400,"INVALID_CURSOR","Cursor does not match this account and filters.");
        }
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch(Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch(Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
