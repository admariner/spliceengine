package com.splicemachine.db.iapi.sql.dictionary;

import com.splicemachine.db.catalog.UUID;

public class DisplayedTriggerInfo {
    private UUID id;
    private String name;
    private long txnId;
    private long parentTxnId = -1; // currently not displayed
    private java.util.UUID queryId;
    private java.util.UUID parentQueryId;
    private long elapsedTime = -1;
    private long modifiedRowCount = -1;

    public DisplayedTriggerInfo(UUID id, String name, long txnId, java.util.UUID queryId) {
        this.id = id;
        this.name = name;
        this.txnId = txnId;
        this.queryId = queryId;
    }

    public DisplayedTriggerInfo(UUID id, String name, long txnId, java.util.UUID queryId, long parentTxnId, java.util.UUID parentQueryId) {
        this(id, name, txnId, queryId);
        this.parentTxnId = parentTxnId;
        this.parentQueryId = parentQueryId;
    }


    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getTxnId() {
        return txnId;
    }

    public long getParentTxnId() {
        return parentTxnId;
    }

    public java.util.UUID getQueryId() {
        return queryId;
    }

    public java.util.UUID getParentQueryId() {
        return parentQueryId;
    }

    public void setQueryId(java.util.UUID queryId) {
        this.queryId = queryId;
    }

    public void setTxnId(long txnId) {
        this.txnId = txnId;
    }

    public void setElapsedTime(long elapsedTime) {
        this.elapsedTime = elapsedTime;
    }

    public long getElapsedTime() {
        return elapsedTime;
    }

    public long getModifiedRowCount() {
        return modifiedRowCount;
    }

    public void setModifiedRowCount(long modifiedRowCount) {
        this.modifiedRowCount = modifiedRowCount;
    }
}
