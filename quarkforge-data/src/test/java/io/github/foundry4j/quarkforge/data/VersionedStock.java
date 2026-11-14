package io.github.foundry4j.quarkforge.data;

import jakarta.persistence.Entity;

/// 测试用实体：验证 [VersionedEntity] 的 `@Version` 行为。
@Entity
public class VersionedStock extends VersionedEntity {

    public String name;

    public int quantity;
}
