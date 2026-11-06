package com.medianexus.orchestrator.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** BGM 来源和目录目标会话锁的唯一生命周期；连接不承载任务或额度事务。 */
@Service
public class BgmWishlistSessionLocks {
    private static final Logger log = LoggerFactory.getLogger(BgmWishlistSessionLocks.class);
    private final DataSource dataSource;

    public BgmWishlistSessionLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T withLock(String name, Supplier<? extends RuntimeException> busy, Supplier<T> operation) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException exception) {
            log.warn("BGM 会话锁连接不可用 lock={}", name, exception);
            throw new LockUnavailableException(exception);
        }
        boolean acquired = false;
        try {
            acquired = queryLock(connection, "SELECT GET_LOCK(?, 0)", name);
            if (!acquired) throw busy.get();
            return operation.get();
        } catch (SQLException exception) {
            log.warn("BGM 会话锁获取失败 lock={}", name, exception);
            abort(connection, name);
            throw new LockUnavailableException(exception);
        } finally {
            if (acquired) {
                try {
                    if (!queryLock(connection, "SELECT RELEASE_LOCK(?)", name)) {
                        throw new SQLException("BGM session lock was not released");
                    }
                } catch (SQLException exception) {
                    log.warn("BGM 会话锁释放失败 lock={}", name, exception);
                    abort(connection, name);
                }
            }
            try {
                connection.close();
            } catch (SQLException exception) {
                // 清理失败不能覆盖已创建任务的返回值或原业务异常。
                log.warn("BGM 会话锁连接关闭失败 lock={}", name, exception);
                abort(connection, name);
            }
        }
    }

    private boolean queryLock(Connection connection, String sql, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private void abort(Connection connection, String name) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException exception) {
            log.warn("BGM 会话锁连接中止失败 lock={}", name, exception);
        }
    }

    public static class LockUnavailableException extends RuntimeException {
        private LockUnavailableException(SQLException cause) {
            super("BGM 会话锁不可用", cause);
        }
    }
}
