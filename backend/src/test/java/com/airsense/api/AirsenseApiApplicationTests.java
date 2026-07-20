package com.airsense.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"historical.ingestion-enabled=false",
		"forecast.engine.evaluation-enabled=false",
		"logging.level.org.springframework.web=INFO",
		"logging.level.org.springframework.web.HttpLogging=INFO"
})
class AirsenseApiApplicationTests {

	@Test
	void contextLoads() {
	}

}


