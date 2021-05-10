/*
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Some parts of this source code are based on Apache Derby, and the following notices apply to
 * Apache Derby:
 *
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified the Apache Derby code in this file.
 *
 * All such Splice Machine modifications are Copyright 2012 - 2020 Splice Machine, Inc.,
 * and are licensed to you under the GNU Affero General Public License.
 */

package com.splicemachine.db.impl.sql.compile;

import com.splicemachine.db.catalog.IndexDescriptor;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.compile.CostEstimate;
import com.splicemachine.db.iapi.sql.compile.Optimizable;
import com.splicemachine.db.iapi.sql.compile.costing.ScanCostEstimator;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.dictionary.ColumnDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptor;
import com.splicemachine.db.iapi.store.access.StoreCostController;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * A Mutable object representing a builder for the Table-scan cost.
 *
 * In practice, the act of estimating the cost of a table scan is really quite convoluted, as it depends
 * a great deal on the type and nature of the predicates which are passed in. To resolve this complexity, and
 * to support flexible behaviors, we use this builder pattern instead of direct coding.
 *
 * An access path of a base table includes at maximum three parts:
 * 1) Scanning the base conglomerate. This can be a scan on a base table or an index.
 * 2) Looking up a base row according to an index. This happens only when the index is not covering.
 * 3) Projecting columns away and further reject rows by evaluating remaining predicates.
 *
 * With the support of index on expressions, columns scanned in phase 1 are not necessarily base table columns.
 * Further, the numbers of scanned columns are not bound to the number of base table columns, either. For this
 * reason, we split the list of SelectivityHolder into two parts. SCAN is for phase 1 and TOP is for phase 2
 * and 3.
 *
 * @author Scott Fines
 *         Date: 5/15/15
 */
public abstract class AbstractScanCostEstimator implements ScanCostEstimator {
    public static final Logger LOG = Logger.getLogger(AbstractScanCostEstimator.class);

    protected static final int SCAN = 0;  // qualifier phase: BASE, FILTER_BASE
    protected static final int TOP  = 1;  // qualifier phase: FILTER_PROJECTION
    /* These values are based on the results of index lookup microbenchmark (DB-11737) performed
     * on an EKS cluster with 4 OLTP + 4 OLAP pods.
     * Next step is to make them tunable (maybe as configurable parameters in site.xml). These
     * numbers can be used as defaults. In theory, they can be set based on results of running
     * index lookup microbenchmark in target environment.
     */
    private static final int OLTP_INDEXLOOKUP_COST_PER_ROW = 125;
    private static final int OLAP_INDEXLOOKUP_COST_PER_ROW = 18;
    private static final int OLAP_INDEXLOOKUP_STARTUP_COST = 85000;

    protected final Optimizable            baseTable;
    protected final ConglomerateDescriptor cd;
    protected final IndexDescriptor        indexDescriptor;
    private final boolean isIndex;
    private final boolean isPrimaryKey;
    protected final boolean isIndexOnExpression;

    protected final CostEstimate scanCost;
    protected final StoreCostController scc;

    // resultColumns from the base table
    // at this point, this seems to be always full column list because no access path is chosen
    private final ResultColumnList resultColumns;

    // base columns returned from scanning phase
    protected final BitSet baseColumnsInScan;

    // base columns returned from looking up phase
    protected final BitSet baseColumnsInLookup;

    // whether it's possible to consider further scan predicates or not
    protected boolean scanPredicatePossible = true;

    // positions of columns used in estimating selectivity but missing real statistics
    private final HashSet<Integer> usedNoStatsColumnIds;

    // when index lookup is needed, the maximum number of rows that can be fired as a batch
    // controlled by splice.index.batchSize, default value in SQLConfiguration
    private final int indexLookupBatchRowCount;

    // when index lookup is needed, the maximum number of batches that can be fired concurrently
    // controlled by splice.index.numConcurrentLookups, default value in SQLConfiguration
    private final int indexLookupConcurrentBatchesCount;

