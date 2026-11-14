package io.github.foundry4j.quarkforge.data;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/// ## VersionedEntity
/// 带乐观锁版本号的实体基类，用于防止并发覆盖（库存、余额、状态机等）。
///
/// ```java
/// @Entity
/// public class Account extends VersionedEntity {
///     public BigDecimal balance;
/// }
/// ```
///
/// 更新时 Hibernate 以 `where id = ? and version = ?` 提交，版本不匹配会抛出
/// `OptimisticLockException`，web 层统一映射为 HTTP 409、错误码 `40901`。
///
/// 需要前端参与“读-改-写”时，把 `version` 放进 DTO；提交时先用 id 查出当前实体，
/// 再校验 DTO 中的 `version` 是否一致（或直接赋值给实体让 JPA 检测冲突）。
///
/// 未继承本类的实体没有 `version` 列，原有表结构和行为不变，可按需选用。
@MappedSuperclass
public abstract class VersionedEntity extends BaseEntity {

    /// 版本号，初始为 0，每次成功更新自动加一。
    @Version
    public long version;
}
