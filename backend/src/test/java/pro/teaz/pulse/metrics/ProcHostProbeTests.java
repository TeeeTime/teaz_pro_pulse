package pro.teaz.pulse.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcHostProbeTests {

	@TempDir
	Path proc;

	@Test
	void readsCpuMemoryAndLoad() throws IOException {
		Files.writeString(proc.resolve("stat"), """
				cpu  1000 50 400 8000 200 10 40 0 300 0
				cpu0 500 25 200 4000 100 5 20 0 150 0
				cpu1 500 25 200 4000 100 5 20 0 150 0
				intr 12345
				ctxt 67890
				""");
		Files.writeString(proc.resolve("meminfo"), """
				MemTotal:        8000000 kB
				MemFree:          500000 kB
				MemAvailable:    6000000 kB
				Buffers:          100000 kB
				""");
		Files.writeString(proc.resolve("loadavg"), "0.52 0.41 0.30 2/345 6789\n");

		HostSample sample = new ProcHostProbe(proc, proc).sample();

		// total = user..steal = 9700 (guest is already in user); idle + iowait = 8200
		assertThat(sample.cpuBusyTicks()).isEqualTo(1500);
		assertThat(sample.cpuTotalTicks()).isEqualTo(9700);
		assertThat(sample.cores()).isEqualTo(2);
		assertThat(sample.memoryTotal()).isEqualTo(8_000_000L * 1024);
		assertThat(sample.memoryUsed()).isEqualTo(2_000_000L * 1024);
		assertThat(sample.load()).containsExactly(0.52, 0.41, 0.30);
		assertThat(sample.diskTotal()).isPositive();
		assertThat(sample.diskUsed()).isBetween(0L, sample.diskTotal());
	}

	@Test
	void failsClearlyWithoutProc() {
		assertThatThrownBy(() -> new ProcHostProbe(proc.resolve("missing"), proc).sample())
				.isInstanceOf(UncheckedIOException.class);
	}
}