    // will be used shortly
    private final boolean forUpdate;

    protected final boolean isOlap;

    // selectivity elements for scanning phase
    protected final List<SelectivityHolder>[] scanSelectivityHolder;

    // selectivity elements for look up and projection phase
    protected final List<SelectivityHolder>[] topSelectivityHolder;

    // for base tables, this is null
    // for normal indexes and primary keys, stores column references to the base table
    // for indexes on expressions, stores the ASTs of index expressions in their defined order
    protected final ValueNode[] indexColumns;

    // cost of evaluating all expressions used in added predicates per row
    protected double exprEvalCostPerRow = 0.0;

    /**
     *     Selectivity is computed at 3 levels.
     *     BASE -> Qualifiers on the row keys (start/stop Qualifiers, Multiprobe)
     *     FILTER_BASE -> Qualifiers applied after the scan but before the index lookup.
     *     FILTER_PROJECTION -> Qualifers and Predicates applied after any potential lookup, usually performed on the Projection Node in the scan.
     *
     *  @param baseTable  A base table on which a scan cost is going to be estimated.
     *  @param cd         A conglomerate descriptor of the base table to be considered.
     *  @param scc        A StoreCostController instance.
     *  @param scanCost   A CostEstimate instance where the result will be stored. Output parameter.
     *  @param resultColumns  The result columns of the base table.
     *  @param scanRowTemplate  The row template of the base table.
     *  @param baseColumnsInScan  The set of columns from the base table that will be scanned in store.
     *  @param baseColumnsInLookup  The set of columns that has to be looked up because of a non-covering index.
     *  @param indexLookupBatchRowCount  Maximum number of rows for which index lookup operation can be batched together.
     *  @param indexLookupConcurrentBatchesCount  Maximum number of index lookup batches that can run concurrently.
     *  @param forUpdate  Whether the base table is updatable (see forUpdate() for detailed explanation) or not (currently unused).
     *  @param isOlap  Whether estimating a cost for OLAP or not.
     *  @param usedNoStatsColumnIds  A set of columns which do not have statistics but should have to improve cost estimation. Output parameter.
     */
    public AbstractScanCostEstimator(Optimizable baseTable,
                            ConglomerateDescriptor cd,
                            StoreCostController scc,
                            CostEstimate scanCost,
                            ResultColumnList resultColumns,
                            DataValueDescriptor[] scanRowTemplate,
                            BitSet baseColumnsInScan,
                            BitSet baseColumnsInLookup,
                            int indexLookupBatchRowCount,
                            int indexLookupConcurrentBatchesCount,
                            boolean forUpdate,
                            boolean isOlap,
                            HashSet<Integer> usedNoStatsColumnIds) throws StandardException {
        this.baseTable=baseTable;
        this.cd = cd;
        this.indexDescriptor = cd.getIndexDescriptor();
        this.isIndex = cd.isIndex();
        this.isPrimaryKey = cd.isPrimaryKey();
        this.isIndexOnExpression = isIndex && indexDescriptor.isOnExpression();
        this.scanCost = scanCost;
        this.scc = scc;
        this.resultColumns = resultColumns;
        this.baseColumnsInScan = baseColumnsInScan;
        this.baseColumnsInLookup = baseColumnsInLookup;
        this.indexLookupBatchRowCount = indexLookupBatchRowCount;
        this.indexLookupConcurrentBatchesCount = indexLookupConcurrentBatchesCount;
        this.forUpdate = forUpdate;
        this.isOlap = isOlap;
        this.usedNoStatsColumnIds = usedNoStatsColumnIds;

        /* We always allocate one extra column in a selectivity holder array because column
         * positions are 1-based, and slot index 0 is used for storing predicates fall into
         * default selectivity estimation.
         */
        int numColumnsInScan;
        int numColumnsInTop;
        if (isIndexOnExpression) {
            /* For an index row, scanRowTemplate has one extra column for the source row key.
             * It's impossible to have predicates on source row key, so we can safely use
             * slot index 0 for storing default selectivity estimation.
             */
            numColumnsInScan = scanRowTemplate.length;
            numColumnsInTop = baseColumnsInLookup == null ? numColumnsInScan /* covering index */ : resultColumns.size() + 1;
        } else {
            numColumnsInScan = resultColumns.size() + 1;
            numColumnsInTop = numColumnsInScan;
        }
        this.scanSelectivityHolder = new List[numColumnsInScan];
        this.topSelectivityHolder = new List[numColumnsInTop];

        this.indexColumns = getIndexColumns();
        correctBaseColumnInfo();
    }

