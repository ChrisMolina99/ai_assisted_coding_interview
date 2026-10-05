package brex.interview.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static brex.interview.transfer.TransferTypes.*;
import static brex.interview.transfer.MoneyRequestTypes.*;
import static brex.interview.transfer.TransferIntegrationTests.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MoneyRequestIntegrationTests {
    @Autowired MoneyRequestService requests;
    @Autowired TransferService transfers;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    @BeforeEach void reset() {
        db.update("DELETE FROM money_request_actions");
        db.update("DELETE FROM money_requests");
        db.update("DELETE FROM transfer_requests");
        db.update("DELETE FROM transfers");
        db.update("UPDATE accounts SET balance_in_cents=opening_balance_in_cents");
    }

    CreateMoneyRequest input(long amount) {
        return new CreateMoneyRequest(A2, amount, "Consulting");
    }

    UUID create(long amount) throws Exception {
        Outcome result = requests.create(B1, UUID.randomUUID().toString(), input(amount));
        assertEquals(201, result.status());
        return UUID.fromString(json.readTree(result.payload()).get("requestId").asText());
    }

    Outcome action(UUID business, UUID id, String action, String key) {
        return requests.action(business, id, action, "null", key);
    }

    @Test void createReplayAndValidation() {
        Outcome created = requests.create(B1, "create", input(100));
        assertEquals(201, created.status());
        assertEquals(created, requests.create(B1, "create", input(100)));
        assertEquals(409, assertThrows(Rejection.class, () -> requests.create(B1, "create", input(101))).status);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM money_requests", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class));
        assertEquals(1000000, transfers.account(B1).balanceInCents());
        assertEquals(500000, transfers.account(B2).balanceInCents());
        CreateMoneyRequest self = new CreateMoneyRequest(A1, 1L, null);
        Outcome rejected = requests.create(B1, "self", self);
        assertEquals(422, rejected.status());
        assertEquals(rejected, requests.create(B1, "self", self));
        assertEquals(400, assertThrows(Rejection.class, () -> requests.create(B1, "bad", input(0))).status);
        assertEquals(400, assertThrows(Rejection.class, () -> requests.create(B1, "reason", new CreateMoneyRequest(A2, 1L, "x".repeat(281)))).status);
        assertEquals(404, requests.create(B1, "missing", new CreateMoneyRequest(UUID.randomUUID(), 1L, null)).status());
    }

    @Test void payMovesMoneyOnceAndLinksHistory() throws Exception {
        UUID id = create(125);
        Outcome paid = action(B2, id, "pay", "payment");
        assertEquals(200, paid.status());
        assertEquals(paid, action(B2, id, "pay", "payment"));
        MoneyRequest request = requests.get(B1, id);
        assertEquals("PAID", request.status());
        assertNotNull(request.resolvedAt());
        assertNotNull(request.transferId());
        assertEquals(1000125, transfers.account(B1).balanceInCents());
        assertEquals(499875, transfers.account(B2).balanceInCents());
        assertEquals(1, transfers.history(B1, null, null, 20, null).items().size());
        assertEquals(A2, transfers.transfer(B1, request.transferId()).senderAccountId());
        assertEquals(409, action(B2, id, "pay", "new-payment").status());
        assertEquals(409, action(B2, id, "decline", "decline").status());
        assertEquals(409, action(B1, id, "cancel", "cancel").status());
    }

    @Test void roleAndParticipantScope() throws Exception {
        UUID id = create(1);
        assertEquals(404, assertThrows(Rejection.class, () -> requests.get(B3, id)).status);
        assertEquals(404, action(B3, id, "pay", "stranger").status());
        assertEquals(403, action(B1, id, "pay", "wrong").status());
        assertEquals(403, action(B1, id, "decline", "wrong").status());
        assertEquals(403, action(B2, id, "cancel", "wrong").status());
        assertEquals("PENDING", requests.get(B2, id).status());
        assertEquals(200, action(B2, id, "decline", "decline").status());
        assertEquals("DECLINED", requests.get(B1, id).status());
        UUID cancelled = create(2);
        assertEquals(200, action(B1, cancelled, "cancel", "cancel").status());
        assertEquals("CANCELLED", requests.get(B2, cancelled).status());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class));
    }

    @Test void rejectedPaymentReplayAndNewAttempt() throws Exception {
        UUID id = create(500001);
        Outcome rejected = action(B2, id, "pay", "poor");
        assertEquals(409, rejected.status());
        assertTrue(rejected.payload().contains("INSUFFICIENT_FUNDS"));
        assertEquals("PENDING", requests.get(B1, id).status());
        db.update("UPDATE accounts SET balance_in_cents=600000 WHERE id=?", A2);
        assertEquals(rejected, action(B2, id, "pay", "poor"));
        assertEquals(200, action(B2, id, "pay", "funded").status());
        UUID other = create(1);
        assertEquals(409, assertThrows(Rejection.class, () -> action(B2, other, "pay", "poor")).status);
    }

    @Test void rollbackAfterFundsMoveAndSameKeyRetry() throws Exception {
        UUID id = create(77);
        db.execute("ALTER TABLE money_requests ADD CONSTRAINT test_request_failure CHECK(status<>'PAID')");
        try {
            assertThrows(Exception.class, () -> action(B2, id, "pay", "rollback"));
        } finally {
            db.execute("ALTER TABLE money_requests DROP CONSTRAINT test_request_failure");
        }
        assertEquals(1000000, transfers.account(B1).balanceInCents());
        assertEquals(500000, transfers.account(B2).balanceInCents());
        assertEquals("PENDING", requests.get(B1, id).status());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM money_request_actions WHERE action='pay'", Integer.class));
        assertEquals(200, action(B2, id, "pay", "rollback").status());
    }

    @Test void concurrentCreationAndActions() throws Exception {
        List<Outcome> duplicateCreate = parallel(() -> requests.create(B1, "race-create", input(100)), () -> requests.create(B1, "race-create", input(100)));
        assertEquals(duplicateCreate.get(0), duplicateCreate.get(1));
        UUID id = UUID.fromString(json.readTree(duplicateCreate.get(0).payload()).get("requestId").asText());
        List<Outcome> sameKey = parallel(() -> action(B2, id, "pay", "race-pay"), () -> action(B2, id, "pay", "race-pay"));
        assertEquals(sameKey.get(0), sameKey.get(1));
        for (String competing : List.of("pay", "cancel", "decline")) {
            UUID next = create(100);
            UUID rival = competing.equals("cancel") ? B1 : B2;
            List<Outcome> result = parallel(() -> action(B2, next, "pay", UUID.randomUUID().toString()),
                    () -> action(rival, next, competing, UUID.randomUUID().toString()));
            assertEquals(List.of(200, 409), result.stream().map(Outcome::status).sorted().toList());
            MoneyRequest resolved = requests.get(B1, next);
            assertNotEquals("PENDING", resolved.status());
            assertEquals(resolved.status().equals("PAID"), resolved.transferId() != null);
        }
        long paid = db.queryForObject("SELECT COUNT(*) FROM money_requests WHERE status='PAID'", Long.class);
        assertEquals(paid, db.queryForObject("SELECT COUNT(*) FROM transfers", Long.class));
        assertEquals(1000000 + paid * 100, transfers.account(B1).balanceInCents());
        assertEquals(500000 - paid * 100, transfers.account(B2).balanceInCents());
    }

    @Test void directTransferAndRequestPaymentShareSpendingLocks() throws Exception {
        UUID id = create(300000);
        List<Outcome> results = parallel(() -> action(B2, id, "pay", "request-spend"),
                () -> transfers.create(B2, "direct-spend", new CreateTransfer(A1, 300000L, null)));
        assertEquals(1, results.stream().filter(result -> result.status() == 409).count());
        assertEquals(1, results.stream().filter(result -> result.status() == 200 || result.status() == 201).count());
        assertEquals(200000, transfers.account(B2).balanceInCents());
        assertEquals(1300000, transfers.account(B1).balanceInCents());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class));
    }

    List<Outcome> parallel(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> a = pool.submit(() -> { start.await(); return first.call(); });
            Future<Outcome> b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void listFiltersAndCursorScopes() throws Exception {
        create(1);
        UUID second = create(2);
        create(3);
        action(B2, second, "decline", "decline");
        Page<MoneyRequest> first = requests.list(B2, "incoming", null, 1, null);
        Page<MoneyRequest> next = requests.list(B2, "incoming", null, 1, first.nextCursor());
        assertNotEquals(first.items().get(0).requestId(), next.items().get(0).requestId());
        assertEquals(3, requests.list(B1, "outgoing", null, 20, null).items().size());
        assertEquals(2, requests.list(B2, "incoming", "PENDING", 20, null).items().size());
        assertEquals(1, requests.list(B1, "outgoing", "DECLINED", 20, null).items().size());
        assertTrue(requests.list(B3, "incoming", null, 20, null).items().isEmpty());
        assertThrows(Rejection.class, () -> requests.list(B1, "outgoing", null, 1, first.nextCursor()));
        assertThrows(Rejection.class, () -> requests.list(B2, "incoming", "PENDING", 1, first.nextCursor()));
        assertThrows(Rejection.class, () -> requests.list(B2, "invalid", null, 1, null));
        assertThrows(Rejection.class, () -> requests.list(B2, "incoming", "INVALID", 1, null));
    }

    @Test void httpContractAndStrictValidation() throws Exception {
        mvc.perform(post("/api/money-requests").header("X-Business-Id", B1).header("Idempotency-Key", "http")
                .contentType("application/json").content(json.writeValueAsString(input(100))))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.transferId").isEmpty());
        for (String amount : List.of("\"1\"", "1.5", "0", "-1", "1000000001", "9223372036854775808", "true")) {
            mvc.perform(post("/api/money-requests").header("X-Business-Id", B1).header("Idempotency-Key", "invalid")
                    .contentType("application/json").content("{\"payerAccountId\":\"" + A2 + "\",\"amountInCents\":" + amount + "}"))
                    .andExpect(status().isBadRequest());
        }
        UUID id = create(1);
        mvc.perform(post("/api/money-requests/" + id + "/pay").header("X-Business-Id", B2).header("Idempotency-Key", "pay"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.transferId").isString());
        mvc.perform(get("/api/money-requests").header("X-Business-Id", B2)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/money-requests")).andExpect(status().isBadRequest());
    }

    @Test void noDetailsActionBodiesReplayAcrossEquivalentHttpForms() throws Exception {
        for (boolean emptyObjectFirst : List.of(false, true)) {
            for (boolean sufficientFunds : List.of(false, true)) {
                UUID id = create(sufficientFunds ? 1 : 1000000000);
                String key = "canonical-" + emptyObjectFirst + "-" + sufficientFunds;
                var first = post("/api/money-requests/" + id + "/pay")
                        .header("X-Business-Id", B2).header("Idempotency-Key", key);
                if (emptyObjectFirst) first.contentType("application/json").content("{}");
                String payload = mvc.perform(first)
                        .andExpect(status().is(sufficientFunds ? 200 : 409))
                        .andReturn().getResponse().getContentAsString();

                var replay = post("/api/money-requests/" + id + "/pay")
                        .header("X-Business-Id", B2).header("Idempotency-Key", key);
                if (!emptyObjectFirst) replay.contentType("application/json").content("{}");
                mvc.perform(replay).andExpect(status().is(sufficientFunds ? 200 : 409))
                        .andExpect(content().string(payload));
                assertEquals(sufficientFunds ? "PAID" : "PENDING", requests.get(B1, id).status());
            }
        }
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class));
        assertEquals(499998, transfers.account(B2).balanceInCents());
        assertEquals(1000002, transfers.account(B1).balanceInCents());
    }
}
