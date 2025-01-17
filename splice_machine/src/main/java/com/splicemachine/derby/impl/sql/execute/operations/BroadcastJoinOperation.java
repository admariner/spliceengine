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

package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.GlobalDBProperties;
import com.splicemachine.db.iapi.services.loader.GeneratedMethod;
import com.splicemachine.db.iapi.services.property.PropertyUtil;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.stream.function.*;
import com.splicemachine.derby.stream.function.broadcast.BroadcastJoinFlatMapFunction;
import com.splicemachine.derby.stream.function.broadcast.CogroupBroadcastJoinFunction;
import com.splicemachine.derby.stream.function.broadcast.SubtractByKeyBroadcastJoinFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.derby.stream.iapi.OperationContext;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.utils.SpliceLogUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.log4j.Logger;

import java.io.IOException;

/**
 *
 * BroadcastJoinOperation
 *
 * There are 6 different relational processing paths determined by the different valid combinations of these boolean
 * fields (getJoinType, antiJoin, hasRestriction).  For more detail on these paths please check out:
 *
 * @see com.splicemachine.derby.impl.sql.execute.operations.JoinOperation
 *
 * Before determining the different paths, each operation retrieves its left and right datasets and keys them by a Keyer Function.
 *
 * @see com.splicemachine.derby.iapi.sql.execute.SpliceOperation#getDataSet(com.splicemachine.derby.stream.iapi.DataSetProcessor)
 * @see com.splicemachine.derby.stream.iapi.DataSet
 * @see DataSet#keyBy(com.splicemachine.derby.stream.function.SpliceFunction)
 * @see com.splicemachine.derby.stream.function.KeyerFunction
 *
 * Once each dataset is keyed, the following logic is performed for the appropriate processing path.
 *
 * 1. (inner,join,no restriction)
 *     Flow:  leftDataSet -> broadcastJoin (rightDataSet) -> map (InnerJoinFunction)
 *
 * @see com.splicemachine.derby.stream.iapi.PairDataSet#broadcastJoin(com.splicemachine.derby.stream.iapi.PairDataSet)
 * @see com.splicemachine.derby.stream.function.InnerJoinFunction
 *
 * 2. (inner,join,restriction)
 *     Flow:  leftDataSet -> broadcastLeftOuterJoin (RightDataSet)
 *     -> map (OuterJoinPairFunction) - filter (JoinRestrictionPredicateFunction)
 *
 * @see com.splicemachine.derby.stream.iapi.PairDataSet#broadcastLeftOuterJoin(com.splicemachine.derby.stream.iapi.PairDataSet)
 * @see com.splicemachine.derby.stream.function.OuterJoinPairFunction
 * @see com.splicemachine.derby.stream.function.JoinRestrictionPredicateFunction
 *
 * 3. (inner,antijoin,no restriction)
 *     Flow:  leftDataSet -> broadcastSubtractByKey (rightDataSet) -> map (AntiJoinFunction)
 *
 * @see com.splicemachine.derby.stream.iapi.PairDataSet#broadcastSubtractByKey(com.splicemachine.derby.stream.iapi.PairDataSet)
 * @see com.splicemachine.derby.stream.function.AntiJoinFunction
 *
 * 4. (inner,antijoin,restriction)
 *     Flow:  leftDataSet -> broadcastLeftOuterJoin (rightDataSet) -> map (AntiJoinRestrictionFlatMapFunction)
 *
 * @see com.splicemachine.derby.stream.iapi.PairDataSet#broadcastLeftOuterJoin(com.splicemachine.derby.stream.iapi.PairDataSet)
 * @see com.splicemachine.derby.stream.function.AntiJoinRestrictionFlatMapFunction
 *
 * 5. (outer,join,no restriction)
 *     Flow:  leftDataSet -> broadcastLeftOuterJoin (rightDataSet) -> map (OuterJoinPairFunction)
 *
 * @see com.splicemachine.derby.stream.iapi.PairDataSet#broadcastLeftOuterJoin(com.splicemachine.derby.stream.iapi.PairDataSet)
 * @see com.splicemachine.derby.stream.function.OuterJoinPairFunction
 *
 * 6. (outer,join,restriction)
 *     Flow:  leftDataSet -> broadcastLeftOuterJoin (rightDataSet) -> map (OuterJoinPairFunction)
 *     -> Filter (JoinRestrictionPredicateFunction)
 *
 * @see com.splicemachine.derby.stream.iapi.PairDataSet#broadcastLeftOuterJoin(com.splicemachine.derby.stream.iapi.PairDataSet)
 * @see com.splicemachine.derby.stream.function.OuterJoinPairFunction
 * @see com.splicemachine.derby.stream.function.JoinRestrictionPredicateFunction
 *
 *
 */

