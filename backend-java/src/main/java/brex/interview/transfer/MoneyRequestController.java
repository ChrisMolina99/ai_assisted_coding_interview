package brex.interview.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
import static brex.interview.transfer.TransferTypes.*;
import static brex.interview.transfer.MoneyRequestTypes.*;

@RestController
@RequestMapping("/api/money-requests")
public class MoneyRequestController {
    private final MoneyRequestService service;
    private final ObjectMapper json;

    public MoneyRequestController(MoneyRequestService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestHeader("X-Business-Id") UUID business,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CreateMoneyRequest input) throws Exception {
        Outcome outcome = service.create(business, key, input);
        var response = ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON);
        if (outcome.status() == 201) response.location(URI.create("/api/money-requests/" + json.readTree(outcome.payload()).get("requestId").asText()));
        return response.body(outcome.payload());
    }

    @GetMapping
    public Page<MoneyRequest> list(@RequestHeader("X-Business-Id") UUID business,
            @RequestParam(defaultValue = "incoming") String direction, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return service.list(business, direction, status, limit, cursor);
    }

    @GetMapping("/{id}")
    public MoneyRequest get(@RequestHeader("X-Business-Id") UUID business, @PathVariable UUID id) {
        return service.get(business, id);
    }

    @PostMapping({"/{id}/pay", "/{id}/decline", "/{id}/cancel"})
    public ResponseEntity<String> action(@RequestHeader("X-Business-Id") UUID business,
            @RequestHeader("Idempotency-Key") String key, @PathVariable UUID id,
            @RequestBody(required = false) JsonNode body, jakarta.servlet.http.HttpServletRequest request) {
        if (body != null && (!body.isObject() || !body.isEmpty())) throw new Rejection(400, "INVALID_INPUT", "This action takes no request details.");
        String action = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
        // Both accepted forms mean the same no-details action and must replay identically.
        Outcome outcome = service.action(business, id, action, "null", key);
        return ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON).body(outcome.payload());
    }
}