    private ValueNode[] getIndexColumns() throws StandardException {
        if (!isIndex && !isPrimaryKey) {
            return null;
        }

        ValueNode[] indexColumns;
        if (isIndexOnExpression) {
            assert baseTable instanceof QueryTreeNode;
            LanguageConnectionContext lcc = ((QueryTreeNode)baseTable).getLanguageConnectionContext();
            indexColumns = indexDescriptor.getParsedIndexExpressions(lcc, baseTable);
        } else {
            int[] keyColumns = indexDescriptor.baseColumnPositions();
            indexColumns = new ValueNode[keyColumns.length];
            for (int i = 0; i < keyColumns.length; i++) {
                ColumnReference cr = resultColumns.getResultColumn(keyColumns[i]).getColumnReference(null);
                cr.setTableNumber(baseTable.getTableNumber());
                indexColumns[i] = cr;
            }
        }
        return indexColumns;
    }

    private void correctBaseColumnInfo() {
        if (isIndexOnExpression) {
            if (baseColumnsInLookup != null) {
                // for a non-covering index defined on expressions, we potentially look up all base columns
                baseColumnsInLookup.or(baseColumnsInScan);
            }
            // no base column is scanned when scanning an index defined on expressions
            baseColumnsInScan.clear();
        }
    }

    protected int getTotalNumberOfBaseColumnsInvolved() {
        BitSet result = new BitSet(resultColumns.size());
        result.or(baseColumnsInScan);
        if (baseColumnsInLookup != null) {
            result.or(baseColumnsInLookup);
        }
        return result.cardinality();
    }

    private int mapQualifierPhaseToPhase(QualifierPhase qPhase) {
        switch (qPhase) {
            case BASE:
            case FILTER_BASE:
                return SCAN;
            case FILTER_PROJECTION:
                return TOP;
            default:
                throw new RuntimeException("invalid QualifierPhase value");
        }
    }

    /**
     *
     * Add Selectivity to the selectivity holder.
     *
     * @param holder
     */
    protected void addSelectivity(SelectivityHolder holder, int phase) {
        List<SelectivityHolder>[] selectivityHolder =
                phase == SCAN ? scanSelectivityHolder : topSelectivityHolder;
        List<SelectivityHolder> holders = selectivityHolder[holder.getColNum()];
        if (holders == null) {
            holders = new LinkedList<>();
            selectivityHolder[holder.getColNum()] = holders;
        }
        holders.add(holder);
    }

    /**
     *
     * Retrieve the selectivity for the columns.
     *
     * @param colNum
     * @return
     */
    private List<SelectivityHolder> getSelectivityListForColumn(int colNum, int phase) {
        List<SelectivityHolder>[] selectivityHolder =
                phase == SCAN ? scanSelectivityHolder : topSelectivityHolder;
        List<SelectivityHolder> holders = selectivityHolder[colNum];
        if (holders == null) {
            holders = new LinkedList<>();
            selectivityHolder[colNum] = holders;
        }
        return holders;
    }

    protected void collectNoStatsColumnsFromInListPred(Predicate p) throws StandardException {
        if (p.getSourceInList() != null) {
            for (Object o : p.getSourceInList().leftOperandList) {
                List<ColumnReference> crList = ((ValueNode)o).getHashableJoinColumnReference();
                for (ColumnReference cr : crList) {
                    if (!scc.useRealColumnStatistics(cr.isGeneratedToReplaceIndexExpression(), cr.getColumnNumber()))
                        usedNoStatsColumnIds.add(cr.getColumnNumber());
                }
            }
        }
    }

