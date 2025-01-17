/*
 * Copyright (c) 2012 - 2020 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.derby.impl.sql.execute.altertable;

import com.splicemachine.EngineDriver;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.iapi.sql.olap.OlapStatus;
import com.splicemachine.derby.iapi.sql.olap.SuccessfulOlapResult;
import com.splicemachine.derby.stream.function.KVPairFunction;
import com.splicemachine.derby.stream.function.RowTransformFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DistributedDataSetProcessor;
import com.splicemachine.derby.stream.iapi.PairDataSet;
import com.splicemachine.kvpair.KVPair;

import java.util.concurrent.Callable;

/**
 * Created by dgomezferro on 6/15/16.
 */
public class AlterTableTransformJob implements Callable<Void> {
    private final DistributedAlterTableTransformJob request;
    private final OlapStatus jobStatus;

    public AlterTableTransformJob(DistributedAlterTableTransformJob request, OlapStatus jobStatus) {
        this.request = request;
        this.jobStatus = jobStatus;
    }

    @Override
    public Void call() throws Exception {
        if (!jobStatus.markRunning()) {
            //the client has already cancelled us or has died before we could get started, so stop now
            return null;
        }

        DistributedDataSetProcessor dsp = EngineDriver.driver().processorFactory().distributedProcessor();
        dsp.setSchedulerPool(request.pool);
        dsp.setJobGroup(request.jobGroup, request.description);


        DataSet<KVPair> dataSet = request.scanSetBuilder.buildDataSet(this);

        // Write new conglomerate
        PairDataSet<ExecRow,KVPair> ds = dataSet.map(new RowTransformFunction(request.ddlChange)).index(new KVPairFunction());
        //side effects are what matters here
        @SuppressWarnings("unused") DataSet<ExecRow> result = ds.directWriteData()
                .txn(request.childTxn)
                .destConglomerate(request.destConglom)
                .skipIndex(true).build().write();
        jobStatus.markCompleted(new SuccessfulOlapResult());
        return null;
    }
}
