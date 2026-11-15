package io.github.royalgit.pineapplemonitor.example;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A small stand-in for a production API, so the example canary has something real to call. */
@RestController
@RequestMapping("/example-api/accounts/{id}/display-name")
public class ExampleAccountApi {

    private final Map<String, String> displayNames = new ConcurrentHashMap<>();
    private final byte[] apiKey;

    public ExampleAccountApi(@Value("${example.api-key:demo-key}") String apiKey) {
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public String get(@PathVariable String id, @RequestHeader(name = "X-Api-Key", defaultValue = "") String key) {
        checkKey(key);
        String displayName = displayNames.get(id);
        if (displayName == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return displayName;
    }

    @PutMapping
    public void set(@PathVariable String id, @RequestHeader(name = "X-Api-Key", defaultValue = "") String key,
            @RequestBody String displayName) {
        checkKey(key);
        displayNames.put(id, displayName);
    }

    private void checkKey(String key) {
        if (!MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
