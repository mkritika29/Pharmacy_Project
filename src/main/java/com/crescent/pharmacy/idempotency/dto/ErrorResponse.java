package com.crescent.pharmacy.idempotency.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private String error;
    private String message;
    private int    status;
    private Instant timestamp;
    /** Field-level validation errors, keyed by field name. */
    private Map<String, String> fieldErrors;

    public static ErrorResponse of(String error, String message, int status) {
        return ErrorResponse.builder()
            .error(error)
            .message(message)
            .status(status)
            .timestamp(Instant.now())
            .build();
    }
}
