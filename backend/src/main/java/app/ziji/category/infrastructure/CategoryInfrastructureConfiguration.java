package app.ziji.category.infrastructure;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.category.application.CategoryCommandStore;
import app.ziji.category.application.CategoryCursorCodec;
import app.ziji.category.application.CategoryQueryReadPort;
import app.ziji.category.application.CategoryService;
import app.ziji.shared.application.TransactionRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 在 infrastructure 装配分类应用服务；application/domain 保持无 Spring 依赖。 */
@Configuration(proxyBeanMethods = false)
class CategoryInfrastructureConfiguration {

	@Bean
	CategoryService categoryService(
		CategoryQueryReadPort queries,
		CategoryCommandStore commands,
		AccountMembershipReadPort memberships,
		CategoryCursorCodec cursors,
		TransactionRunner transactions,
		Clock clock) {
		// 生产路径始终由服务端生成 UUID；测试可直接替换工厂。
		return new CategoryService(queries, commands, memberships, cursors, transactions, clock, UUID::randomUUID);
	}

	@Bean
	CategoryCursorCodec categoryCursorCodec(
		@Value("${ziji.account.cursor-key-base64}") String cursorKeyBase64) {
		try {
			// 与账户游标共用本地密钥但使用独立 domain AAD；不得复用认证、幂等或 outbox 密钥。
			return new AesGcmCategoryCursorCodec(Base64.getDecoder().decode(cursorKeyBase64), new SecureRandom());
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("分类游标密钥配置无效。", exception);
		}
	}
}
