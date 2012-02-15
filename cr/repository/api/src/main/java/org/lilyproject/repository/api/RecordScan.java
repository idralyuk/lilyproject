package org.lilyproject.repository.api;

import org.lilyproject.repository.api.filter.RecordFilter;

public class RecordScan {
    private RecordId startRecordId;
    private RecordId stopRecordId;
    private RecordFilter recordFilter;
    private ReturnFields returnFields;

    public RecordId getStartRecordId() {
        return startRecordId;
    }

    public void setStartRecordId(RecordId startRecordId) {
        this.startRecordId = startRecordId;
    }

    public RecordId getStopRecordId() {
        return stopRecordId;
    }

    /**
     * @param stopRecordId this is exclusive, scan stops at last entry before this id
     */
    public void setStopRecordId(RecordId stopRecordId) {
        this.stopRecordId = stopRecordId;
    }

    public RecordFilter getRecordFilter() {
        return recordFilter;
    }

    public void setRecordFilter(RecordFilter recordFilter) {
        this.recordFilter = recordFilter;
    }

    public ReturnFields getReturnFields() {
        return returnFields;
    }

    public void setReturnFields(ReturnFields returnFields) {
        this.returnFields = returnFields;
    }
}
