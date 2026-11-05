package com.medianexus.orchestrator.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 观看采集的增量结构；逐项检查支持中断后重试，不重算或补填任何历史观看值。 */
@Component
public class EmbyWatchCollectionSchema {
    private final JdbcTemplate jdbc;
    public EmbyWatchCollectionSchema(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void initialize() {
        for (String table : new String[]{"emby_active_playback_sessions", "emby_watch_sessions", "emby_watch_daily_contributions"}) {
            addColumn(table, "playback_instance_id", "CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
        }
        String active = "emby_active_playback_sessions";
        addColumn(active, "last_observed_at", "DATETIME(6) NULL");
        addColumn(active, "observed_state", "VARCHAR(16) NULL");
        addColumn(active, "observation_count", "INT NOT NULL DEFAULT 0");
        index(active, "uk_emby_active_instance", "UNIQUE", "playback_instance_id");
        String history = "emby_watch_sessions";
        index(history, "uk_emby_watch_instance", "UNIQUE", "playback_instance_id");
        index(history, "idx_emby_watch_legacy_settlement", "", "emby_session_id,item_id,stop_time");
        if (hasIndex(history, "uk_emby_watch_settlement")) jdbc.execute("ALTER TABLE " + history + " DROP INDEX uk_emby_watch_settlement");
        String daily = "emby_watch_daily_contributions";
        // AUTO_INCREMENT 必须在同一 ALTER 中成为主键；旧的 tuple 唯一约束不能留下拒绝同秒重播。
        if (!hasColumn(daily, "id")) {
            jdbc.execute("ALTER TABLE " + daily + " ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT, DROP PRIMARY KEY, ADD PRIMARY KEY(id)");
        }
        var primary = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX", String.class, daily);
        if (!primary.equals(java.util.List.of("id"))) {
            jdbc.execute("ALTER TABLE " + daily + (primary.isEmpty() ? "" : " DROP PRIMARY KEY,") + " ADD PRIMARY KEY(id)");
        }
        String extra = jdbc.queryForObject("SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='id'", String.class, daily);
        if (!extra.contains("auto_increment")) jdbc.execute("ALTER TABLE " + daily + " MODIFY id BIGINT NOT NULL AUTO_INCREMENT");
        index(daily, "idx_emby_daily_legacy", "", "emby_session_id,item_id,start_time,watch_date");
        index(daily, "uk_emby_daily_instance_date", "UNIQUE", "playback_instance_id,watch_date");
        precise(active, "start_time"); precise(active, "playback_start_time");
        precise(history, "start_time"); precise(history, "stop_time"); precise(daily, "start_time");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS emby_watch_collection_checkpoint (
                    id TINYINT NOT NULL PRIMARY KEY,
                    writer_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    last_observed_at DATETIME(6) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.update("INSERT IGNORE INTO emby_watch_collection_checkpoint(id) VALUES(1)");
    }
    private boolean hasColumn(String table, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", Integer.class, table, column) > 0;
    }
    private void addColumn(String table, String column, String definition) {
        if (!hasColumn(table, column)) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
    private boolean hasIndex(String table, String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?", Integer.class, table, name) > 0;
    }
    private void index(String table, String name, String unique, String columns) {
        if (!hasIndex(table, name)) jdbc.execute("ALTER TABLE " + table + " ADD " + unique + " INDEX " + name + "(" + columns + ")");
    }
    private void precise(String table, String column) {
        Integer precision = jdbc.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", Integer.class, table, column);
        if (precision != null && precision < 6) jdbc.execute("ALTER TABLE " + table + " MODIFY " + column + " DATETIME(6) " + (column.equals("playback_start_time") ? "NULL" : "NOT NULL"));
    }
}
