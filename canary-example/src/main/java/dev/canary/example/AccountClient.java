package dev.canary.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** A client for the example API's test account. */
final class AccountClient {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final URI displayName;
    private final String apiKey;

    AccountClient(String baseUrl, String accountId, String apiKey) {
        this.displayName = URI.create(baseUrl + "/accounts/" + accountId + "/display-name");
        this.apiKey = apiKey;
    }

    String displayName() throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(displayName).GET());
    }

    void setDisplayName(String name) throws IOException, InterruptedException {
        send(HttpRequest.newBuilder(displayName).PUT(HttpRequest.BodyPublishers.ofString(name)).header("Content-Type", "text/plain"));
    }

    private String send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request.header("X-Api-Key", apiKey).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("The example API returned " + response.statusCode());
        }
        return response.body();
    }
}
