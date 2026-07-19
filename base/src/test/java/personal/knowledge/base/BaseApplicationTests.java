package personal.knowledge.base;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import personal.knowledge.base.support.PgVectorContainerTest;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key-not-used")
class BaseApplicationTests extends PgVectorContainerTest {

	@Test
	void contextLoads() {
	}

}