    protected void collectNoStatsColumnsFromUnaryAndBinaryPred(Predicate p) {
        if (p.getRelop() != null) {
            ColumnReference cr = p.getRelop().getColumnOperand(baseTable);
            if (cr != null && !scc.useRealColumnStatistics(cr.isGeneratedToReplaceIndexExpression(), cr.getColumnNumber())) {
                usedNoStatsColumnIds.add(cr.getColumnNumber());
            }
        }
    }

    protected void accumulateExprEvalCost(Predicate p) throws StandardException {
        exprEvalCostPerRow += p.getAndNode().getLeftOperand().getBaseOperationCost();
    }

    /**
     *
     * Performs qualifier selectivity based on a qualifierPhase.
     *
     * @param p
     * @param qualifierPhase
     * @throws StandardException
     */
    protected void performQualifierSelectivity(Predicate p, QualifierPhase qualifierPhase, boolean forIndexExpr, double selectivityFactor, int phase) throws StandardException {
        if(p.compareWithKnownConstant(baseTable, true) &&
                (p.getRelop().getColumnOperand(baseTable) != null ||
                        (forIndexExpr && p.getRelop().getExpressionOperand(baseTable.getTableNumber(), -1, (FromTable)baseTable, true) != null) && p.getIndexPosition() >= 0))
        {
            // Range Qualifier
            addRangeQualifier(p, qualifierPhase, forIndexExpr, selectivityFactor);
        }
        else if(p.isBetween() && forIndexExpr && ((BetweenOperatorNode) p.getAndNode().getLeftOperand()).compareWithKnownConstant(true)) {
            addRangeQualifier(p, qualifierPhase, true, selectivityFactor);
        }
        else {
            // Predicate Cannot Be Transformed to Range, use Predicate Selectivity Defaults
            addSelectivity(new DefaultPredicateSelectivity(p, baseTable, qualifierPhase, selectivityFactor), phase);
        }
    }

    /**
     * Computing the total selectivity.  All conglomerates need to have the same total selectivity.
     */
    public static double computeTotalSelectivity(List<SelectivityHolder>[] scanSelectivityHolder,
                                                 List<SelectivityHolder>[] topSelectivityHolder) throws StandardException {
        double totalSelectivity = 1.0d;
        List<SelectivityHolder> holders = new ArrayList();
        for (List<SelectivityHolder> aSelectivityHolder : scanSelectivityHolder) {
            if (aSelectivityHolder != null)
                holders.addAll(aSelectivityHolder);
        }
        for (List<SelectivityHolder> aSelectivityHolder : topSelectivityHolder) {
            if (aSelectivityHolder != null)
                holders.addAll(aSelectivityHolder);
        }
        Collections.sort(holders);
        return computeSelectivity(totalSelectivity,holders);
    }


    /**
     *
     * Gathers the selectivities for the phases and sorts them ascending (most selective first) and then supplied them to computeSelectivity.
     *
     * @param scanSelectivityHolder
     * @param topSelectivityHolder
     * @param phases
     * @return
     * @throws StandardException
     */
    public static double computePhaseSelectivity(List<SelectivityHolder>[] scanSelectivityHolder,
                                                 List<SelectivityHolder>[] topSelectivityHolder,
                                                 QualifierPhase... phases) throws StandardException {
        double totalSelectivity = 1.0d;
        List<SelectivityHolder> holders = new ArrayList();
        collectSelectivityHolders(scanSelectivityHolder, holders, phases);
        collectSelectivityHolders(topSelectivityHolder, holders, phases);
        Collections.sort(holders);
        return computeSelectivity(totalSelectivity,holders);
    }

