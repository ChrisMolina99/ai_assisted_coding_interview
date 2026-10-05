package brex.interview.transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;
import static brex.interview.transfer.TransferTypes.*;
@RestControllerAdvice(assignableTypes={TransferController.class, MoneyRequestController.class})
public class TransferErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(TransferErrorHandler.class);
    @ExceptionHandler(Rejection.class) ResponseEntity<ApiError> rejection(Rejection e) {
        return ResponseEntity.status(e.status).body(e.error);
    }

    @ExceptionHandler( {
        MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class
    }
    ) ResponseEntity<ApiError> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT","Provide valid headers, parameters and transfer details.",Map.of()));
    }

    @ExceptionHandler(TransientDataAccessException.class) ResponseEntity<ApiError> temporary(Exception e) {
        log.warn("Transfer database contention: {}",e.getClass().getSimpleName());
        return ResponseEntity.status(503).body(new ApiError("TEMPORARILY_UNAVAILABLE","Please retry with the same idempotency key.",Map.of()));
    }

    @ExceptionHandler(Exception.class) ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Transfer API failure",e);
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR","Unable to complete the request. Retry with the same idempotency key.",Map.of()));
    }
}
