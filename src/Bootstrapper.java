import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mjson.Json;

/** Downloads the artifacts declared by cxyz_manifest.json. */
public final class Bootstrapper {
    private static final String API_BASE = "https://api.cerrot.xyz";
    private static final String API_PATH = "/get_artifact";
    private static final String MANIFEST_API_PATH = "/get_manifest";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private Bootstrapper() {}

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception exception) {
            System.err.println("Bootstrapper failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run() throws IOException, InterruptedException, GeneralSecurityException {
        String identifier = requiredEnvironment("IDENTIFIER");
        String secret = requiredEnvironment("SECRET");
        Path manifestPath = Path.of("cxyz_manifest.json");
        Path revisionPath = manifestPath.resolveSibling(".cxyz_revision");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        if (!Files.isRegularFile(manifestPath)) {
            String manifestText = fetchManifest(client, identifier, secret);
            Json fetchedManifest = parseManifest(manifestText);
            writeManifest(manifestPath, manifestText);
            System.out.println("Fetched manifest revision "
                    + requireInteger(fetchedManifest.at("revision"), "manifest.revision")
                    + " -> " + manifestPath);
        }

        Json manifest = parseManifest(Files.readString(manifestPath, StandardCharsets.UTF_8));
        long manifestRevision = requireInteger(manifest.at("revision"), "manifest.revision");
        long installedRevision = readRevision(revisionPath);
        boolean revisionChanged = installedRevision != manifestRevision;
        List<Json> artifacts = requireArray(manifest.at("artifacts"), "manifest.artifacts");

        for (int index = 0; index < artifacts.size(); index++) {
            Json artifact = artifacts.get(index);
            requireObject(artifact, "artifacts[" + index + "]");
            String source = requireString(artifact.at("source"), "artifacts[" + index + "].source");
            String targetText = requireString(artifact.at("target"), "artifacts[" + index + "].target");
            boolean replaceExisting = requireBoolean(
                    artifact.at("replace-existing"), "artifacts[" + index + "].replace-existing");
            Path target = Path.of(targetText);

            if (Files.exists(target) && (!revisionChanged || !replaceExisting)) {
                System.out.println("Skipping existing artifact: " + target);
                continue;
            }

            download(client, identifier, secret, source, target);
        }

        Files.writeString(revisionPath, Long.toString(manifestRevision) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        System.out.println("Updated revision file: " + revisionPath);
    }

    private static Json parseManifest(String manifestText) {
        Json manifest = Json.read(manifestText);
        requireObject(manifest, "manifest");
        requireInteger(manifest.at("revision"), "manifest.revision");
        requireArray(manifest.at("artifacts"), "manifest.artifacts");
        return manifest;
    }

    private static String fetchManifest(HttpClient client, String identifier, String secret)
            throws IOException, InterruptedException, GeneralSecurityException {
        URI requestUri = URI.create(API_BASE + MANIFEST_API_PATH);
        HttpRequest request = authenticatedGet(identifier, secret, MANIFEST_API_PATH, requestUri);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Manifest API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static void writeManifest(Path manifestPath, String manifestText) throws IOException {
        Path absoluteManifest = manifestPath.toAbsolutePath().normalize();
        Path temporary = absoluteManifest.resolveSibling(absoluteManifest.getFileName() + ".part-"
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong()));
        try {
            Files.writeString(temporary, manifestText, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, absoluteManifest, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absoluteManifest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long readRevision(Path revisionPath) throws IOException {
        if (!Files.exists(revisionPath)) {
            return 0L;
        }

        String contents = Files.readString(revisionPath, StandardCharsets.UTF_8).trim();
        if (contents.isEmpty()) {
            throw new IOException("Revision file is empty: " + revisionPath);
        }

        try {
            return Long.parseLong(contents);
        } catch (NumberFormatException exception) {
            throw new IOException("Revision file must contain a single integer: " + revisionPath, exception);
        }
    }

    private static void download(HttpClient client, String identifier, String secret,
                                 String source, Path target)
            throws IOException, InterruptedException, GeneralSecurityException {
        String query = "path=" + URLEncoder.encode(source, StandardCharsets.UTF_8);
        URI requestUri = URI.create(API_BASE + API_PATH + "?" + query);
        long timestamp = System.currentTimeMillis() / 1000L;
        String signature = generateSignature(identifier, secret, timestamp, "GET", API_PATH, "");

        HttpRequest request = authenticatedGet(identifier, secret, API_PATH, requestUri);

        System.out.println("Requesting " + source + " -> " + target);
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status < 300 || status >= 400) {
            throw new IOException("Artifact API returned HTTP " + status + " for " + source);
        }

        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new IOException("Artifact API response did not include a download URL"));
        downloadToTarget(URI.create(location), target);
        System.out.println("Downloaded " + source + " -> " + target);
    }

    private static HttpRequest authenticatedGet(String identifier, String secret, String path, URI requestUri)
            throws GeneralSecurityException {
        long timestamp = System.currentTimeMillis() / 1000L;
        String signature = generateSignature(identifier, secret, timestamp, "GET", path, "");
        return HttpRequest.newBuilder(requestUri)
                .timeout(REQUEST_TIMEOUT)
                .header("X-Identifier", identifier)
                .header("X-Timestamp", Long.toString(timestamp))
                .header("X-Signature", signature)
                // The API request tracker requires this header even though it is not part of the signature.
                .header("X-Request-ID", UUID.randomUUID().toString())
                .GET()
                .build();
    }

    private static void downloadToTarget(URI downloadUri, Path target) throws IOException, InterruptedException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String tempName = absoluteTarget.getFileName() + ".part-"
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        Path temporary = absoluteTarget.resolveSibling(tempName);

        try {
            HttpRequest downloadRequest = HttpRequest.newBuilder(downloadUri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("Download URL returned HTTP " + response.statusCode());
            }

            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)) {
                input.transferTo(output);
            }

            try {
                Files.move(temporary, absoluteTarget, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String generateSignature(String identifier, String secret, long timestamp,
                                           String method, String path, String payloadJson)
            throws GeneralSecurityException {
        String message = identifier + " | " + timestamp + " | " + method + " | " + path + " | " + payloadJson;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Environment variable " + name + " is not set");
        }
        return value;
    }

    private static void requireObject(Json value, String name) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
    }

    private static List<Json> requireArray(Json value, String name) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(name + " must be a JSON array");
        }
        return value.asJsonList();
    }

    private static String requireString(Json value, String name) {
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.asString();
    }

    private static boolean requireBoolean(Json value, String name) {
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return value.asBoolean();
    }

    private static long requireInteger(Json value, String name) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }

        double number = value.asDouble();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Long.MIN_VALUE || number > Long.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be an integer");
        }

        return value.asLong();
    }
}