    private static void collectSelectivityHolders(List<SelectivityHolder>[] from, List<SelectivityHolder> to,
                                                  QualifierPhase... phases) {
        for (List<SelectivityHolder> aSelectivityHolder : from) {
            if (aSelectivityHolder != null) {
                for (SelectivityHolder holder : aSelectivityHolder) {
                    for (QualifierPhase phase : phases) {
                        if (holder.getPhase().equals(phase))
                            to.add(holder); // Only add Phased Qualifiers
                    }
                }
            }
        }
    }

    /**
     *
     * Helper method to compute increasing sqrt levels.
     *
     * @param selectivity
     * @param holders
     * @return
     * @throws StandardException
     */
    public static double computeSelectivity(double selectivity, List<SelectivityHolder> holders) throws StandardException {
        int level = 0;
        for (int i = 0; i< holders.size();i++) {
            // Do not include join predicates unless join strategy is nested loop.
            if (holders.get(i).shouldApplySelectivity()) {
                selectivity = computeSqrtLevel(selectivity, level, holders.get(i));
                level++;
            }
        }
        return selectivity;
    }

    /**
     *
     * Compute SQRT selectivity based on the level.
     *
     * @param selectivity
     * @param level
     * @param holder
     * @return
     * @throws StandardException
     */
    public static double computeSqrtLevel(double selectivity, int level, SelectivityHolder holder) throws StandardException {
        if (level ==0) {
            selectivity *= holder.getSelectivity();
            return selectivity;
        }
        double incrementalSelectivity = 0.0d;
        incrementalSelectivity += holder.getSelectivity();
        for (int i =1;i<=level;i++)
            incrementalSelectivity=Math.sqrt(incrementalSelectivity);
        selectivity*=incrementalSelectivity;
        return selectivity;
    }

    /**
     *
     * Method to combine range qualifiers a>12 and a< 15 -> range qualifier (12<a<15)
     *
     * @param p
     * @param phase
     * @return
     * @throws StandardException
     */

