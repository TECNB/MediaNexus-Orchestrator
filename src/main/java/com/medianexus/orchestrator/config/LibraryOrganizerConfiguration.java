package com.medianexus.orchestrator.config;

import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2LibraryOrganizer;
import com.medianexus.orchestrator.integration.openlist.OpenListLibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为每个产品类别选择文件整理数据面。
 *
 * 全局开关关闭时四类任务统一回退 OpenList；全局开启后，各产品开关可以独立灰度。
 */
@Configuration
public class LibraryOrganizerConfiguration {

    @Bean
    public LibraryOrganizer animeLibraryOrganizer(
            CloudDrive2Properties properties,
            OpenListLibraryOrganizer openListOrganizer,
            ObjectProvider<CloudDrive2LibraryOrganizer> cloudDrive2Organizer
    ) {
        return select(properties, properties.isAnimeOrganizationEnabled(), openListOrganizer, cloudDrive2Organizer);
    }

    @Bean
    public LibraryOrganizer movieLibraryOrganizer(
            CloudDrive2Properties properties,
            OpenListLibraryOrganizer openListOrganizer,
            ObjectProvider<CloudDrive2LibraryOrganizer> cloudDrive2Organizer
    ) {
        return select(properties, properties.isMovieOrganizationEnabled(), openListOrganizer, cloudDrive2Organizer);
    }

    @Bean
    public LibraryOrganizer seriesLibraryOrganizer(
            CloudDrive2Properties properties,
            OpenListLibraryOrganizer openListOrganizer,
            ObjectProvider<CloudDrive2LibraryOrganizer> cloudDrive2Organizer
    ) {
        return select(properties, properties.isSeriesOrganizationEnabled(), openListOrganizer, cloudDrive2Organizer);
    }

    @Bean
    public LibraryOrganizer adultLibraryOrganizer(
            CloudDrive2Properties properties,
            OpenListLibraryOrganizer openListOrganizer,
            ObjectProvider<CloudDrive2LibraryOrganizer> cloudDrive2Organizer
    ) {
        return select(properties, properties.isAdultOrganizationEnabled(), openListOrganizer, cloudDrive2Organizer);
    }

    private LibraryOrganizer select(
            CloudDrive2Properties properties,
            boolean productEnabled,
            OpenListLibraryOrganizer openListOrganizer,
            ObjectProvider<CloudDrive2LibraryOrganizer> cloudDrive2Organizer
    ) {
        if (!properties.isOrganizationEnabled() || !productEnabled) {
            return openListOrganizer;
        }
        CloudDrive2LibraryOrganizer selected = cloudDrive2Organizer.getIfAvailable();
        if (selected == null) {
            throw new IllegalStateException("CloudDrive2 organizer is unavailable while product organization is enabled");
        }
        return selected;
    }
}
