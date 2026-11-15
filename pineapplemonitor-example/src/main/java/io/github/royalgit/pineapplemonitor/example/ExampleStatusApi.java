package io.github.royalgit.pineapplemonitor.example;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A deliberately unreliable endpoint, so the example dashboard has failures and slow requests to show.
 * It takes 20 to 120ms, sometimes much longer, and fails at {@code example.status.failure-rate}.
 */
@RestController
public class ExampleStatusApi {

    private final byte[] apiKey;
    private final double failureRate;

    public ExampleStatusApi(@Value("${example.api-key:demo-key}") String apiKey,
            @Value("${example.status.failure-rate:0.1}") double failureRate) {
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.failureRate = failureRate;
    }

    @GetMapping("/example-api/status")
    public String status(@RequestHeader(name = "X-Api-Key", defaultValue = "") String key) throws InterruptedException {
        if (!MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean slow = random.nextDouble() < 0.05;
        Thread.sleep(slow ? random.nextLong(400, 800) : random.nextLong(20, 120));
        if (random.nextDouble() < failureRate) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return "ok";
    }
}
