package app.ziji.category.application;

/** 分类对当前用户不可见；统一按资源不存在处理，不区分默认、个人或账户边界。 */
public final class CategoryNotVisibleException extends CategoryApplicationException {
}
