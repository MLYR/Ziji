package app.ziji.category.application;

/** 标签 merge-patch 类型化命令；null 表示字段未提交。 */
public record TagUpdateCommand(String name, TagStatus status) {
}
