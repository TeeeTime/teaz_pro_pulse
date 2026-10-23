package pro.teaz.pulse.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class LoadSummaryTests {

	@Test
	void keepsOnlyTheServerAndTheTeazTotals() {
		Instant at = Instant.parse("2026-10-23T12:00:00Z");
		MetricsView.Point point = new MetricsView.Point(at, 25, 8, 7000, 1600);
		MetricsView metrics = new MetricsView(at, 10,
				new MetricsView.Host(4, 25.0, new double[] {1, 1, 1}, 7000, 16000, 40, 100),
				new MetricsView.Usage(8.0, 1600),
				new MetricsView.Usage(17.0, 5400),
				List.of(new MetricsView.AppUsage("Portal", 8.0, 1600, List.of())),
				List.of(point));

		LoadSummary load = LoadSummary.of(metrics, Duration.ofHours(1));

		assertThat(load.sampledAt()).isEqualTo(at);
		assertThat(load.intervalSeconds()).isEqualTo(10);
		assertThat(load.historySeconds()).isEqualTo(3600);
		assertThat(load.cores()).isEqualTo(4);
		assertThat(load.memoryTotalBytes()).isEqualTo(16000);
		assertThat(load.host()).isEqualTo(new MetricsView.Usage(25.0, 7000));
		assertThat(load.teaz()).isEqualTo(new MetricsView.Usage(8.0, 1600));
		assertThat(load.history()).containsExactly(point);
	}
}