    private boolean addRangeQualifier(Predicate p, QualifierPhase phase, boolean forIndexExpr, double selectivityFactor)
            throws StandardException
    {
        RelationalOperator relop=p.getRelop();
        boolean useExtrapolation = false;

        int colNum;
        if (forIndexExpr && p.getIndexPosition() >= 0) {
            colNum = p.getIndexPosition() + 1;
        } else {
            ColumnReference cr = relop.getColumnOperand(baseTable);
            ColumnDescriptor columnDescriptor = cr.getSource().getTableColumnDescriptor();
            if (columnDescriptor != null)
                useExtrapolation = columnDescriptor.getUseExtrapolation() != 0;

            colNum = cr.getColumnNumber();
        }

        List<SelectivityHolder> columnHolder = getSelectivityListForColumn(colNum, mapQualifierPhaseToPhase(phase));

        if (p.isBetween()) {
            BetweenOperatorNode bon = (BetweenOperatorNode) p.getAndNode().getLeftOperand();
            DataValueDescriptor start = ((ValueNode) bon.getRightOperandList().elementAt(0)).getKnownConstantValue();
            DataValueDescriptor stop  = ((ValueNode) bon.getRightOperandList().elementAt(1)).getKnownConstantValue();
            columnHolder.add(new RangeSelectivity(scc, start, stop, true, true, forIndexExpr, colNum, phase, selectivityFactor, useExtrapolation, p));
            return true;
        }

        DataValueDescriptor value=p.getCompareValue(baseTable);
        int relationalOperator = relop.getOperator();
        OP_SWITCH: switch(relationalOperator){
            case RelationalOperator.EQUALS_RELOP:
                columnHolder.add(new RangeSelectivity(scc,value,value,true,true, forIndexExpr, colNum,phase, selectivityFactor, useExtrapolation, p));
                break;
            case RelationalOperator.NOT_EQUALS_RELOP:
                for(SelectivityHolder sh: columnHolder){
                    if (sh.isRangeSelectivity()) {
                        RangeSelectivity rq = (RangeSelectivity) sh;
                        boolean combined = combineNEAndRange(scc, value, rq, forIndexExpr, colNum);
                        if (combined) {
                            break OP_SWITCH;
                        }
                    }
                }
                columnHolder.add(new NotEqualsSelectivity(scc, forIndexExpr, colNum, phase, value, selectivityFactor, useExtrapolation, p));
                break;
            case RelationalOperator.IS_NULL_RELOP:
                columnHolder.add(new NullSelectivity(scc, forIndexExpr, colNum, phase, p));
                break;
            case RelationalOperator.IS_NOT_NULL_RELOP:
                columnHolder.add(new NotNullSelectivity(scc, forIndexExpr, colNum, phase, p));
                break;
            case RelationalOperator.GREATER_EQUALS_RELOP:
                for(SelectivityHolder sh: columnHolder){
                    if (!sh.isRangeSelectivity())
                        continue;
                    RangeSelectivity rq = (RangeSelectivity) sh;
                    if(rq.start==null){
                        rq.start = value;
                        rq.includeStart = true;
                        break OP_SWITCH;
                    }
                }
                combineNEAndRange(columnHolder, new RangeSelectivity(scc,value,null,true,true, forIndexExpr, colNum, phase, selectivityFactor, useExtrapolation, p));
                break;
            case RelationalOperator.GREATER_THAN_RELOP:
                for(SelectivityHolder sh: columnHolder){
                    if (!sh.isRangeSelectivity())
                        continue;
                    RangeSelectivity rq = (RangeSelectivity) sh;
                    if(rq.start==null){
                        rq.start = value;
                        rq.includeStart = false;
                        break OP_SWITCH;
                    }
                }
                combineNEAndRange(columnHolder, new RangeSelectivity(scc,value,null,false,true, forIndexExpr, colNum, phase, selectivityFactor, useExtrapolation, p));
                break;
            case RelationalOperator.LESS_EQUALS_RELOP:
                for(SelectivityHolder sh: columnHolder){
                    if (!sh.isRangeSelectivity())
                        continue;
                    RangeSelectivity rq = (RangeSelectivity) sh;
                    if(rq.stop==null){
                        rq.stop = value;
                        rq.includeStop = true;
                        break OP_SWITCH;
                    }
                }
                combineNEAndRange(columnHolder, new RangeSelectivity(scc, null, value, true, true, forIndexExpr, colNum, phase, selectivityFactor, useExtrapolation, p));
                break;
            case RelationalOperator.LESS_THAN_RELOP:
                for(SelectivityHolder sh: columnHolder){
                    if (!sh.isRangeSelectivity())
                        continue;
                    RangeSelectivity rq = (RangeSelectivity) sh;
                    if(rq.stop==null){
                        rq.stop = value;
                        rq.includeStop = false;
                        break OP_SWITCH;
                    }
                }
                combineNEAndRange(columnHolder, new RangeSelectivity(scc, null, value, true, false, forIndexExpr, colNum, phase, selectivityFactor, useExtrapolation, p));
                break;
            default:
                throw new RuntimeException("Unknown Qualifier Type");
         }
        return true;
    }

