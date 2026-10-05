package brex.interview.transfer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
import static brex.interview.transfer.TransferTypes.*;
@RestController
@RequestMapping("/api")
public class TransferController {
    private final TransferService service;
    private final ObjectMapper json;
    public TransferController(TransferService service,ObjectMapper json) {
        this.service=service;
        this.json=json;
    }

    @GetMapping("/businesses") public Page<Business> businesses() {
        return service.businesses();
    }

    @GetMapping("/accounts/me") public Account account(@RequestHeader("X-Business-Id") UUID business) {
        return service.account(business);
    }

    @GetMapping("/accounts/recipients") public Page<Recipient> recipients(@RequestHeader("X-Business-Id") UUID business,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return service.recipients(business,limit,cursor);
    }

    @PostMapping("/transfers") public ResponseEntity<String> create(@RequestHeader("X-Business-Id") UUID business,@RequestHeader("Idempotency-Key") String key,@RequestBody CreateTransfer request) throws Exception {
        Outcome outcome=service.create(business,key,request);
        var response=ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON);
        if(outcome.status()==201) response.location(URI.create("/api/transfers/"+json.readTree(outcome.payload()).get("transferId").asText()));
        return response.body(outcome.payload());
    }

    @GetMapping("/transfers/{id}") public Transfer transfer(@RequestHeader("X-Business-Id") UUID business,@PathVariable UUID id) {
        return service.transfer(business,id);
    }

    @GetMapping("/transfers") public Page<Transfer> history(@RequestHeader("X-Business-Id") UUID business,@RequestParam(required=false) String startDate,@RequestParam(required=false) String endDate,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return service.history(business,startDate,endDate,limit,cursor);
    }
}
