package com.medianexus.orchestrator.service.organization;

@FunctionalInterface
public interface LibraryOrganizationProgressObserver {

    void record(String message, String detail);
}
