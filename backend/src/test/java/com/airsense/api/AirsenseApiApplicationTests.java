package com.airsense.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
		"historical.ingestion-enabled=false",
		"forecast.engine.evaluation-enabled=false",
		"logging.level.org.springframework.web=INFO",
		"logging.level.org.springframework.web.HttpLogging=INFO"
})
class AirsenseApiApplicationTests {

	private final ApplicationContext context;

	@Autowired
	AirsenseApiApplicationTests(ApplicationContext context) {
		this.context = context;
	}

	@Test
	void contextLoads() {
	}

	@Test
	void legacyForecastBatchPathIsDisabledByDefault() {
		assertThat(context.getBeansOfType(com.airsense.api.ingestion.ForecastOrchestrator.class)).isEmpty();
		assertThat(context.getBeansOfType(com.airsense.api.services.ForecastClient.class)).isEmpty();
		assertThat(context.getBeansOfType(com.airsense.api.forecast.ForecastModel.class)).isEmpty();
	}

}


