package com.medianexus.orchestrator.integration.qas;

public interface QasExecutionObserver {

    void onOutput(String level, String message);

    void onCompleted();

    void onInterrupted();
}
