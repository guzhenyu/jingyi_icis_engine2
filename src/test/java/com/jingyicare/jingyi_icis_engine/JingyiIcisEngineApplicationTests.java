package com.jingyicare.jingyi_icis_engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

class JingyiIcisEngineApplicationTests extends TestsBase {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PostgreSQLContainer<?> postgresContainer;

	@Test
	void contextLoads() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
			assertEquals(postgresContainer.getJdbcUrl(), connection.getMetaData().getURL());
			assertEquals(postgresContainer.getDatabaseName(), connection.getCatalog());
		}
	}

}