    /* Handle cases where NE value falls into a range as a boundary value and this boundary value is highly skewed.
     * Example:
     * A column that has values [0, 0, 0, ...(80 0s), 1, 2, 3, ... , 20]. Among 100 values, there are 80 zeros.
     * Consider predicates "COL < 3 AND COL <> 0" and we don't combine NE and range predicates.
     * sel(COL <> 0) = 0.2, sel(COL < 3) = 0.82, sel_total = 0.2 * sqrt(0.82) = 0.18.
     * Actually, if NE value is highly skewed, other predicates do not matter that much because the NE predicate is
     * too selective and dominates the estimation. Suppose it's (COL <> 0 AND COL < 18), we see that total
     * selectivity is 0.198 and it's not a big difference to 0.18. However, the former case returns 2 rows, while
     * the latter returns 17 rows. In reality, this could be a big difference in the number of estimated rows.
     *
     * Note that in case of open ranges (start / stop == null), this procedure could under estimates when statistics
     * are outdated over time when min/max are different.
     */
    private boolean combineNEAndRange(StoreCostController scc, DataValueDescriptor neValue, RangeSelectivity rsHolder,
                                      boolean forIndexExpr, int colNum) throws StandardException {
        boolean combined = false;
        if (rsHolder.includeStart) {
            if (rsHolder.start != null && neValue.compare(rsHolder.start) == 0) {
                rsHolder.includeStart = false;
                combined = true;
            } else if (rsHolder.start == null && neValue.compare(scc.minValue(forIndexExpr, colNum)) == 0) {
                rsHolder.start = neValue;
                rsHolder.includeStart = false;
                combined = true;
            }
        }
        if (rsHolder.includeStop) {
            if (rsHolder.stop != null && neValue.compare(rsHolder.stop) == 0) {
                rsHolder.includeStop = false;
                combined = true;
            } else if (rsHolder.stop == null && neValue.compare(scc.maxValue(forIndexExpr, colNum)) == 0) {
                rsHolder.stop = neValue;
                rsHolder.includeStop = false;
                combined = true;
            }
        }
        return combined;
    }

    private void combineNEAndRange(List<SelectivityHolder> columnHolders, RangeSelectivity rsHolder) throws StandardException {
        List<SelectivityHolder> toRemove = new ArrayList<>();
        for (SelectivityHolder sh : columnHolders) {
            if (sh instanceof NotEqualsSelectivity) {
                NotEqualsSelectivity neq = (NotEqualsSelectivity) sh;
                boolean combined = combineNEAndRange(scc, neq.value, rsHolder, rsHolder.useExprIndexStats, rsHolder.colNum);
                if (combined) {
                    toRemove.add(sh);
                }
            }
        }
        columnHolders.removeAll(toRemove);
        columnHolders.add(rsHolder);
    }

    protected double estimateIndexLookupCost(double lookupRowsCount, double openLatency, double closeLatency) {
        if (isOlap || lookupRowsCount == 1.0) {  // OLTP formula is simplified to OLAP formula if row count == 1
            return lookupRowsCount * getIndexLookupCostPerRow() + openLatency + closeLatency
                    + (isOlap ? OLAP_INDEXLOOKUP_STARTUP_COST : 0);
        } else {
            // a whole batch is a batch containing 'indexLookupBatchRowCount' rows
            // if lookupRowsCount < indexLookupBatchRowCount, wholeBatchesCount == 0
            double wholeBatchesCount = Math.floor(lookupRowsCount / indexLookupBatchRowCount);
            double oneWholeBatchCost = indexLookupBatchRowCount * getIndexLookupCostPerRow() + openLatency + closeLatency;
            double serialBatchesCount = Math.max(wholeBatchesCount / indexLookupConcurrentBatchesCount, 1);

            boolean considerLastBatchSeparately = wholeBatchesCount % indexLookupConcurrentBatchesCount == 0;
            if (considerLastBatchSeparately) {
                // if we have k serial batch groups but the last batch group contains only one batch that is not a whole
                // batch, calculate the cost of last batch separately
                double lastBatchRowsCount = lookupRowsCount % indexLookupBatchRowCount;
                double lastBatchCost = lastBatchRowsCount * getIndexLookupCostPerRow() + openLatency + closeLatency;
                return oneWholeBatchCost * (wholeBatchesCount == 0 ? 0 : serialBatchesCount)
                        + (lastBatchRowsCount == 0 ? 0 : lastBatchCost);
            } else {
                // if the last serial batch group has at least one whole batch, take it as a complete serial step
                return oneWholeBatchCost * Math.ceil(serialBatchesCount);
            }
        }
    }

    private double getIndexLookupCostPerRow() {
        return isOlap ? OLAP_INDEXLOOKUP_COST_PER_ROW : OLTP_INDEXLOOKUP_COST_PER_ROW;
    }
}