public class BroadcastJoinOperation extends JoinOperation{
    private static final long serialVersionUID=2l;
    private static Logger LOG=Logger.getLogger(BroadcastJoinOperation.class);
    protected int leftHashKeyItem;
    protected int[] leftHashKeys;
    protected int rightHashKeyItem;
    protected int[] rightHashKeys;
    protected long rightSequenceId;
    protected long leftSequenceId;
    protected boolean noCacheBroadcastJoinRight;
    protected static final String NAME = BroadcastJoinOperation.class.getSimpleName().replaceAll("Operation","");

	@Override
	public String getName() {
			return NAME;
	}

    public BroadcastJoinOperation() {
        super();
    }

    public BroadcastJoinOperation(SpliceOperation leftResultSet,
                                  int leftNumCols,
                                  SpliceOperation rightResultSet,
                                  int rightNumCols,
                                  int leftHashKeyItem,
                                  int rightHashKeyItem,
                                  boolean noCacheBroadcastJoinRight,
                                  Activation activation,
                                  GeneratedMethod restriction,
                                  int resultSetNumber,
                                  boolean oneRowRightSide,
                                  byte semiJoinType,
                                  boolean rightFromSSQ,
                                  double optimizerEstimatedRowCount,
                                  double optimizerEstimatedCost,
                                  String userSuppliedOptimizerOverrides,
                                  String sparkExpressionTreeAsString) throws
            StandardException{
        super(leftResultSet,leftNumCols,rightResultSet,rightNumCols,
                activation,restriction,resultSetNumber,oneRowRightSide, semiJoinType, rightFromSSQ,
                optimizerEstimatedRowCount,optimizerEstimatedCost,userSuppliedOptimizerOverrides, sparkExpressionTreeAsString);
        this.leftHashKeyItem=leftHashKeyItem;
        this.rightHashKeyItem=rightHashKeyItem;
        this.rightSequenceId = Bytes.toLong(operationInformation.getUUIDGenerator().nextBytes());
        this.leftSequenceId = Bytes.toLong(operationInformation.getUUIDGenerator().nextBytes());
        this.noCacheBroadcastJoinRight = noCacheBroadcastJoinRight;
        init();
    }

    public long getRightSequenceId() {
        return rightSequenceId;
    }

    public long getLeftSequenceId() {
        return leftSequenceId;
    }

    @Override
    public void init(SpliceOperationContext context) throws StandardException, IOException {
        super.init(context);
        leftHashKeys = generateHashKeys(leftHashKeyItem);
        rightHashKeys = generateHashKeys(rightHashKeyItem);
    }

