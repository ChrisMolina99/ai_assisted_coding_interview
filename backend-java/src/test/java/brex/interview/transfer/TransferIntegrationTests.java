package brex.interview.transfer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.List;
import static brex.interview.transfer.TransferTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class TransferIntegrationTests {
    static final UUID B1=UUID.fromString("00000000-0000-0000-0000-000000000001"),B2=UUID.fromString("00000000-0000-0000-0000-000000000002"),B3=UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID A1=UUID.fromString("10000000-0000-0000-0000-000000000001"),A2=UUID.fromString("10000000-0000-0000-0000-000000000002");
    @Autowired TransferService service;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @BeforeEach void reset() {
        db.update("DELETE FROM money_request_actions");
        db.update("DELETE FROM money_requests");
        db.update("DELETE FROM transfer_requests");
        db.update("DELETE FROM transfers");
        db.update("UPDATE accounts SET balance_in_cents=opening_balance_in_cents");
    }
    CreateTransfer request(long amount) {
        return new CreateTransfer(A2,amount,"Services");
    }

    @Test void successfulReplayAndConflict() throws Exception {
        Outcome first=service.create(B1,"same",request(123));
        assertEquals(201,first.status());
        assertEquals(first,service.create(B1,"same",request(123)));
        assertEquals(999877,service.account(B1).balanceInCents());
        assertEquals(500123,service.account(B2).balanceInCents());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM transfers",Integer.class));
        assertEquals(409,assertThrows(Rejection.class,()->service.create(B1,"same",request(124))).status);
        UUID id=UUID.fromString(json.readTree(first.payload()).get("transferId").asText());
        assertEquals(id,service.transfer(B2,id).transferId());
        assertEquals(404,assertThrows(Rejection.class,()->service.transfer(B3,id)).status);
    }

    @Test void rejectedOutcomesRemainStable() {
        Outcome rejected=service.create(B1,"poor",request(1000001));
        assertEquals(409,rejected.status());
        db.update("UPDATE accounts SET balance_in_cents=2000000 WHERE id=?",A1);
        assertEquals(rejected,service.create(B1,"poor",request(1000001)));
        assertEquals(422,service.create(B1,"self",new CreateTransfer(A1,1L,null)).status());
        assertEquals(404,service.create(B1,"missing",new CreateTransfer(UUID.randomUUID(),1L,null)).status());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM transfers",Integer.class));
    }

    @Test void rollbackAfterBalanceUpdates() {
        db.execute("ALTER TABLE transfers ADD CONSTRAINT test_forced_failure CHECK(amount_in_cents <> 77)");
        try {
            assertThrows(Exception.class,()->service.create(B1,"rollback",request(77)));
        }
        finally {
            db.execute("ALTER TABLE transfers DROP CONSTRAINT test_forced_failure");
        }
        assertEquals(1000000,service.account(B1).balanceInCents());
        assertEquals(500000,service.account(B2).balanceInCents());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM transfer_requests",Integer.class));
        assertEquals(201,service.create(B1,"rollback",request(77)).status());
    }

    @Test void concurrentSpendingCannotOverdraw() throws Exception {
        List<Outcome> outcomes=parallel(()->service.create(B1,"spend-a",request(750000)),()->service.create(B1,"spend-b",request(750000)));
        assertEquals(List.of(201,409),outcomes.stream().map(Outcome::status).sorted().toList());
        assertEquals(250000,service.account(B1).balanceInCents());
        assertEquals(1250000,service.account(B2).balanceInCents());
    }

    @Test void concurrentIdenticalAndOppositeDirectionTransfers() throws Exception {
        List<Outcome> outcomes=parallel(()->service.create(B1,"race",request(100)),()->service.create(B1,"race",request(100)));
        assertEquals(outcomes.get(0),outcomes.get(1));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM transfers",Integer.class));
        assertTrue(parallel(()->service.create(B1,"out",request(100)),()->service.create(B2,"back",new CreateTransfer(A1,100L,null))).stream().allMatch(o->o.status()==201));
    }
    List<Outcome> parallel(Callable<Outcome> a,Callable<Outcome> b) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Outcome> first=pool.submit(()-> {
                start.await();return a.call();
            });
            Future<Outcome> second=pool.submit(()-> {
                start.await();return b.call();
            });
            start.countDown();
            return List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test void historyPaginationScopeAndDateBoundaries() {
        service.create(B1,"one",request(1));
        service.create(B1,"two",request(2));
        service.create(B2,"back",new CreateTransfer(A1,3L,null));
        Page<Transfer> first=service.history(B1,null,null,1,null),second=service.history(B1,null,null,1,first.nextCursor());
        assertNotEquals(first.items().get(0).transferId(),second.items().get(0).transferId());
        assertEquals(3,service.history(B2,null,null,20,null).items().size());
        assertTrue(service.history(B3,null,null,20,null).items().isEmpty());
        assertThrows(Rejection.class,()->service.history(B2,null,null,1,first.nextCursor()));
        String boundary=first.items().get(0).createdAt().toString();
        assertEquals(1,service.history(B1,boundary,null,20,null).items().size());
        assertEquals(2,service.history(B1,null,boundary,20,null).items().size());
        assertThrows(Rejection.class,()->service.history(B1,boundary,boundary,20,null));
        assertThrows(Rejection.class,()->service.history(B1,"bad",null,20,null));
        String invalidBoundary = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("history:" + A1 + ":null:null\n|" + A2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(400, assertThrows(Rejection.class,
                () -> service.history(B1, null, null, 20, invalidBoundary)).status);
        assertThrows(Rejection.class,()->service.history(B1,null,null,0,null));
        assertEquals(1,service.recipients(B1,1,null).items().size());
        assertEquals(1,service.recipients(B1,1,service.recipients(B1,1,null).nextCursor()).items().size());
        assertTrue(service.recipients(B1,20,null).items().stream().noneMatch(r->r.id().equals(A1)));
    }

    @Test void httpContractAndValidation() throws Exception {
        mvc.perform(get("/api/businesses")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/accounts/me")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors").isMap());
        mvc.perform(post("/api/transfers").header("X-Business-Id",B1).header("Idempotency-Key","http").contentType("application/json").content(json.writeValueAsString(request(100))))
        .andExpect(status().isCreated()).andExpect(header().exists("Location")).andExpect(jsonPath("$.status").value("COMPLETED"));
        for(String amount:List.of("1.5","0","-1","1000000001","9223372036854775808","\"abc\"","\"1\"","true")) {
            mvc.perform(post("/api/transfers").header("X-Business-Id",B1).header("Idempotency-Key","invalid").contentType("application/json").content("{\"recipientAccountId\":\""+A2+"\",\"amountInCents\":"+amount+"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
        }
        mvc.perform(get("/api/transfers").header("X-Business-Id",B1).param("startDate","yesterday")).andExpect(status().isBadRequest());
    }

    @Test void extremeDatesAndCursorDatesAreValidated() throws Exception {
        for (String date : List.of("+1000000000-01-01T00:00:00Z", "-1000000000-01-01T00:00:00Z")) {
            for (String parameter : List.of("startDate", "endDate")) {
                mvc.perform(get("/api/transfers").header("X-Business-Id", B1).param(parameter, date))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE"));
            }
            String cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("history:" + A1 + ":null:null\n" + date + "|" + A2)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mvc.perform(get("/api/transfers").header("X-Business-Id", B1).param("cursor", cursor))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE"));
        }
        for (String date : List.of("+999999999-12-31T23:59:59Z", "-999999999-01-01T00:00:00Z")) {
            mvc.perform(get("/api/transfers").header("X-Business-Id", B1).param("startDate", date))
                    .andExpect(status().isOk());
        }
    }

    @Test void scopedApiPreflight() throws Exception {
        mvc.perform(options("/api/accounts/me").header("Origin", "http://localhost:3000")
        .header("Access-Control-Request-Method", "GET")
        .header("Access-Control-Request-Headers", "x-business-id"))
        .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options("/api/transfers").header("Origin", "http://localhost:3000")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "x-business-id,idempotency-key,content-type"))
        .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test void recipientOverflowAndReasonValidation() {
        db.update("UPDATE accounts SET balance_in_cents=? WHERE id=?",Long.MAX_VALUE,A2);
        assertEquals(422,service.create(B1,"overflow",request(1)).status());
        assertEquals(1000000,service.account(B1).balanceInCents());
        assertEquals(400,assertThrows(Rejection.class,()->service.create(B1,"long",new CreateTransfer(A2,1L,"x".repeat(281)))).status);
    }
}
