package app.ziji.auth.infrastructure;

import java.time.Clock;

import app.ziji.auth.application.EmailChallengeOutboxConsumer;
import app.ziji.auth.application.EmailDelivery;
import app.ziji.auth.application.EnvelopeDecryptor;
import app.ziji.auth.application.EmailOutboxStore;
import app.ziji.shared.application.TransactionRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** 装配邮件 outbox 消费者；调度开关独立于消费者 Bean，测试 profile 只关闭后台线程。 */
@Configuration(proxyBeanMethods = false)
class EmailInfrastructureConfiguration {

	@Bean
	EnvelopeDecryptor envelopeDecryptor(EnvelopeKey key) {
		return new AesGcmEnvelopeDecryptor(key);
	}

	@Bean
	EmailDelivery emailDelivery(JavaMailSender mailSender, @Value("${ziji.mail.from}") String from) {
		return new JavaMailEmailDelivery(mailSender, from);
	}

	@Bean
	EmailChallengeOutboxConsumer emailChallengeOutboxConsumer(
		EmailOutboxStore outbox,
		EmailDelivery delivery,
		EnvelopeDecryptor decryptor,
		TransactionRunner transactions,
		Clock clock) {
		return new EmailChallengeOutboxConsumer(outbox, delivery, decryptor, transactions, clock);
	}
}
