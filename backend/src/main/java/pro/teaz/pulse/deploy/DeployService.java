package pro.teaz.pulse.deploy;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import pro.teaz.pulse.docker.DockerClient;
import pro.teaz.pulse.docker.DockerContainer;

/**
 * Deploys of whole compose projects. Checked here for clear messages, and
 * again by the deployer, which decides for itself from Docker and is the one
 * that counts: only projects with managed containers, none of them protected.
 */
@Service
public class DeployService {

	private static final Logger log = LoggerFactory.getLogger(DeployService.class);

	/** Compose's own rule for project names. */
	private static final Pattern PROJECT_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");

	private final DockerClient docker;
	private final Deployer deployer;

	public DeployService(DockerClient docker, Deployer deployer) {
		this.docker = docker;
		this.deployer = deployer;
	}

	public DeployJob deploy(String project, String user) {
		List<DockerContainer> containers = containersOf(project);
		if (containers.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a managed compose project: " + project);
		}
		if (containers.stream().anyMatch(DockerContainer::protectedContainer)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					project + " has protected containers and can't be deployed from Pulse.");
		}
		log.info("Deploy of {} started by {}", project, user);
		return deployer.start(project, user);
	}

	public DeployJob job(String project) {
		checkName(project);
		return deployer.job(project).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
				"No deploy of " + project + " since the deployer started."));
	}

	public List<DeployJob> jobs() {
		return deployer.jobs();
	}

	private List<DockerContainer> containersOf(String project) {
		checkName(project);
		return docker.listManagedContainers().stream().filter(c -> project.equals(c.project())).toList();
	}

	private static void checkName(String project) {
		if (!PROJECT_NAME.matcher(project).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a compose project name: " + project);
		}
	}
}
