package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.SystemSetting;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSetting> {

    @Update("""
            CREATE TABLE IF NOT EXISTS system_settings (
                id BIGINT NOT NULL AUTO_INCREMENT,
                setting_key VARCHAR(128) NOT NULL,
                setting_value TEXT NOT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_system_settings_key (setting_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT setting_value
            FROM system_settings
            WHERE setting_key = #{settingKey}
            LIMIT 1
            """)
    String selectSettingValue(@Param("settingKey") String settingKey);

    @Select("""
            SELECT setting_value
            FROM system_settings
            WHERE setting_key = #{settingKey}
            LIMIT 1
            FOR UPDATE
            """)
    String selectSettingValueForUpdate(@Param("settingKey") String settingKey);

    @Insert("""
            INSERT IGNORE INTO system_settings (setting_key, setting_value)
            VALUES (#{settingKey}, #{settingValue})
            """)
    void insertSettingIfAbsent(
            @Param("settingKey") String settingKey,
            @Param("settingValue") String settingValue
    );

    @Insert("""
            INSERT INTO system_settings (setting_key, setting_value)
            VALUES (#{settingKey}, #{settingValue})
            ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)
            """)
    void upsertSetting(
            @Param("settingKey") String settingKey,
            @Param("settingValue") String settingValue
    );
}
