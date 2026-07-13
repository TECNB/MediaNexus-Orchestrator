package com.medianexus.orchestrator.service.organization;

/**
 * 执行一个完整的媒体库文件整理计划。
 *
 * 调用者只负责生成业务计划；批量策略、外部调用顺序、空目录清理和最终可见性
 * 由具体 Adapter 保证。
 */
public interface LibraryOrganizer {

    void organize(LibraryOrganizationPlan plan, LibraryOrganizationProgressObserver progressObserver);
}
