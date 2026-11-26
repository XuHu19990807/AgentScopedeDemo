import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP protocol probe: reports which HTTP version a request actually used.
 *
 * <p>Purpose: after upgrading AgentScope to 2.0.4+, plaintext {@code http://} endpoints must
 * use HTTP/1.1 (no h2c upgrade). This tool reproduces the client side and prints the
 * version Java really negotiated, plus the response status - so you can confirm on the
 * server box without guessing.
 *
 * <p>Usage:
 * <pre>
 *   java HttpProtocolProbe.java http://10.0.0.10:8000/v1/chat/completions
 *   java HttpProtocolProbe.java http://10.0.0.10:8000/v1/chat/completions --mode h1
 *   java HttpProtocolProbe.java http://10.0.0.10:8000/v1/chat/completions --mode h2
 *   java HttpProtocolProbe.java https://api.example.com/v1/chat/completions --mode auto
 * </pre>
 *
 * <p>Modes (mirror AgentScope 2.0.5 HttpTransportConfig semantics):
 * <ul>
 *   <li>{@code auto} (default): {@code http://} -> HTTP/1.1, {@code https://} -> HTTP/2 via ALPN</li>
 *   <li>{@code h1}: force HTTP/1.1 everywhere</li>
 *   <li>{@code h2}: force HTTP/2 - on plaintext this makes Java try h2c upgrade, which is
 *       exactly the behaviour that made uvicorn/vLLM drop the POST body</li>
 * </ul>
 */
public class HttpProtocolProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: java HttpProtocolProbe.java <url> [--mode auto|h1|h2] [--timeout seconds]");
            System.exit(2);
        }
        String url = args[0];
        String mode = "auto";
        int timeoutSec = 10;
        boolean get = false;
        for (int i = 1; i < args.length; i++) {
            if ("--mode".equals(args[i]) && i + 1 < args.length) {
                mode = args[++i].toLowerCase();
            } else if ("--timeout".equals(args[i]) && i + 1 < args.length) {
                timeoutSec = Integer.parseInt(args[++i]);
            } else if ("--get".equals(args[i])) {
                get = true;
            }
        }

        boolean plain = url.startsWith("http://");
        HttpClient.Version version;
        switch (mode) {
            case "h1" -> version = HttpClient.Version.HTTP_1_1;
            case "h2" -> version = HttpClient.Version.HTTP_2;
            default -> version = plain ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2;
        }

        HttpClient client = HttpClient.newBuilder()
                .version(version)
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        String body = "{\"model\":\"probe\",\"input\":\"ping\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer probe");
        if (get) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        HttpRequest request = builder.build();

        System.out.println("url       : " + url);
        System.out.println("scheme    : " + (plain ? "http (cleartext)" : "https"));
        System.out.println("mode      : " + mode + " -> requested " + version);
        System.out.println("body bytes: " + (get ? "0 (GET)" : String.valueOf(body.length())));

        long started = System.currentTimeMillis();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - started;
            System.out.println("status    : " + response.statusCode() + "  (" + elapsed + " ms)");
            System.out.println("negotiated: " + response.version());
            String respBody = response.body();
            if (respBody != null && !respBody.isBlank()) {
                String flat = respBody.replaceAll("\\s+", " ");
                System.out.println("resp body : " + (flat.length() > 300 ? flat.substring(0, 300) + "..." : flat));
            }
            if (response.statusCode() == 400 && respBody != null && respBody.contains("'loc': ('body',)")) {
                System.out.println("verdict   : server received an EMPTY BODY (h2c upgrade was mishandled by the server stack)");
            } else if (plain && "h2".equals(mode)) {
                System.out.println("verdict   : forced h2c upgrade on cleartext; check the server log for"
                        + " 'Upgrade: h2c' + 'HTTP2-Settings' headers - that is the fingerprint AgentScope 2.0.0 sent");
            } else if (plain && response.version() == HttpClient.Version.HTTP_1_1) {
                System.out.println("verdict   : cleartext request ran over HTTP/1.1 - no h2c upgrade, as expected after 2.0.4+");
            } else if (plain && response.version() == HttpClient.Version.HTTP_2) {
                System.out.println("verdict   : cleartext request used HTTP/2 (h2c) - server accepted the upgrade");
            }
        } catch (Exception e) {
            System.out.println("failed    : " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("verdict   : no HTTP response; check host/port/TLS or server support for the forced version");
        }
    }
}
