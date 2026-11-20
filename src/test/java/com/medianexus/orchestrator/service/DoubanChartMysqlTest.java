package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.mapper.DoubanChartMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 只允许显式隔离本机测试库；不回退应用配置或生产连接。 */
class DoubanChartMysqlTest {
    @Test void concurrentInstancesClaimOneOperationAndRespectSharedLimit() throws Exception {
        String url=System.getenv("DOUBAN_CHART_TEST_JDBC_URL");
        assumeTrue(url!=null,"未配置隔离 MySQL；不声称数据库并发已验证");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:43379/douban_chart_ingest_test?")) throw new IllegalStateException("拒绝非专属隔离测试库");
        var ds=new DriverManagerDataSource(url,System.getenv("DOUBAN_CHART_TEST_JDBC_USER"),System.getenv("DOUBAN_CHART_TEST_JDBC_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS douban_chart_records");
        var config=new MybatisConfiguration(); config.addMapper(DoubanChartMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(ds);
        var sql=new SqlSessionTemplate(factory.getObject());
        try {
            var mapper=sql.getMapper(DoubanChartMapper.class);
            var json=new ObjectMapper().findAndRegisterModules();
            var tx=new DataSourceTransactionManager(ds);
            var a=new DoubanChartStore(mapper,json,tx);var b=new DoubanChartStore(mapper,json,tx);
            a.board(ChartId.movie_hot_gaia);b.board(ChartId.movie_hot_gaia);
            // 隔离库可经跨地域 SSH 隧道访问；等待界限不作为数据库事务吞吐断言。
            var pool=Executors.newFixedThreadPool(8);
            try {
                var start=new CountDownLatch(1);
                List<Future<String>> operations=new ArrayList<>();
                for(int i=0;i<8;i++) {var store=i%2==0?a:b;operations.add(pool.submit(()->{start.await();return store.submit(ChartId.movie_hot_gaia,1L,"MANUAL").operation().id();}));}
                start.countDown();Set<String> ids=new HashSet<>();for(var future:operations)ids.add(future.get(90,TimeUnit.SECONDS));
                assertEquals(1,ids.size());assertEquals(1,a.operations(ChartId.movie_hot_gaia,1,10).total());
                a.limits(new LimitsInput(1,1));
                List<Future<Boolean>> checks=new ArrayList<>();for(int i=0;i<8;i++){var store=i%2==0?a:b;checks.add(pool.submit(store::reserveCheck));}
                int admitted=0;for(var future:checks)if(future.get(90,TimeUnit.SECONDS))admitted++;
                assertEquals(1,admitted);assertEquals(1,b.counts().checked());
            } finally {pool.shutdownNow();}
        } finally {jdbc.execute("DROP TABLE IF EXISTS douban_chart_records");}
    }
}
