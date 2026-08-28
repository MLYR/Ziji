package app.ziji.category.application;

/** 分类 merge-patch 类型化命令；null 表示字段未提交，HTTP 边界不得静默扩展字段。 */
public record CategoryUpdateCommand(String name, CategoryStatus status) {
}
