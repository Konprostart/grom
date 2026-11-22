// SPDX-License-Identifier: Apache-2.0
//
// grom-hub — a small roster for phones that reach the VPS through their own
// reverse SSH tunnels.
//
// Phase C of the fleet plan (docs/HUB.md, D76-D78). Like grom-fleet it lives
// outside the Android tree on purpose:
//
//   * it is an operator-run tool, not a service Grom depends on (D76);
//   * it only reads the documented public data plane:
//       GET /v1/status          signed with the hub's own Ed25519 key
//   * it opens no listening port except a loopback roster (D78);
//   * it writes no secret of a phone and never stores an operator credential.
//
// A phone is reachable without ever opening a LAN or public port: it exposes
// its data plane on 127.0.0.1 and its SSH target forwards it to a distinct
// loopback port on the VPS (one per phone, 41001-41099). The hub polls those
// loopback ports, so the phone can be on any network, behind CGNAT included.
//
// The whole program is one file so it needs nothing but a JDK 17:
//
//     tools/grom-hub identity --init
//     tools/grom-hub check --config hub.json
//     tools/grom-hub run   --config hub.json
//
// Run the built-in protocol tests with `tools/test-hub`.

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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class GromHub {

    static final String VERSION = "1.0.0";

    /** The only endpoint this tool ever calls on a phone (S5-R010). */
    static final String STATUS_PATH = "/v1/status";

    static final String ROSTER_PATH = "/roster.json";
    static final String HEALTH_PATH = "/health";

    /** The only fields a roster row may contain. Pinned by tools/test-policy. */
    static final List<String> ROSTER_FIELDS =
            List.of("label", "nodeId", "state", "lastSeenMs", "lastError");

    static final long ALIVE_AFTER_MS = 90_000;
    static final long MAX_SKEW_SECONDS = 300;
    static final int MAX_BODY_BYTES = 1 << 20;
    static final int MAX_CONFIG_BYTES = 256 << 10;
    static final int MAX_DEVICES = 64;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String RESET = "\u001b[0m";
    private static final String GREEN = "\u001b[32m";
    private static final String RED = "\u001b[31m";
    private static final String GREY = "\u001b[90m";

    static final String USAGE = String.join("\n",
            "grom-hub " + VERSION + " — a loopback roster over reverse SSH tunnels",
            "",
            "Commands:",
            "  identity --init      create this hub's Ed25519 key (mode 0600)",
            "  run                  poll the phones and serve the roster on loopback",
            "  check                poll once, print the table and exit",
            "",
            "Options:",
            "  --config FILE        hub config (default: ./hub.json, then",
            "                       $XDG_CONFIG_HOME/grom/hub.json)",
            "  --init               create the identity (with the identity command)",
            "  --force              replace an existing identity file",
            "  --json               print machine-readable JSON (check only)",
            "  --plain              disable ANSI colours",
            "  --self-test          run the built-in protocol tests and exit",
            "  --version            print the tool version",
            "  --help               print this text",
            "",
            "Each phone needs:",
            "  1. Public access on, bind LOOPBACK, auth Key or Both;",
            "  2. an SSH target whose remote forward port is unique on the VPS",
            "     (one port per phone, 41001-41099), and the tunnel connected;",
            "  3. Devices -> Trust a key on the phone, with this hub's public key",
            "     and the status.read scope.",
            "",
            "The roster names devices and their measured state. It never carries a",
            "port, an address or a key, and it is served on loopback only.",
            "",
            "Exit codes (check):",
            "  0  every device answered and is alive",
            "  1  at least one device is not",
            "  2  usage or configuration error");

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (UsageException e) {
            System.err.println("grom-hub: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            code = 2;
        } catch (Exception e) {
            System.err.println("grom-hub: " + describe(e));
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
            System.out.println("grom-hub " + VERSION);
            return 0;
        }
        if (cli.selfTest) {
            return SelfTest.run();
        }
        return switch (cli.command) {
            case "identity" -> runIdentity(cli);
            case "run" -> runServe(cli);
            case "check" -> runCheck(cli);
            default -> throw new UsageException("choose identity, run or check");
        };
    }

    static int runIdentity(Cli cli) throws Exception {
        if (!cli.init) {
            throw new UsageException("identity needs --init (there is no other way to create a key)");
        }
        Path path = identityPath(cli);
        if (Files.exists(path) && !cli.force) {
            throw new UsageException("identity file already exists: " + path
                    + " (pass --force to replace it; every phone must then trust the new key)");
        }
        Identity identity = Identity.generate();
        identity.save(path);
        System.out.println("grom-hub identity created: " + path);
        System.out.println();
        System.out.println("  key id      " + identity.keyId());
        System.out.println("  fingerprint " + fingerprint(identity.keyId()));
        System.out.println("  public key  " + identity.publicKeyBase64());
        System.out.println();
        System.out.println("On every phone: Menu -> Devices -> Trust a key, paste the public");
        System.out.println("key above, and choose at least the status.read scope.");
        System.out.println("The key file is readable by its owner only; keep it that way.");
        return 0;
    }

    static int runCheck(Cli cli) throws Exception {
        HubConfig config = loadConfig(configPath(cli));
        Identity identity = Identity.load(identityPath(cli));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HubState state = new HubState();
        pollAll(config, identity, client, state, System::currentTimeMillis);

        long now = System.currentTimeMillis();
        if (cli.json) {
            System.out.println(Json.write(state.rosterDocument(now)));
        } else {
            System.out.println(renderTable(config, state, now, isTty(cli.plain)));
        }
        return state.allAlive(now) ? 0 : 1;
    }

    static int runServe(Cli cli) throws Exception {
        HubConfig config = loadConfig(configPath(cli));
        if (!listenIsLoopback(config.listenHost)) {
            throw new UsageException("the roster may listen on loopback only, not "
                    + config.listenHost + " (D78). Publish it through a tunnel or a proxy.");
        }
        Identity identity = Identity.load(identityPath(cli));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HubState state = new HubState();

        HttpServer server = HttpServer.create(
                new InetSocketAddress(config.listenHost, config.listenPort), 0);
        server.createContext(ROSTER_PATH, exchange -> respondRoster(exchange, state));
        server.createContext(HEALTH_PATH, GromHub::respondHealth);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        System.out.println("grom-hub listening on http://" + config.listenHost + ":"
                + config.listenPort + ROSTER_PATH);
        System.out.println("polling " + config.devices.size() + " device(s) every "
                + config.pollSeconds + " s; state is measured, never assumed");

        CountDownLatch stopped = new CountDownLatch(1);
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(runnable ->
                daemon(runnable, "grom-hub-poller"));
        Runnable once = () -> {
            try {
                pollAll(config, identity, client, state, System::currentTimeMillis);
                writeRoster(config, state, System.currentTimeMillis());
            } catch (Exception e) {
                System.err.println("grom-hub: poll failed: " + describe(e));
            }
        };
        poller.scheduleWithFixedDelay(once, 0, config.pollSeconds, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            poller.shutdownNow();
            server.stop(0);
            stopped.countDown();
        }, "grom-hub-shutdown"));
        // Serve until the process is stopped (Ctrl-C runs the hook above).
        stopped.await();
        return 0;
    }

    static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    static String describe(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message;
    }

    // ------------------------------------------------------------------- CLI

    static final class Cli {
        String command = "";
        Path configPath;
        boolean init;
        boolean force;
        boolean json;
        boolean plain;
        boolean help;
        boolean version;
        boolean selfTest;

        static Cli parse(String[] args) {
            Cli cli = new Cli();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> cli.help = true;
                    case "--version" -> cli.version = true;
                    case "--self-test" -> cli.selfTest = true;
                    case "--json" -> cli.json = true;
                    case "--plain" -> cli.plain = true;
                    case "--init" -> cli.init = true;
                    case "--force" -> cli.force = true;
                    case "--config" -> {
                        if (i + 1 >= args.length) throw new UsageException("--config needs a value");
                        cli.configPath = Paths.get(expandHome(args[++i]));
                    }
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new UsageException("unknown option: " + arg);
                        }
                        if (!cli.command.isEmpty()) {
                            throw new UsageException("only one command may be given");
                        }
                        cli.command = arg;
                    }
                }
            }
            if (cli.help || cli.version || cli.selfTest) return cli;
            if (cli.command.isEmpty()) throw new UsageException("choose identity, run or check");
            if (!Set.of("identity", "run", "check").contains(cli.command)) {
                throw new UsageException("unknown command: " + cli.command);
            }
            if (cli.json && !cli.command.equals("check")) {
                throw new UsageException("--json is only meaningful with check");
            }
            return cli;
        }
    }

    static Path configPath(Cli cli) {
        if (cli.configPath != null) return cli.configPath;
        String explicit = System.getenv("GROM_HUB_CONFIG");
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(expandHome(explicit.trim()));
        }
        Path local = Paths.get("hub.json");
        if (Files.isRegularFile(local)) return local;
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            Path candidate = Paths.get(xdg, "grom", "hub.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return Paths.get(System.getProperty("user.home"), ".config", "grom", "hub.json");
    }

    /** The key lives beside the config unless the config names another file. */
    static Path identityPath(Cli cli) {
        Path config = configPath(cli);
        Path base = config.toAbsolutePath().getParent();
        if (Files.isRegularFile(config)) {
            try {
                Object parsed = Json.parse(Files.readString(config, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> root
                        && root.get("identityFile") instanceof String text
                        && !text.isBlank()) {
                    return resolvePath(base, text);
                }
            } catch (Exception ignored) {
                // A broken config is reported by loadConfig when it matters.
            }
        }
        return base == null ? Paths.get("hub-identity.json") : base.resolve("hub-identity.json");
    }

    // --------------------------------------------------------------- config

    record Device(String label, String url, String pinnedNodeId) {}

    record HubConfig(
            Path source,
            int timeoutMs,
            int pollSeconds,
            String listenHost,
            int listenPort,
            Path rosterFile,
            List<Device> devices) {}

    static HubConfig loadConfig(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new UsageException("config file not found: " + path
                    + " (copy tools/hub/hub.example.json and edit it)");
        }
        if (Files.size(path) > MAX_CONFIG_BYTES) {
            throw new UsageException("config file is larger than "
                    + (MAX_CONFIG_BYTES / 1024) + " KiB");
        }
        Object parsed = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new UsageException("config must be a JSON object");
        }
        Object version = root.get("version");
        if (!(version instanceof Number number) || number.longValue() != 1) {
            throw new UsageException("config version must be 1");
        }

        int timeoutMs = root.get("timeoutMs") instanceof Number n
                ? clamp(n.intValue(), 500, 60_000)
                : 8_000;
        int pollSeconds = root.get("pollSeconds") instanceof Number p
                ? clamp(p.intValue(), 5, 3600)
                : 30;

        String listen = root.get("listen") instanceof String text && !text.isBlank()
                ? text.trim()
                : "127.0.0.1:47100";
        int colon = listen.lastIndexOf(':');
        if (colon <= 0 || colon == listen.length() - 1) {
            throw new UsageException("listen must be host:port");
        }
        String listenHost = listen.substring(0, colon);
        int listenPort;
        try {
            listenPort = Integer.parseInt(listen.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new UsageException("listen port is not a number");
        }
        if (listenPort < 1 || listenPort > 65535) {
            throw new UsageException("listen port must be 1..65535");
        }

        Path base = path.toAbsolutePath().getParent();
        Path rosterFile = root.get("rosterFile") instanceof String roster && !roster.isBlank()
                ? resolvePath(base, roster)
                : (base == null ? Paths.get("roster.json") : base.resolve("roster.json"));

        Object rawDevices = root.get("devices");
        if (!(rawDevices instanceof List<?> list) || list.isEmpty()) {
            throw new UsageException("config needs a non-empty \"devices\" array");
        }
        if (list.size() > MAX_DEVICES) {
            throw new UsageException("config lists " + list.size() + " devices; the limit is "
                    + MAX_DEVICES);
        }
        List<Device> devices = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new UsageException("every entry in \"devices\" must be an object");
            }
            String label = string(map, "label");
            if (label == null || label.isBlank()) {
                throw new UsageException("device needs a \"label\"");
            }
            label = label.trim();
            if (label.length() > 64) {
                throw new UsageException("device label must be at most 64 characters");
            }
            if (!labels.add(label.toLowerCase(Locale.ROOT))) {
                throw new UsageException("duplicate device label: " + label);
            }
            String url = string(map, "url");
            if (url == null || url.isBlank()) {
                throw new UsageException(label + ": needs a \"url\" (http://127.0.0.1:41001)");
            }
            String baseUrl = validateBaseUrl(label, url);
            String pinned = string(map, "nodeId");
            if (pinned != null && !pinned.isBlank() && !pinned.trim().matches("[0-9a-f]{16}")) {
                throw new UsageException(label + ": nodeId must be 16 lowercase hex characters");
            }
            devices.add(new Device(label, baseUrl,
                    pinned == null || pinned.isBlank() ? null : pinned.trim()));
        }
        return new HubConfig(path, timeoutMs, pollSeconds, listenHost, listenPort, rosterFile, devices);
    }

    static String validateBaseUrl(String label, String raw) {
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new UsageException(label + ": url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new UsageException(label + ": url must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new UsageException(label + ": url has no host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new UsageException(label + ": url must not embed credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new UsageException(label + ": url must not carry a query or a fragment");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new UsageException(label + ": url must be an origin (no path)");
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** A roster bound to anything but loopback would expose device names. */
    static boolean listenIsLoopback(String host) {
        if (host == null || host.isBlank()) return false;
        String text = host.trim().toLowerCase(Locale.ROOT);
        return text.equals("localhost")
                || text.equals("127.0.0.1")
                || text.equals("::1")
                || text.equals("[::1]");
    }

    // ------------------------------------------------------------- identity

    /**
     * The hub's own Ed25519 key. It is a plain mode-0600 file: there is no
     * Android Keystore on a VPS, so file ownership and permissions are the
     * protection, and that limitation is stated in docs/HUB.md (D76).
     */
    record Identity(String publicKeyBase64, String privateKeyBase64, long createdAtMs) {

        static Identity generate() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            return new Identity(
                    Base64.getEncoder().encodeToString(rawPublicKey(pair.getPublic())),
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                    System.currentTimeMillis());
        }

        String keyId() {
            return keyIdFor(publicKeyBase64);
        }

        PrivateKey privateKey() throws Exception {
            byte[] der = Base64.getDecoder().decode(privateKeyBase64);
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        }

        PublicKey publicKey() throws Exception {
            return publicKeyFromRaw(publicRaw());
        }

        byte[] publicRaw() {
            byte[] raw = Base64.getDecoder().decode(publicKeyBase64);
            if (raw.length != 32) {
                throw new IllegalStateException("stored public key is not 32 bytes");
            }
            return raw;
        }

        void save(Path path) throws Exception {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("version", 1);
            document.put("kind", "grom-hub-identity");
            document.put("publicKey", publicKeyBase64);
            document.put("privateKey", privateKeyBase64);
            document.put("createdAtMs", createdAtMs);
            writeAtomically(path, Json.write(document), true);
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX filesystem; the directory permissions apply.
            }
        }

        static Identity load(Path path) throws Exception {
            if (!Files.isRegularFile(path)) {
                throw new UsageException("hub identity not found: " + path
                        + " (run: tools/grom-hub identity --init)");
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map<?, ?> root)) {
                throw new UsageException("hub identity is not a JSON object");
            }
            String publicKey = root.get("publicKey") instanceof String value ? value.trim() : "";
            String privateKey = root.get("privateKey") instanceof String value ? value.trim() : "";
            if (publicKey.isEmpty() || privateKey.isEmpty()) {
                throw new UsageException("hub identity is incomplete; run identity --init");
            }
            long created = root.get("createdAtMs") instanceof Number number
                    ? number.longValue()
                    : 0L;
            Identity identity = new Identity(publicKey, privateKey, created);

            // Fail here, not on the first poll, if the pair does not match. The
            // JDK cannot derive a public key from an Ed25519 private key, so a
            // sign/verify round trip is the honest check.
            byte[] probe = "grom-hub-identity-check-v1".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(identity.privateKey());
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(identity.publicKey());
            verifier.update(probe);
            if (!verifier.verify(signature)) {
                throw new UsageException("hub identity is corrupt: the key pair does not match");
            }
            return identity;
        }
    }

    static byte[] rawPublicKey(PublicKey key) {
        byte[] spki = key.getEncoded();
        // Ed25519 SPKI is 44 bytes and the raw key is its last 32 bytes.
        if (spki.length == 44) return java.util.Arrays.copyOfRange(spki, 12, 44);
        throw new IllegalStateException("unexpected Ed25519 public key encoding");
    }

    /** Builds a JDK Ed25519 public key from the raw 32-byte form. */
    static PublicKey publicKeyFromRaw(byte[] raw) throws Exception {
        byte[] prefix = {
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        };
        byte[] spki = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, spki, 0, prefix.length);
        System.arraycopy(raw, 0, spki, prefix.length, raw.length);
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(spki));
    }

    // --------------------------------------------------------------- polling

    static final class DeviceState {
        String label = "";
        String nodeId = "";
        long lastSeenMs = 0L;
        Boolean lastOk;
        String lastError;
    }

    static final class HubState {
        private final Map<String, DeviceState> byLabel = new LinkedHashMap<>();

        synchronized DeviceState stateOf(String label) {
            return byLabel.computeIfAbsent(label, key -> new DeviceState());
        }

        synchronized void recordSuccess(String label, String nodeId, long nowMs) {
            DeviceState state = stateOf(label);
            state.nodeId = nodeId;
            state.lastSeenMs = nowMs;
            state.lastOk = true;
            state.lastError = null;
        }

        synchronized void recordFailure(String label, String code) {
            DeviceState state = stateOf(label);
            state.lastOk = false;
            state.lastError = code;
            // lastSeenMs is left alone: "seen 4 minutes ago" stays true.
        }

        synchronized boolean allAlive(long nowMs) {
            if (byLabel.isEmpty()) return false;
            for (DeviceState state : byLabel.values()) {
                if (!STATE_ALIVE.equals(stateName(state, nowMs))) return false;
            }
            return true;
        }

        synchronized Map<String, Object> rosterDocument(long nowMs) {
            List<Object> devices = new ArrayList<>();
            for (DeviceState state : byLabel.values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", state.label);
                row.put("nodeId", state.nodeId);
                row.put("state", stateName(state, nowMs));
                row.put("lastSeenMs", state.lastSeenMs);
                row.put("lastError", state.lastError);
                devices.add(row);
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("version", 1);
            document.put("generatedAtMs", nowMs);
            document.put("devices", devices);
            return document;
        }
    }

    static final String STATE_UNKNOWN = "unknown";
    static final String STATE_ALIVE = "alive";
    static final String STATE_STALE = "stale";
    static final String STATE_UNREACHABLE = "unreachable";

    static String stateName(DeviceState state, long nowMs) {
        if (state.lastOk == null) return STATE_UNKNOWN;
        if (!state.lastOk) return STATE_UNREACHABLE;
        return nowMs - state.lastSeenMs <= ALIVE_AFTER_MS ? STATE_ALIVE : STATE_STALE;
    }

    /** Polls every configured device once and records the measured outcome. */
    static void pollAll(
            HubConfig config,
            Identity identity,
            HttpClient client,
            HubState state,
            Clock clock) {
        for (Device device : config.devices) {
            DeviceState row = state.stateOf(device.label);
            row.label = device.label;
            Probe result = probe(client, device, identity, config.timeoutMs, clock);
            if (result.ok) {
                state.recordSuccess(device.label, result.nodeId, clock.nowMs());
            } else {
                state.recordFailure(device.label, result.error);
            }
        }
    }

    interface Clock {
        long nowMs();
    }

    record Probe(boolean ok, String nodeId, String error) {}

    static Probe probe(
            HttpClient client,
            Device device,
            Identity identity,
            int timeoutMs,
            Clock clock) {
        String timestamp = Long.toString(clock.nowMs() / 1000);
        String nonce = newNonce();
        String canonical = canonical("GET", STATUS_PATH, "", timestamp, nonce);
        String signature;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(identity.privateKey());
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            signature = Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            return new Probe(false, "", "HUB_KEY_UNUSABLE");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(device.url + STATUS_PATH))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("x-grom-key", identity.keyId())
                .header("x-grom-timestamp", timestamp)
                .header("x-grom-nonce", nonce)
                .header("x-grom-signature", signature)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            return new Probe(false, "", "HUB_TIMEOUT");
        } catch (IOException e) {
            return new Probe(false, "", "HUB_UNREACHABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Probe(false, "", "HUB_UNREACHABLE");
        }

        byte[] bytes = response.body();
        if (bytes == null || bytes.length > MAX_BODY_BYTES) {
            return new Probe(false, "", "HUB_BAD_RESPONSE");
        }
        int status = response.statusCode();
        if (status == 401) return new Probe(false, "", "PEER_UNAUTHORIZED");
        if (status == 403) return new Probe(false, "", "PEER_SCOPE_REQUIRED");
        if (status != 200) return new Probe(false, "", "HUB_BAD_RESPONSE");

        Object parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return new Probe(false, "", "HUB_BAD_RESPONSE");
        }
        if (!(parsed instanceof Map<?, ?> document)) {
            return new Probe(false, "", "HUB_BAD_RESPONSE");
        }
        if (!(document.get("fleet") instanceof Map<?, ?> fleet)) {
            return new Probe(false, "", "HUB_BAD_RESPONSE");
        }
        String nodeId = fleet.get("nodeId") instanceof String text ? text.trim() : "";
        if (!nodeId.matches("[0-9a-f]{16}")) return new Probe(false, "", "HUB_BAD_RESPONSE");
        if (device.pinnedNodeId != null && !device.pinnedNodeId.equals(nodeId)) {
            // A device that was reset is a different machine; never silently
            // accept it under the old label (D74).
            return new Probe(false, nodeId, "IDENTITY_CHANGED");
        }
        return new Probe(true, nodeId, null);
    }

    static void writeRoster(HubConfig config, HubState state, long nowMs) throws Exception {
        Path parent = config.rosterFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        writeAtomically(config.rosterFile, Json.write(state.rosterDocument(nowMs)), false);
    }

    // ------------------------------------------------------------------ HTTP

    static void respondRoster(HttpExchange exchange, HubState state) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "{\"ok\":false}");
            return;
        }
        send(exchange, 200, Json.write(state.rosterDocument(System.currentTimeMillis())));
    }

    static void respondHealth(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "{\"ok\":false}");
            return;
        }
        send(exchange, 200, "{\"ok\":true,\"service\":\"grom-hub\",\"api\":1}");
    }

    static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------ signatures

    /**
     * Exactly `RequestSigner.canonicalString` in the engine (D42, S5-R017):
     * six lines, the body contributing only its lowercase SHA-256 hex.
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
            out.append(Character.forDigit(value >>> 4, 16))
                    .append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }

    /** The key id is the first 8 bytes of SHA-256 over the raw public key. */
    static String keyIdFor(String publicKeyBase64) {
        byte[] raw = Base64.getDecoder().decode(publicKeyBase64.trim());
        if (raw.length != 32) throw new IllegalArgumentException("public key must be 32 bytes");
        return hex(sha256(raw), 8);
    }

    static String fingerprint(String nodeId) {
        if (!nodeId.matches("[0-9a-f]{16}")) return nodeId;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < nodeId.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(nodeId.charAt(i));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ table

    static boolean isTty(boolean plain) {
        return !plain && System.getenv("NO_COLOR") == null && System.console() != null;
    }

    static String renderTable(HubConfig config, HubState state, long nowMs, boolean tty) {
        List<String[]> rows = new ArrayList<>();
        List<String> states = new ArrayList<>();
        for (Device device : config.devices) {
            DeviceState row = state.stateOf(device.label);
            String name = stateName(row, nowMs);
            rows.add(new String[] {
                    "\u25cf",
                    device.label,
                    name,
                    row.nodeId.isEmpty() ? "-" : fingerprint(row.nodeId),
                    row.lastSeenMs == 0 ? "-" : ago(nowMs - row.lastSeenMs),
                    row.lastError == null ? "" : row.lastError
            });
            states.add(name);
        }
        String[] headers = {"", "DEVICE", "STATE", "FINGERPRINT", "LAST SEEN", "NOTE"};
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) widths[i] = headers[i].length();
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) widths[i] = Math.max(widths[i], row[i].length());
        }
        StringBuilder out = new StringBuilder();
        out.append("Grom hub — ").append(rows.size())
                .append(rows.size() == 1 ? " device" : " devices")
                .append(" · ").append(config.source).append('\n').append('\n');
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
                if (i == 0 || i == 2) {
                    out.append(colorize(cell, lampColor(states.get(r)), tty));
                } else {
                    out.append(cell);
                }
                if (i < row.length - 1) out.append("  ");
            }
            out.append('\n');
        }
        long alive = states.stream().filter(STATE_ALIVE::equals).count();
        out.append('\n').append(alive).append(" alive · ")
                .append(rows.size() - alive).append(" not alive").append('\n');
        return out.toString().stripTrailing();
    }

    static String lampColor(String state) {
        return switch (state) {
            case STATE_ALIVE -> GREEN;
            case STATE_UNKNOWN, STATE_STALE -> GREY;
            default -> RED;
        };
    }

    static String pad(String text, int width) {
        if (text.length() >= width) return text;
        return text + " ".repeat(width - text.length());
    }

    static String colorize(String text, String color, boolean tty) {
        if (!tty) return text;
        return color + text + RESET;
    }

    static String ago(long millis) {
        if (millis < 0) millis = 0;
        if (millis < 1000) return millis + " ms ago";
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + " s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        return hours + " h ago";
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

    static Path resolvePath(Path baseDir, String raw) {
        Path candidate = Paths.get(expandHome(raw.trim()));
        if (candidate.isAbsolute() || baseDir == null) return candidate;
        return baseDir.resolve(candidate).normalize();
    }

    static void writeAtomically(Path path, String text, boolean ownerOnly) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = parent == null
                ? Paths.get(path.getFileName() + ".tmp")
                : parent.resolve(path.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        if (ownerOnly) {
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX filesystem.
            }
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
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
                for (Map.Entry<?, ?> entry : map.entrySet()) {
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
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') index++;
                    else break;
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
                index++;
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
                    if (peek() == ',') {
                        index++;
                        continue;
                    }
                    if (peek() == '}') {
                        index++;
                        return map;
                    }
                    throw error("expected ',' or '}'");
                }
            }

            List<Object> array() {
                List<Object> list = new ArrayList<>();
                index++;
                skipWhitespace();
                if (peek() == ']') {
                    index++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    skipWhitespace();
                    if (peek() == ',') {
                        index++;
                        continue;
                    }
                    if (peek() == ']') {
                        index++;
                        return list;
                    }
                    throw error("expected ',' or ']'");
                }
            }

            String string() {
                index++;
                StringBuilder out = new StringBuilder();
                while (true) {
                    if (index >= text.length()) throw error("unterminated string");
                    char c = text.charAt(index++);
                    if (c == '"') return out.toString();
                    if (c == '\\') {
                        if (index >= text.length()) throw error("unterminated escape");
                        char escape = text.charAt(index++);
                        switch (escape) {
                            case '"' -> out.append('"');
                            case '\\' -> out.append('\\');
                            case '/' -> out.append('/');
                            case 'b' -> out.append('\b');
                            case 'f' -> out.append('\f');
                            case 'n' -> out.append('\n');
                            case 'r' -> out.append('\r');
                            case 't' -> out.append('\t');
                            case 'u' -> {
                                if (index + 4 > text.length()) throw error("bad unicode escape");
                                out.append((char) Integer.parseInt(
                                        text.substring(index, index + 4), 16));
                                index += 4;
                            }
                            default -> throw error("bad escape");
                        }
                    } else {
                        out.append(c);
                    }
                }
            }

            Object number() {
                int start = index;
                while (index < text.length()
                        && "-+0123456789.eE".indexOf(text.charAt(index)) >= 0) {
                    index++;
                }
                String slice = text.substring(start, index);
                if (slice.isEmpty()) throw error("invalid value");
                if (slice.contains(".") || slice.contains("e") || slice.contains("E")) {
                    return Double.parseDouble(slice);
                }
                return Long.parseLong(slice);
            }
        }
    }

    // -------------------------------------------------------------- self-test

    /**
     * Protocol tests against a real loopback HTTP server that verifies the
     * hub's signature exactly as the engine does. Run by `tools/test-hub`.
     */
    static final class SelfTest {

        private static int passed;
        private static int failed;

        interface Body {
            void run() throws Exception;
        }

        static int run() {
            System.out.println("== grom-hub self-test ==");
            testStates();
            testLoopbackGuard();
            testConfigRefusals();
            body("identity file", SelfTest::testIdentityFile);
            body("signing and roster", SelfTest::testSigningAndRoster);
            body("pinned identity", SelfTest::testPinnedMismatch);
            body("unauthorized", SelfTest::testUnauthorized);
            System.out.println();
            if (failed == 0) {
                System.out.println("test-hub: OK (" + passed + "/" + (passed + failed) + ")");
                return 0;
            }
            System.out.println("test-hub: FAILED (" + passed + "/" + (passed + failed) + ")");
            return 1;
        }

        static void check(String name, boolean condition) {
            if (condition) {
                passed++;
            } else {
                failed++;
                System.out.println("FAIL: " + name);
            }
        }

        static void body(String name, Body body) {
            try {
                body.run();
            } catch (Exception e) {
                failed++;
                System.out.println("FAIL: " + name + ": " + describe(e));
            }
        }

        static void testStates() {
            DeviceState state = new DeviceState();
            state.label = "pixel";
            check("a device that was never polled is unknown",
                    STATE_UNKNOWN.equals(stateName(state, 1_000L)));

            state.nodeId = "0123456789abcdef";
            state.lastSeenMs = 10_000L;
            state.lastOk = true;
            check("a fresh success is alive",
                    STATE_ALIVE.equals(stateName(state, 10_000L + ALIVE_AFTER_MS)));
            check("an old success is stale, not alive",
                    STATE_STALE.equals(stateName(state, 10_000L + ALIVE_AFTER_MS + 1)));

            state.lastOk = false;
            state.lastError = "PEER_UNREACHABLE";
            check("a failure is unreachable even after an earlier success",
                    STATE_UNREACHABLE.equals(stateName(state, 10_001L)));
        }

        static void testLoopbackGuard() {
            check("127.0.0.1 is loopback", listenIsLoopback("127.0.0.1"));
            check("::1 is loopback", listenIsLoopback("::1"));
            check("localhost is loopback", listenIsLoopback("localhost"));
            check("0.0.0.0 is refused", !listenIsLoopback("0.0.0.0"));
            check("a LAN address is refused", !listenIsLoopback("192.168.1.5"));
            check("an empty host is refused", !listenIsLoopback(""));
        }

        static void testConfigRefusals() {
            check("a url with credentials is refused",
                    refusesUrl("http://u:p@127.0.0.1:41001"));
            check("a url with a path is refused", refusesUrl("http://127.0.0.1:41001/v1"));
            check("a url with a query is refused", refusesUrl("http://127.0.0.1:41001?x=1"));
            check("a non-http scheme is refused", refusesUrl("ftp://127.0.0.1:41001"));
            check("a loopback origin is canonicalized",
                    "http://127.0.0.1:41001".equals(
                            validateBaseUrl("t", "http://127.0.0.1:41001/")));
        }

        static boolean refusesUrl(String url) {
            try {
                validateBaseUrl("t", url);
                return false;
            } catch (UsageException e) {
                return true;
            }
        }

        static void testIdentityFile() throws Exception {
            Path dir = Files.createTempDirectory("grom-hub-test");
            Path path = dir.resolve("hub-identity.json");
            Identity created = Identity.generate();
            created.save(path);

            Identity loaded = Identity.load(path);
            check("a saved identity reloads with the same key id",
                    created.keyId().equals(loaded.keyId()));
            check("the key id is 16 hex characters",
                    loaded.keyId().matches("[0-9a-f]{16}"));

            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                check("the identity file is owner-only",
                        permissions.equals(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                check("the identity file is owner-only (skipped, no POSIX)", true);
            }

            Path corrupt = dir.resolve("corrupt.json");
            Files.writeString(corrupt, "{\"publicKey\":\"AAAA\",\"privateKey\":\"AAAA\"}");
            check("a corrupt identity is refused instead of regenerated",
                    refusesLoad(corrupt));
        }

        static boolean refusesLoad(Path path) {
            try {
                Identity.load(path);
                return false;
            } catch (Exception e) {
                return true;
            }
        }

        static void testSigningAndRoster() throws Exception {
            Identity identity = Identity.generate();
            FakeDevice device = FakeDevice.start(Mode.OK);
            try {
                device.expectHub(identity);
                HubConfig config = new HubConfig(
                        Paths.get("self-test.json"), 3_000, 30,
                        "127.0.0.1", 47100, Paths.get("roster.json"),
                        List.of(new Device("pixel-7", device.url(), null)));
                HubState state = new HubState();
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(3_000))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
                long now = System.currentTimeMillis();
                pollAll(config, identity, client, state, () -> now);

                check("a correctly signed request is accepted by a strict verifier",
                        device.acceptedSignatures == 1);
                check("the key id in the request is the hub's key id",
                        identity.keyId().equals(device.lastKeyId));
                check("a successful poll marks the device alive",
                        STATE_ALIVE.equals(stateName(state.stateOf("pixel-7"), now)));
                check("the measured node id is the device's",
                        device.nodeId.equals(state.stateOf("pixel-7").nodeId));

                Map<String, Object> roster = state.rosterDocument(now);
                String json = Json.write(roster);
                check("the roster has one device row",
                        ((List<?>) roster.get("devices")).size() == 1);
                check("the roster carries no url", !json.contains("\"url\""));
                check("the roster carries no address", !json.contains("\"address\""));
                check("the roster carries no port", !json.contains("\"port\""));
                check("the roster carries no key", !json.contains("\"publicKey\""));
                check("the roster carries only the allow-listed fields",
                        ((Map<?, ?>) ((List<?>) roster.get("devices")).get(0))
                                .keySet().equals(new HashSet<>(ROSTER_FIELDS)));
                check("all alive is reported for a healthy fleet", state.allAlive(now));
            } finally {
                device.stop();
            }
        }

        static void testPinnedMismatch() throws Exception {
            Identity identity = Identity.generate();
            FakeDevice device = FakeDevice.start(Mode.MISMATCH);
            try {
                device.expectHub(identity);
                HubConfig config = new HubConfig(
                        Paths.get("self-test.json"), 3_000, 30,
                        "127.0.0.1", 47100, Paths.get("roster.json"),
                        List.of(new Device("pixel-7", device.url(), "1111111111111111")));
                HubState state = new HubState();
                HttpClient client = HttpClient.newHttpClient();
                long now = System.currentTimeMillis();
                pollAll(config, identity, client, state, () -> now);

                DeviceState row = state.stateOf("pixel-7");
                check("a pinned id that changed is reported, not accepted",
                        "IDENTITY_CHANGED".equals(row.lastError));
                check("a changed identity is not alive",
                        !STATE_ALIVE.equals(stateName(row, now)));
                check("a changed identity is not considered healthy", !state.allAlive(now));
            } finally {
                device.stop();
            }
        }

        static void testUnauthorized() throws Exception {
            Identity identity = Identity.generate();
            FakeDevice device = FakeDevice.start(Mode.UNAUTHORIZED);
            try {
                device.expectHub(identity);
                HubConfig config = new HubConfig(
                        Paths.get("self-test.json"), 3_000, 30,
                        "127.0.0.1", 47100, Paths.get("roster.json"),
                        List.of(new Device("pixel-7", device.url(), null)));
                HubState state = new HubState();
                HttpClient client = HttpClient.newHttpClient();
                long now = System.currentTimeMillis();
                pollAll(config, identity, client, state, () -> now);

                DeviceState row = state.stateOf("pixel-7");
                check("a refused signature is reported as unauthorized",
                        "PEER_UNAUTHORIZED".equals(row.lastError));
                check("an unauthorized device is unreachable",
                        STATE_UNREACHABLE.equals(stateName(row, now)));
            } finally {
                device.stop();
            }
        }
    }

    // ------------------------------------------------------------- fake device

    enum Mode { OK, MISMATCH, UNAUTHORIZED }

    /**
     * A stand-in phone that verifies the hub signature the way the engine does:
     * same canonical string, the hub key id, a timestamp window and a nonce it
     * has not seen before.
     */
    static final class FakeDevice {
        private HttpServer server;
        String nodeId = "";
        int acceptedSignatures;
        String lastKeyId = "";
        private Identity hubIdentity;
        private Mode mode;
        private final Set<String> nonces = new HashSet<>();

        static FakeDevice start(Mode mode) throws Exception {
            FakeDevice device = new FakeDevice();
            device.mode = mode;
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            device.nodeId = hex(sha256(raw), 8);
            device.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            device.server.createContext(STATUS_PATH, device::handle);
            device.server.start();
            return device;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        void expectHub(Identity identity) {
            this.hubIdentity = identity;
        }

        void handle(HttpExchange exchange) throws IOException {
            if (mode == Mode.UNAUTHORIZED) {
                send(exchange, 401, "{\"ok\":false,\"error\":{\"code\":\"UNAUTHORIZED\"}}");
                return;
            }
            String keyId = exchange.getRequestHeaders().getFirst("x-grom-key");
            String timestamp = exchange.getRequestHeaders().getFirst("x-grom-timestamp");
            String nonce = exchange.getRequestHeaders().getFirst("x-grom-nonce");
            String signatureText = exchange.getRequestHeaders().getFirst("x-grom-signature");
            lastKeyId = keyId == null ? "" : keyId;

            if (hubIdentity == null || keyId == null || timestamp == null
                    || nonce == null || signatureText == null) {
                send(exchange, 401, "{\"ok\":false,\"error\":{\"code\":\"UNAUTHORIZED\"}}");
                return;
            }
            if (!hubIdentity.keyId().equals(keyId)) {
                send(exchange, 401, "{\"ok\":false,\"error\":{\"code\":\"UNAUTHORIZED\"}}");
                return;
            }
            long seconds;
            try {
                seconds = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                send(exchange, 401, "{\"ok\":false,\"error\":{\"code\":\"SIGNATURE_STALE\"}}");
                return;
            }
            long nowSeconds = System.currentTimeMillis() / 1000;
            if (Math.abs(nowSeconds - seconds) > MAX_SKEW_SECONDS) {
                send(exchange, 401, "{\"ok\":false,\"error\":{\"code\":\"SIGNATURE_STALE\"}}");
                return;
            }
            synchronized (nonces) {
                if (!nonces.add(nonce)) {
                    send(exchange, 401, "{\"ok\":false,\"error\":{\"code\":\"SIGNATURE_REPLAYED\"}}");
                    return;
                }
            }
            String canonical = canonical("GET", STATUS_PATH, "", timestamp, nonce);
            boolean valid;
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(hubIdentity.publicKey());
                verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
                valid = verifier.verify(Base64.getDecoder().decode(signatureText));
            } catch (Exception e) {
                valid = false;
            }
            if (!valid) {
                send(exchange, 401, "{\"ok\":false,\"error\":{\"code\":\"SIGNATURE_INVALID\"}}");
                return;
            }
            acceptedSignatures++;
            String reported = mode == Mode.MISMATCH ? "0000000000000000" : nodeId;
            send(exchange, 200,
                    "{\"ok\":true,\"fleet\":{\"nodeId\":\"" + reported + "\",\"label\":\"Fake\"}}");
        }
    }
}
