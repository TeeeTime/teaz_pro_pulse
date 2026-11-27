import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Pulse's deployer: runs {@code git pull --ff-only} and {@code docker compose up -d --build}
 * for one compose project, on request of the Pulse backend. See "Deploy" in the README.
 *
 * <p>It holds the full Docker socket and write access to the apps' folders, so it trusts
 * nothing it is sent except a project name. Everything else comes from Docker: which
 * folder and compose files the project was started from (compose's own labels), whether
 * Pulse manages it ({@code pro.teaz.pulse.app}) and whether it is protected. No shell is
 * involved; commands run with fixed arguments.
 *
 * <p>API (only reachable from {@code ALLOW_FROM}, the backend):
 * <ul>
 * <li>{@code POST /deploys/{project}}: start; 202 with the job, 409 while any deploy runs</li>
 * <li>{@code GET /deploys/{project}}: that project's last job, 404 if none since start</li>
 * <li>{@code GET /deploys}: every project's last job</li>
 * </ul>
 * Jobs live in memory only. JDK only, no dependencies.
 */
public class Deployer {

	static final String APP_LABEL = "pro.teaz.pulse.app";
	static final String PROTECTED_LABEL = "pro.teaz.pulse.protected";
	static final String PROJECT_LABEL = "com.docker.compose.project";
	static final String WORKING_DIR_LABEL = "com.docker.compose.project.working_dir";
	static final String CONFIG_FILES_LABEL = "com.docker.compose.project.config_files";

	/** What compose itself allows as a project name. Checked before it goes anywhere near a command. */
	static final Pattern PROJECT_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");

	static final int MAX_LOG_CHARS = 200_000;
	static final String USER_HEADER = "X-Pulse-User";

	private final Path root;
	private final String allowFrom;
	private final long timeoutMinutes;
	private final Map<String, Job> jobs = new ConcurrentHashMap<>();
	/** Project currently deploying; one at a time, builds are heavy on a small server. */
	private final AtomicReference<String> running = new AtomicReference<>();

	Deployer(Path root, String allowFrom, long timeoutMinutes) {
		this.root = root;
		this.allowFrom = allowFrom;
		this.timeoutMinutes = timeoutMinutes;
	}

	public static void main(String[] args) throws IOException {
		String rootEnv = System.getenv("DEPLOY_ROOT");
		if (rootEnv == null || rootEnv.isBlank()) {
			System.err.println("DEPLOY_ROOT is not set");
			System.exit(1);
		}
		Path root = Path.of(rootEnv).toAbsolutePath().normalize();
		String allowFrom = env("ALLOW_FROM", "backend");
		long timeout = Long.parseLong(env("DEPLOY_TIMEOUT_MINUTES", "20"));
		int port = Integer.parseInt(env("PORT", "8080"));

		Deployer deployer = new Deployer(root, allowFrom, timeout);
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/deploys", deployer::handle);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		log("listening on :" + port + ", apps under " + root + ", requests only from " + allowFrom);
	}

	// ---- HTTP ----

	void handle(HttpExchange ex) throws IOException {
		try {
			if (!fromAllowedHost(ex)) {
				send(ex, 403, problem("Only " + allowFrom + " may use the deployer."));
				return;
			}
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			if (path.equals("/deploys") || path.equals("/deploys/")) {
				if (!method.equals("GET")) {
					send(ex, 405, problem("Method not allowed."));
					return;
				}
				List<String> all = jobs.values().stream().map(Job::json).toList();
				send(ex, 200, "[" + String.join(",", all) + "]");
				return;
			}
			String project = path.substring("/deploys/".length());
			if (!PROJECT_NAME.matcher(project).matches()) {
				send(ex, 400, problem("Not a compose project name."));
				return;
			}
			switch (method) {
				case "GET" -> {
					Job job = jobs.get(project);
					if (job == null) send(ex, 404, problem("No deploy of " + project + " since the deployer started."));
					else send(ex, 200, job.json());
				}
				case "POST" -> {
					String user = sanitize(ex.getRequestHeaders().getFirst(USER_HEADER));
					Result result = start(project, user);
					send(ex, result.status(), result.body());
				}
				default -> send(ex, 405, problem("Method not allowed."));
			}
		} catch (RuntimeException e) {
			log("request failed: " + e);
			send(ex, 500, problem("Deployer error: " + e.getMessage()));
		} finally {
			ex.close();
		}
	}

	/** The internal network only holds the backend and the socket proxy, but check anyway. */
	private boolean fromAllowedHost(HttpExchange ex) {
		InetAddress remote = ex.getRemoteAddress().getAddress();
		try {
			for (InetAddress allowed : InetAddress.getAllByName(allowFrom)) {
				if (allowed.equals(remote)) return true;
			}
		} catch (UnknownHostException e) {
			// backend not running: nobody is allowed
		}
		return false;
	}

	record Result(int status, String body) {
	}

	Result start(String project, String user) {
		Plan plan;
		try {
			plan = plan(project, inspect(project), root);
		} catch (Refused e) {
			return new Result(e.status, problem(e.getMessage()));
		}
		if (!running.compareAndSet(null, project)) {
			return new Result(409, problem("A deploy of " + running.get() + " is still running."));
		}
		Job job = new Job(project, user);
		jobs.put(project, job);
		log("deploy of " + project + " in " + plan.dir() + " started by " + user);
		Thread.ofVirtual().name("deploy-" + project).start(() -> {
			try {
				run(job, plan);
			} finally {
				running.set(null);
				log("deploy of " + project + " " + job.state + (job.exitCode != null ? " (exit " + job.exitCode + ")" : ""));
			}
		});
		return new Result(202, job.json());
	}

	// ---- Deciding what to run ----

	/** A request we refuse, with the HTTP status to answer. */
	static class Refused extends Exception {
		private static final long serialVersionUID = 1L;

		final int status;

		Refused(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	/** One container of the project, as far as the deployer cares. */
	record Row(String app, String protectedLabel, String workingDir, String configFiles) {
	}

	/** What will run: in which folder, with which compose files. */
	record Plan(String project, Path dir, List<Path> configFiles) {

		List<List<String>> commands() {
			List<String> compose = new ArrayList<>(List.of("docker", "compose", "-p", project));
			for (Path file : configFiles) {
				compose.add("-f");
				compose.add(file.toString());
			}
			compose.addAll(List.of("up", "-d", "--build"));
			return List.of(List.of("git", "pull", "--ff-only"), compose);
		}
	}

	/** Asks Docker for every container of the project, running or not, with the labels that matter. */
	List<Row> inspect(String project) throws Refused {
		// Real tabs between the fields; label values never contain one.
		String format = String.join("\t",
				"{{.Label \"" + APP_LABEL + "\"}}",
				"{{.Label \"" + PROTECTED_LABEL + "\"}}",
				"{{.Label \"" + WORKING_DIR_LABEL + "\"}}",
				"{{.Label \"" + CONFIG_FILES_LABEL + "\"}}");
		List<String> command = List.of("docker", "ps", "-a", "--no-trunc",
				"--filter", "label=" + PROJECT_LABEL + "=" + project, "--format", format);
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
				process.destroyForcibly();
				throw new Refused(503, "Docker not available: " + output.strip());
			}
			List<Row> rows = new ArrayList<>();
			for (String line : output.split("\n")) {
				if (line.isBlank()) continue;
				String[] f = Arrays.copyOf(line.split("\t", -1), 4);
				rows.add(new Row(blankToNull(f[0]), blankToNull(f[1]), blankToNull(f[2]), blankToNull(f[3])));
			}
			return rows;
		} catch (IOException e) {
			throw new Refused(503, "Docker not available: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Refused(503, "Interrupted.");
		}
	}

	/**
	 * Pure decision, no I/O apart from resolving symlinks: refuses projects Pulse
	 * doesn't manage, protected ones (Pulse itself, Traefik, Keycloak), and any folder
	 * or compose file outside the deploy root.
	 */
	static Plan plan(String project, List<Row> rows, Path root) throws Refused {
		if (!PROJECT_NAME.matcher(project).matches()) throw new Refused(400, "Not a compose project name.");
		if (rows.isEmpty()) throw new Refused(404, "No containers of compose project " + project + ".");
		if (rows.stream().noneMatch(r -> r.app() != null)) {
			throw new Refused(404, project + " is not managed by Pulse (no " + APP_LABEL + " label).");
		}
		if (rows.stream().anyMatch(r -> "true".equalsIgnoreCase(r.protectedLabel()))) {
			throw new Refused(403, project + " has protected containers and can't be deployed from Pulse.");
		}
		Set<String> dirs = new LinkedHashSet<>();
		Set<String> configs = new LinkedHashSet<>();
		for (Row r : rows) {
			dirs.add(r.workingDir());
			configs.add(r.configFiles());
		}
		if (dirs.size() != 1 || dirs.contains(null)) {
			throw new Refused(409, project + "'s containers don't agree on its folder: " + dirs);
		}
		if (configs.size() != 1) {
			throw new Refused(409, project + "'s containers don't agree on its compose files: " + configs);
		}

		Path dir = inside(root, dirs.iterator().next(), "folder");
		List<Path> files = new ArrayList<>();
		String configLabel = configs.iterator().next();
		if (configLabel != null) {
			for (String file : configLabel.split(",")) {
				if (!file.isBlank()) files.add(inside(root, file.strip(), "compose file"));
			}
		}
		return new Plan(project, dir, files);
	}

	/** {@code value} as an absolute path strictly below root, after resolving {@code ..} and symlinks. */
	static Path inside(Path root, String value, String what) throws Refused {
		Path path = Path.of(value);
		if (!path.isAbsolute()) throw new Refused(409, "The " + what + " " + value + " is not an absolute path.");
		path = path.normalize();
		Path realRoot = root;
		try {
			if (Files.exists(path)) path = path.toRealPath();
			if (Files.exists(root)) realRoot = root.toRealPath();
		} catch (IOException e) {
			throw new Refused(409, "Can't resolve " + value + ": " + e.getMessage());
		}
		if (!path.startsWith(realRoot) || path.equals(realRoot)) {
			throw new Refused(403, "The " + what + " " + value + " is outside " + root + ".");
		}
		if (!Files.exists(path)) {
			throw new Refused(409, "The " + what + " " + value + " doesn't exist (mounted at the same path?).");
		}
		return path;
	}

	// ---- Running ----

	void run(Job job, Plan plan) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes);
		for (List<String> command : plan.commands()) {
			job.append("$ " + String.join(" ", command) + "\n");
			try {
				ProcessBuilder builder = new ProcessBuilder(command)
						.directory(plan.dir().toFile())
						.redirectErrorStream(true);
				Map<String, String> env = builder.environment();
				// Never wait for a password prompt nobody can answer.
				env.put("GIT_TERMINAL_PROMPT", "0");
				env.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
				// Readable log instead of terminal animations.
				env.put("COMPOSE_ANSI", "never");
				env.put("BUILDKIT_PROGRESS", "plain");
				env.put("COMPOSE_PROGRESS", "plain");
				Process process = builder.start();
				process.getOutputStream().close();
				Thread reader = Thread.ofVirtual().start(() -> copy(process, job));
				long left = deadline - System.nanoTime();
				if (!process.waitFor(Math.max(left, 0), TimeUnit.NANOSECONDS)) {
					process.descendants().forEach(ProcessHandle::destroyForcibly);
					process.destroyForcibly();
					reader.join(5_000);
					job.append("\nTimed out after " + timeoutMinutes + " minutes.\n");
					job.finish("failed", -1);
					return;
				}
				reader.join(5_000);
				int exit = process.exitValue();
				if (exit != 0) {
					job.finish("failed", exit);
					return;
				}
			} catch (IOException e) {
				job.append(e.getMessage() + "\n");
				job.finish("failed", -1);
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				job.finish("failed", -1);
				return;
			}
		}
		job.finish("succeeded", 0);
	}

	private static void copy(Process process, Job job) {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			char[] buffer = new char[4096];
			int n;
			while ((n = in.read(buffer)) > 0) job.append(new String(buffer, 0, n));
		} catch (IOException e) {
			// process killed
		}
	}

	/** One deploy. The log keeps the last {@value #MAX_LOG_CHARS} characters. */
	static final class Job {
		final String project;
		final String startedBy;
		final Instant startedAt = Instant.now();
		volatile String state = "running";
		volatile Instant finishedAt;
		volatile Integer exitCode;
		private final StringBuilder log = new StringBuilder();

		Job(String project, String startedBy) {
			this.project = project;
			this.startedBy = startedBy;
		}

		synchronized void append(String text) {
			log.append(text);
			if (log.length() > MAX_LOG_CHARS) log.delete(0, log.length() - MAX_LOG_CHARS);
		}

		synchronized void finish(String state, int exitCode) {
			this.exitCode = exitCode;
			this.finishedAt = Instant.now();
			this.state = state;
		}

		synchronized String json() {
			return "{" + String.join(",",
					field("project", project),
					field("state", state),
					field("startedBy", startedBy),
					field("startedAt", startedAt.toString()),
					field("finishedAt", finishedAt != null ? finishedAt.toString() : null),
					"\"exitCode\":" + exitCode,
					field("log", log.toString())) + "}";
		}
	}

	// ---- Helpers ----

	static String problem(String detail) {
		return "{" + field("detail", detail) + "}";
	}

	static String field(String name, String value) {
		return quote(name) + ":" + (value == null ? "null" : quote(value));
	}

	static String quote(String s) {
		StringBuilder b = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
					else b.append(c);
				}
			}
		}
		return b.append('"').toString();
	}

	/** The backend's user name for logs; printable, short. */
	static String sanitize(String user) {
		if (user == null || user.isBlank()) return "unknown";
		String clean = user.replaceAll("[^\\p{Alnum}@._ -]", "");
		return clean.length() > 64 ? clean.substring(0, 64) : clean;
	}

	private static void send(HttpExchange ex, int status, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, body.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}

	private static void log(String message) {
		System.out.println(Instant.now() + " " + message);
	}
}
