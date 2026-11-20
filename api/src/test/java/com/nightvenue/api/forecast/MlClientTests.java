package com.nightvenue.api.forecast;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlClientTests {
    private static final String TOKEN = "test-ml-internal-token";

    @Test
    void postsRequestWithIsoDateAndParsesResponse() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.SUCCESS)) {
            MlForecastResponse response = client(stub.url(), TOKEN, 1_000).forecast(request());
            assertEquals("POST", stub.method);
            assertEquals("/ml/forecast/inventory", stub.path);
            assertEquals(TOKEN, stub.token);
            assertTrue(stub.contentType.startsWith("application/json"));
            assertTrue(stub.body.contains("\"ingredientId\":7"));
            assertTrue(stub.body.contains("\"unit\":\"ML\""));
            assertTrue(stub.body.contains("\"horizonDays\":7"));
            assertTrue(stub.body.contains("\"series\":"));
            assertTrue(stub.body.contains("\"businessDate\":\"2026-01-01\""));
            assertFalse(stub.body.contains("\"businessDate\":["));
            assertEquals(new BigDecimal("123.456"), response.predictedQuantity());
            assertEquals(new BigDecimal("2.1"), response.modelMae());
            assertEquals(new BigDecimal("3.2"), response.baselineMae());
            assertEquals(LocalDate.parse("2026-01-01"), response.asOfBusinessDate());
            assertEquals("ols_weekday_trend", response.regressionModelName());
            assertEquals("baseline_dow_mean", response.baselineModelName());
        }
    }

    @Test
    void largeIngredientIdRoundTrips() throws Exception {
        long ingredientId = 3_000_000_000L;
        MlForecastRequest request = new MlForecastRequest(
                ingredientId,
                "ML",
                7,
                List.of(new MlForecastRequest.Observation(
                        LocalDate.parse("2026-01-01"), new BigDecimal("1.25"))));
        try (MlStub stub = new MlStub(MlStub.Mode.ECHO_INGREDIENT_ID)) {
            MlForecastResponse response = client(stub.url(), TOKEN, 1_000).forecast(request);
            assertTrue(stub.body.contains("\"ingredientId\":3000000000"));
            assertEquals(ingredientId, response.ingredientId());
        }
    }

    @Test
    void timeoutBecomesGenericUnavailableWithoutLeakingToken() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.HANG)) {
            long started = System.nanoTime();
            MlServiceUnavailable exception = assertThrows(
                    MlServiceUnavailable.class,
                    () -> client(stub.url(), TOKEN, 300).forecast(request()));
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000);
            assertNoToken(exception);
        }
    }

    @Test
    void serverAndHttpErrorsBecomeGenericUnavailable() throws Exception {
        for (MlStub.Mode mode : List.of(
                MlStub.Mode.SERVER_500,
                MlStub.Mode.UNAUTHORIZED,
                MlStub.Mode.NOT_FOUND_404,
                MlStub.Mode.HTML_502)) {
            try (MlStub stub = new MlStub(mode)) {
                MlServiceUnavailable exception = assertThrows(
                        MlServiceUnavailable.class,
                        () -> client(stub.url(), TOKEN, 1_000).forecast(request()));
                assertNoToken(exception);
                assertFalse(exception.getMessage().contains("private upstream marker"));
            }
        }
    }

    @Test
    void known422CodesBecomeValidationExceptions() throws Exception {
        for (String[] testCase : List.of(
                new String[]{"VALIDATION_422_INSUFFICIENT", "INSUFFICIENT_DATA"},
                new String[]{"VALIDATION_422_INVALID_SERIES", "INVALID_SERIES"})) {
            try (MlStub stub = new MlStub(MlStub.Mode.valueOf(testCase[0]))) {
                MlForecastValidationException exception = assertThrows(
                        MlForecastValidationException.class,
                        () -> client(stub.url(), TOKEN, 1_000).forecast(request()));
                assertEquals(testCase[1], exception.getCode());
                assertEquals("Forecast request rejected", exception.getMessage());
            }
        }
    }

    @Test
    void unknown422AndMalformedOrIncompleteSuccessBecomeUnavailable() throws Exception {
        for (MlStub.Mode mode : List.of(
                MlStub.Mode.PYDANTIC_422,
                MlStub.Mode.MALFORMED_200,
                MlStub.Mode.MISSING_FIELD_200)) {
            try (MlStub stub = new MlStub(mode)) {
                assertThrows(
                        MlServiceUnavailable.class,
                        () -> client(stub.url(), TOKEN, 1_000).forecast(request()));
            }
        }
    }

    @Test
    void refusedConnectionBecomesGenericUnavailableWithoutLeakingToken() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        MlServiceUnavailable exception = assertThrows(
                MlServiceUnavailable.class,
                () -> client("http://127.0.0.1:" + port, TOKEN, 1_000).forecast(request()));
        assertNoToken(exception);
    }

    @Test
    void blankConfigurationFailsClosedWithoutNetworkCall() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.SUCCESS)) {
            assertThrows(
                    MlServiceUnavailable.class,
                    () -> client(" ", TOKEN, 1_000).forecast(request()));
            assertThrows(
                    MlServiceUnavailable.class,
                    () -> client(stub.url(), " ", 1_000).forecast(request()));
            assertEquals(0, stub.requestCount());
        }
    }

    @Test
    void tokenIsAbsentFromMessagesAndStackTracesAndPropertiesString() throws Exception {
        assertFalse(new MlProperties("http://user:password@localhost", TOKEN, 100)
                .toString().contains(TOKEN));
        for (MlStub.Mode mode : List.of(MlStub.Mode.SERVER_500, MlStub.Mode.UNAUTHORIZED)) {
            try (MlStub stub = new MlStub(mode)) {
                assertNoToken(assertThrows(
                        MlServiceUnavailable.class,
                        () -> client(stub.url(), TOKEN, 1_000).forecast(request())));
            }
        }
    }

    @Test
    void htmlBodyDoesNotAppearInExceptionTextOrStackTrace() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.HTML_502)) {
            MlServiceUnavailable exception = assertThrows(
                    MlServiceUnavailable.class,
                    () -> client(stub.url(), TOKEN, 1_000).forecast(request()));
            StringWriter trace = new StringWriter();
            exception.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains("private upstream marker"));
        }
    }

    private static MlClient client(String url, String token, int timeoutMs) {
        return new MlClient(new MlProperties(url, token, timeoutMs));
    }

    private static MlForecastRequest request() {
        return new MlForecastRequest(
                7,
                "ML",
                7,
                List.of(new MlForecastRequest.Observation(
                        LocalDate.parse("2026-01-01"), new BigDecimal("1.25"))));
    }

    private static void assertNoToken(Throwable exception) {
        StringWriter trace = new StringWriter();
        exception.printStackTrace(new PrintWriter(trace));
        assertFalse(exception.getMessage().contains(TOKEN));
        assertFalse(trace.toString().contains(TOKEN));
    }
}
