package app.ziji.category.application;

import java.util.UUID;

/** 分类创建的类型化命令；客户端不能提交 ID、状态或规范化名称。 */
public record CategoryCommand(
	String name,
	CategoryType categoryType,
	UUID parentId,
	UUID accountId) {
}
