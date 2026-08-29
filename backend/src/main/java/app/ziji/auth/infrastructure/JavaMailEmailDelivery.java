package app.ziji.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import app.ziji.auth.application.EmailChallengeEmail;
import app.ziji.auth.application.EmailDelivery;
import app.ziji.auth.domain.EmailChallengePurpose;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** 通过 Spring JavaMailSender（SMTP）投递验证码邮件；过滤收件人之外不记录验证码内容。 */
public final class JavaMailEmailDelivery implements EmailDelivery {

	private static final DateTimeFormatter EXPIRY_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

	private final JavaMailSender mailSender;
	private final String from;

	public JavaMailEmailDelivery(JavaMailSender mailSender, String from) {
		if (mailSender == null || from == null || from.isBlank()) {
			throw new AuthInfrastructureException("邮件投递配置无效。");
		}
		this.mailSender = mailSender;
		this.from = from;
	}

	@Override
	public void send(EmailChallengeEmail email) {
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
			helper.setFrom(from);
			helper.setTo(email.email());
			helper.setSubject(subjectFor(email.purpose()));
			helper.setText(bodyFor(email), true);
			mailSender.send(message);
		} catch (jakarta.mail.MessagingException | RuntimeException exception) {
			// 异常消息可能包含 SMTP 服务器细节，不包含验证码；本地开发仍应可定位。
			throw new AuthInfrastructureException("验证码邮件投递失败。", exception);
		}
	}

	private static String subjectFor(EmailChallengePurpose purpose) {
		return switch (purpose) {
			case REGISTER -> "【资迹】注册验证码";
			case RESET_PASSWORD -> "【资迹】重置密码验证码";
		};
	}

	private static String bodyFor(EmailChallengeEmail email) {
		String expiry = EXPIRY_FORMAT.format(email.expiresAt());
		String action = switch (email.purpose()) {
			case REGISTER -> "完成邮箱注册";
			case RESET_PASSWORD -> "重置账户密码";
		};
		return """
			<div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:480px;margin:0 auto;">
			<h2 style="color:#18181b;">【资迹】%s</h2>
			<p style="color:#3f3f46;line-height:1.7;">你正在%s，请在页面输入以下验证码：</p>
			<p style="font-size:28px;font-weight:700;letter-spacing:6px;color:#0f766e;background:#f0fdfa;border-radius:8px;padding:14px 20px;">%s</p>
			<p style="color:#71717a;font-size:13px;">验证码 %s 前有效，请勿转发给他人。如果这不是你的操作，请忽略本邮件。</p>
			</div>
			""".formatted(action, action, email.verificationCode(), expiry);
	}
}
