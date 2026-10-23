package pro.teaz.pulse.docker;

import java.util.List;

/**
 * The Docker operations Pulse needs, nothing more. {@link DockerEngineClient}
 * talks to the real engine (through the socket proxy), {@link MockDockerClient}
 * serves fake containers for local dev. See {@link DockerConfig}.
 */
public interface DockerClient {

	/** All containers carrying {@link DockerContainer#APP_LABEL}, running or not. */
	List<DockerContainer> listManagedContainers();

	/** Current resource counters of a running container. */
	ContainerStats stats(String id);

	void start(String id);

	void stop(String id);

	void restart(String id);
}
