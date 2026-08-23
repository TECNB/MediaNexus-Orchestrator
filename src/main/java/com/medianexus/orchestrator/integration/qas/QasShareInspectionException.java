package com.medianexus.orchestrator.integration.qas;

public class QasShareInspectionException extends QasClientException {

    private final boolean complexStructureObserved;
    private final boolean timedOut;

    public QasShareInspectionException(
            Reason reason,
            String message,
            boolean complexStructureObserved
    ) {
        this(reason, message, complexStructureObserved, false, null);
    }

    public QasShareInspectionException(
            Reason reason,
            String message,
            boolean complexStructureObserved,
            Throwable cause
    ) {
        this(reason, message, complexStructureObserved, false, cause);
    }

    public QasShareInspectionException(
            Reason reason,
            String message,
            boolean complexStructureObserved,
            boolean timedOut,
            Throwable cause
    ) {
        super(reason, message, cause);
        this.complexStructureObserved = complexStructureObserved;
        this.timedOut = timedOut;
    }

    public boolean isComplexStructureObserved() {
        return complexStructureObserved;
    }

    public boolean isTimedOut() {
        return timedOut;
    }
}
