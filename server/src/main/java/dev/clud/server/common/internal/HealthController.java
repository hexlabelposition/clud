package dev.clud.server.common.internal;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports whether the Server can serve requests. Used by the Web app, Docker
 * healthchecks and monitoring.
 */
@RestController
class HealthController {

	private static final int DATABASE_TIMEOUT_SECONDS = 2;

	private final DataSource dataSource;

	HealthController(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@GetMapping("/api/v1/health")
	ResponseEntity<Health> health() {
		if (!isDatabaseAvailable()) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Health(Status.DOWN));
		}
		return ResponseEntity.ok(new Health(Status.UP));
	}

	private boolean isDatabaseAvailable() {
		try (Connection connection = dataSource.getConnection()) {
			return connection.isValid(DATABASE_TIMEOUT_SECONDS);
		} catch (SQLException ex) {
			return false;
		}
	}

	record Health(Status status) {
	}

	enum Status {
		UP, DOWN
	}

}
