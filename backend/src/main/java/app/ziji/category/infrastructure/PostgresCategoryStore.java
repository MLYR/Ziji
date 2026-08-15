package app.ziji.category.infrastructure;

import java.util.Optional;
import java.util.UUID;

import app.ziji.category.application.CategoryReference;
import app.ziji.category.application.CategoryStore;
import app.ziji.category.application.CategoryType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** categories 的最小公开查询适配器，仅供应用边界校验分类类型和归属。 */
@Repository
public class PostgresCategoryStore implements CategoryStore {

	private final JdbcTemplate jdbc;

	public PostgresCategoryStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<CategoryReference> findById(UUID categoryId) {
		if (categoryId == null) {
			return Optional.empty();
		}
		return jdbc.query("""
			SELECT id, owner_user_id, account_id, category_type, status
			FROM categories
			WHERE id = ?
			""",
			records -> records.next()
				? Optional.of(new CategoryReference(
					records.getObject("id", UUID.class),
					records.getObject("owner_user_id", UUID.class),
					records.getObject("account_id", UUID.class),
					CategoryType.valueOf(records.getString("category_type")),
					"ACTIVE".equals(records.getString("status"))))
				: Optional.empty(),
			categoryId);
	}
}
