package com.sih.materialmaster.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {

    @Test
    void missingStaticResourceIsReportedAsNotFoundInsteadOfServerError() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "api/auth/demo-accounts");

        ResponseEntity<Map<String, Object>> response = handler.handleNoResourceFound(exception);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Resource not found.", response.getBody().get("message"));
    }
}
