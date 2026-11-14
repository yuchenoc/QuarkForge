package io.github.foundry4j.quarkforge.data;

import io.github.foundry4j.quarkforge.core.api.CommonErrorCode;
import io.github.foundry4j.quarkforge.core.api.CursorPage;
import io.github.foundry4j.quarkforge.core.api.PageResult;
import io.github.foundry4j.quarkforge.core.exception.BizException;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/// ## BaseRepository
/// 仓储基类，在 Panache 标准能力之上补充：
///
/// - 分页查询并直接产出统一分页结构 [PageResult]
/// - 游标分页 [findPageAfter]，用于深分页（避免 `offset` 越翻越慢）
/// - `requireById`：查不到即抛 `NOT_FOUND` 业务异常，省去判空样板代码
///
/// 用法：
///
/// ```java
/// @ApplicationScoped
/// public class UserRepository extends BaseRepository<User> {
///     public PageResult<User> search(String keyword, int page, int size) {
///         return page(find("name like ?1", "%" + keyword + "%"), page, size);
///     }
/// }
/// ```
///
/// @param <T> 实体类型
public abstract class BaseRepository<T> implements PanacheRepositoryBase<T, Long> {

    /// 游标分页单页上限。
    private static final int MAX_PAGE_SIZE = 500;

    /// 对任意 Panache 查询执行分页（`page` 从 1 开始）。
    public PageResult<T> page(PanacheQuery<T> query, int page, int size) {
        int pageIndex = Math.max(page, 1);
        return PageResult.of(query.page(Page.of(pageIndex - 1, size)).list(), query.count(), pageIndex, size);
    }

    /// 全表分页查询。
    public PageResult<T> pageAll(int page, int size) {
        return page(findAll(), page, size);
    }

    /// 按 ID 查询，不存在时抛出 `NOT_FOUND` 业务异常。
    public T requireById(Long id) {
        T entity = findById(id);
        if (entity == null) {
            throw new BizException(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    /// 游标（keyset）分页：按 `排序字段 + id` 定位上一页末尾之后的数据。
    ///
    /// 与 `LIMIT/OFFSET` 相比，深页码时不会扫描并丢弃前面所有行；
    /// 排序字段需是非空、可比较的公共字段（如 `id`、`createdAt`），`id` 作为唯一性兜底。
    ///
    /// ```java
    /// CursorPage<User> first = userRepository.findPageAfter("createdAt", false, null, 20);
    /// CursorPage<User> next = userRepository.findPageAfter("createdAt", false, first.nextCursor(), 20);
    /// ```
    ///
    /// @param field     排序字段（实体公共字段名）
    /// @param ascending 是否升序
    /// @param cursor    上一页返回的游标，首次传 null
    /// @param size      每页条数，1~500
    /// @return 当前页与下一页游标
    public CursorPage<T> findPageAfter(String field, boolean ascending, String cursor, int size) {
        SingularAttribute<?, ?> sortAttribute = resolveAttribute(field);
        Class<?> valueType = sortAttribute.getJavaType();
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        String direction = ascending ? "asc" : "desc";
        String comparator = ascending ? ">" : "<";

        List<Object> params = new ArrayList<>(2);
        StringBuilder query = new StringBuilder();
        if (cursor != null && !cursor.isBlank()) {
            CursorCodec.Cursor decoded = CursorCodec.decode(cursor, valueType);
            query.append('(').append(field).append(' ').append(comparator).append(" ?1")
                    .append(" or (").append(field).append(" = ?1 and id ").append(comparator).append(" ?2)) ");
            params.add(decoded.value());
            params.add(decoded.id());
        }
        query.append("order by ").append(field).append(' ').append(direction)
                .append(", id ").append(direction);

        List<T> rows = find(query.toString(), params.toArray()).page(Page.of(0, limit + 1)).list();
        boolean hasMore = rows.size() > limit;
        List<T> records = hasMore ? new ArrayList<>(rows.subList(0, limit)) : rows;
        if (!hasMore) {
            return CursorPage.of(records, null, false);
        }
        Object lastId = identifierOf(records.getLast());
        Object lastValue = readSortValue(field, valueType, lastId);
        return CursorPage.of(records, CursorCodec.encode(lastValue, lastId), true);
    }

    /// 解析仓储的实体类型，用于校验排序字段。
    @SuppressWarnings("unchecked")
    protected Class<T> entityClass() {
        Class<?> current = getClass();
        while (current != null && current != Object.class) {
            Type superType = current.getGenericSuperclass();
            if (superType instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> clazz
                    && clazz != Object.class) {
                return (Class<T>) clazz;
            }
            current = current.getSuperclass();
        }
        throw new BizException(CommonErrorCode.PARAM_ERROR, "无法解析仓储的实体类型");
    }

    /// 通过 JPA 元模型校验排序字段：必须是实体上的标量属性
    /// （Quarkus 会增强实体字节码，字段反射不可靠，因此以元模型为准）。
    private SingularAttribute<?, ?> resolveAttribute(String field) {
        if (field == null || !field.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new BizException(CommonErrorCode.PARAM_ERROR, "无效的排序字段: " + field);
        }
        EntityType<?> entityType = getEntityManager().getMetamodel().entity(entityClass());
        Attribute<?, ?> attribute;
        try {
            attribute = entityType.getAttribute(field);
        } catch (IllegalArgumentException e) {
            attribute = null;
        }
        if (!(attribute instanceof SingularAttribute<?, ?> singular) || singular.isAssociation()) {
            throw new BizException(CommonErrorCode.PARAM_ERROR, "不支持的排序字段: " + field);
        }
        return singular;
    }

    /// 读取最后一条记录的排序值，用于生成下一页游标。
    private Object readSortValue(String field, Class<?> valueType, Object id) {
        EntityType<?> entityType = getEntityManager().getMetamodel().entity(entityClass());
        return getEntityManager()
                .createQuery("select e." + field + " from " + entityType.getName() + " e where e.id = ?1", valueType)
                .setParameter(1, id)
                .getSingleResult();
    }

    private Object identifierOf(T entity) {
        return getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
    }
}
