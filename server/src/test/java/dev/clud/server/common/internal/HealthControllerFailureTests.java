package dev.clud.server.common.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

class HealthControllerFailureTests {

	private final DataSource dataSource = mock(DataSource.class);
	private final MockMvcTester mvc = MockMvcTester.of(new HealthController(dataSource));

	@Test
	void reportsDownWhenConnectionCannotBeObtained() throws SQLException {
		when(dataSource.getConnection()).thenThrow(new SQLException("Database is unavailable"));

		assertThat(mvc.get().uri("/api/v1/health"))
				.hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
				.hasContentType(MediaType.APPLICATION_JSON)
				.bodyJson().isEqualTo("{\"status\":\"DOWN\"}");
	}

	@Test
	void reportsDownWhenConnectionIsInvalid() throws SQLException {
		Connection connection = mock(Connection.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(2)).thenReturn(false);

		assertThat(mvc.get().uri("/api/v1/health"))
				.hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
				.hasContentType(MediaType.APPLICATION_JSON)
				.bodyJson().isEqualTo("{\"status\":\"DOWN\"}");

		verify(connection).close();
	}

}
