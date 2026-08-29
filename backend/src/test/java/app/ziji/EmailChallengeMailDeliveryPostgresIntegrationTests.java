package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.ziji.auth.application.EmailChallengeOutboxConsumer;
import app.ziji.auth.application.EncryptedCodeEnvelope;
import app.ziji.auth.application.EnvelopeDecryptor;
import app.ziji.auth.domain.EmailChallengePurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 PostgreSQL + Mailpit 验收：HTTP 签发挑战后，EMAIL 消费者必须把解密后的验证码投递到 SMTP，
 * 而不是停留在 outbox 或被测试 Mock 掉。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EmailChallengeMailDeliveryPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Pattern CODE_IN_HTML = Pattern.compile(">([0-9]{6})<");
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(3))
		.build();

	@Container
	static final GenericContainer<?> MAILPIT = new GenericContainer<>(
		DockerImageName.parse("axllent/mailpit:v1.27.8"))
		.withExposedPorts(1025, 8025)
		.waitingFor(Wait.forListeningPort());

	@DynamicPropertySource
	static void mailpitSmtp(DynamicPropertyRegistry registry) {
		registry.add("spring.mail.host", MAILPIT::getHost);
		registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EmailChallengeOutboxConsumer consumer;

	@Autowired
	private EnvelopeDecryptor decryptor;

	@Autowired
	private ApplicationContext applicationContext;

	@BeforeEach
	void clearEmailFactsAndInbox() throws Exception {
		jdbc.update("TRUNCATE TABLE outbox_consumer_receipts, outbox_events, email_challenges, auth_rate_limit_buckets");
		HTTP.send(HttpRequest.newBuilder(mailpitUri("/api/v1/messages"))
			.DELETE()
			.build(), HttpResponse.BodyHandlers.discarding());
	}

	@Test
	void httpIssuedRegistrationChallengeIsDecryptedAndDeliveredToMailpit() throws Exception {
		assertTrue(applicationContext.getBeansOfType(TaskScheduler.class).isEmpty());
		String email = "mail-" + UUID.randomUUID() + "@example.test";

		mvc.perform(post("/api/v1/auth/registration-challenges")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"deviceId\":\"mailpit-device\"}"))
			.andExpect(status().isAccepted());

		UUID eventId = jdbc.queryForObject(
			"SELECT id FROM outbox_events WHERE event_type = 'EmailChallengeIssued'", UUID.class);
		UUID challengeId = jdbc.queryForObject(
			"SELECT aggregate_id FROM outbox_events WHERE id = ?", UUID.class, eventId);
		String expectedCode = decryptStoredCode(challengeId);

		assertEquals(1, consumer.consumeAvailable());
		JsonNode message = waitForMessage(email);
		assertEquals("【资迹】注册验证码", message.path("Subject").asText());
		assertEquals("noreply@ziji.test", message.path("From").path("Address").asText());
		Matcher code = CODE_IN_HTML.matcher(message.path("HTML").asText());
		assertTrue(code.find());
		assertEquals(expectedCode, code.group(1));
		assertEquals("SUCCEEDED", jdbc.queryForObject("""
			SELECT status FROM outbox_consumer_receipts
			WHERE consumer_name = 'EMAIL' AND outbox_event_id = ?
			""", String.class, eventId));

		assertEquals(0, consumer.consumeAvailable());
		assertEquals(1, readJson(mailpitUri("/api/v1/messages")).path("count").asInt());
	}

	private String decryptStoredCode(UUID challengeId) throws Exception {
		String payload = jdbc.queryForObject(
			"SELECT payload::text FROM outbox_events WHERE aggregate_id = ?", String.class, challengeId);
		JsonNode envelope = objectMapper.readTree(payload).path("verificationCode");
		return decryptor.decrypt(challengeId, EmailChallengePurpose.REGISTER, new EncryptedCodeEnvelope(
			envelope.path("algorithm").asText(),
			envelope.path("keyEncryptionAlgorithm").asText(),
			envelope.path("keyVersion").asInt(),
			envelope.path("nonce").asText(),
			envelope.path("ciphertext").asText(),
			envelope.path("wrappedDataKey").asText(),
			envelope.path("wrappedDataKeyNonce").asText()));
	}

	private JsonNode waitForMessage(String recipient) throws Exception {
		for (int attempt = 0; attempt < 20; attempt++) {
			JsonNode messages = readJson(mailpitUri("/api/v1/messages")).path("messages");
			if (messages.isArray()) {
				for (JsonNode summary : messages) {
					JsonNode toList = summary.path("To");
					if (!toList.isArray()) {
						continue;
					}
					for (JsonNode to : toList) {
						if (recipient.equals(to.path("Address").asText())) {
							return readJson(mailpitUri("/api/v1/message/" + summary.path("ID").asText()));
						}
					}
				}
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Mailpit 未收到验证码邮件。");
	}

	private JsonNode readJson(URI uri) throws Exception {
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		return objectMapper.readTree(response.body());
	}

	private static URI mailpitUri(String path) {
		return URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
	}
}
