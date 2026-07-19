plugins {
	java
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "personal.knowledge"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "1.1.8"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springframework.ai:spring-ai-advisors-vector-store")
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
	implementation("org.hibernate.orm:hibernate-vector:6.6.53.Final")
	implementation("org.apache.pdfbox:pdfbox:3.0.5")
	implementation("org.jsoup:jsoup:1.18.3")
	implementation("org.apache.httpcomponents.client5:httpclient5")
	compileOnly("org.projectlombok:lombok")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

tasks.test {
	description = "Runs the fast unit and web tests."
	useJUnitPlatform {
		excludeTags("integration", "live-openai")
	}
}

val integrationTest by tasks.registering(Test::class) {
	description = "Runs container-backed integration tests with PostgreSQL and pgvector."
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("integration")
		excludeTags("live-openai")
	}
	shouldRunAfter(tasks.test)
}

val liveOpenAiTest by tasks.registering(Test::class) {
	description = "Runs optional tests that call the live OpenAI API."
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("live-openai")
	}
	shouldRunAfter(integrationTest)
}

tasks.check {
	dependsOn(integrationTest)
}
