package pro.teaz.pulse.containers;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import pro.teaz.pulse.docker.DockerContainer;

/** One container in {@code GET /api/containers}. */
public record ContainerView(
		String id,
		String name,
		String app,
		String service,
		String image,
		String state,
		String health,
		Status status,
		Instant startedAt,
		Instant finishedAt,
		int restartCount,
		@JsonProperty("protected") boolean protectedContainer) {

	static ContainerView of(DockerContainer c) {
		return new ContainerView(c.id(), c.name(), c.app(), c.service(), c.image(), c.state(), c.health(),
				Status.of(c), c.startedAt(), c.finishedAt(), c.restartCount(), c.protectedContainer());
	}
}
