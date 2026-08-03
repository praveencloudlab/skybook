package com.skybook.praveen.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auditable has no logic of its own - its behaviour IS its mapping. Every
 * entity in the fleet inherits created/updated stamps and the optimistic-lock
 * version from here, so the annotations are asserted as the contract they are
 * (dropping @Version, for instance, silently turns concurrent seat holds into
 * last-write-wins).
 */
class AuditableTest {

    /** A minimal concrete subclass - Auditable is abstract by design. */
    private static class SampleEntity extends Auditable {
    }

    private static Field field(String name) throws NoSuchFieldException {
        return Auditable.class.getDeclaredField(name);
    }

    @Nested
    @DisplayName("field access via the inherited Lombok accessors")
    class FieldAccess {

        private final SampleEntity entity = new SampleEntity();

        @Test
        @DisplayName("a freshly constructed entity has no audit stamps yet")
        void auditFieldsStartNull() {
            assertThat(entity.getCreatedAt()).isNull();
            assertThat(entity.getUpdatedAt()).isNull();
            assertThat(entity.getCreatedBy()).isNull();
            assertThat(entity.getUpdatedBy()).isNull();
            assertThat(entity.getVersion()).isNull();
        }

        @Test
        @DisplayName("every audit field round trips through its setter and getter")
        void everyFieldRoundTrips() {
            LocalDateTime created = LocalDateTime.of(2026, 7, 1, 8, 0);
            LocalDateTime updated = LocalDateTime.of(2026, 7, 2, 9, 30);

            entity.setCreatedAt(created);
            entity.setUpdatedAt(updated);
            entity.setCreatedBy("praveen");
            entity.setUpdatedBy("agent-42");
            entity.setVersion(3L);

            assertThat(entity.getCreatedAt()).isEqualTo(created);
            assertThat(entity.getUpdatedAt()).isEqualTo(updated);
            assertThat(entity.getCreatedBy()).isEqualTo("praveen");
            assertThat(entity.getUpdatedBy()).isEqualTo("agent-42");
            assertThat(entity.getVersion()).isEqualTo(3L);
        }

        @Test
        @DisplayName("setting a field back to null is allowed - nothing here defaults or self-heals")
        void fieldsCanBeClearedAgain() {
            entity.setCreatedBy("praveen");
            entity.setVersion(1L);
            entity.setCreatedBy(null);
            entity.setVersion(null);

            assertThat(entity.getCreatedBy()).isNull();
            assertThat(entity.getVersion()).isNull();
        }

        @Test
        @DisplayName("two subclass instances stay distinct - Auditable adds no equals/hashCode")
        void auditableDoesNotOverrideIdentity() {
            SampleEntity other = new SampleEntity();
            entity.setCreatedBy("praveen");
            other.setCreatedBy("praveen");

            assertThat(entity).isNotEqualTo(other);
            assertThat(entity).isEqualTo(entity);
        }
    }

    @Nested
    @DisplayName("the JPA / Spring Data mapping contract")
    class MappingContract {

        @Test
        @DisplayName("the class is an abstract @MappedSuperclass, never an entity of its own")
        void isAnAbstractMappedSuperclass() {
            assertThat(Modifier.isAbstract(Auditable.class.getModifiers())).isTrue();
            assertThat(Auditable.class.isAnnotationPresent(MappedSuperclass.class)).isTrue();
        }

        @Test
        @DisplayName("AuditingEntityListener is wired on, or nothing would populate the stamps")
        void auditingEntityListenerIsRegistered() {
            EntityListeners listeners = Auditable.class.getAnnotation(EntityListeners.class);

            assertThat(listeners).isNotNull();
            assertThat(listeners.value()).contains(AuditingEntityListener.class);
        }

        @Test
        @DisplayName("createdAt is @CreatedDate and its column is NOT NULL and non-updatable")
        void createdAtIsImmutableOnceWritten() throws Exception {
            Field createdAt = field("createdAt");
            Column column = createdAt.getAnnotation(Column.class);

            assertThat(createdAt.getType()).isEqualTo(LocalDateTime.class);
            assertThat(createdAt.isAnnotationPresent(CreatedDate.class)).isTrue();
            assertThat(column.nullable()).isFalse();
            assertThat(column.updatable()).isFalse();
        }

        @Test
        @DisplayName("updatedAt is @LastModifiedDate, NOT NULL, and stays updatable")
        void updatedAtIsRewrittenOnEveryFlush() throws Exception {
            Field updatedAt = field("updatedAt");
            Column column = updatedAt.getAnnotation(Column.class);

            assertThat(updatedAt.isAnnotationPresent(LastModifiedDate.class)).isTrue();
            assertThat(column.nullable()).isFalse();
            assertThat(column.updatable()).isTrue();
        }

        @Test
        @DisplayName("createdBy is @CreatedBy, capped at 100 chars and non-updatable")
        void createdByIsCappedAndImmutable() throws Exception {
            Field createdBy = field("createdBy");
            Column column = createdBy.getAnnotation(Column.class);

            assertThat(createdBy.getType()).isEqualTo(String.class);
            assertThat(createdBy.isAnnotationPresent(CreatedBy.class)).isTrue();
            assertThat(column.length()).isEqualTo(100);
            assertThat(column.updatable()).isFalse();
        }

        @Test
        @DisplayName("updatedBy is @LastModifiedBy and capped at the same 100 chars")
        void updatedByIsCapped() throws Exception {
            Field updatedBy = field("updatedBy");
            Column column = updatedBy.getAnnotation(Column.class);

            assertThat(updatedBy.isAnnotationPresent(LastModifiedBy.class)).isTrue();
            assertThat(column.length()).isEqualTo(100);
            assertThat(column.updatable()).isTrue();
        }

        @Test
        @DisplayName("version carries @Version as a nullable Long - optimistic locking for every entity")
        void versionEnablesOptimisticLocking() throws Exception {
            Field version = field("version");

            assertThat(version.isAnnotationPresent(Version.class)).isTrue();
            assertThat(version.getType()).isEqualTo(Long.class);
        }

        @Test
        @DisplayName("all audit state is private - subclasses go through the accessors")
        void allAuditFieldsArePrivate() {
            for (Field f : Auditable.class.getDeclaredFields()) {
                if (f.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isPrivate(f.getModifiers()))
                        .as("field %s should be private", f.getName())
                        .isTrue();
            }
        }
    }
}
