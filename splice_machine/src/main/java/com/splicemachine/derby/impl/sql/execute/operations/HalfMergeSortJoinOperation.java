package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.loader.GeneratedMethod;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.ResultSet;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.stream.function.CountJoinedLeftFunction;
import com.splicemachine.derby.stream.function.KeyerFunction;
import com.splicemachine.derby.stream.function.Partitioner;
import com.splicemachine.derby.stream.function.RowComparator;
import com.splicemachine.derby.stream.function.merge.MergeAntiJoinFlatMapFunction;
import com.splicemachine.derby.stream.function.merge.MergeInnerJoinFlatMapFunction;
import com.splicemachine.derby.stream.function.merge.MergeOuterJoinFlatMapFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.derby.stream.iapi.OperationContext;
import com.splicemachine.derby.stream.iapi.PairDataSet;
import com.splicemachine.utils.IntArrays;
import org.apache.log4j.Logger;
import org.sparkproject.guava.primitives.Ints;

import java.util.Arrays;

/**
 * @author P Trolard
 *         Date: 18/11/2013
 */
public class HalfMergeSortJoinOperation extends MergeJoinOperation {
    private static final Logger LOG = Logger.getLogger(HalfMergeSortJoinOperation.class);

    protected static final String NAME = HalfMergeSortJoinOperation.class.getSimpleName().replaceAll("Operation","");

	@Override
	public String getName() {
			return NAME;
	}


    public HalfMergeSortJoinOperation() {
        super();
    }

    public HalfMergeSortJoinOperation(SpliceOperation leftResultSet,
                                      int leftNumCols,
                                      SpliceOperation rightResultSet,
                                      int rightNumCols,
                                      int leftHashKeyItem,
                                      int rightHashKeyItem,
                                      Activation activation,
                                      GeneratedMethod restriction,
                                      int resultSetNumber,
                                      boolean oneRowRightSide,
                                      boolean notExistsRightSide,
                                      double optimizerEstimatedRowCount,
                                      double optimizerEstimatedCost,
                                      String userSuppliedOptimizerOverrides)
            throws StandardException {
        super(leftResultSet, leftNumCols, rightResultSet, rightNumCols, leftHashKeyItem, rightHashKeyItem, activation, restriction,
                resultSetNumber, oneRowRightSide, notExistsRightSide, optimizerEstimatedRowCount, optimizerEstimatedCost, userSuppliedOptimizerOverrides);
    }

    @Override
    public DataSet<LocatedRow> getDataSet(DataSetProcessor dsp) throws StandardException {
        OperationContext<JoinOperation> operationContext = dsp.<JoinOperation>createOperationContext(this);
        DataSet<LocatedRow> left = leftResultSet.getDataSet(dsp);

        operationContext.pushScope();
        try {
            PairDataSet leftDataSet = left.map(new CountJoinedLeftFunction(operationContext))
                    .keyBy(new KeyerFunction<LocatedRow, JoinOperation>(operationContext, leftHashKeys));

            DataSet<LocatedRow> sorted = leftDataSet.partitionBy(getPartitioner(dsp), new RowComparator(getRightOrder(), true)).values();
            if (isOuterJoin)
                return sorted.mapPartitions(new MergeOuterJoinFlatMapFunction(operationContext));
            else {
                if (notExistsRightSide)
                    return sorted.mapPartitions(new MergeAntiJoinFlatMapFunction(operationContext));
                else {
                    return sorted.mapPartitions(new MergeInnerJoinFlatMapFunction(operationContext));
                }
            }
        } finally {
            operationContext.popScope();
        }
    }

    private ScanOperation getScanOperation(ResultSet resultSet) {
        if (resultSet instanceof ScanOperation) {
            return (ScanOperation) resultSet;
        } else if (resultSet instanceof ProjectRestrictOperation) {
            return getScanOperation(((ProjectRestrictOperation)resultSet).getSource());
        } else if (resultSet instanceof IndexRowToBaseRowOperation)
            return getScanOperation(((IndexRowToBaseRowOperation)resultSet).getSource());
        return null;
    }

    private boolean[] getRightOrder() throws StandardException {
        ScanOperation scanOperation = getScanOperation(rightResultSet);

        boolean[] ascDescInfo = scanOperation.getAscDescInfo();
        boolean[] result = new boolean[scanOperation.getKeyDecodingMap().length];
        if (ascDescInfo == null) {
            // primary-key, all ascending
            Arrays.fill(result, true);
            return result;
        }
        return ascDescInfo;
    }

    private Partitioner getPartitioner(DataSetProcessor dsp) throws StandardException {
        ScanOperation scanOperation = getScanOperation(rightResultSet);
        scanOperation.getExecRowDefinition().getNewNullRow();
        return dsp.getPartitioner(rightResultSet.getDataSet(dsp),scanOperation.getExecRowDefinition().getNewNullRow()
                , scanOperation.getKeyDecodingMap(), getRightOrder(),rightHashKeys);
    }
}
