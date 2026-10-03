package com.fixgo.module.admin.repository;

import com.fixgo.module.iam.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read-only account queries for the admin dashboard (separate from the existing UserRepository on purpose). */
public interface AdminAccountReportRepository extends Repository<User, UUID> {

    /** Columns: role, status, count. */
    @Query("select u.role, u.status, count(u) from User u group by u.role, u.status")
    List<Object[]> countByRoleAndStatus();

    /** Columns: userId, primary phone — one round trip for a whole page instead of one query per row. */
    @Query("select i.user.id, i.providerUid from UserIdentity i where i.user.id in :userIds and i.primary = true")
    List<Object[]> primaryUidsFor(@Param("userIds") Collection<UUID> userIds);
}
