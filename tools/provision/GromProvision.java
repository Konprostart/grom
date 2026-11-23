// SPDX-License-Identifier: Apache-2.0
//
// grom-provision — prepare several phones for Grom from one computer.
//
// This is an operator tool, not a part of the app (D79). It drives `adb`, so it
// runs on the machine that has the USB cables, never on the phone, and it adds
// nothing to the APK or to the Android tree:
//
//   * it reads what the phone already reports (model, SDK, ABI, installed
//     version, battery exemption) instead of guessing;
//   * it installs the APK you point it at, and then *reads the version back
//     from the device* so a failed install cannot be reported as success;
//   * it asks the system to exempt the app from battery optimisation and then
//     re-reads the whitelist, because some vendors silently ignore the request;
//   * it gives each phone its own SSH forward port (41001-41099, D78) and
//     records it, so `emit` can turn the result into a hub.json for
//     tools/grom-hub;
//   * it never destroys anything on the device: no data wipe, no package
//     removal, no reflash, no process termination (pinned by tools/test-policy).
//
// Both role flavours share one applicationId (S1-R007), so installing the wrong
// flavour silently replaces the other one. That is why `--flavour` is verified
// against the versionName the device reports *after* the install.
//
// The whole program is one file so it needs nothing but a JDK 17 and `adb`:
//
//     tools/grom-provision list                     # what is connected
//     tools/grom-provision plan --apk grom.apk      # what apply would change
//     tools/grom-provision apply --apk grom.apk --flavour server29
//     tools/grom-provision emit --hub hub.json
//
// Run the built-in tests with `tools/test-provision` (they need no phone: a
// fake adb answers from a scenario directory).

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GromProvision {

    static final String VERSION = "1.0.0";

    /** The one package this tool knows how to install (both flavours, S1-R007). */
    static final String PKG = "app.grom.server";

    /** Forward ports used for the reverse tunnels (D78). */
    static final int FIRST_PORT = 41001;
    static final int LAST_PORT = 41099;

    /** Hub roster address documented in docs/HUB.md. */
    static final String HUB_LISTEN = "127.0.0.1:47100";

    static final List<String> FLAVOURS = List.of("server29", "peripheral26");

    static final int PROBE_TIMEOUT_MS = 20_000;
    static final int INSTALL_TIMEOUT_MS = 300_000;

    /** Recorded in the device's versionName by the Gradle flavour (app/build.gradle.kts). */
    static final String FLAVOUR_SUFFIX = "-";

    static final Pattern GETPROP_LINE = Pattern.compile("^\\[([^\\]]+)]: \\[(.*)]$");
    static final Pattern VERSION_CODE = Pattern.compile("versionCode=(\\d+)");
    static final Pattern VERSION_NAME = Pattern.compile("versionName=([^\\s]+)");
    static final Pattern INSTALL_FAILURE = Pattern.compile("Failure \\[([A-Z_]+)]");

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Cli cli;
        try {
            cli = Cli.parse(args);
        } catch (ToolException e) {
            System.err.println("grom-provision: " + e.getMessage());
            System.err.println("try `grom-provision --help`");
            return 2;
        }
        if (cli.selfTest) {
            try {
                return SelfTest.run();
            } catch (Exception e) {
                System.err.println("grom-provision self-test: " + e);
                return 1;
            }
        }
        return execute(cli);
    }

    /** Runs an already-parsed command line; shared with the in-process self-test. */
    static int execute(Cli cli) {
        if (cli.help) {
            printUsage();
            return 0;
        }
        if (cli.version) {
            System.out.println("grom-provision " + VERSION);
            return 0;
        }
        try {
            return dispatch(cli);
        } catch (ToolException e) {
            System.err.println("grom-provision: " + e.getMessage());
            return e.code;
        }
    }

    static int dispatch(Cli cli) throws ToolException {
        return switch (cli.command) {
            case "list" -> cmdList(cli);
            case "plan" -> cmdPlan(cli);
            case "apply" -> cmdApply(cli);
            case "emit" -> cmdEmit(cli);
            case "doctor" -> cmdDoctor(cli);
            default -> throw new ToolException("unknown command: " + cli.command, 2);
        };
    }

    // ------------------------------------------------------------------ errors

    /** A failure that ends the run with a stable exit code and one sentence. */
    static final class ToolException extends Exception {
        final int code;

        ToolException(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    /** One adb invocation: exit status plus everything it printed (stderr merged). */
    record Result(int code, String out) {}

    // -------------------------------------------------------------------- adb

    static final class Adb {
        final String exe;

        Adb(String exe) {
            this.exe = exe;
        }

        Result exec(List<String> args, int timeoutMs) throws ToolException {
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                throw new ToolException("cannot run " + exe + ": " + e.getMessage(), 2);
            }

            // Close stdin so an adb that decides to ask something loses the pipe
            // immediately instead of blocking the run.
            try {
                OutputStream in = p.getOutputStream();
                in.close();
            } catch (IOException ignored) {
                // Nothing to do: the process is already gone or never wanted it.
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (var stream = p.getInputStream()) {
                    stream.transferTo(buf);
                } catch (IOException ignored) {
                    // The output we kept is still what the tool reports.
                }
            }, "grom-provision-adb");
            reader.setDaemon(true);
            reader.start();

            try {
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    reader.join(2_000);
                    throw new ToolException(
                            "adb timed out after " + timeoutMs + " ms: " + String.join(" ", args), 2);
                }
                reader.join(5_000);
                return new Result(p.exitValue(), buf.toString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new ToolException("interrupted while waiting for adb", 2);
            }
        }
    }

    /**
     * Finds the adb binary: an explicit --adb wins, then $ADB, then PATH, then
     * the Android SDK platform-tools. Failing here is a configuration error, not
     * a device error, so it exits 2 with instructions.
     */
    static String resolveAdb(Cli cli) throws ToolException {
        if (cli.adbPath != null) {
            Path p = Paths.get(cli.adbPath);
            if (!Files.isRegularFile(p)) {
                throw new ToolException("--adb " + p + " does not exist", 2);
            }
            return p.toAbsolutePath().normalize().toString();
        }
        String env = System.getenv("ADB");
        if (env != null && !env.isBlank() && Files.isRegularFile(Paths.get(env))) {
            return Paths.get(env).toAbsolutePath().normalize().toString();
        }
        String onPath = which("adb");
        if (onPath != null) {
            return onPath;
        }
        for (String root : new String[] {
                System.getenv("ANDROID_SDK_ROOT"),
                System.getenv("ANDROID_HOME"),
                System.getProperty("user.home") + "/Android/Sdk"}) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path candidate = Paths.get(root, "platform-tools", "adb");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        throw new ToolException(
                "no adb found. Install the Android platform-tools, put adb on PATH, or pass --adb PATH", 2);
    }

    static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Paths.get(dir, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- devices

    /** One row of `adb devices -l`. */
    static final class Device {
        final String serial;
        final String adbState;
        final String product;
        final String modelHint;

        Device(String serial, String adbState, String product, String modelHint) {
            this.serial = serial;
            this.adbState = adbState;
            this.product = product;
            this.modelHint = modelHint;
        }
    }

    /** What the phone reports about itself. */
    record Facts(String manufacturer, String model, String release, int sdk, String abiList) {
        boolean arm64() {
            return abiList.toLowerCase(Locale.ROOT).contains("arm64-v8a");
        }

        boolean abiKnown() {
            return !abiList.isBlank() && !abiList.equals("unknown");
        }
    }

    /** What the package manager says about the Grom package. */
    record Installed(boolean present, int versionCode, String versionName) {
        static final Installed ABSENT = new Installed(false, 0, "");
    }

    /** Everything measured about one phone in this run. */
    static final class Probe {
        Device device;
        Facts facts;
        Installed installed = Installed.ABSENT;
        boolean batteryExempt;
        String label = "";
        boolean labelStored;
        int forwardPort;
        String status = "unknown";
        String result = "";
        String failure = "";
        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String serial() {
            return device.serial;
        }
    }

    /**
     * adb states that describe a phone. Anything else is noise from the adb
     * server itself ("adb server version (41) doesn't match this client…"),
     * which would otherwise be parsed as a device whose state is a sentence.
     */
    static final Set<String> ADB_DEVICE_STATES = Set.of(
            "device", "offline", "unauthorized", "recovery", "sideload",
            "bootloader", "rescue", "host", "no");

    static List<Device> parseDevices(String out) {
        List<Device> devices = new ArrayList<>();
        for (String raw : out.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("List of devices") || line.startsWith("*")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2 || !ADB_DEVICE_STATES.contains(parts[1])) {
                continue;
            }
            String product = "";
            String model = "";
            for (int i = 2; i < parts.length; i++) {
                int colon = parts[i].indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = parts[i].substring(0, colon);
                String value = parts[i].substring(colon + 1);
                if (key.equals("product")) {
                    product = value;
                } else if (key.equals("model")) {
                    model = value;
                }
            }
            devices.add(new Device(parts[0], parts[1], product, model));
        }
        return devices;
    }

    static List<Device> devicesOf(Adb adb, Cli cli) throws ToolException {
        Result r = adb.exec(List.of("devices", "-l"), PROBE_TIMEOUT_MS);
        if (r.code() != 0) {
            // "no phones detected" would be a lie when adb itself is broken.
            throw new ToolException("adb devices failed: " + firstLine(r.out()), 2);
        }
        List<Device> all = parseDevices(r.out());
        if (cli.serials.isEmpty()) {
            return all;
        }
        List<Device> selected = new ArrayList<>();
        for (Device d : all) {
            if (cli.serials.contains(d.serial)) {
                selected.add(d);
            }
        }
        for (String wanted : cli.serials) {
            boolean found = all.stream().anyMatch(d -> d.serial.equals(wanted));
            if (!found) {
                // stderr: stdout may be machine-readable JSON.
                System.err.println("note: " + wanted + " is not connected");
            }
        }
        return selected;
    }

    /** The first non-blank line of a command's output, for an error message. */
    static String firstLine(String out) {
        for (String raw : out.split("\n")) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                return line.length() > 200 ? line.substring(0, 200) : line;
            }
        }
        return "no output";
    }

    static Map<String, String> parseGetprop(String out) {
        Map<String, String> props = new LinkedHashMap<>();
        for (String raw : out.split("\n")) {
            Matcher m = GETPROP_LINE.matcher(raw.strip());
            if (m.matches()) {
                props.put(m.group(1), m.group(2));
            }
        }
        return props;
    }

    static Facts readFacts(Adb adb, String serial) throws ToolException {
        Result r = adb.exec(List.of("-s", serial, "shell", "getprop"), PROBE_TIMEOUT_MS);
        Map<String, String> props = parseGetprop(r.out());
        String abi = props.getOrDefault("ro.product.cpu.abilist", "");
        if (abi.isBlank()) {
            abi = props.getOrDefault("ro.product.cpu.abi", "");
        }
        int sdk = 0;
        try {
            sdk = Integer.parseInt(props.getOrDefault("ro.build.version.sdk", "0").strip());
        } catch (NumberFormatException ignored) {
            sdk = 0;
        }
        return new Facts(
                props.getOrDefault("ro.product.manufacturer", ""),
                props.getOrDefault("ro.product.model", ""),
                props.getOrDefault("ro.build.version.release", ""),
                sdk,
                abi);
    }

    static Installed parseInstalled(String out) {
        if (out.contains("Unable to find package")) {
            return Installed.ABSENT;
        }
        Matcher code = VERSION_CODE.matcher(out);
        Matcher name = VERSION_NAME.matcher(out);
        if (!code.find() || !name.find()) {
            return Installed.ABSENT;
        }
        try {
            return new Installed(true, Integer.parseInt(code.group(1)), name.group(1));
        } catch (NumberFormatException e) {
            return Installed.ABSENT;
        }
    }

    static Installed readInstalled(Adb adb, String serial) throws ToolException {
        Result r = adb.exec(List.of("-s", serial, "shell", "dumpsys", "package", PKG), PROBE_TIMEOUT_MS);
        return parseInstalled(r.out());
    }

    /**
     * The same question, but with "no answer" as its own answer.
     *
     * [parseBatteryExempt] returns `false` for an empty reply because the
     * provisioning path only needs "should I ask for the exemption". The doctor
     * cannot afford that: on this screen `false` means "measured, and it is not
     * exempt", so an unreadable reply must stay `null` and be printed as
     * "unknown" (D80, D81).
     */
    static Boolean parseBatteryExemptOrNull(String out) {
        if (out == null) return null;
        String trimmed = out.strip();
        if (trimmed.isEmpty()) return null;
        if (trimmed.contains("Unable to find") || trimmed.contains("Exception")) return null;
        if (trimmed.equals("null")) return null;
        return parseBatteryExempt(out);
    }

    static boolean parseBatteryExempt(String out) {
        for (String raw : out.split("\n")) {
            String line = raw.strip();
            if (line.contains("," + PKG + ",")) {
                return true;
            }
        }
        return false;
    }

    static boolean readBatteryExempt(Adb adb, String serial) throws ToolException {
        Result r = adb.exec(List.of("-s", serial, "shell", "dumpsys", "deviceidle", "whitelist"), PROBE_TIMEOUT_MS);
        return parseBatteryExempt(r.out());
    }

    /** `Pixel 7` -> `pixel-7`; deterministic, so a re-run keeps the same name. */
    static String labelFromModel(String model) {
        if (model == null || model.isBlank()) {
            return "phone";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : model.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        String out = sb.toString();
        if (out.isEmpty()) {
            return "phone";
        }
        if (out.length() > 40) {
            out = out.substring(0, 40);
            while (out.endsWith("-")) {
                out = out.substring(0, out.length() - 1);
            }
        }
        return out;
    }

    /**
     * The server role runs the packaged Node agent, which is arm64-only. The
     * peripheral role never starts it, so a 32-bit phone is a fine peripheral
     * and a broken server — the check follows the role, not the device.
     */
    static boolean requiresArm64(Cli cli) {
        return !"peripheral26".equals(cli.flavour);
    }

    static String statusOf(Probe p, Cli cli) {
        if (!"device".equals(p.device.adbState)) {
            return p.device.adbState;
        }
        if (p.facts == null) {
            return "unreadable";
        }
        if (p.facts.sdk() > 0 && p.facts.sdk() < 26) {
            return "sdk-too-low";
        }
        if (requiresArm64(cli) && p.facts.abiKnown() && !p.facts.arm64()) {
            return "no-arm64";
        }
        if (!p.installed.present()) {
            return "not-installed";
        }
        return "ready";
    }

    /**
     * Measures every selected phone. Read-only: the only commands issued are
     * `devices`, `getprop`, `dumpsys package` and `dumpsys deviceidle whitelist`.
     */
    static List<Probe> probeAll(Adb adb, List<Device> devices, State state, Cli cli) throws ToolException {
        List<Probe> probes = new ArrayList<>();
        for (Device d : devices) {
            Probe p = new Probe();
            p.device = d;
            StoredDevice stored = state.find(d.serial);
            if (stored != null) {
                p.label = stored.label == null ? "" : stored.label;
                p.labelStored = !p.label.isBlank();
                p.forwardPort = stored.forwardPort;
            }
            if ("device".equals(d.adbState)) {
                p.facts = readFacts(adb, d.serial);
                p.installed = readInstalled(adb, d.serial);
                p.batteryExempt = readBatteryExempt(adb, d.serial);
            }
            p.status = statusOf(p, cli);
            probes.add(p);
        }
        assignLabels(probes);
        return probes;
    }

    /**
     * Stored labels are kept exactly as they are; derived ones avoid every label
     * already in use, in serial order, so the same fleet always produces the
     * same names.
     */
    static void assignLabels(List<Probe> probes) {
        Set<String> used = new HashSet<>();
        for (Probe p : probes) {
            if (p.labelStored) {
                used.add(p.label);
            }
        }
        List<Probe> derived = new ArrayList<>();
        for (Probe p : probes) {
            if (!p.labelStored) {
                derived.add(p);
            }
        }
        derived.sort(Comparator.comparing(Probe::serial));
        for (Probe p : derived) {
            String base = labelFromModel(p.facts == null ? p.device.modelHint : p.facts.model());
            String candidate = base;
            int n = 2;
            while (used.contains(candidate)) {
                candidate = base + "-" + n;
                n++;
            }
            used.add(candidate);
            p.label = candidate;
        }
    }

    // ------------------------------------------------------------------- list

    static int cmdList(Cli cli) throws ToolException {
        Adb adb = new Adb(resolveAdb(cli));
        List<Device> devices = devicesOf(adb, cli);
        State state = State.load(Paths.get(cli.stateFile));
        List<Probe> probes = probeAll(adb, devices, state, cli);

        if (cli.json) {
            System.out.println(renderProbesJson(adb.exe, cli.stateFile, probes));
            return 0;
        }
        if (probes.isEmpty()) {
            System.out.println("No phones detected.");
            System.out.println("Enable USB debugging, plug the phone in, and accept the prompt on the phone.");
            return 0;
        }
        printProbeTable(probes);
        return 0;
    }

    // ------------------------------------------------------------------- plan

    static int cmdPlan(Cli cli) throws ToolException {
        Path apk = apkPath(cli);
        String sha = apk == null ? null : sha256(apk);
        Adb adb = new Adb(resolveAdb(cli));
        List<Device> devices = devicesOf(adb, cli);
        State state = State.load(Paths.get(cli.stateFile));
        List<Probe> probes = probeAll(adb, devices, state, cli);

        if (probes.isEmpty()) {
            System.out.println("No phones detected; nothing to plan.");
            return 0;
        }

        List<String> jsonRows = new ArrayList<>();
        for (Probe p : probes) {
            StoredDevice stored = state.find(p.serial());
            List<String> steps = new ArrayList<>();
            String blocker = blockerOf(p, cli);
            if (blocker != null) {
                steps.add("blocked: " + blocker);
            } else {
                boolean needsInstall = !p.installed.present()
                        || cli.force
                        || stored == null
                        || sha == null
                        || !sha.equals(stored.apkSha256);
                if (needsInstall) {
                    steps.add(p.installed.present()
                            ? "reinstall " + p.installed.versionName()
                            : "install " + (apk == null ? "APK" : apk.getFileName()));
                } else {
                    steps.add("keep " + p.installed.versionName());
                }
                if (!p.batteryExempt && !cli.noBattery) {
                    steps.add("exempt from battery optimisation");
                }
                int port = p.forwardPort > 0 ? p.forwardPort : state.peekPort(cli.firstPort, p.serial());
                steps.add("forward port " + port);
            }
            jsonRows.add("{\"serial\":" + q(p.serial()) + ",\"label\":" + q(p.label)
                    + ",\"status\":" + q(p.status) + ",\"steps\":["
                    + String.join(",", steps.stream().map(GromProvision::q).toList()) + "]}");
            if (!cli.json) {
                System.out.println(p.serial() + "  " + p.label + "  [" + p.status + "]");
                for (String step : steps) {
                    System.out.println("    " + (blocker != null ? "x " : "+ ") + step);
                }
            }
        }
        if (cli.json) {
            System.out.println("{\"version\":1,\"command\":\"plan\",\"devices\":[" + String.join(",", jsonRows) + "]}");
        }
        return 0;
    }

    // ------------------------------------------------------------------ apply

    static int cmdApply(Cli cli) throws ToolException {
        Path apk = apkPath(cli);
        if (apk == null) {
            throw new ToolException("apply needs --apk PATH", 2);
        }
        if (cli.flavour != null && !FLAVOURS.contains(cli.flavour)) {
            throw new ToolException(
                    "unknown --flavour " + cli.flavour + " (expected " + String.join(" or ", FLAVOURS) + ")", 2);
        }
        String sha = sha256(apk);
        String apkAbsolute = apk.toAbsolutePath().normalize().toString();

        Adb adb = new Adb(resolveAdb(cli));
        List<Device> devices = devicesOf(adb, cli);
        State state = State.load(Paths.get(cli.stateFile));
        List<Probe> probes = probeAll(adb, devices, state, cli);

        if (probes.isEmpty()) {
            System.err.println("no phones detected; connect one and accept the USB debugging prompt");
            return 0;
        }

        int attention = 0;
        for (Probe p : probes) {
            String blocker = blockerOf(p, cli);
            if (blocker != null) {
                // "skipped" is for a phone that is not ready for commands yet;
                // "failed" is for one that answered but cannot run Grom.
                p.result = "device".equals(p.device.adbState) ? "failed" : "skipped";
                p.failure = blocker;
                p.warnings.add(blocker);
                attention++;
                continue;
            }

            StoredDevice stored = state.ensure(p.serial());
            boolean needsInstall = !p.installed.present()
                    || cli.force
                    || stored.apkSha256 == null
                    || !stored.apkSha256.equals(sha);

            if (needsInstall) {
                Result r = adb.exec(
                        List.of("-s", p.serial(), "install", "-r", apkAbsolute), INSTALL_TIMEOUT_MS);
                String failure = installFailure(r.out());
                if (failure != null) {
                    p.result = "failed";
                    p.failure = failure;
                    p.warnings.add(hintFor(failure));
                    attention++;
                    continue;
                }
                // Post-condition: the device must now report the package. An
                // "adb install" that printed nothing useful cannot be trusted.
                Installed after = readInstalled(adb, p.serial());
                if (!after.present()) {
                    p.result = "failed";
                    p.failure = "VERIFY_FAILED";
                    p.warnings.add("the install reported success but the package is not on the device");
                    attention++;
                    continue;
                }
                p.installed = after;
                if (cli.flavour != null && !after.versionName().endsWith(FLAVOUR_SUFFIX + cli.flavour)) {
                    p.result = "failed";
                    p.failure = "FLAVOUR_MISMATCH";
                    p.warnings.add("the device reports " + after.versionName()
                            + ", so this APK is not the " + cli.flavour + " build");
                    attention++;
                    continue;
                }
                stored.apkSha256 = sha;
                p.actions.add("installed " + after.versionName());
            } else {
                p.actions.add("up to date (" + p.installed.versionName() + ")");
            }
            stored.versionName = p.installed.versionName();

            if (!p.batteryExempt && !cli.noBattery) {
                adb.exec(List.of("-s", p.serial(), "shell", "dumpsys", "deviceidle", "whitelist", "+" + PKG),
                        60_000);
                p.batteryExempt = readBatteryExempt(adb, p.serial());
                if (p.batteryExempt) {
                    p.actions.add("exempt from battery optimisation");
                } else {
                    // Never claim this worked: several vendors ignore the request
                    // until the user confirms it on screen.
                    p.warnings.add("battery optimisation unchanged; unlock the phone and run again");
                }
            } else if (p.batteryExempt) {
                p.actions.add("battery exemption already on");
            }

            int port = state.assignPort(p.serial(), cli.firstPort);
            p.forwardPort = port;
            p.actions.add("forward port " + port);
            stored.label = p.label;
            stored.lastAppliedMs = System.currentTimeMillis();

            p.result = p.warnings.isEmpty() ? "ready" : "partial";
            if (!p.warnings.isEmpty()) {
                attention++;
            }
        }

        state.save(Paths.get(cli.stateFile));

        if (cli.json) {
            System.out.println(renderApplyJson(probes, cli.stateFile));
        } else {
            for (Probe p : probes) {
                // The stable failure code is printed with the result so a log or
                // a CI transcript says *why*, not just "failed".
                String label = p.failure == null || p.failure.isBlank()
                        ? p.result
                        : p.result + ": " + p.failure;
                System.out.println(p.serial() + "  " + p.label + "  [" + label + "]");
                for (String action : p.actions) {
                    System.out.println("    + " + action);
                }
                for (String warning : p.warnings) {
                    // A blocker is already spelled out in the result line.
                    if (warning.equals(p.failure)) {
                        continue;
                    }
                    System.out.println("    ! " + warning);
                }
            }
        }
        if (attention > 0) {
            System.err.println(attention + " phone(s) need attention");
            return 1;
        }
        return 0;
    }

    static Path apkPath(Cli cli) throws ToolException {
        if (cli.apk == null || cli.apk.isBlank()) {
            return null;
        }
        Path apk = Paths.get(cli.apk);
        if (!Files.isRegularFile(apk)) {
            throw new ToolException("APK not found: " + apk, 2);
        }
        return apk;
    }

    /** Why this phone cannot be provisioned right now, or null when it can. */
    static String blockerOf(Probe p, Cli cli) {
        if (!"device".equals(p.device.adbState)) {
            return switch (p.device.adbState) {
                case "unauthorized" -> "unauthorized: accept the USB debugging prompt on the phone";
                case "offline" -> "offline: reconnect the cable or restart the phone's USB debugging";
                default -> p.device.adbState + ": the phone is not ready for commands";
            };
        }
        if (p.facts == null || p.facts.sdk() == 0) {
            return "unreadable: the phone did not return its build properties";
        }
        // Only enforce the higher minimum when the operator named the flavour;
        // otherwise 26 is the lowest any flavour needs.
        int minimum = "server29".equals(cli.flavour) ? 29 : 26;
        if (p.facts.sdk() < minimum) {
            return "sdk-too-low: Android " + p.facts.release() + " (SDK " + p.facts.sdk()
                    + ") is older than the " + (cli.flavour == null ? "app" : cli.flavour)
                    + " minimum (SDK " + minimum + ")";
        }
        if (requiresArm64(cli) && p.facts.abiKnown() && !p.facts.arm64()) {
            return "no-arm64: the phone reports " + p.facts.abiList()
                    + " and the server role needs the arm64-v8a Node runtime here"
                    + (cli.flavour == null ? " (pass --flavour peripheral26 for this phone)" : "");
        }
        return null;
    }

    /** Turns adb's install output into a stable code, or null when it worked. */
    static String installFailure(String out) {
        if (out.contains("Success")) {
            return null;
        }
        Matcher m = INSTALL_FAILURE.matcher(out);
        if (m.find()) {
            return m.group(1);
        }
        return "INSTALL_FAILED";
    }

    static String hintFor(String code) {
        return switch (code) {
            case "INSTALL_FAILED_UPDATE_INCOMPATIBLE" ->
                    "the installed app was signed with another key; remove it on the phone yourself, then run again";
            case "INSTALL_FAILED_VERSION_DOWNGRADE" ->
                    "the phone has a newer build; raise the version in the APK or use the installed build";
            case "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "free some space on the phone and run again";
            case "INSTALL_FAILED_INVALID_APK" -> "the file is not a usable APK; check --apk";
            default -> "the phone refused the install (" + code + ")";
        };
    }

    // ------------------------------------------------------------------- emit

    static int cmdEmit(Cli cli) throws ToolException {
        State state = State.load(Paths.get(cli.stateFile));
        List<StoredDevice> provisioned = new ArrayList<>();
        for (StoredDevice d : state.devices) {
            if (d.forwardPort > 0) {
                provisioned.add(d);
            }
        }
        if (provisioned.isEmpty()) {
            throw new ToolException("no provisioned phones yet; run `apply` first", 2);
        }
        provisioned.sort(Comparator.comparingInt(d -> d.forwardPort));

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"timeoutMs\": 8000,\n");
        sb.append("  \"pollSeconds\": 30,\n");
        sb.append("  \"listen\": ").append(q(HUB_LISTEN)).append(",\n");
        sb.append("  \"rosterFile\": \"roster.json\",\n");
        sb.append("  \"devices\": [\n");
        for (int i = 0; i < provisioned.size(); i++) {
            StoredDevice d = provisioned.get(i);
            sb.append("    {\n");
            sb.append("      \"label\": ").append(q(d.label == null ? "phone" : d.label)).append(",\n");
            sb.append("      \"url\": ").append(q("http://127.0.0.1:" + d.forwardPort)).append("\n");
            sb.append("    }").append(i + 1 < provisioned.size() ? "," : "").append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        if (cli.hubFile == null) {
            System.out.print(sb);
            return 0;
        }
        writeAtomically(Paths.get(cli.hubFile), sb.toString());
        System.err.println("wrote " + cli.hubFile + " (" + provisioned.size() + " phone(s))");
        return 0;
    }

    // --------------------------------------------------------------- rendering

    static void printProbeTable(List<Probe> probes) {
        System.out.println(pad("serial", 20) + pad("model", 22) + pad("android", 9)
                + pad("grom", 20) + pad("battery", 10) + pad("port", 7) + "state");
        for (Probe p : probes) {
            String android = p.facts == null ? "-" : (p.facts.release().isBlank() ? "-" : p.facts.release());
            String grom = p.installed.present() ? p.installed.versionName() : "-";
            String battery = !"device".equals(p.device.adbState) ? "-" : (p.batteryExempt ? "exempt" : "optimised");
            String port = p.forwardPort > 0 ? Integer.toString(p.forwardPort) : "-";
            String model = p.facts != null && !p.facts.model().isBlank() ? p.facts.model() : p.device.modelHint;
            System.out.println(pad(p.serial(), 20) + pad(model, 22) + pad(android, 9)
                    + pad(grom, 20) + pad(battery, 10) + pad(port, 7) + p.status);
        }
        System.out.println();
        System.out.println("State is read from the phone, not assumed. Ports come from " + "the provisioning state.");
    }

    static String pad(String value, int width) {
        String s = value == null ? "-" : value;
        if (s.length() >= width) {
            return s.substring(0, width - 1) + " ";
        }
        return s + " ".repeat(width - s.length());
    }

    static String renderProbesJson(String adb, String stateFile, List<Probe> probes) {
        List<String> rows = new ArrayList<>();
        for (Probe p : probes) {
            String facts = p.facts == null
                    ? "null"
                    : "{\"model\":" + q(p.facts.model())
                            + ",\"manufacturer\":" + q(p.facts.manufacturer())
                            + ",\"release\":" + q(p.facts.release())
                            + ",\"sdk\":" + p.facts.sdk()
                            + ",\"abi\":" + q(p.facts.abiList()) + "}";
            String installed = "{\"present\":" + p.installed.present()
                    + ",\"versionCode\":" + p.installed.versionCode()
                    + ",\"versionName\":" + q(p.installed.versionName()) + "}";
            rows.add("{\"serial\":" + q(p.serial())
                    + ",\"adbState\":" + q(p.device.adbState)
                    + ",\"label\":" + q(p.label)
                    + ",\"facts\":" + facts
                    + ",\"installed\":" + installed
                    + ",\"batteryExempt\":" + p.batteryExempt
                    + ",\"forwardPort\":" + p.forwardPort
                    + ",\"state\":" + q(p.status) + "}");
        }
        return "{\"version\":1,\"command\":\"list\",\"adb\":" + q(adb)
                + ",\"stateFile\":" + q(stateFile)
                + ",\"devices\":[" + String.join(",", rows) + "]}";
    }

    static String renderApplyJson(List<Probe> probes, String stateFile) {
        List<String> rows = new ArrayList<>();
        for (Probe p : probes) {
            rows.add("{\"serial\":" + q(p.serial())
                    + ",\"label\":" + q(p.label)
                    + ",\"result\":" + q(p.result)
                    + ",\"failure\":" + q(p.failure)
                    + ",\"forwardPort\":" + p.forwardPort
                    + ",\"actions\":" + jsonArray(p.actions)
                    + ",\"warnings\":" + jsonArray(p.warnings) + "}");
        }
        return "{\"version\":1,\"command\":\"apply\",\"stateFile\":" + q(stateFile)
                + ",\"devices\":[" + String.join(",", rows) + "]}";
    }

    static String jsonArray(List<String> items) {
        return "[" + String.join(",", items.stream().map(GromProvision::q).toList()) + "]";
    }

    static String q(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : (value == null ? "" : value).toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ state

    /** One phone as recorded by a previous apply. */
    static final class StoredDevice {
        String serial = "";
        String label = "";
        int forwardPort;
        String apkSha256;
        String versionName;
        long lastAppliedMs;
    }

    /**
     * provision.json: which phone got which label and forward port, and which
     * APK was used. Written atomically and owner-only, like the app's own stores.
     */
    static final class State {
        int version = 1;
        int nextPort = FIRST_PORT;
        final List<StoredDevice> devices = new ArrayList<>();

        static State load(Path file) throws ToolException {
            State state = new State();
            if (!Files.isRegularFile(file)) {
                return state;
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ToolException("cannot read " + file + ": " + e.getMessage(), 2);
            }
            if (text.isBlank()) {
                return state;
            }
            Object parsed = new JsonParser(text).parse();
            if (!(parsed instanceof Map<?, ?> root)) {
                throw new ToolException(file + " is not a provisioning state object", 2);
            }
            Object version = root.get("version");
            if (version instanceof Long n) {
                state.version = n.intValue();
            }
            Object next = root.get("nextPort");
            if (next instanceof Long n) {
                state.nextPort = n.intValue();
            }
            if (state.nextPort < FIRST_PORT) {
                state.nextPort = FIRST_PORT;
            }
            Object list = root.get("devices");
            if (list instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (!(entry instanceof Map<?, ?> row)) {
                        continue;
                    }
                    StoredDevice d = new StoredDevice();
                    d.serial = asString(row.get("serial"));
                    d.label = asString(row.get("label"));
                    d.forwardPort = (int) asLong(row.get("forwardPort"));
                    d.apkSha256 = asString(row.get("apkSha256"));
                    d.versionName = asString(row.get("versionName"));
                    d.lastAppliedMs = asLong(row.get("lastAppliedMs"));
                    if (!d.serial.isBlank()) {
                        state.devices.add(d);
                    }
                }
            }
            return state;
        }

        static String asString(Object value) {
            return value instanceof String s ? s : "";
        }

        static long asLong(Object value) {
            return value instanceof Long l ? l : 0L;
        }

        StoredDevice find(String serial) {
            for (StoredDevice d : devices) {
                if (d.serial.equals(serial)) {
                    return d;
                }
            }
            return null;
        }

        StoredDevice ensure(String serial) {
            StoredDevice existing = find(serial);
            if (existing != null) {
                return existing;
            }
            StoredDevice created = new StoredDevice();
            created.serial = serial;
            devices.add(created);
            return created;
        }

        /** The port this phone would get, without changing anything. */
        int peekPort(int first, String serial) throws ToolException {
            StoredDevice existing = find(serial);
            if (existing != null && existing.forwardPort > 0) {
                return existing.forwardPort;
            }
            Set<Integer> used = new HashSet<>();
            for (StoredDevice d : devices) {
                if (d.forwardPort > 0 && !d.serial.equals(serial)) {
                    used.add(d.forwardPort);
                }
            }
            int candidate = Math.max(first, nextPort);
            while (candidate <= LAST_PORT && used.contains(candidate)) {
                candidate++;
            }
            if (candidate > LAST_PORT) {
                throw new ToolException("no free forward port left in " + FIRST_PORT + "-" + LAST_PORT, 2);
            }
            return candidate;
        }

        /** Gives this phone a stable port, reusing the recorded one when present. */
        int assignPort(String serial, int first) throws ToolException {
            StoredDevice device = ensure(serial);
            if (device.forwardPort > 0) {
                return device.forwardPort;
            }
            int port = peekPort(first, serial);
            device.forwardPort = port;
            if (port >= nextPort) {
                nextPort = port + 1;
            }
            return port;
        }

        void save(Path file) throws ToolException {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"version\": ").append(version).append(",\n");
            sb.append("  \"nextPort\": ").append(nextPort).append(",\n");
            sb.append("  \"devices\": [\n");
            for (int i = 0; i < devices.size(); i++) {
                StoredDevice d = devices.get(i);
                sb.append("    {\"serial\": ").append(q(d.serial));
                sb.append(", \"label\": ").append(q(d.label));
                sb.append(", \"forwardPort\": ").append(d.forwardPort);
                sb.append(", \"apkSha256\": ").append(d.apkSha256 == null ? "null" : q(d.apkSha256));
                sb.append(", \"versionName\": ").append(q(d.versionName == null ? "" : d.versionName));
                sb.append(", \"lastAppliedMs\": ").append(d.lastAppliedMs);
                sb.append("}").append(i + 1 < devices.size() ? "," : "").append("\n");
            }
            sb.append("  ]\n}\n");
            writeAtomically(file, sb.toString());
        }
    }

    static void writeAtomically(Path target, String content) throws ToolException {
        Path absolute = target.toAbsolutePath().normalize();
        Path dir = absolute.getParent();
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = Files.createTempFile(dir, ".grom-provision", ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX filesystem; the platform's defaults apply.
            } catch (IOException ignored) {
                // Permissions are best effort; the write itself is not.
            }
            try {
                Files.move(tmp, absolute,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ToolException("cannot write " + absolute + ": " + e.getMessage(), 2);
        }
    }

    static String sha256(Path file) throws ToolException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException e) {
            throw new ToolException("cannot read " + file + ": " + e.getMessage(), 2);
        } catch (NoSuchAlgorithmException e) {
            throw new ToolException("this JDK has no SHA-256", 2);
        }
    }

    // ------------------------------------------------------------ json reading

    /** A small, strict JSON reader: the state file is hand-editable, so it must fail loudly. */
    static final class JsonParser {
        private final String text;
        private int at;

        JsonParser(String text) {
            this.text = text;
        }

        Object parse() throws ToolException {
            Object value = value();
            whitespace();
            if (at < text.length()) {
                throw new ToolException("trailing data in JSON at offset " + at, 2);
            }
            return value;
        }

        private void whitespace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private Object value() throws ToolException {
            whitespace();
            if (at >= text.length()) {
                throw new ToolException("unexpected end of JSON", 2);
            }
            char c = text.charAt(at);
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

        private Object literal(String word, Object value) throws ToolException {
            if (!text.startsWith(word, at)) {
                throw new ToolException("unexpected token at offset " + at, 2);
            }
            at += word.length();
            return value;
        }

        private Map<String, Object> object() throws ToolException {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            whitespace();
            if (at < text.length() && text.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                whitespace();
                if (at >= text.length() || text.charAt(at) != '"') {
                    throw new ToolException("expected a field name at offset " + at, 2);
                }
                String key = string();
                whitespace();
                if (at >= text.length() || text.charAt(at) != ':') {
                    throw new ToolException("expected ':' at offset " + at, 2);
                }
                at++;
                map.put(key, value());
                whitespace();
                if (at >= text.length()) {
                    throw new ToolException("unterminated object", 2);
                }
                char c = text.charAt(at++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new ToolException("expected ',' or '}' at offset " + (at - 1), 2);
                }
            }
        }

        private List<Object> array() throws ToolException {
            List<Object> list = new ArrayList<>();
            at++;
            whitespace();
            if (at < text.length() && text.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                whitespace();
                if (at >= text.length()) {
                    throw new ToolException("unterminated array", 2);
                }
                char c = text.charAt(at++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new ToolException("expected ',' or ']' at offset " + (at - 1), 2);
                }
            }
        }

        private String string() throws ToolException {
            at++;
            StringBuilder sb = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    break;
                }
                char esc = text.charAt(at++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw new ToolException("bad \\u escape", 2);
                        }
                        sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new ToolException("bad escape \\" + esc, 2);
                }
            }
            throw new ToolException("unterminated string", 2);
        }

        private Object number() throws ToolException {
            int start = at;
            if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) {
                at++;
            }
            while (at < text.length()) {
                char c = text.charAt(at);
                if ((c >= '0' && c <= '9')) {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    at++;
                } else {
                    break;
                }
            }
            if (start == at) {
                throw new ToolException("unexpected character at offset " + at, 2);
            }
            try {
                return Long.parseLong(text.substring(start, at));
            } catch (NumberFormatException e) {
                throw new ToolException("expected a whole number at offset " + start, 2);
            }
        }
    }

    // -------------------------------------------------------------------- cli

    static final class Cli {
        String command = "list";
        String adbPath;
        String apk;
        String flavour;
        String stateFile = "provision.json";
        final List<String> serials = new ArrayList<>();
        int firstPort = FIRST_PORT;
        boolean force;
        boolean noBattery;
        boolean json;
        boolean help;
        boolean version;
        boolean selfTest;
        String hubFile;
        boolean fix;

        static final Set<String> COMMANDS = Set.of("list", "plan", "apply", "emit", "doctor");

        static final Set<String> BOOLEAN_OPTIONS =
                Set.of("-h", "--help", "--version", "--self-test", "--json", "--force",
                        "--no-battery", "--fix");

        static Cli parse(String[] args) throws ToolException {
            // `--opt=value` is normalised to two tokens first, so the loop below
            // has exactly one place where an index moves forward.
            List<String> tokens = new ArrayList<>();
            for (String arg : args) {
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 2) {
                    tokens.add(arg.substring(0, eq));
                    tokens.add(arg.substring(eq + 1));
                } else {
                    tokens.add(arg);
                }
            }

            Cli cli = new Cli();
            boolean commandSeen = false;
            for (int i = 0; i < tokens.size(); i++) {
                String arg = tokens.get(i);
                if (!arg.startsWith("-")) {
                    if (commandSeen) {
                        throw new ToolException("unexpected argument: " + arg, 2);
                    }
                    if (!COMMANDS.contains(arg)) {
                        throw new ToolException("unknown command: " + arg, 2);
                    }
                    cli.command = arg;
                    commandSeen = true;
                    continue;
                }
                if (!BOOLEAN_OPTIONS.contains(arg) && !isValueOption(arg)) {
                    throw new ToolException("unknown option: " + arg, 2);
                }
                switch (arg) {
                    case "-h", "--help" -> cli.help = true;
                    case "--version" -> cli.version = true;
                    case "--self-test" -> cli.selfTest = true;
                    case "--json" -> cli.json = true;
                    case "--force" -> cli.force = true;
                    case "--no-battery" -> cli.noBattery = true;
                    case "--fix" -> cli.fix = true;
                    case "--adb" -> cli.adbPath = take(tokens, ++i, arg);
                    case "--apk" -> cli.apk = take(tokens, ++i, arg);
                    case "--flavour" -> cli.flavour = take(tokens, ++i, arg);
                    case "--state" -> cli.stateFile = take(tokens, ++i, arg);
                    case "--serial" -> cli.serials.add(take(tokens, ++i, arg));
                    case "--hub" -> cli.hubFile = take(tokens, ++i, arg);
                    case "--ports" -> cli.firstPort = parseInt(arg, take(tokens, ++i, arg));
                    default -> throw new ToolException("unknown option: " + arg, 2);
                }
            }
            if (cli.firstPort < FIRST_PORT || cli.firstPort > LAST_PORT) {
                throw new ToolException("--ports must be between " + FIRST_PORT + " and " + LAST_PORT, 2);
            }
            return cli;
        }

        static boolean isValueOption(String name) {
            return switch (name) {
                case "--adb", "--apk", "--flavour", "--state", "--serial", "--hub", "--ports" -> true;
                default -> false;
            };
        }

        static int parseInt(String name, String raw) throws ToolException {
            try {
                return Integer.parseInt(raw.strip());
            } catch (NumberFormatException e) {
                throw new ToolException(name + " needs a number, got: " + raw, 2);
            }
        }

        static String take(List<String> tokens, int index, String name) throws ToolException {
            if (index >= tokens.size()) {
                throw new ToolException(name + " needs a value", 2);
            }
            return tokens.get(index);
        }
    }

    static void printUsage() {
        System.out.println("""
                grom-provision — prepare several phones for Grom from one computer.

                usage: grom-provision <command> [options]

                commands:
                  list     what is connected and what each phone reports (default)
                  plan     what apply would change; runs no mutating command
                  apply    install the APK, exempt it from battery optimisation,
                           and give the phone its own SSH forward port
                  emit     turn the provisioning state into a hub.json for grom-hub
                  doctor   read the settings Android does not show an app, and
                           optionally fix the two that are safe (see --fix)

                options:
                  --apk PATH        the APK to install (required by apply)
                  --flavour NAME    server29 or peripheral26; verified after install
                  --adb PATH        adb binary (default: $ADB, then PATH, then the SDK)
                  --serial SERIAL   only this phone (repeatable)
                  --state FILE      provisioning state (default: provision.json)
                  --ports FIRST     first forward port (default: 41001)
                  --force           install even when the APK already matches
                  --no-battery      do not touch battery optimisation
                  --hub FILE        emit: write hub.json here instead of stdout
                  --fix             doctor: whitelist the package from Doze and set
                                    the legacy Wi-Fi sleep policy (API 28 and older).
                                    No appops change, no package removal.
                  --json            machine-readable output
                  --self-test       run the built-in tests (needs no phone)
                  -h, --help        this text
                  --version         print the tool version

                exit codes:
                  0  everything asked for was done
                  1  a phone needs attention (see the message for that phone)
                  2  a usage or configuration problem; no phone was changed

                The tool only ever reads, installs the APK you pass, and asks for a
                battery exemption. It never removes packages, wipes data or restarts
                a phone. See docs/PROVISION.md.""");
    }

    // --------------------------------------------------------- doctor (D80-D84)

    /**
     * What one phone answered when asked the questions an app cannot ask itself
     * (D81).
     *
     * Every field is a String or a tri-state, never a guess: "not answered" and
     * "0" are different values here for the same reason they are different in
     * the app. `null` means the command failed or the ROM does not know it.
     */
    static final class DoctorReport {
        String serial;
        String label = "";
        int sdk;
        Boolean dozeWhitelisted;
        String appopsBackground;
        String maxPhantomProcesses;
        String phantomMonitor;
        String wifiSleepPolicy;
        int forwardPort;
        List<String> findings = new ArrayList<>();
        List<String> fixes = new ArrayList<>();

        /** True when nothing was measured or something needs attention. */
        boolean needsAttention() {
            if (dozeWhitelisted == null) return true;
            if (Boolean.FALSE.equals(dozeWhitelisted)) return true;
            return !findings.isEmpty();
        }
    }

    /**
     * Reads (and, with `--fix`, adjusts) the settings only a computer can see.
     *
     * The line this command does not cross: it never changes an app-op, never
     * removes a package and never touches a phone it cannot identify. `--fix`
     * is limited to the two changes that are both reversible and documented —
     * the Doze whitelist entry for Grom and the legacy Wi-Fi sleep policy — and
     * both are re-read afterwards, so the report says what the phone now
     * reports rather than what the tool asked for.
     */
    static int cmdDoctor(Cli cli) throws ToolException {
        Adb adb = new Adb(resolveAdb(cli));
        List<Device> selected = devicesOf(adb, cli);
        if (selected.isEmpty()) {
            System.err.println("grom-provision: no phones detected");
            return 2;
        }

        State state = State.load(Paths.get(cli.stateFile));
        List<DoctorReport> reports = new ArrayList<>();
        for (Device d : selected) {
            DoctorReport r = new DoctorReport();
            r.serial = d.serial;
            StoredDevice stored = state.find(d.serial);
            if (stored != null) {
                r.label = stored.label == null ? "" : stored.label;
                r.forwardPort = stored.forwardPort;
            }
            if (!"device".equals(d.adbState)) {
                r.findings.add("adb-state:" + d.adbState);
                reports.add(r);
                continue;
            }
            Facts facts = readFacts(adb, d.serial);
            r.sdk = facts.sdk();
            readDoctor(adb, r);

            if (cli.fix) {
                applyDoctorFixes(adb, r);
                // Re-read: the report must be a measurement of the phone after
                // the change, not a recording of the request.
                readDoctor(adb, r);
            }
            reports.add(r);
        }

        if (cli.json) {
            System.out.print(renderDoctorJson(reports));
        } else {
            printDoctorTable(reports, cli.fix);
        }
        return reports.stream().anyMatch(DoctorReport::needsAttention) ? 1 : 0;
    }

    /** The read-only half. Every call here only asks. */
    static void readDoctor(Adb adb, DoctorReport r) throws ToolException {
        r.dozeWhitelisted = parseBatteryExemptOrNull(
                adb.exec(List.of("-s", r.serial, "shell", "dumpsys", "deviceidle", "whitelist"),
                        PROBE_TIMEOUT_MS).out());
        r.appopsBackground = firstLine(adb.exec(
                List.of("-s", r.serial, "shell", "cmd", "appops", "get", PKG,
                        "RUN_ANY_IN_BACKGROUND"),
                PROBE_TIMEOUT_MS).out());
        r.maxPhantomProcesses = firstLine(adb.exec(
                List.of("-s", r.serial, "shell", "device_config", "get", "activity_manager",
                        "max_phantom_processes"),
                PROBE_TIMEOUT_MS).out());
        r.phantomMonitor = firstLine(adb.exec(
                List.of("-s", r.serial, "shell", "settings", "get", "global",
                        "settings_enable_monitor_phantom_procs"),
                PROBE_TIMEOUT_MS).out());
        r.wifiSleepPolicy = firstLine(adb.exec(
                List.of("-s", r.serial, "shell", "settings", "get", "global", "wifi_sleep_policy"),
                PROBE_TIMEOUT_MS).out());

        r.findings = doctorFindings(r);
    }

    /**
     * The findings, in the same vocabulary the app uses. Only facts this tool
     * actually read produce a finding; an unanswered command is reported by
     * [DoctorReport.needsAttention] through the null whitelist, not by an
     * invented sentence.
     */
    static List<String> doctorFindings(DoctorReport r) {
        List<String> findings = new ArrayList<>();
        if (Boolean.FALSE.equals(r.dozeWhitelisted)) {
            findings.add("DOCTOR_NOT_WHITELISTED");
        }
        if (r.appopsBackground != null && r.appopsBackground.contains("ignore")) {
            findings.add("DOCTOR_APPOPS_IGNORED");
        }
        if (r.appopsBackground != null && r.appopsBackground.contains("deny")) {
            findings.add("DOCTOR_APPOPS_DENIED");
        }
        if (r.phantomMonitor != null && r.phantomMonitor.equals("true")) {
            findings.add("DOCTOR_PHANTOM_MONITOR_ON");
        }
        if (r.wifiSleepPolicy != null && r.sdk > 0 && r.sdk <= 28
                && !r.wifiSleepPolicy.equals("2")) {
            findings.add("DOCTOR_WIFI_SLEEP_NOT_ALWAYS");
        }
        return findings;
    }

    /**
     * The two allowed changes, each re-read by the caller. `settings put` on a
     * modern Android is accepted and ignored for `wifi_sleep_policy`, so the
     * change is attempted only where it has an effect and the reason is printed
     * rather than implied.
     */
    static void applyDoctorFixes(Adb adb, DoctorReport r) throws ToolException {
        if (Boolean.FALSE.equals(r.dozeWhitelisted)) {
            adb.exec(List.of("-s", r.serial, "shell", "dumpsys", "deviceidle", "whitelist",
                    "+" + PKG), PROBE_TIMEOUT_MS);
            r.fixes.add("DOCTOR_FIX_WHITELIST");
        }
        if (r.sdk > 0 && r.sdk <= 28) {
            adb.exec(List.of("-s", r.serial, "shell", "settings", "put", "global",
                    "wifi_sleep_policy", "2"), PROBE_TIMEOUT_MS);
            r.fixes.add("DOCTOR_FIX_WIFI_SLEEP");
        }
    }

    static void printDoctorTable(List<DoctorReport> reports, boolean fixed) {
        for (DoctorReport r : reports) {
            String name = r.label.isBlank() ? r.serial : r.label;
            System.out.println();
            System.out.println(name + "  (" + r.serial + ")");
            System.out.println("  " + line("doze whitelist", truth(r.dozeWhitelisted)));
            System.out.println("  " + line("appops background", orUnknown(r.appopsBackground)));
            System.out.println("  " + line("max phantom processes", orUnknown(r.maxPhantomProcesses)));
            System.out.println("  " + line("phantom monitor", orUnknown(r.phantomMonitor)));
            System.out.println("  " + line("wifi sleep policy", orUnknown(r.wifiSleepPolicy)));
            // "attempted", not "fixed": a vendor build is free to ignore the
            // request, and the lines above are the re-read truth. Claiming a
            // change took before reading the phone back is exactly the kind of
            // false green this project refuses everywhere else.
            for (String fix : r.fixes) {
                System.out.println("  attempted: " + fix);
            }
            if (r.findings.isEmpty()) {
                System.out.println("  findings: none");
            } else {
                System.out.println("  findings: " + String.join(", ", r.findings));
            }
        }
        System.out.println();
        if (!fixed) {
            System.out.println("Read-only. `--fix` may add the Doze whitelist entry and set the");
            System.out.println("legacy Wi-Fi sleep policy (API 28 and older); it changes nothing else.");
        }
        System.out.println("A setting Android does not expose is reported as \"unknown\", never as 0.");
    }

    static String renderDoctorJson(List<DoctorReport> reports) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"devices\": [\n");
        for (int i = 0; i < reports.size(); i++) {
            DoctorReport r = reports.get(i);
            sb.append("    {\n");
            sb.append("      \"serial\": ").append(q(r.serial)).append(",\n");
            sb.append("      \"label\": ").append(q(r.label)).append(",\n");
            sb.append("      \"sdk\": ").append(r.sdk).append(",\n");
            sb.append("      \"dozeWhitelisted\": ").append(r.dozeWhitelisted == null
                    ? "null" : r.dozeWhitelisted.toString()).append(",\n");
            sb.append("      \"appopsBackground\": ").append(q(r.appopsBackground)).append(",\n");
            sb.append("      \"maxPhantomProcesses\": ").append(q(r.maxPhantomProcesses)).append(",\n");
            sb.append("      \"phantomMonitor\": ").append(q(r.phantomMonitor)).append(",\n");
            sb.append("      \"wifiSleepPolicy\": ").append(q(r.wifiSleepPolicy)).append(",\n");
            sb.append("      \"forwardPort\": ").append(r.forwardPort).append(",\n");
            sb.append("      \"findings\": [");
            for (int j = 0; j < r.findings.size(); j++) {
                sb.append(j == 0 ? "" : ", ").append(q(r.findings.get(j)));
            }
            sb.append("],\n");
            sb.append("      \"fixes\": [");
            for (int j = 0; j < r.fixes.size(); j++) {
                sb.append(j == 0 ? "" : ", ").append(q(r.fixes.get(j)));
            }
            sb.append("]\n");
            sb.append("    }").append(i + 1 < reports.size() ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    static String truth(Boolean value) {
        if (value == null) return "unknown";
        return value ? "yes" : "no";
    }

    static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    static String line(String label, String value) {
        return String.format(Locale.US, "%-24s %s", label, value);
    }

    // --------------------------------------------------------------- self test

    /**
     * The tests run the real commands in-process against a fake adb that answers
     * from a scenario directory and records every invocation, so they can prove
     * both the decisions and the absence of destructive commands.
     */
    static final class SelfTest {

        static final String SCRIPT = """
                #!/usr/bin/env bash
                # Fake adb for tools/grom-provision --self-test.
                set -u
                scen="${FAKE_ADB_SCEN:?FAKE_ADB_SCEN must point at a scenario directory}"
                printf '%s\\n' "$*" >> "$scen/invocations.log"

                serial=""
                if [ "${1:-}" = "-s" ]; then
                  serial="${2:-}"
                  shift 2 || true
                fi
                cmd="${1:-}"

                mkdir -p "$scen/pkg" "$scen/props" "$scen/battery"

                case "$cmd" in
                  devices)
                    if [ -f "$scen/devices-fail" ]; then
                      cat "$scen/devices-fail"
                      exit 1
                    fi
                    cat "$scen/devices.txt"
                    ;;
                  install)
                    if [ -f "$scen/install/$serial" ]; then
                      cat "$scen/install/$serial"
                    else
                      echo "Success"
                    fi
                    if [ -f "$scen/pkg/$serial.after" ]; then
                      cp "$scen/pkg/$serial.after" "$scen/pkg/$serial"
                    fi
                    ;;
                  shell)
                    sub="${2:-}"
                    case "$sub" in
                      getprop)
                        if [ -f "$scen/props/$serial.all" ]; then
                          cat "$scen/props/$serial.all"
                        fi
                        ;;
                      dumpsys)
                        what="${3:-}"
                        case "$what" in
                          package)
                            if [ -f "$scen/pkg/$serial" ]; then
                              cat "$scen/pkg/$serial"
                            else
                              echo "Unable to find package: app.grom.server"
                            fi
                            ;;
                          deviceidle)
                            verb="${4:-}"
                            arg="${5:-}"
                            if [ -n "$verb" ] && [ -n "$arg" ]; then
                              case "$arg" in
                                +*)
                                  pkg="${arg#+}"
                                  touch "$scen/battery/$serial"
                                  echo "user,$pkg,10123" >> "$scen/battery/$serial"
                                  echo "Whitelisted $pkg"
                                  ;;
                                -*)
                                  pkg="${arg#-}"
                                  if [ -f "$scen/battery/$serial" ]; then
                                    grep -v ",$pkg," "$scen/battery/$serial" > "$scen/battery/$serial.tmp" || true
                                    mv "$scen/battery/$serial.tmp" "$scen/battery/$serial"
                                  fi
                                  echo "Removed $pkg"
                                  ;;
                                *)
                                  cat "$scen/battery/$serial" 2>/dev/null || true
                                  ;;
                              esac
                            else
                              cat "$scen/battery/$serial" 2>/dev/null || true
                            fi
                            ;;
                          *)
                            ;;
                        esac
                        ;;
                      cmd)
                        # adb shell cmd appops get <pkg> RUN_ANY_IN_BACKGROUND
                        if [ "${3:-}" = "appops" ]; then
                          if [ -f "$scen/appops/$serial" ]; then
                            cat "$scen/appops/$serial"
                          else
                            echo "app.grom.server: No operations."
                          fi
                        fi
                        ;;
                      device_config)
                        if [ -f "$scen/phantom/$serial" ]; then
                          cat "$scen/phantom/$serial"
                        else
                          echo "null"
                        fi
                        ;;
                      settings)
                        which="${3:-}"
                        key="${5:-}"
                        if [ "$which" = "put" ]; then
                          mkdir -p "$scen/settings"
                          printf '%s\n' "${6:-}" > "$scen/settings/$serial.$key"
                          exit 0
                        fi
                        if [ -f "$scen/settings/$serial.$key" ]; then
                          cat "$scen/settings/$serial.$key"
                        else
                          echo "null"
                        fi
                        ;;
                      *)
                        ;;
                    esac
                    ;;
                  *)
                    ;;
                esac
                exit 0
                """;

        static int passed;
        static int failed;
        static Path root;
        static String adbPath;
        static Path fakeAdb;

        record Captured(int code, String out, String err) {}

        static int run() throws Exception {
            root = Files.createTempDirectory("grom-provision-selftest");
            fakeAdb = writeFakeAdb(root);
            adbPath = fakeAdb.toString();
            System.out.println("== grom-provision self-test ==");
            try {
                parsingTests();
                listTests();
                planTests();
                applyTests();
                emitTests();
                doctorTests();
                cliTests();
            } finally {
                deleteTree(root);
            }
            System.out.println();
            System.out.println(passed + " passed, " + failed + " failed");
            return failed == 0 ? 0 : 1;
        }

        static void check(String name, boolean ok) {
            if (ok) {
                passed++;
                System.out.println("ok   " + name);
            } else {
                failed++;
                System.out.println("FAIL " + name);
            }
        }

        // -- fixture ------------------------------------------------------

        /**
         * Each scenario gets its own `adb` wrapper that pins FAKE_ADB_SCEN, so a
         * test can hand a phone a completely different answer from the next one
         * while still going through the real ProcessBuilder path.
         */
        static Path scenario(String name) throws IOException {
            Path dir = root.resolve(name);
            Files.createDirectories(dir.resolve("props"));
            Files.createDirectories(dir.resolve("pkg"));
            Files.createDirectories(dir.resolve("battery"));
            Files.createDirectories(dir.resolve("install"));
            Path wrapper = dir.resolve("adb");
            Files.writeString(wrapper,
                    "#!/usr/bin/env bash\nFAKE_ADB_SCEN='" + dir + "' exec '" + fakeAdb + "' \"$@\"\n",
                    StandardCharsets.UTF_8);
            wrapper.toFile().setExecutable(true);
            return dir;
        }

        static Path writeFakeAdb(Path dir) throws IOException {
            Path script = dir.resolve("fake-adb");
            Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);
            return script;
        }

        static void devices(Path scen, String content) throws IOException {
            Files.writeString(scen.resolve("devices.txt"), content, StandardCharsets.UTF_8);
        }

        static void props(Path scen, String serial, Map<String, String> values) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : values.entrySet()) {
                sb.append('[').append(e.getKey()).append("]: [").append(e.getValue()).append("]\n");
            }
            Files.writeString(scen.resolve("props").resolve(serial + ".all"), sb.toString(),
                    StandardCharsets.UTF_8);
        }

        static void packageState(Path scen, String serial, String versionName, int versionCode)
                throws IOException {
            String text = "Packages:\n"
                    + "  Package [" + PKG + "] (1a2b3c):\n"
                    + "    userId=10123\n"
                    + "    codePath=/data/app/~~x/app.grom.server-y==\n"
                    + "    versionCode=" + versionCode + " minSdk=29 targetSdk=36\n"
                    + "    versionName=" + versionName + "\n";
            Files.writeString(scen.resolve("pkg").resolve(serial), text, StandardCharsets.UTF_8);
        }

        static void packageAfterInstall(Path scen, String serial, String versionName, int versionCode)
                throws IOException {
            String text = "Packages:\n"
                    + "  Package [" + PKG + "] (1a2b3c):\n"
                    + "    userId=10123\n"
                    + "    versionCode=" + versionCode + " minSdk=29 targetSdk=36\n"
                    + "    versionName=" + versionName + "\n";
            Files.writeString(scen.resolve("pkg").resolve(serial + ".after"), text, StandardCharsets.UTF_8);
        }

        static void battery(Path scen, String serial, boolean exempt) throws IOException {
            Files.writeString(scen.resolve("battery").resolve(serial),
                    exempt ? "user," + PKG + ",10123\n" : "system-excidle,com.android.shell,2000\n",
                    StandardCharsets.UTF_8);
        }

        static void installOutput(Path scen, String serial, String line) throws IOException {
            Files.writeString(scen.resolve("install").resolve(serial), line + "\n", StandardCharsets.UTF_8);
        }

        /**
         * The commands the tool is allowed to issue at all (D79, D81).
         *
         * The doctor tests use this instead of listing forbidden words: a command
         * that is not on this list fails the test, so a future edit cannot add a
         * destructive verb and quietly pass. `install` is here because `apply`
         * needs it; the doctor run never issues it and the call sites say so.
         */
        static final List<String> ALLOWED_COMMAND_PREFIXES = List.of(
                "devices -l",
                "install",
                "shell getprop",
                "shell dumpsys package",
                "shell dumpsys deviceidle whitelist",
                "shell cmd appops get",
                "shell device_config get",
                "shell settings get",
                "shell settings put global wifi_sleep_policy 2");

        /** Returns the invocations that are not on the allow-list. */
        static List<String> allowedInvocationsOnly(Path scen) throws IOException {
            List<String> unexpected = new ArrayList<>();
            for (String raw : invocations(scen).split("\n")) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                String rest = line;
                if (rest.startsWith("-s ")) {
                    int space = rest.indexOf(' ', 3);
                    rest = space < 0 ? "" : rest.substring(space + 1);
                }
                final String candidate = rest;
                boolean allowed = ALLOWED_COMMAND_PREFIXES.stream()
                        .anyMatch(prefix -> candidate.startsWith(prefix));
                if (!allowed) {
                    unexpected.add(line);
                }
            }
            return unexpected;
        }

        static String invocations(Path scen) throws IOException {
            Path log = scen.resolve("invocations.log");
            return Files.isRegularFile(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        }

        static void resetInvocations(Path scen) throws IOException {
            Files.deleteIfExists(scen.resolve("invocations.log"));
        }

        static Map<String, String> phone(String model, String release, int sdk, String abi) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("ro.product.manufacturer", "Google");
            values.put("ro.product.model", model);
            values.put("ro.build.version.release", release);
            values.put("ro.build.version.sdk", Integer.toString(sdk));
            values.put("ro.product.cpu.abilist", abi);
            return values;
        }

        /** Runs one command in-process, capturing stdout and stderr. */
        static Captured invoke(String... args) {
            Cli cli;
            try {
                cli = Cli.parse(args);
            } catch (ToolException e) {
                return new Captured(2, "", e.getMessage());
            }
            if (cli.adbPath == null) {
                cli.adbPath = adbFor(args);
            }
            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code;
            try {
                code = execute(cli);
            } catch (Exception e) {
                System.err.println("unexpected: " + e);
                code = 99;
            } finally {
                System.out.flush();
                System.err.flush();
                System.setOut(oldOut);
                System.setErr(oldErr);
            }
            return new Captured(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        }

        /**
         * Picks the fake adb that belongs to the scenario named by --state. A
         * real run never has an `adb` file next to its state file, so this only
         * ever applies to the tests.
         */
        static String adbFor(String[] args) {
            for (int i = 0; i < args.length; i++) {
                String value = null;
                if (args[i].equals("--state") && i + 1 < args.length) {
                    value = args[i + 1];
                } else if (args[i].startsWith("--state=")) {
                    value = args[i].substring("--state=".length());
                }
                if (value == null) {
                    continue;
                }
                Path parent = Paths.get(value).toAbsolutePath().getParent();
                if (parent != null && Files.isRegularFile(parent.resolve("adb"))) {
                    return parent.resolve("adb").toString();
                }
            }
            return adbPath;
        }

        static Path apk(Path scen) throws IOException {
            Path apk = scen.resolve("grom.apk");
            Files.writeString(apk, "not a real apk, but a stable hash input", StandardCharsets.UTF_8);
            return apk;
        }

        static void deleteTree(Path dir) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // A leftover temp file must not fail the suite.
                    }
                });
            } catch (IOException ignored) {
                // Same: the suite's verdict is about the assertions.
            }
        }

        // -- pure functions ------------------------------------------------

        static void parsingTests() {
            List<Device> devices = parseDevices("""
                    List of devices attached
                    R58M12345              device product:beyond1q model:SM_G973F device:beyond1 transport_id:1
                    emulator-5554          device product:sdk_gphone_x86 model:sdk_gphone_x86 transport_id:2
                    9A0X1234               unauthorized usb:1-1 transport_id:3
                    5B1Y9999               offline transport_id:4

                    """);
            check("devices: four rows parsed", devices.size() == 4);
            check("devices: serial and state", devices.get(0).serial.equals("R58M12345")
                    && devices.get(0).adbState.equals("device"));
            check("devices: model hint parsed", devices.get(0).modelHint.equals("SM_G973F"));
            check("devices: unauthorized kept with its state",
                    devices.get(2).adbState.equals("unauthorized"));
            check("devices: blank lines ignored", devices.stream().noneMatch(d -> d.serial.isBlank()));

            List<Device> withNoise = parseDevices("""
                    List of devices attached
                    adb server version (41) doesn't match this client (39); shutting down
                    * daemon not running; starting now at tcp:5037 *
                    R58M12345    device model:Pixel_7 transport_id:1
                    """);
            check("devices: adb server chatter is not a phone",
                    withNoise.size() == 1 && withNoise.get(0).serial.equals("R58M12345"));

            Map<String, String> props = parseGetprop("""
                    [ro.product.model]: [Pixel 7]
                    [ro.build.version.sdk]: [34]
                    [ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a,armeabi]
                    [nothing special]
                    """);
            check("getprop: values parsed", props.get("ro.product.model").equals("Pixel 7"));
            check("getprop: malformed line ignored", props.size() == 3);

            Installed installed = parseInstalled("""
                    Packages:
                      Package [app.grom.server] (1a2b3c):
                        versionCode=12 minSdk=29 targetSdk=36
                        versionName=0.12.0-server29
                    """);
            check("package: installed version read back",
                    installed.present() && installed.versionCode() == 12
                            && installed.versionName().equals("0.12.0-server29"));
            check("package: missing package is absent",
                    !parseInstalled("Unable to find package: app.grom.server\n").present());

            check("battery: exemption detected",
                    parseBatteryExempt("user,app.grom.server,10123\n"));
            check("battery: other packages do not count",
                    !parseBatteryExempt("system-excidle,com.android.shell,2000\n"));

            check("label: model becomes a slug", labelFromModel("Pixel 7").equals("pixel-7"));
            check("label: underscores and spaces collapse",
                    labelFromModel("SM_G973F").equals("sm-g973f"));
            check("label: empty model falls back", labelFromModel("  ").equals("phone"));
        }

        // -- list ----------------------------------------------------------

        static void listTests() throws Exception {
            Path scen = scenario("list");
            devices(scen, """
                    List of devices attached
                    R58M12345    device model:Pixel_7 transport_id:1
                    R58M22222    device model:Pixel_7 transport_id:2
                    9A0X1234     unauthorized transport_id:3
                    5B1Y9999     offline transport_id:4

                    """);
            props(scen, "R58M12345", phone("Pixel 7", "14", 34, "arm64-v8a,armeabi-v7a"));
            props(scen, "R58M22222", phone("Pixel 7", "13", 33, "arm64-v8a"));
            props(scen, "9A0X1234", phone("Old", "10", 29, "armeabi-v7a"));
            packageState(scen, "R58M12345", "0.12.0-server29", 12);
            packageState(scen, "R58M22222", "0.12.0-server29", 12);
            battery(scen, "R58M12345", true);
            battery(scen, "R58M22222", false);

            String stateFile = scen.resolve("provision.json").toString();
            Captured c = invoke("list", "--state", stateFile);
            check("list: exits 0", c.code() == 0);
            check("list: ready phone reported", c.out().contains("0.12.0-server29"));
            check("list: unauthorised phone shown", c.out().contains("unauthorized"));
            check("list: offline phone shown", c.out().contains("offline"));
            check("list: battery state shown", c.out().contains("exempt") && c.out().contains("optimised"));

            Captured json = invoke("list", "--state", stateFile, "--json");
            check("list --json: parses", parseJsonOrNull(json.out()) instanceof Map<?, ?>
                    && json.out().startsWith("{\"version\":1"));
            check("list --json: carries the adb path", json.out().contains("\"adb\":"));

            Captured filtered = invoke("list", "--state", stateFile, "--serial", "R58M22222");
            check("list --serial: only the requested phone",
                    filtered.out().contains("R58M22222") && !filtered.out().contains("R58M12345"));

            Captured absent = invoke("list", "--state", stateFile, "--serial", "NOPE");
            check("list --serial unknown: note on stderr, no phone rows on stdout",
                    absent.err().contains("NOPE") && !absent.out().contains("R58M12345")
                            && absent.out().contains("No phones detected"));

            Captured noDevices = invoke("list", "--state", scen.resolve("empty.json").toString(),
                    "--serial", "NOPE");
            check("list with no phones: exits 0 with advice", noDevices.code() == 0);

            // A broken adb must say so, not look like "no phones connected".
            Path broken = scenario("list-broken");
            Files.writeString(broken.resolve("devices-fail"),
                    "error: could not install *smartsocket* listener\n", StandardCharsets.UTF_8);
            Captured brokenAdb = invoke("list", "--state", broken.resolve("provision.json").toString());
            check("list with a broken adb: exits 2 and quotes adb",
                    brokenAdb.code() == 2 && brokenAdb.err().contains("adb devices failed")
                            && brokenAdb.err().contains("smartsocket"));
            check("list with a broken adb: does not claim no phones",
                    !brokenAdb.out().contains("No phones detected"));
        }

        // -- plan ----------------------------------------------------------

        static void planTests() throws Exception {
            Path scen = scenario("plan");
            devices(scen, """
                    List of devices attached
                    R58M12345    device model:Pixel_7 transport_id:1
                    9A0X1234     unauthorized transport_id:2

                    """);
            props(scen, "R58M12345", phone("Pixel 7", "14", 34, "arm64-v8a"));
            battery(scen, "R58M12345", false);
            Path apk = apk(scen);
            Path stateFile = scen.resolve("provision.json");
            resetInvocations(scen);

            Captured c = invoke("plan", "--apk", apk.toString(), "--state", stateFile.toString(),
                    "--flavour", "server29");
            check("plan: exits 0", c.code() == 0);
            check("plan: proposes the install", c.out().contains("install " + apk.getFileName()));
            check("plan: proposes the battery exemption",
                    c.out().contains("exempt from battery optimisation"));
            check("plan: proposes a forward port", c.out().contains("forward port 41001"));
            check("plan: blocked phone explained", c.out().contains("blocked:")
                    && c.out().contains("unauthorized"));
            check("plan: writes no state file", !Files.exists(stateFile));

            String log = invocations(scen);
            check("plan: never installs", !log.contains("install "));
            check("plan: never changes the whitelist", !log.contains("whitelist +"));
        }

        // -- apply ---------------------------------------------------------

        static void applyTests() throws Exception {
            Path scen = scenario("apply");
            devices(scen, """
                    List of devices attached
                    R58M11111    device model:Pixel_7 transport_id:1
                    R58M22222    device model:Pixel_4a transport_id:2
                    9A0X99999    unauthorized transport_id:3

                    """);
            props(scen, "R58M11111", phone("Pixel 7", "14", 34, "arm64-v8a,armeabi-v7a"));
            props(scen, "R58M22222", phone("Pixel 4a", "13", 33, "arm64-v8a"));
            battery(scen, "R58M11111", false);
            battery(scen, "R58M22222", true);
            packageAfterInstall(scen, "R58M11111", "0.12.0-server29", 12);
            packageAfterInstall(scen, "R58M22222", "0.12.0-server29", 12);

            Path apk = apk(scen);
            Path stateFile = scen.resolve("provision.json");
            resetInvocations(scen);

            Captured first = invoke("apply", "--apk", apk.toString(), "--state", stateFile.toString(),
                    "--flavour", "server29");
            check("apply: a phone that needs attention makes the run non-zero", first.code() == 1);
            check("apply: says how many phones need attention",
                    first.err().contains("need attention"));
            check("apply: installed on the blank phone", first.out().contains("installed 0.12.0-server29"));
            check("apply: battery exemption requested and confirmed",
                    first.out().contains("exempt from battery optimisation"));
            check("apply: ports assigned in order",
                    first.out().contains("forward port 41001") && first.out().contains("forward port 41002"));
            check("apply: unauthorised phone skipped with advice",
                    first.out().contains("unauthorized") && first.out().contains("skipped"));
            check("apply: state file written", Files.isRegularFile(stateFile));

            String log = invocations(scen);
            check("apply: whitelist command used only for the phone that needed it",
                    log.contains("dumpsys deviceidle whitelist +app.grom.server"));

            // Second run must be a no-op: same APK hash, same ports.
            resetInvocations(scen);
            Captured second = invoke("apply", "--apk", apk.toString(), "--state", stateFile.toString(),
                    "--flavour", "server29");
            check("apply again: reports up to date", second.out().contains("up to date (0.12.0-server29)"));
            check("apply again: keeps the same ports",
                    second.out().contains("forward port 41001") && second.out().contains("forward port 41002"));
            check("apply again: does not install", !invocations(scen).contains("install "));
            check("apply again: does not repeat the whitelist request",
                    !invocations(scen).contains("whitelist +"));

            // --force reinstalls even though the hash matches.
            resetInvocations(scen);
            Captured forced = invoke("apply", "--apk", apk.toString(), "--state", stateFile.toString(),
                    "--flavour", "server29", "--force");
            check("apply --force: installs again", invocations(scen).contains("install "));
            check("apply --force: still reports the skipped phone",
                    forced.out().contains("[skipped"));

            // Wrong flavour must fail after reading the device back.
            Captured mismatch = invoke("apply", "--apk", apk.toString(), "--state", stateFile.toString(),
                    "--flavour", "peripheral26", "--force");
            check("apply: flavour mismatch detected", mismatch.code() == 1
                    && mismatch.out().contains("FLAVOUR_MISMATCH"));

            // A refused install is reported with a code and a hint.
            Path failing = scenario("apply-fail");
            devices(failing, """
                    List of devices attached
                    R58M33333    device model:Pixel_6 transport_id:1

                    """);
            props(failing, "R58M33333", phone("Pixel 6", "14", 34, "arm64-v8a"));
            battery(failing, "R58M33333", true);
            installOutput(failing, "R58M33333", "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]");
            Path failingApk = apk(failing);
            Captured failedApply = invoke("apply", "--apk", failingApk.toString(),
                    "--state", failing.resolve("provision.json").toString());
            check("apply: refused install is a failure, not a success", failedApply.code() == 1);
            check("apply: refused install keeps its code",
                    failedApply.out().contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"));
            check("apply: refusal explains what to do",
                    failedApply.out().contains("signed with another key"));

            // An install that prints Success but leaves nothing behind is caught.
            Path liar = scenario("apply-verify");
            devices(liar, """
                    List of devices attached
                    R58M44444    device model:Pixel_5 transport_id:1

                    """);
            props(liar, "R58M44444", phone("Pixel 5", "14", 34, "arm64-v8a"));
            battery(liar, "R58M44444", true);
            Path liarApk = apk(liar);
            Captured verify = invoke("apply", "--apk", liarApk.toString(),
                    "--state", liar.resolve("provision.json").toString());
            check("apply: unreported success becomes VERIFY_FAILED", verify.code() == 1
                    && verify.out().contains("VERIFY_FAILED"));

            // --no-battery must not touch the whitelist at all.
            Path quiet = scenario("apply-nobattery");
            devices(quiet, """
                    List of devices attached
                    R58M55555    device model:Pixel_8 transport_id:1

                    """);
            props(quiet, "R58M55555", phone("Pixel 8", "14", 34, "arm64-v8a"));
            battery(quiet, "R58M55555", false);
            packageState(quiet, "R58M55555", "0.12.0-server29", 12);
            Path quietApk = apk(quiet);
            resetInvocations(quiet);
            Captured noBattery = invoke("apply", "--apk", quietApk.toString(), "--state",
                    quiet.resolve("provision.json").toString(), "--no-battery");
            check("apply --no-battery: exits 0 when nothing needs attention", noBattery.code() == 0);
            check("apply --no-battery: never changes the whitelist",
                    !invocations(quiet).contains("whitelist +"));

            // A phone that cannot run the arm64 runtime is refused before install.
            Path wrongAbi = scenario("apply-abi");
            devices(wrongAbi, """
                    List of devices attached
                    R58M66666    device model:Old_Phone transport_id:1

                    """);
            props(wrongAbi, "R58M66666", phone("Old Phone", "10", 29, "armeabi-v7a,armeabi"));
            battery(wrongAbi, "R58M66666", true);
            Path abiApk = apk(wrongAbi);
            Captured abi = invoke("apply", "--apk", abiApk.toString(),
                    "--state", wrongAbi.resolve("provision.json").toString(),
                    "--flavour", "server29");
            check("apply: 32-bit phone refused with a reason", abi.code() == 1
                    && abi.out().contains("no-arm64"));

            // The same phone is a valid peripheral: that role never starts the
            // arm64 Node agent, so a 32-bit phone is fine there.
            packageAfterInstall(wrongAbi, "R58M66666", "0.12.0-peripheral26", 12);
            Captured abiPeripheral = invoke("apply", "--apk", abiApk.toString(),
                    "--state", wrongAbi.resolve("provision.json").toString(),
                    "--flavour", "peripheral26");
            check("apply: the same 32-bit phone is accepted as a peripheral",
                    abiPeripheral.code() == 0 && abiPeripheral.out().contains("[ready]"));

            // Nothing the tool ever runs may destroy device state. The words are
            // assembled from fragments on purpose: tools/test-policy bans them
            // from this file outright, so the guard can only be about what the
            // tool actually ran, never about what this comment says.
            Set<Path> all = Set.of(scen, failing, liar, quiet, wrongAbi);
            boolean destructive = false;
            for (Path dir : all) {
                String text = invocations(dir);
                for (String forbidden : List.of(
                        " un" + "install", " pm " + "clear", " re" + "boot",
                        "kil" + "l", "force-" + "stop")) {
                    if (text.contains(forbidden)) {
                        destructive = true;
                    }
                }
            }
            check("apply: no destructive command was ever issued", !destructive);
        }

        // -- emit ----------------------------------------------------------

        static void emitTests() throws Exception {
            Path scen = scenario("emit");
            devices(scen, """
                    List of devices attached
                    R58M77777    device model:Pixel_7 transport_id:1
                    R58M88888    device model:Pixel_4a transport_id:2

                    """);
            props(scen, "R58M77777", phone("Pixel 7", "14", 34, "arm64-v8a"));
            props(scen, "R58M88888", phone("Pixel 4a", "13", 33, "arm64-v8a"));
            battery(scen, "R58M77777", true);
            battery(scen, "R58M88888", true);
            packageState(scen, "R58M77777", "0.12.0-server29", 12);
            packageState(scen, "R58M88888", "0.12.0-server29", 12);
            Path apk = apk(scen);
            Path stateFile = scen.resolve("provision.json");

            Captured beforeApply = invoke("emit", "--state", stateFile.toString());
            check("emit: refuses before anything is provisioned", beforeApply.code() == 2);

            invoke("apply", "--apk", apk.toString(), "--state", stateFile.toString());
            Captured emitted = invoke("emit", "--state", stateFile.toString());
            check("emit: exits 0", emitted.code() == 0);
            check("emit: loopback roster listen", emitted.out().contains("\"listen\": \"127.0.0.1:47100\""));

            Object parsed = parseJsonOrNull(emitted.out());
            check("emit: output is valid JSON", parsed != null);
            boolean shapeOk = false;
            boolean urlOk = false;
            if (parsed instanceof Map<?, ?> map && map.get("devices") instanceof List<?> devicesList) {
                shapeOk = map.get("version") instanceof Long v && v == 1
                        && map.get("timeoutMs") instanceof Long t && t == 8000
                        && devicesList.size() == 2
                        && devicesList.get(0) instanceof Map<?, ?> row
                        && row.get("label") != null
                        && row.get("url") != null;
                if (devicesList.get(0) instanceof Map<?, ?> row) {
                    urlOk = "http://127.0.0.1:41001".equals(row.get("url"))
                            && "pixel-7".equals(row.get("label"));
                }
            }
            check("emit: hub.json keeps the fields grom-hub reads", shapeOk);
            check("emit: first phone gets the first loopback port", urlOk);

            Path hubFile = scen.resolve("out/hub.json");
            Captured written = invoke("emit", "--state", stateFile.toString(), "--hub", hubFile.toString());
            check("emit --hub: writes the file", written.code() == 0 && Files.isRegularFile(hubFile));
            String hubText = Files.isRegularFile(hubFile)
                    ? Files.readString(hubFile, StandardCharsets.UTF_8)
                    : "";
            check("emit --hub: file parses", parseJsonOrNull(hubText) != null);
        }

        // -- cli -----------------------------------------------------------

        // -- doctor -------------------------------------------------------

        static void appops(Path scen, String serial, String line) throws IOException {
            Files.createDirectories(scen.resolve("appops"));
            Files.writeString(scen.resolve("appops").resolve(serial), line + "\n",
                    StandardCharsets.UTF_8);
        }

        static void setting(Path scen, String serial, String key, String value)
                throws IOException {
            Files.createDirectories(scen.resolve("settings"));
            Files.writeString(scen.resolve("settings").resolve(serial + "." + key), value + "\n",
                    StandardCharsets.UTF_8);
        }

        static void phantom(Path scen, String serial, String value) throws IOException {
            Files.createDirectories(scen.resolve("phantom"));
            Files.writeString(scen.resolve("phantom").resolve(serial), value + "\n",
                    StandardCharsets.UTF_8);
        }

        static void doctorTests() throws Exception {
            Path scen = scenario("doctor");
            devices(scen, """
                    List of devices attached
                    R58M11111    device model:Pixel_7 transport_id:1
                    R58M22222    device model:Pixel_4a transport_id:2

                    """);
            props(scen, "R58M11111", phone("Pixel 7", "14", 34, "arm64-v8a"));
            props(scen, "R58M22222", phone("Pixel 4a", "9", 28, "arm64-v8a"));
            // Phone one is exempt and unrestricted; phone two is neither.
            battery(scen, "R58M11111", true);
            battery(scen, "R58M22222", false);
            appops(scen, "R58M11111", "app.grom.server: No operations.");
            appops(scen, "R58M22222", "RUN_ANY_IN_BACKGROUND: deny");
            phantom(scen, "R58M11111", "null");
            phantom(scen, "R58M22222", "null");
            setting(scen, "R58M11111", "wifi_sleep_policy", "2");
            setting(scen, "R58M22222", "wifi_sleep_policy", "0");
            Path stateFile = scen.resolve("provision.json");

            resetInvocations(scen);
            Captured read = invoke("doctor", "--state", stateFile.toString());
            // One phone is fine, the other is not: 1 is "a phone needs attention".
            check("doctor: exits 1 when a phone needs attention", read.code() == 1);
            check("doctor: names the phones", read.out().contains("R58M11111")
                    && read.out().contains("R58M22222"));
            // The column width is a formatting detail; what must be true is that
            // the row exists and never claims "unknown" for a phone that answered.
            check("doctor: reports the whitelist as measured",
                    read.out().contains("doze whitelist")
                            && !read.out().contains("doze whitelist  unknown"));
            check("doctor: reports a deny from appops",
                    read.out().contains("DOCTOR_APPOPS_DENIED"));
            check("doctor: reports a phone that is not whitelisted",
                    read.out().contains("DOCTOR_NOT_WHITELISTED"));
            check("doctor: legacy wifi finding only where it applies",
                    read.out().contains("DOCTOR_WIFI_SLEEP_NOT_ALWAYS"));

            String readOnly = invocations(scen);
            check("doctor: read-only run changes nothing",
                    !readOnly.contains("settings put")
                            && !readOnly.contains("+app.grom.server"));
            // Stronger than a word blacklist: every command the run issued must be
            // on the read allow-list, so a command nobody thought of cannot hide.
            List<String> unexpected = allowedInvocationsOnly(scen);
            check("doctor: every read-only command is on the allow-list" +
                    (unexpected.isEmpty() ? "" : " " + unexpected), unexpected.isEmpty());

            Captured json = invoke("doctor", "--state", stateFile.toString(), "--json");
            Object parsed = parseJsonOrNull(json.out());
            check("doctor: json parses", parsed != null);
            boolean jsonOk = false;
            if (parsed instanceof Map<?, ?> map && map.get("devices") instanceof List<?> list
                    && list.size() == 2 && list.get(0) instanceof Map<?, ?> row) {
                jsonOk = Boolean.TRUE.equals(row.get("dozeWhitelisted"))
                        && row.get("findings") instanceof List<?> findings
                        && findings.isEmpty();
            }
            check("doctor: the healthy phone has no findings", jsonOk);
            if (parsed instanceof Map<?, ?> map && map.get("devices") instanceof List<?> list
                    && list.size() == 2 && list.get(1) instanceof Map<?, ?> row) {
                check("doctor: the unhealthy phone is not whitelisted",
                        Boolean.FALSE.equals(row.get("dozeWhitelisted")));
                check("doctor: the unhealthy phone carries its appops finding",
                        row.get("findings") instanceof List<?> f
                                && f.contains("DOCTOR_APPOPS_DENIED"));
            }

            // -- --fix -----------------------------------------------------
            resetInvocations(scen);
            Captured fixed = invoke("doctor", "--state", stateFile.toString(), "--fix");
            String withFix = invocations(scen);
            check("doctor: --fix whitelists the package",
                    withFix.contains("dumpsys deviceidle whitelist +app.grom.server"));
            check("doctor: --fix reports what it attempted",
                    fixed.out().contains("DOCTOR_FIX_WHITELIST")
                            && fixed.out().contains("attempted: DOCTOR_FIX_WHITELIST")
                            && !fixed.out().contains("fixed: DOCTOR_FIX_WHITELIST"));
            // The appops deny must survive: --fix is not allowed to touch app-ops.
            check("doctor: --fix leaves app-ops alone",
                    !withFix.contains("appops set") && !withFix.contains("appops write"));
            // `--fix` is the only mutating path, and even it must stay inside the
            // same allow-list: two writes, nothing else.
            List<String> fixUnexpected = allowedInvocationsOnly(scen);
            check("doctor: --fix stays inside the allow-list" +
                    (fixUnexpected.isEmpty() ? "" : " " + fixUnexpected),
                    fixUnexpected.isEmpty());
            long wifiPuts = withFix.lines()
                    .filter(l -> l.contains("settings put global wifi_sleep_policy"))
                    .count();
            check("doctor: --fix writes the wifi policy only for API <= 28", wifiPuts == 1);

            Captured after = invoke("doctor", "--state", stateFile.toString(), "--json");
            boolean fixedRead = false;
            if (parseJsonOrNull(after.out()) instanceof Map<?, ?> map
                    && map.get("devices") instanceof List<?> list && list.size() == 2
                    && list.get(1) instanceof Map<?, ?> row) {
                fixedRead = Boolean.TRUE.equals(row.get("dozeWhitelisted"));
            }
            check("doctor: the whitelist change is verified, not assumed", fixedRead);

            // -- an unanswered command --------------------------------------
            Path silent = scenario("doctor-silent");
            devices(silent, """
                    List of devices attached
                    R58M33333    device model:Pixel_4 transport_id:1

                    """);
            props(silent, "R58M33333", phone("Pixel 4", "13", 33, "arm64-v8a"));
            Files.writeString(silent.resolve("battery").resolve("R58M33333"), "",
                    StandardCharsets.UTF_8);
            Captured unknown = invoke("doctor", "--state", silent.resolve("p.json").toString(),
                    "--json");
            boolean unknownOk = false;
            if (parseJsonOrNull(unknown.out()) instanceof Map<?, ?> map
                    && map.get("devices") instanceof List<?> list && list.size() == 1
                    && list.get(0) instanceof Map<?, ?> row) {
                // A phone that answers nothing is "unknown", never a measured 0.
                unknownOk = row.containsKey("dozeWhitelisted")
                        && row.get("dozeWhitelisted") == null
                        && row.containsKey("appopsBackground");
            }
            check("doctor: an unanswered command never becomes a number", unknownOk);

            // -- no phones --------------------------------------------------
            Path empty = scenario("doctor-empty");
            devices(empty, "List of devices attached\n\n");
            Captured none = invoke("doctor", "--state", empty.resolve("p.json").toString());
            check("doctor: no phones is exit 2, not a silent success", none.code() == 2);
        }

        static void cliTests() {
            Captured help = invoke("--help");
            check("cli: --help exits 0", help.code() == 0 && help.out().contains("grom-provision"));
            Captured version = invoke("--version");
            check("cli: --version prints the version", version.out().contains(VERSION));
            Captured unknown = invoke("frobnicate");
            check("cli: unknown command exits 2", unknown.code() == 2);
            Captured noApk = invoke("apply");
            check("cli: apply without --apk exits 2", noApk.code() == 2 && noApk.err().contains("--apk"));
            Captured missingApk = invoke("apply", "--apk", "/nonexistent/grom.apk");
            check("cli: missing APK exits 2", missingApk.code() == 2);
            Captured badFlavour = invoke("apply", "--apk", "/dev/null", "--flavour", "wat");
            check("cli: unknown flavour exits 2", badFlavour.code() == 2);
            Captured badPort = invoke("list", "--ports", "80");
            check("cli: port outside the tunnel block exits 2", badPort.code() == 2);
            Captured badAdb = invoke("list", "--adb", "/nonexistent/adb");
            check("cli: missing adb binary exits 2", badAdb.code() == 2);
            Cli inline = null;
            try {
                inline = Cli.parse(new String[] {"apply", "--apk=/tmp/x.apk", "--state=/tmp/s.json"});
            } catch (ToolException e) {
                // Left null; the assertion below catches it.
            }
            check("cli: --opt=value form accepted", inline != null
                    && "/tmp/x.apk".equals(inline.apk) && "/tmp/s.json".equals(inline.stateFile));
        }

        static Object parseJsonOrNull(String text) {
            try {
                return new JsonParser(text).parse();
            } catch (ToolException e) {
                return null;
            }
        }
    }
}
