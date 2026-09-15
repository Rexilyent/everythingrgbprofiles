package com.everythingrgbprofile.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON over HTTP to a vendor service on this machine. Used by the SteelSeries
 * and Razer backends, both of which are local REST servers rather than DLLs.
 *
 * <h2>Two ways to send, on purpose</h2>
 * {@link #send} blocks and throws. It is for the handful of setup calls in
 * {@code connect}, where an answer is required before anything else can happen
 * and blocking the worker thread for a second is harmless.
 *
 * <p>{@link Lane#fire} never blocks. It is for frames, which arrive about thirty
 * times a second from the render loop. If the vendor service stalls — and a
 * desktop app that is busy updating itself will — a blocking frame send would
 * freeze every animation in the mod for as long as the stall lasts. So a lane
 * allows exactly one request in flight and simply declines a new one while it
 * is busy; the backend keeps its colours marked dirty and the next frame sends
 * the latest state instead. Latest-wins is the right semantics for lighting: a
 * frame that could not be delivered on time is worthless, not queued.
 *
 * <h2>Threading</h2>
 * The async completions run on the HTTP client's own threads, which is why they
 * touch nothing but two atomics. They never call back into a backend or the
 * mod, so {@link LightingBackend}'s one-thread rule still holds in every way
 * that matters.
 */
final class LocalHttp {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(800))
            .build();

    /** Blocking request. Returns the body on any 2xx, throws otherwise. */
    String send(String method, String url, String json, int timeoutMillis) throws IOException {
        try {
            HttpResponse<String> r = client.send(request(method, url, json, timeoutMillis),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new HttpStatusException(method + " " + url, r.statusCode(), r.body());
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during " + method + " " + url, e);
        }
    }

    /**
     * The vendor service answered, and said no. Kept apart from a plain
     * {@link IOException} because the two mean different things to a player:
     * nothing answering is their software not running, while a refusal from
     * software that is running usually means this mod sent something wrong.
     */
    static final class HttpStatusException extends IOException {
        final int status;
        final String body;

        HttpStatusException(String request, int status, String body) {
            super(request + " -> HTTP " + status + ": " + BackendHealth.clip(body));
            this.status = status;
            this.body = body;
        }
    }

    /** A single-flight channel. Frames and keep-alives get one each, so neither starves the other. */
    Lane lane() {
        return new Lane();
    }

    final class Lane {
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        /** What the most recent failure was, for the diagnosis written when a backend gives up. */
        private volatile String lastFailure;

        /** {@link #fire(String, String, String, int, java.util.function.Predicate)} where any 2xx counts. */
        boolean fire(String method, String url, String json, int timeoutMillis) {
            return fire(method, url, json, timeoutMillis, null);
        }

        /**
         * Sends without waiting.
         *
         * @param accepts optional check on the response body. Needed because
         *                "HTTP 200" means different things per vendor: Razer
         *                reports a rejected frame as a 200 with
         *                {@code {"result": 87}} in the body, so a status check
         *                alone would count every rejection as a success.
         * @return false if the previous request on this lane has not finished,
         *         in which case nothing was sent and the caller should try again
         *         next frame
         */
        boolean fire(String method, String url, String json, int timeoutMillis,
                     java.util.function.Predicate<String> accepts) {
            if (!inFlight.compareAndSet(false, true)) return false;
            try {
                client.sendAsync(request(method, url, json, timeoutMillis),
                                HttpResponse.BodyHandlers.ofString())
                        .whenComplete((response, error) -> {
                            boolean ok = error == null
                                    && response.statusCode() / 100 == 2
                                    && (accepts == null || accepts.test(String.valueOf(response.body())));
                            if (ok) {
                                consecutiveFailures.set(0);
                            } else {
                                consecutiveFailures.incrementAndGet();
                                lastFailure = error != null
                                        ? method + " " + url + " -> " + error
                                        : method + " " + url + " -> HTTP " + response.statusCode() + ": "
                                                + BackendHealth.clip(String.valueOf(response.body()));
                            }
                            inFlight.set(false);
                        });
                return true;
            } catch (RuntimeException e) {
                // A malformed URI or a client that has been shut down. Count it
                // like any other failure so the backend notices and gives up.
                consecutiveFailures.incrementAndGet();
                lastFailure = method + " " + url + " -> " + e;
                inFlight.set(false);
                return true;
            }
        }

        /** How many requests in a row have failed. Reset by any success. */
        int consecutiveFailures() {
            return consecutiveFailures.get();
        }

        /** The most recent failure on this lane, or null if there has not been one. */
        String lastFailure() {
            return lastFailure;
        }
    }

    private static HttpRequest request(String method, String url, String json, int timeoutMillis) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMillis));
        if (json != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return b.build();
    }
}
