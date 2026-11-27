package pro.teaz.pulse.deploy;

import java.util.List;
import java.util.Optional;

/**
 * Deploys compose projects. {@link DeployerClient} talks to the deployer
 * container (see compose.yml), {@link MockDeployer} pretends to for local dev.
 * See {@link DeployConfig}.
 */
public interface Deployer {

	/** Starts a deploy and returns at once; one deploy at a time across all projects. */
	DeployJob start(String project, String user);

	/** The project's last deploy since the deployer started. */
	Optional<DeployJob> job(String project);

	/** Every project's last deploy since the deployer started. */
	List<DeployJob> jobs();
}
