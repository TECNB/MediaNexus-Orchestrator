package com.medianexus.orchestrator.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class QuarkIngestTaskChildMappingTest {

    @Test
    void renameRuleColumnDoesNotUseMysqlReservedWordAsAlias() {
        TableInfo tableInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                QuarkIngestTaskChild.class
        );

        assertThat(tableInfo.getAllSqlSelect())
                .contains("replace_rule")
                .doesNotContain(" AS replace");
    }
}
