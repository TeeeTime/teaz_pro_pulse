package pro.teaz.pulse.deploy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local-dev stand-in for {@link DeployerClient}: plays a made-up deploy over a
 * few seconds, so the UI's log and states can be tried without Docker. Demo's
 * deploy fails (git can't fast-forward), every other one succeeds. Used
 * whenever {@code pulse.deployer.url} is unset.
 */
public class MockDeployer implements Deployer {

	private final Map<String, DeployJob> jobs = new ConcurrentHashMap<>();
	private final AtomicReference<String> running = new AtomicReference<>();
	private final long stepMillis;

	public MockDeployer() {
		this(700);
	}

	MockDeployer(long stepMillis) {
		this.stepMillis = stepMillis;
	}

	@Override
	public DeployJob start(String project, String user) {
		if (!running.compareAndSet(null, project)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A deploy of " + running.get() + " is still running.");
		}
		DeployJob job = new DeployJob(project, "running", user, Instant.now(), null, null, "");
		jobs.put(project, job);
		Thread.ofVirtual().start(() -> play(project));
		return job;
	}

	private void play(String project) {
		try {
			append(project, "$ git pull --ff-only\n");
			if (project.contains("demo")) {
				append(project, "hint: Diverging branches can't be fast-forwarded.\n");
				append(project, "fatal: Not possible to fast-forward, aborting.\n");
				finish(project, "failed", 128);
				return;
			}
			append(project, "Updating 6facd9c..1b2c3d4\nFast-forward\n frontend/src/App.vue | 4 ++--\n"
					+ " 1 file changed, 2 insertions(+), 2 deletions(-)\n");
			append(project, "$ docker compose -p " + project + " -f /srv/" + project + "/compose.yml up -d --build\n");
			append(project, "#1 [backend internal] load build definition from Dockerfile\n#1 DONE 0.0s\n");
			append(project, "#2 [backend build 5/6] RUN mvn -q dependency:go-offline\n#2 CACHED\n");
			append(project, "#3 [backend build 6/6] RUN mvn -q package -DskipTests\n#3 DONE 41.2s\n");
			append(project, "#4 [frontend build 4/4] RUN npm run build\n#4 DONE 12.8s\n");
			append(project, " Container " + project + "-backend-1  Recreate\n Container " + project + "-backend-1  Started\n");
			finish(project, "succeeded", 0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			finish(project, "failed", -1);
		} finally {
			running.set(null);
		}
	}

	private void append(String project, String text) throws InterruptedException {
		Thread.sleep(stepMillis);
		jobs.computeIfPresent(project, (p, j) -> new DeployJob(p, j.state(), j.startedBy(), j.startedAt(),
				j.finishedAt(), j.exitCode(), j.log() + text));
	}

	private void finish(String project, String state, int exitCode) {
		jobs.computeIfPresent(project, (p, j) -> new DeployJob(p, state, j.startedBy(), j.startedAt(),
				Instant.now(), exitCode, j.log()));
	}

	@Override
	public Optional<DeployJob> job(String project) {
		return Optional.ofNullable(jobs.get(project));
	}

	@Override
	public List<DeployJob> jobs() {
		return List.copyOf(jobs.values());
	}
}