    @Override
    public SpliceOperation getLeftOperation(){
        return leftResultSet;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public DataSet<ExecRow> getDataSet(DataSetProcessor dsp) throws StandardException {
        if (!isOpen)
            throw new IllegalStateException("Operation is not open");

        OperationContext operationContext = dsp.createOperationContext(this);

        boolean useDataset = true;

        /** TODO don't know how to let spark report SQLState.LANG_SCALAR_SUBQUERY_CARDINALITY_VIOLATION error,
         * so route to the rdd implementation for now for SSQ.
         */
        if (rightFromSSQ)
            useDataset = false;

        DataSet<ExecRow> result;
        boolean usesNativeSparkDataSet =
           (useDataset && dsp.getType().equals(DataSetProcessor.Type.SPARK) &&
             ((restriction == null || hasSparkJoinPredicate()) || (!isOuterJoin() && !isAntiJoin() && !isOneRowRightSide())) &&
              !containsUnsafeSQLRealComparison());

        dsp.incrementOpDepth();
        if (usesNativeSparkDataSet)
            dsp.finalizeTempOperationStrings();
        DataSet<ExecRow> leftDataSet = leftResultSet.getDataSet(dsp);

//        operationContext.pushScope();
        leftDataSet = leftDataSet.map(new CountJoinedLeftFunction(operationContext));
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "getDataSet Performing BroadcastJoin type=%s, antiJoin=%s, hasRestriction=%s",
                isOuterJoin() ? "outer" : "inner", isAntiJoin(), restriction != null);

        dsp.finalizeTempOperationStrings();

        if (usesNativeSparkDataSet)
        {
            DataSet<ExecRow> rightDataSet = rightResultSet.getDataSet(dsp);
            dsp.decrementOpDepth();
            if (isOuterJoin())
                result = leftDataSet.join(operationContext,rightDataSet, DataSet.JoinType.LEFTOUTER,true);
            else if (isAntiJoin())
                result = leftDataSet.join(operationContext,rightDataSet, DataSet.JoinType.LEFTANTI,true);

            else { // Inner Join
                if (isInclusionJoin()) {
                    result = leftDataSet.join(operationContext,rightDataSet, DataSet.JoinType.LEFTSEMI,true);
                } else {
                    result = leftDataSet.join(operationContext,rightDataSet, DataSet.JoinType.INNER,true);
                }
                // Adding a filter in this manner disables native spark execution,
                // so only do it if required.
                boolean varcharDB2CompatibilityMode = PropertyUtil.getCachedBoolean(
                        operationContext.getActivation().getLanguageConnectionContext(),
                        GlobalDBProperties.SPLICE_DB2_VARCHAR_COMPATIBLE);
                if (restriction != null && (varcharDB2CompatibilityMode || sparkJoinPredicate == null)) {
                    result = result.filter(new JoinRestrictionPredicateFunction(operationContext));
                }
            }
            handleSparkExplain(result, leftDataSet, rightDataSet, dsp);
        }
        else {
            if (isOuterJoin()) { // Left Outer Join with and without restriction
                result = leftDataSet.mapPartitions(new CogroupBroadcastJoinFunction(operationContext, noCacheBroadcastJoinRight))
                        .flatMap(new LeftOuterJoinRestrictionFlatMapFunction<SpliceOperation>(operationContext))
                        .map(new SetCurrentLocatedRowFunction<SpliceOperation>(operationContext));
            } else {
                if (this.leftHashKeys.length != 0 && !isAntiJoin())
                    leftDataSet = leftDataSet.filter(new InnerJoinNullFilterFunction(operationContext,this.leftHashKeys));
                if (isAntiJoin()) { // antijoin
                    if (restriction != null) { // with restriction
                        result = leftDataSet.mapPartitions(new CogroupBroadcastJoinFunction(operationContext, noCacheBroadcastJoinRight))
                                .flatMap(new AntiJoinRestrictionFlatMapFunction(operationContext));
                    } else { // No Restriction
                        result = leftDataSet.mapPartitions(new SubtractByKeyBroadcastJoinFunction(operationContext, noCacheBroadcastJoinRight))
                                .map(new AntiJoinFunction(operationContext));
                    }
                } else { // Inner Join
                    // if inclusion join or regular inner join with one matching row on right
                    if (isOneRowRightSide()) {
                        result = leftDataSet.mapPartitions(new CogroupBroadcastJoinFunction(operationContext, noCacheBroadcastJoinRight))
                                .flatMap(new InnerJoinRestrictionFlatMapFunction(operationContext));
                    } else {
                        result = leftDataSet.mapPartitions(new BroadcastJoinFlatMapFunction(operationContext, noCacheBroadcastJoinRight))
                                .map(new InnerJoinFunction<SpliceOperation>(operationContext));

                        if (restriction != null) { // with restriction
                            result = result.filter(new JoinRestrictionPredicateFunction(operationContext));
                        }
                    }
                }
                if (dsp.isSparkExplain()) {
                    // Need to call getDataSet to fully print the spark explain.
                    DataSet<ExecRow> rightDataSet = rightResultSet.getDataSet(dsp);
                    dsp.decrementOpDepth();
                    handleSparkExplain(result, leftDataSet, rightDataSet, dsp);
                }
            }
        }

        result = result.map(new CountProducedFunction(operationContext), /*isLast=*/true);

//        operationContext.popScope();

        return result;
    }

    public String getPrettyExplainPlan() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.getPrettyExplainPlan());
        sb.append("\n\nBroadcast Join Right Side:\n\n");
        sb.append(getRightOperation() != null ? getRightOperation().getPrettyExplainPlan() : "");
        return sb.toString();
    }
    
    @SuppressFBWarnings(value = "EI_EXPOSE_REP",justification = "Intentional")
    public int[] getRightHashKeys() {
        return rightHashKeys;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP",justification = "Intentional")
    public int[] getLeftHashKeys() {
        return leftHashKeys;
    }
}
