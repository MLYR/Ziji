package app.ziji.category.application;

/** 标签不存在或不属于当前用户；对外统一不泄露存在性。 */
public class TagNotVisibleException extends RuntimeException {
}
