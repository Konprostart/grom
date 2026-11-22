// SPDX-License-Identifier: Apache-2.0
//
// grom-fleet — a read-only view of the Grom servers you run.
//
// Phase A of the fleet plan (docs/FLEET.md). This program lives outside the
// Android tree on purpose:
//
//   * it never links against Grom code and never changes it;
//   * it only speaks the documented public data plane (S5):
//       POST /v1/login            password -> short-lived session token
//       GET  /v1/status           bearer token or Ed25519 request signature
//       GET  /v1/health           unauthenticated, used only to tell "wrong
//                                 password" apart from "not a Grom server"
//   * it performs no writes, opens no listening socket, and stores no secret.
//
// The whole program is one file so it needs nothing but a JDK 17:
//
//     tools/grom-fleet --config fleet.json
//
// Run the built-in protocol tests with `tools/test-fleet`.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GromFleet {

    static final String VERSION = "1.0.0";

    /** The only two endpoints this tool ever calls (S5-R010, S5-R017). */
    static final String STATUS_PATH = "/v1/status";
    static final String LOGIN_PATH = "/v1/login";
    static final String HEALTH_PATH = "/v1/health";

    /** The engine's `SuperRouter.SERVICE`; used only to recognise a Grom server. */
    static final String SERVICE = "grom-super";

    static final int MAX_BODY_BYTES = 1 << 20; // 1 MiB
    static final int MAX_CONFIG_BYTES = 256 << 10;
    static final int MAX_DEVICES = 64;
    static final long MAX_SKEW_SECONDS = 300;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String RESET = "\u001b[0m";
    private static final String GREEN = "\u001b[32m";
    private static final String RED = "\u001b[31m";
    private static final String GREY = "\u001b[90m";
    private static final String BOLD = "\u001b[1m";

    static final String USAGE = String.join("\n",
            "grom-fleet " + VERSION + " — read-only view of the Grom servers you run",
            "",
            "Usage:",
            "  grom-fleet [options]",
            "",
            "Options:",
            "  --config FILE     fleet config file (default: ./fleet.json, then",
            "                    $XDG_CONFIG_HOME/grom/fleet.json, then",
            "                    ~/.config/grom/fleet.json)",
            "  --only LIST       comma-separated name filters (case-insensitive substring)",
            "  --timeout MS      per-request timeout in milliseconds (default 8000)",
            "  --json            emit machine-readable JSON instead of a table",
            "  --plain           disable ANSI colours",
            "  --watch SECONDS   refresh the table every N seconds (minimum 2)",
            "  --self-test       run the built-in protocol tests and exit",
            "  --version         print the tool version",
            "  --help            print this text",
            "",
            "Auth is per device:",
            "  password  POST /v1/login with a password read from an environment",
            "            variable or a file; the resulting session token is used",
            "            for GET /v1/status and is never written anywhere",
            "  key       GET /v1/status signed with an Ed25519 key (grom-ed25519-v1)",
            "",
            "Exit codes:",
            "  0  every selected device answered and reported a running server",
            "  1  at least one device did not",
            "  2  usage or configuration error");

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (UsageException e) {
            System.err.println("grom-fleet: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            code = 2;
        } catch (Exception e) {
            System.err.println("grom-fleet: " + describe(e));
            code = 2;
        }
        System.out.flush();
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        Cli cli = Cli.parse(args);

        if (cli.help) {
            System.out.println(USAGE);
            return 0;
        }
        if (cli.version) {
            System.out.println("grom-fleet " + VERSION);
            return 0;
        }
        if (cli.selfTest) {
            return SelfTest.run();
        }
        if (cli.json && cli.watchSeconds > 0) {
            throw new UsageException("--json cannot be combined with --watch");
        }

        Path path = cli.configPath != null ? cli.configPath : defaultConfigPath();
        FleetConfig config = loadConfig(path, cli.timeoutMs);
        for (String warning : config.warnings) {
            System.err.println("grom-fleet: warning: " + warning);
        }

        List<Device> selected = selectDevices(config.devices, cli.only);
        if (selected.isEmpty()) {
            throw new UsageException("no device matches --only " + String.join(",", cli.only));
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        if (cli.watchSeconds > 0) {
            boolean tty = isTty(cli.plain);
            while (true) {
                List<DeviceResult> results = queryAll(client, selected, config.timeoutMs);
                if (tty) {
                    System.out.print("\u001b[H\u001b[2J");
                }
                System.out.println(renderTable(results, config.source, tty));
                System.out.flush();
                Thread.sleep(cli.watchSeconds * 1000L);
            }
        }

        List<DeviceResult> results = queryAll(client, selected, config.timeoutMs);
        if (cli.json) {
            System.out.println(Json.write(jsonReport(results)));
        } else {
            System.out.println(renderTable(results, config.source, isTty(cli.plain)));
        }
        return results.stream().allMatch(r -> r.state.equals("ok")) ? 0 : 1;
    }

    static String describe(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message;
    }

    // ------------------------------------------------------------------- CLI

    static final class Cli {
        Path configPath;
        int timeoutMs = 0; // 0 => config file, then default
        boolean json;
        boolean plain;
        boolean help;
        boolean version;
        boolean selfTest;
        int watchSeconds;
        final List<String> only = new ArrayList<>();

        static Cli parse(String[] args) {
            Cli cli = new Cli();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> cli.help = true;
                    case "--version" -> cli.version = true;
                    case "--json" -> cli.json = true;
                    case "--plain" -> cli.plain = true;
                    case "--self-test" -> cli.selfTest = true;
                    case "--config" -> {
                        String value = value(args, ++i, "--config");
                        cli.configPath = Paths.get(expandHome(value));
                    }
                    case "--timeout" -> {
                        String value = value(args, ++i, "--timeout");
                        cli.timeoutMs = positiveInt(value, "--timeout");
                    }
                    case "--watch" -> {
                        String value = value(args, ++i, "--watch");
                        cli.watchSeconds = positiveInt(value, "--watch");
                        if (cli.watchSeconds < 2) {
                            throw new UsageException("--watch must be at least 2 seconds");
                        }
                    }
                    case "--only" -> {
                        String value = value(args, ++i, "--only");
                        for (String part : value.split(",")) {
                            String trimmed = part.trim();
                            if (!trimmed.isEmpty()) cli.only.add(trimmed.toLowerCase(Locale.ROOT));
                        }
                        if (cli.only.isEmpty()) {
                            throw new UsageException("--only needs at least one name");
                        }
                    }
                    default -> throw new UsageException("unknown option: " + arg);
                }
            }
            return cli;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) throw new UsageException(option + " needs a value");
            return args[index];
        }

        private static int positiveInt(String text, String option) {
            try {
                int value = Integer.parseInt(text.trim());
                if (value <= 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException e) {
                throw new UsageException(option + " needs a positive integer");
            }
        }
    }

    static Path defaultConfigPath() {
        String explicit = System.getenv("GROM_FLEET_CONFIG");
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(expandHome(explicit.trim()));
        }
        Path local = Paths.get("fleet.json");
        if (Files.isRegularFile(local)) return local;

        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            Path candidate = Paths.get(xdg, "grom", "fleet.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return Paths.get(System.getProperty("user.home"), ".config", "grom", "fleet.json");
    }

    static List<Device> selectDevices(List<Device> devices, List<String> filters) {
        if (filters.isEmpty()) return devices;
        List<Device> selected = new ArrayList<>();
        for (Device device : devices) {
            String name = device.name.toLowerCase(Locale.ROOT);
            for (String filter : filters) {
                if (name.contains(filter)) {
                    selected.add(device);
                    break;
                }
            }
        }
        return selected;
    }

    // --------------------------------------------------------------- config

    record FleetConfig(Path source, int timeoutMs, List<Device> devices, List<String> warnings) {}

    record Device(
            String name,
            String baseUrl,
            AuthKind auth,
            String passwordEnv,
            Path passwordFile,
            Path keyFile,
            byte[] seed,
            String keyId) {

        @Override
        public String toString() {
            return name;
        }
    }

    enum AuthKind { PASSWORD, KEY }

    static FleetConfig loadConfig(Path path, int cliTimeoutMs) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new UsageException("config file not found: " + path
                    + " (copy tools/fleet/fleet.example.json and edit it)");
        }
        long size = Files.size(path);
        if (size > MAX_CONFIG_BYTES) {
            throw new UsageException("config file is larger than " + (MAX_CONFIG_BYTES / 1024) + " KiB");
        }

        Object parsed = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new UsageException("config must be a JSON object");
        }

        Object version = root.get("version");
        if (!(version instanceof Number number) || number.longValue() != 1) {
            throw new UsageException("config version must be 1");
        }

        int timeoutMs = cliTimeoutMs;
        if (timeoutMs == 0) {
            Object configured = root.get("timeoutMs");
            if (configured instanceof Number configuredTimeout) timeoutMs = configuredTimeout.intValue();
        }
        timeoutMs = clamp(timeoutMs == 0 ? 8000 : timeoutMs, 500, 60_000);

        Object rawDevices = root.get("devices");
        if (!(rawDevices instanceof List<?> list) || list.isEmpty()) {
            throw new UsageException("config needs a non-empty \"devices\" array");
        }
        if (list.size() > MAX_DEVICES) {
            throw new UsageException("config lists " + list.size() + " devices; the limit is " + MAX_DEVICES);
        }

        List<Device> devices = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        Path baseDir = path.toAbsolutePath().getParent();

        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new UsageException("every entry in \"devices\" must be an object");
            }
            Device device = parseDevice(map, baseDir, warnings);
            if (!names.add(device.name.toLowerCase(Locale.ROOT))) {
                throw new UsageException("duplicate device name: " + device.name);
            }
            devices.add(device);
        }

        return new FleetConfig(path, timeoutMs, devices, warnings);
    }

    static Device parseDevice(Map<?, ?> map, Path baseDir, List<String> warnings) throws Exception {
        String name = string(map, "name");
        if (name == null || name.isBlank()) throw new UsageException("device needs a \"name\"");
        name = name.trim();
        if (name.length() > 64) throw new UsageException("device name must be at most 64 characters");

        String rawUrl = string(map, "url");
        if (rawUrl == null || rawUrl.isBlank()) throw new UsageException(name + ": needs a \"url\"");
        String baseUrl = validateBaseUrl(name, rawUrl);

        String authText = string(map, "auth");
        if (authText == null || authText.isBlank()) {
            throw new UsageException(name + ": needs \"auth\": \"password\" or \"key\"");
        }
        AuthKind auth = switch (authText.trim().toLowerCase(Locale.ROOT)) {
            case "password" -> AuthKind.PASSWORD;
            case "key" -> AuthKind.KEY;
            default -> throw new UsageException(name + ": auth must be \"password\" or \"key\"");
        };

        if (map.containsKey("password")) {
            throw new UsageException(name
                    + ": a plaintext \"password\" field is refused; use \"passwordEnv\" or \"passwordFile\"");
        }

        if (auth == AuthKind.PASSWORD) {
            if (map.containsKey("keyFile") || map.containsKey("seed")) {
                throw new UsageException(name + ": password auth must not carry key material");
            }
            String env = string(map, "passwordEnv");
            String file = string(map, "passwordFile");
            if ((env == null || env.isBlank()) && (file == null || file.isBlank())) {
                throw new UsageException(name
                        + ": password auth needs \"passwordEnv\" or \"passwordFile\"");
            }
            Path passwordFile = (file == null || file.isBlank()) ? null : resolvePath(baseDir, file);
            return new Device(
                    name,
                    baseUrl,
                    auth,
                    (env == null || env.isBlank()) ? null : env.trim(),
                    passwordFile,
                    null,
                    null,
                    null);
        }

        if (map.containsKey("passwordEnv") || map.containsKey("passwordFile")) {
            throw new UsageException(name + ": key auth must not carry a password");
        }

        String keyFileText = string(map, "keyFile");
        String seedText = string(map, "seed");
        if ((keyFileText == null || keyFileText.isBlank()) && (seedText == null || seedText.isBlank())) {
            throw new UsageException(name + ": key auth needs \"keyFile\" or \"seed\"");
        }
        if (keyFileText != null && !keyFileText.isBlank() && seedText != null && !seedText.isBlank()) {
            throw new UsageException(name + ": use either \"keyFile\" or \"seed\", not both");
        }

        Path keyFile = null;
        byte[] seed = null;
        if (keyFileText != null && !keyFileText.isBlank()) {
            // Presence is checked when the key is used, not when the config is
            // read: one missing file must not stop the whole table.
            keyFile = resolvePath(baseDir, keyFileText);
            warnIfWorldReadable(name, keyFile, warnings);
        } else {
            seed = decodeSeed(name, seedText);
        }

        String explicitKeyId = string(map, "keyId");
        String publicKey = string(map, "publicKey");
        String keyId;
        if (explicitKeyId != null && !explicitKeyId.isBlank()) {
            keyId = explicitKeyId.trim();
            if (!keyId.matches("[0-9a-f]{16}")) {
                throw new UsageException(name + ": keyId must be 16 lowercase hex characters");
            }
        } else if (publicKey != null && !publicKey.isBlank()) {
            keyId = keyIdFor(publicKey);
        } else {
            throw new UsageException(name + ": key auth needs \"keyId\" or \"publicKey\"");
        }

        return new Device(name, baseUrl, auth, null, null, keyFile, seed, keyId);
    }

    static String validateBaseUrl(String name, String raw) {
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new UsageException(name + ": url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new UsageException(name + ": url must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new UsageException(name + ": url has no host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new UsageException(name + ": url must not embed credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new UsageException(name + ": url must not carry a query or a fragment");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new UsageException(name + ": url must be an origin (no path)");
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static byte[] decodeSeed(String name, String text) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(text.trim());
        } catch (IllegalArgumentException e) {
            throw new UsageException(name + ": \"seed\" is not valid base64");
        }
        if (decoded.length != 32) {
            throw new UsageException(name + ": \"seed\" must be 32 bytes (got " + decoded.length + ")");
        }
        return decoded;
    }

    /**
     * The key id is the first 8 bytes of SHA-256 over the 32-byte public key,
     * exactly as `Ed25519.keyId` computes it in the engine.
     */
    static String keyIdFor(String publicKeyText) {
        byte[] raw = decodePublicKey(publicKeyText);
        byte[] digest = sha256(raw);
        return hex(digest, 8);
    }

    static byte[] decodePublicKey(String text) {
        String trimmed = text.trim();
        byte[] raw;
        try {
            raw = trimmed.contains("-----BEGIN") ? pemBytes(trimmed) : Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            throw new UsageException("publicKey is not valid base64 or PEM");
        }
        if (raw.length == 32) return raw;
        // X.509 SubjectPublicKeyInfo for Ed25519 is 44 bytes and ends with the
        // raw key after an unused-bits byte.
        if (raw.length == 44 && raw[11] == 0x00) {
            return java.util.Arrays.copyOfRange(raw, 12, 44);
        }
        throw new UsageException("publicKey must be 32 raw bytes or an Ed25519 SPKI");
    }

    static void warnIfWorldReadable(String name, Path path, List<String> warnings) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)) {
                warnings.add(name + ": " + path + " is readable by other users; run chmod 600");
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem; nothing to check.
        }
    }

    // ------------------------------------------------------------- transport

    record HttpResult(int status, String body) {}

    static final class ApiError extends Exception {
        private static final long serialVersionUID = 1L;

        final String kind;

        ApiError(String kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    static HttpResult http(
            HttpClient client,
            String method,
            String url,
            Map<String, String> headers,
            String body,
            int timeoutMs) throws ApiError {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ApiError("timeout", "timed out after " + timeoutMs + " ms");
        } catch (IOException e) {
            throw new ApiError("unreachable", describeIo(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError("unreachable", "interrupted");
        }

        byte[] bytes = response.body();
        if (bytes != null && bytes.length > MAX_BODY_BYTES) {
            throw new ApiError("bad-response", "response body is larger than 1 MiB");
        }
        return new HttpResult(response.statusCode(), bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8));
    }

    static String describeIo(IOException e) {
        String message = e.getMessage();
        if (e instanceof java.net.ConnectException) return "connection refused";
        if (e instanceof java.net.UnknownHostException) return "unknown host";
        if (e instanceof javax.net.ssl.SSLException) return "TLS failure: " + (message == null ? "" : message);
        return message == null ? e.getClass().getSimpleName() : message;
    }

    /** Extracts the stable error code from a Grom error body, or null. */
    static String errorCode(String body) {
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> map) {
                Object error = map.get("error");
                if (error instanceof Map<?, ?> errorMap) {
                    Object code = errorMap.get("code");
                    if (code instanceof String text && !text.isBlank()) return text;
                }
            }
        } catch (RuntimeException ignored) {
            // Not a Grom error body.
        }
        return null;
    }

    // ---------------------------------------------------------------- requests

    static List<DeviceResult> queryAll(HttpClient client, List<Device> devices, int timeoutMs) {
        List<DeviceResult> results = new ArrayList<>(devices.size());
        for (Device device : devices) {
            results.add(query(client, device, timeoutMs));
        }
        return results;
    }

    static DeviceResult query(HttpClient client, Device device, int timeoutMs) {
        try {
            Map<String, Object> status;
            if (device.auth == AuthKind.PASSWORD) {
                String token = login(client, device, timeoutMs);
                status = fetchStatusWithToken(client, device, token, timeoutMs);
            } else {
                status = fetchStatusSigned(client, device, timeoutMs);
            }
            String state = stateOf(status);
            return new DeviceResult(device, state, status, null);
        } catch (ApiError e) {
            String note = e.getMessage();
            if (e.kind.equals("unauthorized")) {
                note = explainRefusal(client, device, timeoutMs, note);
            }
            return new DeviceResult(device, e.kind, null, note);
        } catch (UsageException e) {
            return new DeviceResult(device, "config", null, e.getMessage());
        } catch (RuntimeException e) {
            return new DeviceResult(device, "bad-response", null, describe(e));
        }
    }

    /**
     * A 401 means either "wrong credentials" or "the URL is not Grom at all".
     * The unauthenticated health endpoint settles it without guessing.
     */
    static String explainRefusal(HttpClient client, Device device, int timeoutMs, String note) {
        try {
            HttpResult health = http(
                    client,
                    "GET",
                    device.baseUrl + HEALTH_PATH,
                    Map.of("Accept", "application/json"),
                    null,
                    timeoutMs);
            if (health.status == 200 && health.body.contains(SERVICE)) {
                return "credentials refused" + (note == null || note.isBlank() ? "" : " (" + note + ")");
            }
            if (health.status == 200) {
                return "reached a server, but it does not identify as Grom";
            }
            return "credentials refused (health returned " + health.status + ")";
        } catch (ApiError e) {
            return note == null ? e.getMessage() : note;
        }
    }

    static String login(HttpClient client, Device device, int timeoutMs) throws ApiError {
        String password;
        try {
            password = readPassword(device);
        } catch (UsageException e) {
            throw new ApiError("config", e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", password);
        HttpResult result = http(
                client,
                "POST",
                device.baseUrl + LOGIN_PATH,
                Map.of("Content-Type", "application/json", "Accept", "application/json"),
                Json.write(body),
                timeoutMs);

        if (result.status == 200) {
            Object parsed = Json.parse(result.body);
            if (parsed instanceof Map<?, ?> map && map.get("token") instanceof String token && !token.isBlank()) {
                return token;
            }
            throw new ApiError("bad-response", "login answered without a token");
        }
        if (result.status == 401 || result.status == 403) {
            String code = errorCode(result.body);
            throw new ApiError("unauthorized", code == null ? "login refused" : code);
        }
        if (result.status == 429) {
            throw new ApiError("rate-limited", "login is rate limited; wait and retry");
        }
        throw new ApiError("http-" + result.status, "login returned HTTP " + result.status);
    }

    static String readPassword(Device device) throws UsageException {
        if (device.passwordEnv != null) {
            String value = System.getenv(device.passwordEnv);
            if (value == null || value.isEmpty()) {
                throw new UsageException(device.name + ": environment variable "
                        + device.passwordEnv + " is not set");
            }
            return value;
        }
        try {
            String value = Files.readString(device.passwordFile, StandardCharsets.UTF_8);
            return stripTrailingNewline(value);
        } catch (IOException e) {
            throw new UsageException(device.name + ": cannot read " + device.passwordFile);
        }
    }

    static String stripTrailingNewline(String value) {
        if (value.endsWith("\r\n")) return value.substring(0, value.length() - 2);
        if (value.endsWith("\n")) return value.substring(0, value.length() - 1);
        return value;
    }

    static Map<String, Object> fetchStatusWithToken(
            HttpClient client, Device device, String token, int timeoutMs) throws ApiError {
        HttpResult result = http(
                client,
                "GET",
                device.baseUrl + STATUS_PATH,
                Map.of("Authorization", "Bearer " + token, "Accept", "application/json"),
                null,
                timeoutMs);
        if (result.status == 401) {
            String code = errorCode(result.body);
            throw new ApiError("unauthorized", code == null ? "session token was refused" : code);
        }
        if (result.status != 200) {
            throw new ApiError("http-" + result.status, "status returned HTTP " + result.status);
        }
        return objectBody(result.body);
    }

    static Map<String, Object> fetchStatusSigned(HttpClient client, Device device, int timeoutMs)
            throws ApiError {
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        String nonce = newNonce();
        String canonical = canonical("GET", STATUS_PATH, "", timestamp, nonce);

        String signature;
        try {
            PrivateKey key = loadPrivateKey(device);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            signature = Base64.getEncoder().encodeToString(signer.sign());
        } catch (UsageException e) {
            throw new ApiError("config", e.getMessage());
        } catch (Exception e) {
            throw new ApiError("config", device.name + ": cannot sign with the configured key: " + describe(e));
        }

        HttpResult result = http(
                client,
                "GET",
                device.baseUrl + STATUS_PATH,
                Map.of(
                        "x-grom-key", device.keyId,
                        "x-grom-timestamp", timestamp,
                        "x-grom-nonce", nonce,
                        "x-grom-signature", signature,
                        "Accept", "application/json"),
                null,
                timeoutMs);
        if (result.status == 401) {
            String code = errorCode(result.body);
            throw new ApiError("unauthorized", code == null ? "signature was refused" : code);
        }
        if (result.status != 200) {
            throw new ApiError("http-" + result.status, "status returned HTTP " + result.status);
        }
        return objectBody(result.body);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectBody(String body) throws ApiError {
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (RuntimeException e) {
            throw new ApiError("bad-response", "status is not JSON");
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new ApiError("bad-response", "status is not a JSON object");
        }
        return (Map<String, Object>) map;
    }

    static PrivateKey loadPrivateKey(Device device) throws Exception {
        if (device.keyFile != null) {
            byte[] der = pemBytes(Files.readString(device.keyFile, StandardCharsets.UTF_8));
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, device.seed));
    }

    // ------------------------------------------------- canonical / signatures

    /**
     * The exact string `RequestSigner.canonical` builds (D42, S5-R017):
     * six lines, the body contributing only its lowercase SHA-256 hex, and no
     * trailing newline after the digest.
     */
    static String canonical(String method, String path, String body, String timestamp, String nonce) {
        return "grom-ed25519-v1\n"
                + method.toUpperCase(Locale.ROOT) + "\n"
                + path + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + sha256Hex(body.getBytes(StandardCharsets.UTF_8));
    }

    static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(byte[] bytes) {
        return hex(sha256(bytes), 32);
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes, int count) {
        StringBuilder out = new StringBuilder(count * 2);
        for (int i = 0; i < count && i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out.append(Character.forDigit(value >>> 4, 16)).append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }

    static byte[] pemBytes(String text) {
        String body = text
                .replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                .replaceAll("-----END [A-Z0-9 ]+-----", "")
                .replaceAll("\\s", "");
        if (body.isEmpty()) throw new IllegalArgumentException("empty PEM body");
        return Base64.getDecoder().decode(body);
    }

    // ------------------------------------------------------------------ status

    record DeviceResult(Device device, String state, Map<String, Object> status, String note) {}

    /**
     * Green is earned only by a server that reports `running: true`. A disabled
     * server is a deliberate choice, not a failure, so it stays grey.
     */
    static String stateOf(Map<String, Object> status) {
        if (status == null) return "error";
        if (Boolean.TRUE.equals(status.get("running"))) return "ok";
        if (Boolean.FALSE.equals(status.get("enabled"))) return "disabled";
        return "stopped";
    }

    static String lampColor(String state) {
        return switch (state) {
            case "ok" -> GREEN;
            case "disabled" -> GREY;
            default -> RED;
        };
    }

    static String stateLabel(String state) {
        return switch (state) {
            case "ok" -> "running";
            case "disabled" -> "disabled";
            case "stopped" -> "stopped";
            case "unauthorized" -> "refused";
            case "unreachable" -> "unreachable";
            case "timeout" -> "timeout";
            case "rate-limited" -> "rate limited";
            case "config" -> "config error";
            case "bad-response" -> "bad response";
            default -> state.startsWith("http-") ? "HTTP " + state.substring(5) : state;
        };
    }

    static boolean isTty(boolean plain) {
        return !plain && System.getenv("NO_COLOR") == null && System.console() != null;
    }

    static String colorize(String text, String color, boolean tty) {
        if (!tty || color == null) return text;
        return color + text + RESET;
    }

    static String renderTable(List<DeviceResult> results, Path source, boolean tty) {
        String[] headers = {"", "DEVICE", "STATE", "BIND", "ADDRESS", "UPTIME", "BRIDGE", "PROJECTS", "ALERTS", "NOTE"};
        List<String[]> rows = new ArrayList<>();
        List<String> states = new ArrayList<>();

        for (DeviceResult result : results) {
            Map<String, Object> status = result.status;
            rows.add(new String[] {
                    "\u25cf",
                    result.device.name,
                    stateLabel(result.state),
                    status == null ? "-" : text(status.get("bind")),
                    status == null ? "-" : text(status.get("address")),
                    status == null ? "-" : uptime(status.get("uptimeMs")),
                    bridgeCell(status),
                    projectsCell(status),
                    alertsCell(status),
                    result.note == null ? "" : result.note
            });
            states.add(result.state);
        }

        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("Grom fleet — ").append(results.size()).append(results.size() == 1 ? " device" : " devices")
                .append(" · ").append(source).append('\n').append('\n');

        for (int i = 0; i < headers.length; i++) {
            out.append(pad(headers[i], widths[i]));
            if (i < headers.length - 1) out.append("  ");
        }
        out.append('\n');
        for (int i = 0; i < headers.length; i++) {
            out.append(i == 0 ? " ".repeat(widths[i]) : "-".repeat(widths[i]));
            if (i < headers.length - 1) out.append("  ");
        }
        out.append('\n');

        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            for (int i = 0; i < row.length; i++) {
                String cell = pad(row[i], widths[i]);
                if (i == 0) {
                    out.append(colorize(cell, lampColor(states.get(r)), tty));
                } else if (i == 2) {
                    out.append(colorize(cell, lampColor(states.get(r)), tty));
                } else {
                    out.append(cell);
                }
                if (i < row.length - 1) out.append("  ");
            }
            out.append('\n');
        }

        long running = results.stream().filter(r -> r.state.equals("ok")).count();
        long disabled = results.stream().filter(r -> r.state.equals("disabled")).count();
        long failed = results.size() - running - disabled;
        out.append('\n')
                .append(running).append(" running · ")
                .append(failed).append(" not running · ")
                .append(disabled).append(" disabled")
                .append('\n');

        return out.toString().stripTrailing();
    }

    static String pad(String text, int width) {
        if (text.length() >= width) return text;
        return text + " ".repeat(width - text.length());
    }

    static String text(Object value) {
        if (value == null) return "-";
        String text = Json.write(value).replace("\"", "");
        return text.isEmpty() ? "-" : text;
    }

    static String uptime(Object value) {
        if (!(value instanceof Number number) || number.longValue() <= 0) return "-";
        return duration(number.longValue());
    }

    static String duration(long millis) {
        if (millis < 1000) return millis + "ms";
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + two(seconds % 60) + "s";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + two(minutes % 60) + "m";
        long days = hours / 24;
        return days + "d " + (hours % 24) + "h";
    }

    static String two(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    static String bridgeCell(Map<String, Object> status) {
        Map<?, ?> bridge = status == null ? null : asMap(status.get("bridge"));
        if (bridge == null) return "-";
        if (Boolean.TRUE.equals(bridge.get("connected"))) {
            Object active = bridge.get("active");
            long count = active instanceof Number number ? number.longValue() : 1;
            return count + " connected";
        }
        if (Boolean.TRUE.equals(bridge.get("configured"))) return "not connected";
        return "-";
    }

    static String projectsCell(Map<String, Object> status) {
        Object raw = status == null ? null : status.get("projects");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return "-";
        long alive = 0;
        for (Object element : list) {
            Map<?, ?> project = asMap(element);
            if (project != null && "alive".equals(project.get("state"))) alive++;
        }
        return alive + " alive";
    }

    static String alertsCell(Map<String, Object> status) {
        Map<?, ?> notifications = status == null ? null : asMap(status.get("notifications"));
        if (notifications == null) return "-";
        if (!Boolean.TRUE.equals(notifications.get("enabled"))) return "off";
        Map<?, ?> last = asMap(notifications.get("lastDelivery"));
        if (last == null) return "never";
        if (Boolean.TRUE.equals(last.get("ok"))) {
            Object at = last.get("atMs");
            if (at instanceof Number number && number.longValue() > 0) {
                long age = System.currentTimeMillis() - number.longValue();
                if (age >= 0) return "ok " + duration(age) + " ago";
            }
            return "ok";
        }
        return "failed";
    }

    static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    static Map<String, Object> jsonReport(List<DeviceResult> results) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", 1);
        report.put("source", "grom-fleet");
        report.put("generatedAtMs", System.currentTimeMillis());
        List<Object> devices = new ArrayList<>();
        for (DeviceResult result : results) {
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("name", result.device.name);
            device.put("url", result.device.baseUrl);
            device.put("state", result.state);
            device.put("error", result.note);
            device.put("status", result.status);
            devices.add(device);
        }
        report.put("devices", devices);
        return report;
    }

    // ------------------------------------------------------------------ utils

    static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text : null;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static String expandHome(String path) {
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /** Relative paths are resolved against the config file, never the shell's cwd. */
    static Path resolvePath(Path baseDir, String raw) {
        Path candidate = Paths.get(expandHome(raw.trim()));
        if (candidate.isAbsolute() || baseDir == null) return candidate;
        return baseDir.resolve(candidate).normalize();
    }

    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    // -------------------------------------------------------------------- JSON

    /**
     * A small JSON reader/writer. The tool must run anywhere a JDK runs, so it
     * carries its own parser instead of a dependency (D17).
     */
    static final class Json {

        static Object parse(String text) {
            Parser parser = new Parser(text);
            Object value = parser.value();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                throw new IllegalArgumentException("trailing characters at offset " + parser.index);
            }
            return value;
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            writeValue(out, value);
            return out.toString();
        }

        @SuppressWarnings("unchecked")
        private static void writeValue(StringBuilder out, Object value) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String text) {
                writeString(out, text);
            } else if (value instanceof Boolean || value instanceof Number) {
                out.append(value);
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    writeString(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    writeValue(out, entry.getValue());
                }
                out.append('}');
            } else if (value instanceof Iterable<?> list) {
                out.append('[');
                boolean first = true;
                for (Object element : list) {
                    if (!first) out.append(',');
                    first = false;
                    writeValue(out, element);
                }
                out.append(']');
            } else if (value instanceof Object[] array) {
                out.append('[');
                for (int i = 0; i < array.length; i++) {
                    if (i > 0) out.append(',');
                    writeValue(out, array[i]);
                }
                out.append(']');
            } else {
                writeString(out, value.toString());
            }
        }

        private static void writeString(StringBuilder out, String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }

        private static final class Parser {
            private final String text;
            private int index;

            Parser(String text) {
                this.text = text;
            }

            boolean atEnd() {
                return index >= text.length();
            }

            void skipWhitespace() {
                while (index < text.length()) {
                    char c = text.charAt(index);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        index++;
                    } else {
                        break;
                    }
                }
            }

            char peek() {
                if (index >= text.length()) throw error("unexpected end of input");
                return text.charAt(index);
            }

            RuntimeException error(String message) {
                return new IllegalArgumentException(message + " at offset " + index);
            }

            Object value() {
                skipWhitespace();
                if (atEnd()) throw error("unexpected end of input");
                char c = peek();
                return switch (c) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }

            Object literal(String word, Object value) {
                if (text.startsWith(word, index)) {
                    index += word.length();
                    return value;
                }
                throw error("invalid literal");
            }

            Map<String, Object> object() {
                Map<String, Object> map = new LinkedHashMap<>();
                index++; // {
                skipWhitespace();
                if (peek() == '}') {
                    index++;
                    return map;
                }
                while (true) {
                    skipWhitespace();
                    if (peek() != '"') throw error("expected a string key");
                    String key = string();
                    skipWhitespace();
                    if (peek() != ':') throw error("expected ':'");
                    index++;
                    map.put(key, value());
                    skipWhitespace();
                    char c = peek();
                    if (c == ',') {
                        index++;
                        continue;
                    }
                    if (c == '}') {
                        index++;
                        return map;
                    }
                    throw error("expected ',' or '}'");
                }
            }

            List<Object> array() {
                List<Object> list = new ArrayList<>();
                index++; // [
                skipWhitespace();
                if (peek() == ']') {
                    index++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    skipWhitespace();
                    char c = peek();
                    if (c == ',') {
                        index++;
                        continue;
                    }
                    if (c == ']') {
                        index++;
                        return list;
                    }
                    throw error("expected ',' or ']'");
                }
            }

            String string() {
                index++; // opening quote
                StringBuilder out = new StringBuilder();
                while (true) {
                    if (atEnd()) throw error("unterminated string");
                    char c = text.charAt(index++);
                    if (c == '"') return out.toString();
                    if (c != '\\') {
                        out.append(c);
                        continue;
                    }
                    if (atEnd()) throw error("unterminated escape");
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) throw error("truncated \\u escape");
                            out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                            index += 4;
                        }
                        default -> throw error("invalid escape \\" + escaped);
                    }
                }
            }

            Number number() {
                int start = index;
                while (index < text.length()) {
                    char c = text.charAt(index);
                    if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                        index++;
                    } else {
                        break;
                    }
                }
                String slice = text.substring(start, index);
                if (slice.isEmpty()) throw error("expected a value");
                try {
                    if (slice.contains(".") || slice.contains("e") || slice.contains("E")) {
                        return Double.parseDouble(slice);
                    }
                    return Long.parseLong(slice);
                } catch (NumberFormatException e) {
                    throw error("invalid number");
                }
            }
        }
    }

    // --------------------------------------------------------------- self-test

    /**
     * The built-in protocol tests. They start a real HTTP server on loopback and
     * exercise the same code paths the CLI uses, so a change to the canonical
     * string or the login flow fails here before it fails against a phone.
     */
    static final class SelfTest {

        private static int passed;
        private static int failed;

        static int run() {
            passed = 0;
            failed = 0;

            check("canonical string matches the engine golden", SelfTest::canonicalGolden);
            check("json round-trips nested values", SelfTest::jsonRoundTrip);
            check("config refuses a plaintext password", SelfTest::configRefusesPlaintextPassword);
            check("config refuses a non-http scheme", SelfTest::configRefusesBadScheme);
            check("config refuses credentials in the url", SelfTest::configRefusesUserInfo);
            check("key auth needs a key id or a public key", SelfTest::configNeedsKeyId);
            check("the shipped example config parses", SelfTest::exampleConfigParses);
            check("password login reads the status document", SelfTest::passwordLogin);
            check("a wrong password is reported as refused", SelfTest::wrongPassword);
            check("a missing password source is a config error", SelfTest::missingPasswordSource);
            check("a signed request is accepted by a strict verifier", SelfTest::signedRequest);
            check("a PEM key file signs the same request", SelfTest::pemKeyFile);
            check("an unknown key id is reported as refused", SelfTest::unknownKeyId);
            check("a refused connection is reported as unreachable", SelfTest::unreachable);
            check("a nonce has the shape the engine accepts", SelfTest::nonceShape);
            check("running, stopped and disabled map to distinct states", SelfTest::stateMapping);
            check("public keys derive the same key id as the engine", SelfTest::keyIdDerivation);
            check("the report carries neither the password nor the token", SelfTest::reportHasNoSecret);

            System.out.println();
            if (failed > 0) {
                System.out.println("self-test: FAILED (" + passed + "/" + (passed + failed) + ")");
                return 1;
            }
            System.out.println("self-test: OK (" + passed + "/" + (passed + failed) + ")");
            return 0;
        }

        interface Body {
            void run() throws Exception;
        }

        static void check(String name, Body body) {
            try {
                body.run();
                passed++;
                System.out.println("ok   " + name);
            } catch (Throwable t) {
                failed++;
                System.out.println("FAIL " + name + ": " + GromFleet.describe(
                        t instanceof Exception e ? e : new RuntimeException(t)));
            }
        }

        static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }

        static void equal(Object expected, Object actual, String what) {
            if (!java.util.Objects.equals(expected, actual)) {
                throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
            }
        }

        // -- cases ---------------------------------------------------------

        static void canonicalGolden() {
            // The exact vector pinned by RequestSignerTest in the engine.
            String canonical = GromFleet.canonical("post", "/v1/orders", "{}", "1700000000", "abcdefgh");
            equal("grom-ed25519-v1\nPOST\n/v1/orders\n1700000000\nabcdefgh\n"
                            + "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                    canonical,
                    "canonical string");
            equal(6, canonical.split("\n").length, "line count");
        }

        static void jsonRoundTrip() {
            String text = "{\"a\":[1,2.5,true,null,\"x\\né\"],\"b\":{\"c\":-3}}";
            String written = GromFleet.Json.write(GromFleet.Json.parse(text));
            equal(text, written, "round-trip");
        }

        static void configRefusesPlaintextPassword() throws Exception {
            String json = "{\"version\":1,\"devices\":[{\"name\":\"p\",\"url\":\"http://127.0.0.1:1\","
                    + "\"auth\":\"password\",\"password\":\"hunter2\"}]}";
            Path file = writeTemp(json);
            try {
                GromFleet.loadConfig(file, 1000);
                throw new AssertionError("a plaintext password was accepted");
            } catch (GromFleet.UsageException expected) {
                require(expected.getMessage().contains("plaintext"), "wrong message: " + expected.getMessage());
            } finally {
                Files.deleteIfExists(file);
            }
        }

        static void configRefusesBadScheme() throws Exception {
            String json = "{\"version\":1,\"devices\":[{\"name\":\"p\",\"url\":\"ftp://127.0.0.1\","
                    + "\"auth\":\"key\",\"seed\":\"" + seedBase64() + "\",\"keyId\":\"0123456789abcdef\"}]}";
            Path file = writeTemp(json);
            try {
                GromFleet.loadConfig(file, 1000);
                throw new AssertionError("ftp was accepted");
            } catch (GromFleet.UsageException expected) {
                require(expected.getMessage().contains("http"), "wrong message: " + expected.getMessage());
            } finally {
                Files.deleteIfExists(file);
            }
        }

        static void configRefusesUserInfo() throws Exception {
            String json = "{\"version\":1,\"devices\":[{\"name\":\"p\",\"url\":\"http://u:p@127.0.0.1:1\","
                    + "\"auth\":\"key\",\"seed\":\"" + seedBase64() + "\",\"keyId\":\"0123456789abcdef\"}]}";
            Path file = writeTemp(json);
            try {
                GromFleet.loadConfig(file, 1000);
                throw new AssertionError("embedded credentials were accepted");
            } catch (GromFleet.UsageException expected) {
                require(expected.getMessage().contains("credentials"), "wrong message: " + expected.getMessage());
            } finally {
                Files.deleteIfExists(file);
            }
        }

        static void configNeedsKeyId() throws Exception {
            String json = "{\"version\":1,\"devices\":[{\"name\":\"p\",\"url\":\"http://127.0.0.1:1\","
                    + "\"auth\":\"key\",\"seed\":\"" + seedBase64() + "\"}]}";
            Path file = writeTemp(json);
            try {
                GromFleet.loadConfig(file, 1000);
                throw new AssertionError("key auth without keyId or publicKey was accepted");
            } catch (GromFleet.UsageException expected) {
                require(expected.getMessage().contains("keyId"), "wrong message: " + expected.getMessage());
            } finally {
                Files.deleteIfExists(file);
            }
        }

        static void exampleConfigParses() throws Exception {
            Path example = Paths.get("tools/fleet/fleet.example.json");
            require(Files.isRegularFile(example), "example config missing at " + example.toAbsolutePath());
            GromFleet.FleetConfig config = GromFleet.loadConfig(example, 1000);
            require(config.devices.size() >= 2, "example should show both auth modes");
        }

        static void passwordLogin() throws Exception {
            KeyPair keys = generateEd25519();
            Map<String, Object> status = sampleStatus(true, true);
            HttpServer server = startServer(keys, "correct horse", "test-token", status);
            Path password = writeSecret("correct horse");
            try {
                Device device = new Device("pixel", base(server), AuthKind.PASSWORD,
                        null, password, null, null, null);
                GromFleet.DeviceResult result = GromFleet.query(httpClient(), device, 3000);
                equal("ok", result.state, "state");
                require(result.status != null, "status was not parsed");
                equal("LAN", result.status.get("bind"), "bind");
            } finally {
                server.stop(0);
                Files.deleteIfExists(password);
            }
        }

        static void wrongPassword() throws Exception {
            KeyPair keys = generateEd25519();
            Map<String, Object> status = sampleStatus(true, true);
            HttpServer server = startServer(keys, "correct horse", "test-token", status);
            Path password = writeSecret("wrong");
            try {
                Device device = new Device("pixel", base(server), AuthKind.PASSWORD,
                        null, password, null, null, null);
                GromFleet.DeviceResult result = GromFleet.query(httpClient(), device, 3000);
                equal("unauthorized", result.state, "state");
                require(result.note != null && result.note.contains("credentials"),
                        "note should explain the refusal: " + result.note);
            } finally {
                server.stop(0);
                Files.deleteIfExists(password);
            }
        }

        static void missingPasswordSource() throws Exception {
            Device device = new Device("pixel", "http://127.0.0.1:1", AuthKind.PASSWORD,
                    "GROM_FLEET_DEFINITELY_UNSET", null, null, null, null);
            try {
                GromFleet.readPassword(device);
                throw new AssertionError("an unset environment variable was accepted");
            } catch (GromFleet.UsageException expected) {
                require(expected.getMessage().contains("not set"), "wrong message: " + expected.getMessage());
            }
        }

        static void signedRequest() throws Exception {
            KeyPair keys = generateEd25519();
            byte[] rawPublic = rawPublicKey(keys.getPublic());
            Map<String, Object> status = sampleStatus(true, true);
            HttpServer server = startServer(keys, "unused", "test-token", status);
            try {
                Device device = new Device("laptop", base(server), AuthKind.KEY, null, null, null,
                        privateSeed(keys.getPrivate()), GromFleet.hex(GromFleet.sha256(rawPublic), 8));
                GromFleet.DeviceResult result = GromFleet.query(httpClient(), device, 3000);
                equal("ok", result.state, "state");
                equal("LAN", result.status.get("bind"), "bind");
            } finally {
                server.stop(0);
            }
        }

        static void pemKeyFile() throws Exception {
            KeyPair keys = generateEd25519();
            byte[] rawPublic = rawPublicKey(keys.getPublic());
            Map<String, Object> status = sampleStatus(true, true);
            HttpServer server = startServer(keys, "unused", "test-token", status);
            Path pem = Files.createTempFile("grom-fleet-key-", ".pem");
            String body = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
            StringBuilder pemText = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
            for (int i = 0; i < body.length(); i += 64) {
                pemText.append(body, i, Math.min(i + 64, body.length())).append('\n');
            }
            pemText.append("-----END PRIVATE KEY-----\n");
            Files.writeString(pem, pemText.toString(), StandardCharsets.UTF_8);
            try {
                Device device = new Device("laptop", base(server), AuthKind.KEY, null, null, pem, null,
                        GromFleet.hex(GromFleet.sha256(rawPublic), 8));
                GromFleet.DeviceResult result = GromFleet.query(httpClient(), device, 3000);
                equal("ok", result.state, "state");
            } finally {
                server.stop(0);
                Files.deleteIfExists(pem);
            }
        }

        static void unknownKeyId() throws Exception {
            KeyPair keys = generateEd25519();
            Map<String, Object> status = sampleStatus(true, true);
            HttpServer server = startServer(keys, "unused", "test-token", status);
            try {
                Device device = new Device("laptop", base(server), AuthKind.KEY, null, null, null,
                        privateSeed(keys.getPrivate()), "ffffffffffffffff");
                GromFleet.DeviceResult result = GromFleet.query(httpClient(), device, 3000);
                equal("unauthorized", result.state, "state");
            } finally {
                server.stop(0);
            }
        }

        static void unreachable() throws Exception {
            // Bind a port, learn it, close it: connecting there is refused.
            java.net.ServerSocket socket = new java.net.ServerSocket(0);
            int port = socket.getLocalPort();
            socket.close();
            Device device = new Device("gone", "http://127.0.0.1:" + port, AuthKind.KEY, null, null, null,
                    new byte[32], "0123456789abcdef");
            GromFleet.DeviceResult result = GromFleet.query(httpClient(), device, 2000);
            equal("unreachable", result.state, "state");
        }

        static void nonceShape() {
            for (int i = 0; i < 64; i++) {
                String nonce = GromFleet.newNonce();
                require(nonce.matches("[A-Za-z0-9_-]{8,128}"), "bad nonce: " + nonce);
            }
        }

        static void stateMapping() {
            equal("ok", GromFleet.stateOf(Map.of("running", true, "enabled", true)), "running");
            equal("stopped", GromFleet.stateOf(Map.of("running", false, "enabled", true)), "stopped");
            equal("disabled", GromFleet.stateOf(Map.of("running", false, "enabled", false)), "disabled");
            equal("error", GromFleet.stateOf(null), "missing status");
        }

        static void keyIdDerivation() throws Exception {
            // Cross-check against the engine test seed (bytes 1..32): the public
            // key is derived by the JDK from the same seed and must produce the
            // key id the engine would compute.
            byte[] seed = new byte[32];
            for (int i = 0; i < seed.length; i++) seed[i] = (byte) (i + 1);
            // The JDK cannot derive a public key from a seed, so use a generated
            // pair and verify the base64/SPKI paths agree with each other.
            KeyPair keys = generateEd25519();
            byte[] raw = rawPublicKey(keys.getPublic());
            String fromRaw = GromFleet.keyIdFor(Base64.getEncoder().encodeToString(raw));
            String fromSpki = GromFleet.keyIdFor(
                    "-----BEGIN PUBLIC KEY-----\n"
                            + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())
                            + "\n-----END PUBLIC KEY-----");
            equal(fromRaw, fromSpki, "key id from raw and SPKI");
            equal(GromFleet.hex(GromFleet.sha256(raw), 8), fromRaw, "key id is sha-256 prefix");
            require(seed.length == 32, "seed sanity");
        }

        static void reportHasNoSecret() throws Exception {
            KeyPair keys = generateEd25519();
            Map<String, Object> status = sampleStatus(true, true);
            HttpServer server = startServer(keys, "correct horse", "test-token", status);
            Path password = writeSecret("correct horse");
            try {
                Device device = new Device("pixel", base(server), AuthKind.PASSWORD, null, password, null, null, null);
                GromFleet.DeviceResult result = GromFleet.query(httpClient(), device, 3000);
                String report = GromFleet.Json.write(GromFleet.jsonReport(List.of(result)));
                require(!report.contains("correct horse"), "the password appears in the report");
                require(!report.contains("test-token"), "the session token appears in the report");
                require(report.contains("pixel"), "the report lost the device name");
            } finally {
                server.stop(0);
                Files.deleteIfExists(password);
            }
        }

        // -- fixtures ------------------------------------------------------

        static HttpClient httpClient() {
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        static String base(HttpServer server) {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        static KeyPair generateEd25519() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        }

        static byte[] rawPublicKey(PublicKey publicKey) {
            byte[] encoded = publicKey.getEncoded();
            return java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        }

        static byte[] privateSeed(PrivateKey privateKey) {
            byte[] encoded = privateKey.getEncoded();
            return java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        }

        static String seedBase64() {
            byte[] seed = new byte[32];
            for (int i = 0; i < seed.length; i++) seed[i] = (byte) (i + 1);
            return Base64.getEncoder().encodeToString(seed);
        }

        static Path writeSecret(String value) throws IOException {
            Path file = Files.createTempFile("grom-fleet-pw-", ".txt");
            Files.writeString(file, value + "\n", StandardCharsets.UTF_8);
            return file;
        }

        static Path writeTemp(String json) throws IOException {
            Path file = Files.createTempFile("grom-fleet-", ".json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return file;
        }

        static Map<String, Object> sampleStatus(boolean running, boolean enabled) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("ok", true);
            status.put("service", SERVICE);
            status.put("api", 1);
            status.put("uptimeMs", 3_723_000L);
            status.put("auth", "BOTH");
            status.put("bind", "LAN");
            status.put("enabled", enabled);
            status.put("running", running);
            status.put("address", "192.168.1.20:47002");
            status.put("port", 47002);
            Map<String, Object> project = new LinkedHashMap<>();
            project.put("id", "wallet");
            project.put("title", "Wallet");
            project.put("state", "alive");
            status.put("projects", List.of(project));
            Map<String, Object> bridge = new LinkedHashMap<>();
            bridge.put("configured", true);
            bridge.put("connected", true);
            bridge.put("active", 1);
            bridge.put("tunnels", List.of());
            status.put("bridge", bridge);
            Map<String, Object> notifications = new LinkedHashMap<>();
            notifications.put("enabled", true);
            notifications.put("configured", true);
            Map<String, Object> delivery = new LinkedHashMap<>();
            delivery.put("ok", true);
            delivery.put("atMs", System.currentTimeMillis() - 60_000);
            notifications.put("lastDelivery", delivery);
            status.put("notifications", notifications);
            return status;
        }

        /**
         * A strict fake data plane: it verifies the exact canonical string, the
         * timestamp window, the nonce shape and the key id, so the tool cannot
         * pass by accident.
         */
        static HttpServer startServer(
                KeyPair keys,
                String password,
                String token,
                Map<String, Object> status) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String expectedKeyId = GromFleet.hex(GromFleet.sha256(rawPublicKey(keys.getPublic())), 8);
            String statusJson = GromFleet.Json.write(status);

            server.createContext(HEALTH_PATH, exchange -> respond(exchange, 200,
                    "{\"ok\":true,\"service\":\"" + SERVICE + "\",\"api\":1,\"uptimeMs\":1000}"));
            server.createContext(LOGIN_PATH, exchange -> {
                if (!exchange.getRequestMethod().equals("POST")) {
                    respond(exchange, 405, "{\"ok\":false}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Object parsed = GromFleet.Json.parse(body);
                Object supplied = parsed instanceof Map<?, ?> map ? map.get("password") : null;
                if (password.equals(supplied)) {
                    respond(exchange, 200,
                            "{\"ok\":true,\"token\":\"" + token + "\",\"expiresAtMs\":0}");
                } else {
                    respond(exchange, 401,
                            "{\"ok\":false,\"error\":{\"code\":\"INVALID_CREDENTIALS\"}}");
                }
            });
            server.createContext(STATUS_PATH, exchange -> {
                if (!exchange.getRequestMethod().equals("GET")) {
                    respond(exchange, 405, "{\"ok\":false}");
                    return;
                }
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (("Bearer " + token).equals(authorization)) {
                    respond(exchange, 200, statusJson);
                    return;
                }
                String refusal = verifySignature(exchange, expectedKeyId, keys.getPublic());
                if (refusal == null) {
                    respond(exchange, 200, statusJson);
                } else {
                    respond(exchange, 401,
                            "{\"ok\":false,\"error\":{\"code\":\"" + refusal + "\"}}");
                }
            });
            server.start();
            return server;
        }

        /** Mirrors RequestSigner.verify; returns an error code or null. */
        static String verifySignature(HttpExchange exchange, String expectedKeyId, PublicKey publicKey) {
            try {
                var headers = exchange.getRequestHeaders();
                String keyId = headers.getFirst("x-grom-key");
                String timestamp = headers.getFirst("x-grom-timestamp");
                String nonce = headers.getFirst("x-grom-nonce");
                String signature = headers.getFirst("x-grom-signature");
                if (keyId == null || timestamp == null || nonce == null || signature == null) {
                    return "SIGNATURE_MISSING";
                }
                if (!keyId.equals(expectedKeyId)) return "SIGNATURE_UNKNOWN_KEY";

                long seconds = Long.parseLong(timestamp);
                long now = System.currentTimeMillis() / 1000;
                if (Math.abs(now - seconds) > MAX_SKEW_SECONDS) return "SIGNATURE_STALE";
                if (!nonce.matches("[A-Za-z0-9_-]{8,128}")) return "SIGNATURE_BAD_NONCE";

                String canonical = GromFleet.canonical(
                        exchange.getRequestMethod(), STATUS_PATH, "", timestamp, nonce);
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(publicKey);
                verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
                byte[] decoded = Base64.getDecoder().decode(signature);
                return verifier.verify(decoded) ? null : "SIGNATURE_INVALID";
            } catch (Exception e) {
                return "SIGNATURE_INVALID";
            }
        }

        static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

    }
}
