package com.medianexus.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.context.support.GenericApplicationContext;

class MediaNexusOrchestratorApplicationTest {

    @Test
    void mapperScanOnlyRegistersDatabaseMappers() {
        MapperScan mapperScan = MediaNexusOrchestratorApplication.class.getAnnotation(MapperScan.class);
        assertThat(mapperScan).isNotNull();

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            ClassPathMapperScanner scanner = new ClassPathMapperScanner(context, context.getEnvironment());
            scanner.registerFilters();
            scanner.scan(mapperScan.value());

            assertThat(context.containsBeanDefinition("animeMagnetIngestTaskMapper")).isTrue();
            assertThat(context.containsBeanDefinition("cloudDrive2FileOperations")).isFalse();
            assertThat(context.containsBeanDefinition("animeLibraryOrganizer")).isFalse();
        }
    }
}
