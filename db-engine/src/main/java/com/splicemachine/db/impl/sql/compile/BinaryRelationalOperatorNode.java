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
 import com.splicemachine.db.iapi.reference.ClassName;
 import com.splicemachine.db.iapi.services.compiler.MethodBuilder;
 import com.splicemachine.db.iapi.services.sanity.SanityManager;
 import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;
 import com.splicemachine.db.iapi.sql.compile.Optimizable;
 import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptor;
 import com.splicemachine.db.iapi.sql.dictionary.IndexRowGenerator;
 import com.splicemachine.db.iapi.store.access.ScanController;
 import com.splicemachine.db.iapi.store.access.StoreCostController;
 import com.splicemachine.db.iapi.types.DataValueDescriptor;
 import com.splicemachine.db.iapi.types.Orderable;
 import com.splicemachine.db.iapi.types.TypeId;
 import com.splicemachine.db.iapi.util.JBitSet;

 import java.sql.Types;
 import java.util.HashSet;
 import java.util.List;

 import static com.splicemachine.db.impl.sql.compile.SelectivityUtil.*;

 /**
 * This class represents the 6 binary operators: LessThan, LessThanEquals,
 * Equals, NotEquals, GreaterThan and GreaterThanEquals.
 */

public class BinaryRelationalOperatorNode
        extends BinaryComparisonOperatorNode
        implements RelationalOperator{
    /* values for determining which side contains key columns or index expression */
    public static final int LEFT    = -1;
    public static final int NEITHER =  0;
    public static final int RIGHT   =  1;
     
    private int operatorType;
    private int outerJoinLevel;
    /* RelationalOperator Interface */

    // Visitor for finding base tables beneath optimizables and column
    // references.  Created once and re-used thereafter.
    private BaseTableNumbersVisitor btnVis;

    // Bit sets for holding base tables beneath optimizables and
    // column references.  Created once and re-used thereafter.
    JBitSet optBaseTables;
    JBitSet valNodeBaseTables;

    /* If this BinRelOp was created for an IN-list "probe predicate"
     * then we keep a pointer to the original IN-list.  This serves
     * two purposes: 1) if this field is non-null then we know that
     * this BinRelOp is for an IN-list probe predicate; 2) if the
     * optimizer chooses a plan for which the probe predicate is
     * not usable as a start/stop key then we'll "revert" the pred
     * back to the InListOperatorNode referenced here.  NOTE: Once
     * set, this variable should *only* ever be accessed via the
     * isInListProbeNode() or getInListOp() methods--see comments
     * in the latter method for more.
     */
    private InListOperatorNode inListProbeSource=null;

    private HashSet<String> noStatsColumns;

    public void init(Object leftOperand,Object rightOperand){
        String methodName="";
        String operatorName="";

        switch(getNodeType()){
            case C_NodeTypes.BINARY_EQUALS_OPERATOR_NODE:
                methodName="equals";
                operatorName="=";
                operatorType=RelationalOperator.EQUALS_RELOP;
                break;

            case C_NodeTypes.BINARY_GREATER_EQUALS_OPERATOR_NODE:
                methodName="greaterOrEquals";
                operatorName=">=";
                operatorType=RelationalOperator.GREATER_EQUALS_RELOP;
                break;

            case C_NodeTypes.BINARY_GREATER_THAN_OPERATOR_NODE:
                methodName="greaterThan";
                operatorName=">";
                operatorType=RelationalOperator.GREATER_THAN_RELOP;
                break;

            case C_NodeTypes.BINARY_LESS_EQUALS_OPERATOR_NODE:
                methodName="lessOrEquals";
                operatorName="<=";
                operatorType=RelationalOperator.LESS_EQUALS_RELOP;
                break;

            case C_NodeTypes.BINARY_LESS_THAN_OPERATOR_NODE:
                methodName="lessThan";
                operatorName="<";
                operatorType=RelationalOperator.LESS_THAN_RELOP;
                break;
            case C_NodeTypes.BINARY_NOT_EQUALS_OPERATOR_NODE:
                methodName="notEquals";
                operatorName="<>";
                operatorType=RelationalOperator.NOT_EQUALS_RELOP;
                break;

            default:
                if(SanityManager.DEBUG){
                    SanityManager.THROWASSERT("init for BinaryRelationalOperator called with wrong nodeType = "+getNodeType());
                }
                break;
        }
        super.init(leftOperand, rightOperand, operatorName, methodName);
        btnVis=null;
        noStatsColumns = new HashSet<>();
    }

    public void reInitWithNodeType(int nodeType){
        setNodeType(nodeType);
        init(getLeftOperand(),getRightOperand());
    }

    /**
     * Same as init() above except takes a third argument that is
     * an InListOperatorNode.  This version is used during IN-list
     * preprocessing to create a "probe predicate" for the IN-list.
     * See InListOperatorNode.preprocess() for more.
     */
    public void init(Object leftOperand,Object rightOperand,Object inListOp){
        init(leftOperand, rightOperand);
        this.inListProbeSource=(InListOperatorNode)inListOp;
    }

    /**
     * If this rel op was created for an IN-list probe predicate then return
     * the underlying InListOperatorNode.  Will return null if this rel
     * op is a "legitimate" relational operator (as opposed to a disguised
     * IN-list).  With the exception of nullability checking via the
     * isInListProbeNode() method, all access to this.inListProbeSource
     * MUST come through this method, as this method ensures that the
     * left operand of the inListProbeSource is set correctly before
     * returning it.
     */
    public InListOperatorNode getInListOp() throws StandardException {
        if(inListProbeSource!=null){
            /* Depending on where this probe predicate currently sits
             * in the query tree, this.leftOperand *may* have been
             * transformed, replaced, or remapped one or more times
             * since inListProbeSource was last referenced. Since the
             * leftOperand of the IN list should be the same regardless
             * of which "version" of the operation we're looking at
             * (i.e. the "probe predicate" version (this node) vs the
             * original version (inListProbeSource)), we have to make
             * sure that all of the changes made to this.leftOperand
             * are reflected in inListProbeSource's leftOperand, as
             * well.  In doing so we ensure the caller of this method
             * will see an up-to-date version of the InListOperatorNode--
             * and thus, if the caller references the InListOperatorNode's
             * leftOperand, it will see the correct information. One
             * notable example of this is at code generation time, where
             * if this probe predicate is deemed "not useful", we'll
             * generate the underlying InListOperatorNode instead of
             * "this".  For that to work correctly, the InListOperatorNode
             * must have the correct leftOperand. DERBY-3253.
             *
             * That said, since this.leftOperand will always be "up-to-
             * date" w.r.t. the current query tree (because this probe
             * predicate sits in the query tree and so all relevant
             * transformations will be applied here), the simplest way
             * to ensure the underlying InListOperatorNode also has an
             * up-to-date leftOperand is to set it to this.leftOperand.
             */
            // No remapping of multicolumn IN list for now.
            if (inListProbeSource.leftOperandList.size() == 1)
                inListProbeSource.setLeftOperand(this.getLeftOperand());
        }

        return inListProbeSource;
    }

    @Override
    public ColumnReference getColumnOperand(
            Optimizable optTable,
            int columnPosition){
        FromTable ft=(FromTable)optTable;

        // When searching for a matching column operand, we search
        // the entire subtree (if there is one) beneath optTable
        // to see if we can find any FromTables that correspond to
        // either of this op's column references.

        boolean walkSubtree=true;
        List<ColumnReference> columnReferences = getLeftOperand().getHashableJoinColumnReference();
        if(columnReferences != null && columnReferences.size() == 1){
            /*
            ** The left operand is a column reference.
            ** Is it the correct column?
            */
            ColumnReference cr = columnReferences.get(0);
            if(valNodeReferencesOptTable(cr,ft,false,walkSubtree)){
                /*
                ** The table is correct, how about the column position?
                */
                if(cr.getSource().getColumnPosition()==columnPosition){
                    /* We've found the correct column - return it */
                    return cr;
                }
            }
            walkSubtree=false;
        }
        columnReferences = getRightOperand().getHashableJoinColumnReference();
        if(columnReferences != null && columnReferences.size() == 1){
            ColumnReference cr = columnReferences.get(0);
            if (valNodeReferencesOptTable(cr, ft, false, walkSubtree)) {
                /*
                ** The table is correct, how about the column position?
                */
                if (cr.getSource().getColumnPosition() == columnPosition) {
                    /* We've found the correct column - return it */
                    return cr;
                }
            }

        }

        /* Neither side is the column we're looking for */
        return null;
    }

    @Override
    public ColumnReference getColumnOperand(Optimizable optTable){
        ColumnReference cr;

        boolean walkSubtree=true;
        if(getLeftOperand() instanceof ColumnReference){
            /*
            ** The left operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getLeftOperand();
            if(valNodeReferencesOptTable(cr,(FromTable)optTable,false,walkSubtree)){
                /*
                ** The table is correct.
                */
                return cr;
            }
            walkSubtree=false;
        }

        if(getRightOperand() instanceof ColumnReference){
            /*
            ** The right operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getRightOperand();
            if(valNodeReferencesOptTable(cr,(FromTable)optTable,false,walkSubtree)){
                /*
                ** The table is correct
                */
                return cr;
            }
        }

        if(getLeftOperand() instanceof CastNode){
            if(((CastNode) getLeftOperand()).castOperand instanceof ColumnReference){
                cr = (ColumnReference)(((CastNode) getLeftOperand()).castOperand);
                if(valNodeReferencesOptTable(cr, (FromTable)optTable, false, walkSubtree)){
                    return cr;
                }
            }
        }

        if(getRightOperand() instanceof CastNode){
            if(((CastNode) getRightOperand()).castOperand instanceof ColumnReference){
                cr = (ColumnReference)(((CastNode) getRightOperand()).castOperand);
                if(valNodeReferencesOptTable(cr, (FromTable)optTable, false, walkSubtree)){
                    return cr;
                }
            }
        }
        /* Neither side is the column we're looking for */
        return null;
    }

    @Override
    public ValueNode getExpressionOperand(int tableNumber,int columnPosition,
                                          FromTable ft,boolean forIndexExpression)
            throws StandardException
    {
        if (forIndexExpression) {
            if (leftMatchIndexExpr >= 0 && leftMatchIndexExpr == tableNumber) {
                return getRightOperand();
            } else if (rightMatchIndexExpr >= 0 && rightMatchIndexExpr == tableNumber) {
                return getLeftOperand();
            } else {
                return null;
            }
        }

        ColumnReference cr;
        boolean walkSubtree=true;

        if(getLeftOperand() instanceof ColumnReference){
            /*
            ** The left operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getLeftOperand();
            if(cr.getColumnName().compareToIgnoreCase("ROWID")==0){
                return getRightOperand();
            }
            if(valNodeReferencesOptTable(cr,ft,false,walkSubtree)){
                /*
                ** The table is correct, how about the column position?
                */
                if(cr.getSource().getColumnPosition()==columnPosition){
                    /*
                    ** We've found the correct column -
                    ** return the other side
                    */
                    return getRightOperand();
                }
            }
            walkSubtree=false;
        }

        if(getRightOperand() instanceof ColumnReference){
            /*
            ** The right operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getRightOperand();
            if(cr.getColumnName().compareToIgnoreCase("ROWID")==0){
                return getLeftOperand();
            }
            if(valNodeReferencesOptTable(cr,ft,false,walkSubtree)){
                /*
                ** The table is correct, how about the column position?
                */
                if(cr.getSource().getColumnPosition()==columnPosition){
                    /*
                    ** We've found the correct column -
                    ** return the other side
                    */
                    return getLeftOperand();
                }
            }
        }

        return null;
    }

    /**
     * @see RelationalOperator#getOperand
     */
    public ValueNode getOperand(ColumnReference cRef,
                                int refSetSize,boolean otherSide){
        return getOperand(cRef.getTableNumber(), cRef.getColumnNumber(), refSetSize, otherSide);
    }

    public ValueNode getOperand(int tableNum, int colNum,
                                int refSetSize,boolean otherSide){
        // Following call will initialize/reset the btnVis,
        // valNodeBaseTables, and optBaseTables fields of this object.
        initBaseTableVisitor(refSetSize,true);

        // We search for the column reference by getting the *base*
        // table number for each operand and checking to see if
        // that matches the *base* table number for the cRef
        // that we're looking for.  If so, then we the two
        // reference the same table so we go on to check
        // column position.
        try{

            // Use optBaseTables for cRef's base table numbers.
            optBaseTables.set(tableNum);

            // Use valNodeBaseTables for operand base table nums.
            btnVis.setTableMap(valNodeBaseTables);

            ColumnReference cr;
            if(getLeftOperand() instanceof ColumnReference){
                /*
                ** The left operand is a column reference.
                ** Is it the correct column?
                */
                cr=(ColumnReference)getLeftOperand();
                cr.accept(btnVis);
                valNodeBaseTables.and(optBaseTables);
                if(valNodeBaseTables.getFirstSetBit()!=-1){
                    /*
                    ** The table is correct, how about the column position?
                    */
                    if(cr.getSource().getColumnPosition()==
                            colNum){
                        /*
                        ** We've found the correct column -
                        ** return the appropriate side.
                        */
                        if(otherSide)
                            return getRightOperand();
                        return getLeftOperand();
                    }
                }
            }

            if(getRightOperand() instanceof ColumnReference){
                /*
                ** The right operand is a column reference.
                ** Is it the correct column?
                */
                valNodeBaseTables.clearAll();
                cr=(ColumnReference)getRightOperand();
                cr.accept(btnVis);
                valNodeBaseTables.and(optBaseTables);
                if(valNodeBaseTables.getFirstSetBit()!=-1){
                    /*
                    ** The table is correct, how about the column position?
                    */
                    if(cr.getSource().getColumnPosition()==
                            colNum){
                        /*
                        ** We've found the correct column -
                        ** return the appropriate side
                        */
                        if(otherSide)
                            return getLeftOperand();
                        return getRightOperand();
                    }
                }
            }

        }catch(Throwable t){
            if(SanityManager.DEBUG){
                SanityManager.THROWASSERT("Failed when trying to "+
                        "find base table number for column reference check:", t);
            }
        }

        return null;
    }

    /**
     * @throws StandardException Thrown on error
     * @see RelationalOperator#generateExpressionOperand
     */
    @Override
    public void generateExpressionOperand(
            Optimizable optTable,
            int columnPosition,
            boolean forIndexExpression,
            ExpressionClassBuilder acb,
            MethodBuilder mb)
            throws StandardException{
        ColumnReference cr;
        FromBaseTable ft;

        if(SanityManager.DEBUG){
            SanityManager.ASSERT(optTable instanceof FromBaseTable);
        }
        ft=(FromBaseTable)optTable;

        ValueNode exprOp=getExpressionOperand(
                ft.getTableNumber(),columnPosition,ft,forIndexExpression);

        if(SanityManager.DEBUG){
            if(exprOp==null){
                SanityManager.THROWASSERT(
                        "ColumnReference for correct column (columnPosition = "+
                                columnPosition+
                                ", exposed table name = "+ft.getExposedName()+
                                ") not found on either side of BinaryRelationalOperator");
            }
        }

        exprOp.generateExpression(acb,mb);
    }

    @Override
    public int getMatchingExprIndexColumnPosition(int tableNumber) {
        if (leftMatchIndexExpr >= 0 && leftMatchIndexExpr == tableNumber) {
            assert leftMatchIndexExprColumnPosition >= 0;
            return leftMatchIndexExprColumnPosition;
        } else if (rightMatchIndexExpr >= 0 && rightMatchIndexExpr == tableNumber) {
            assert rightMatchIndexExprColumnPosition >= 0;
            return rightMatchIndexExprColumnPosition;
        } else {
            return -1;
        }
    }

    /**
     * @throws StandardException Thrown on error
     * @see RelationalOperator#selfComparison
     */
    @Override
    public boolean selfComparison(ColumnReference cr, boolean forIndexExpression)
            throws StandardException{
        ValueNode otherSide = null;
        JBitSet tablesReferenced;

        List<ColumnReference> lcr, rcr;

        lcr = getLeftOperand().getHashableJoinColumnReference();

        /*
        ** Figure out which side the given ColumnReference is on,
        ** and look for the same table on the other side.
        */
        if(lcr != null && !lcr.isEmpty()){
            if (forIndexExpression || lcr.size() == 1) {
                for (ColumnReference columnReference : lcr) {
                    if (columnReference.equals(cr)) {
                        otherSide = getRightOperand();
                        break;
                    }
                }
            }
        }

        if (otherSide == null) {
            rcr = getRightOperand().getHashableJoinColumnReference();
            if (rcr != null && !rcr.isEmpty()) {
                if (forIndexExpression || rcr.size() == 1) {
                    for (ColumnReference columnReference : rcr) {
                        if (columnReference.equals(cr)) {
                            otherSide = getLeftOperand();
                            break;
                        }
                    }
                }
            }
        }

        if (otherSide == null) {
            if(SanityManager.DEBUG){
                SanityManager.THROWASSERT(
                        "ColumnReference not found on either side of binary comparison.");
            }
        }

        assert otherSide != null;
        tablesReferenced=otherSide.getTablesReferenced();

        /* Return true if the table we're looking for is in the bit map */
        return tablesReferenced.get(cr.getTableNumber());
    }

    /**
     * @see RelationalOperator#usefulStartKey
     */
    public boolean usefulStartKey(Optimizable optTable, IndexDescriptor id) {

        BinaryRelationalOperatorNodeUtil.coerceDataTypeIfNecessary(this);

        /*
        ** Determine whether this operator is a useful start operator
        ** with knowledge of whether the key column is on the left or right.
        */
        boolean isIndexOnExpr = id != null && id.isOnExpression();
        int columnSide = isIndexOnExpr ? indexExprOnOneSide(optTable) : columnOnOneSide(optTable);

        return columnSide != NEITHER && usefulStartKey(columnSide == LEFT);
    }

     /**
      * @see RelationalOperator#usefulStopKey
      */
     public boolean usefulStopKey(Optimizable optTable, IndexDescriptor id) {
         /*
          ** Determine whether this operator is a useful start operator
          ** with knowledge of whether the key column is on the left or right.
          */
         boolean isIndexOnExpr = id != null && id.isOnExpression();
         int columnSide = isIndexOnExpr ? indexExprOnOneSide(optTable) : columnOnOneSide(optTable);

         return columnSide != NEITHER && usefulStopKey(columnSide == LEFT);
     }

    /**
     * Return true if a key column for the given table is found on the
     * left side of this operator, false if it is found on the right
     * side of this operator.
     * <p/>
     * NOTE: This method assumes that a key column will be found on one
     * side or the other.  If you don't know whether a key column exists,
     * use the columnOnOneSide() method (below).
     *
     * @param optTable The Optimizable table that we're looking for a key
     *                 column on.
     * @return true if a key column for the given table is on the left
     * side of this operator, false if one is found on the right
     * side of this operator.
     */
    protected boolean keyColumnOnLeft(Optimizable optTable){
        boolean left=false;

        /* Is the key column on the left or the right? */
        List<ColumnReference> columnReferences = getLeftOperand().getHashableJoinColumnReference();
        if (columnReferences != null && (leftMatchIndexExpr >= 0 || columnReferences.size() == 1)) {
            if (valNodeReferencesOptTable(columnReferences.get(0), (FromTable) optTable, false, true)) {
                /* The left operand is the key column */
                left = true;
            }
        }
        // Else the right operand must be the key column.
        if(SanityManager.DEBUG){
            if(!left){
                boolean right = false;
                columnReferences = getRightOperand().getHashableJoinColumnReference();
                if (columnReferences != null && (rightMatchIndexExpr >= 0 || columnReferences.size() == 1)) {
                    if(valNodeReferencesOptTable(columnReferences.get(0),(FromTable)optTable,false,true)){
                    /* The right operand is the key column */
                        right=true;
                    }
                }
                SanityManager.ASSERT(right,"Key column not found on either side.");
            }
        }

        return left;
    }

    /**
     * Determine whether there is a column from the given table on one side
     * of this operator, and if so, which side is it on?
     *
     * @param optTable The Optimizable table that we're looking for a key
     *                 column on.
     * @return LEFT if there is a column on the left, RIGHT if there is
     * a column on the right, NEITHER if no column found on either
     * side.
     */
    protected int columnOnOneSide(Optimizable optTable){
        ColumnReference cr;
        boolean walkSubtree=true;

        /* Is a column on the left */
        if(getLeftOperand() instanceof ColumnReference){
            /*
            ** The left operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getLeftOperand();
            if(valNodeReferencesOptTable(
                    cr,(FromTable)optTable,false,walkSubtree)){
                /* Key column found on left */
                return LEFT;
            }
            walkSubtree=false;
        }

        if(getRightOperand() instanceof ColumnReference){
            /*
            ** The right operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getRightOperand();
            if(valNodeReferencesOptTable(
                    cr,(FromTable)optTable,false,walkSubtree)){
                /* Key column found on right */
                return RIGHT;
            }
        }

        return NEITHER;
    }

    private int indexExprOnOneSide(Optimizable optTable) {
        if (optTable.hasTableNumber() && leftMatchIndexExpr == optTable.getTableNumber()) {
            return LEFT;
        } else if (optTable.hasTableNumber() && rightMatchIndexExpr == optTable.getTableNumber()) {
            return RIGHT;
        } else {
            return NEITHER;
        }
    }

    private boolean indexExprOnLeft(Optimizable optTable) {
        int columnSide = indexExprOnOneSide(optTable);
        if (SanityManager.DEBUG) {
            if (columnSide == NEITHER) {
                SanityManager.THROWASSERT("index expression not found on either side");
            }
        }
        return columnSide == LEFT;
    }

    /**
     * Determine whether this comparison operator is a useful stop key
     * with knowledge of whether the key column is on the left or right.
     *
     * @param left    true means the key column is on the left, false means
     *                it is on the right.
     *
     * @return true if this is a useful stop key
     */
    /**
     * @see RelationalOperator#generateAbsoluteColumnId
     */
    public void generateAbsoluteColumnId(MethodBuilder mb,
                                         Optimizable optTable) throws StandardException {
        // Get the absolute column position for the column
        int columnPosition=getAbsoluteColumnPosition(optTable);
        mb.push(columnPosition);

        int storagePosition=getAbsoluteStoragePosition(optTable);
        mb.push(storagePosition);
    }

    /**
     * @see RelationalOperator#generateRelativeColumnId
     */
    public void generateRelativeColumnId(MethodBuilder mb,
                                         Optimizable optTable) throws StandardException {
        // Get the absolute column position for the column
        int columnPosition=getAbsoluteColumnPosition(optTable);
        // Convert the absolute to the relative 0-based column position
        columnPosition=optTable.convertAbsoluteToRelativeColumnPosition(columnPosition);
        mb.push(columnPosition);

        int storagePosition=getAbsoluteStoragePosition(optTable);
        storagePosition=optTable.convertAbsoluteToRelativeColumnPosition(storagePosition);
        mb.push(storagePosition);
    }

    /**
     * Get the absolute 0-based column position of the ColumnReference from
     * the conglomerate for this Optimizable.
     *
     * @param optTable The Optimizable
     * @return The absolute 0-based column position of the ColumnReference
     */
    private int getAbsoluteColumnPosition(Optimizable optTable) throws StandardException {
        List<ColumnReference> columnReferences;
        ConglomerateDescriptor bestCD;
        int columnPosition;

        if(keyColumnOnLeft(optTable)){
            columnReferences = getLeftOperand().getHashableJoinColumnReference();

        }else{
            columnReferences = getRightOperand().getHashableJoinColumnReference();
        }

        bestCD=optTable.getTrulyTheBestAccessPath().
                getConglomerateDescriptor();

        assert columnReferences != null && columnReferences.size() == 1: "getAbsoluteColumnPosition: one column reference is expected";
        ColumnReference cr = columnReferences.get(0);
        /*
        ** Column positions are one-based, store is zero-based.
        */
        columnPosition=cr.getSource().getColumnPosition();

        /*
        ** If it's an index, find the base column position in the index
        ** and translate it to an index column position.
        */
        if(bestCD!=null && bestCD.isIndex()){
            columnPosition=bestCD.getIndexDescriptor().
                    getKeyColumnPosition(columnPosition);

            if(SanityManager.DEBUG){
                SanityManager.ASSERT(columnPosition>0,
                        "Base column not found in index");
            }
        }

        // return the 0-based column position
        return columnPosition-1;
    }

    private int getAbsoluteStoragePosition(Optimizable optTable) throws StandardException {
        List<ColumnReference> columnReferences;
        ConglomerateDescriptor bestCD;
        int columnPosition;

        if(keyColumnOnLeft(optTable)){
            columnReferences = getLeftOperand().getHashableJoinColumnReference();
        }else{
            columnReferences = getRightOperand().getHashableJoinColumnReference();
        }

        bestCD=optTable.getTrulyTheBestAccessPath().
                getConglomerateDescriptor();

        assert columnReferences != null && columnReferences.size() == 1: "getAbsoluteStoragePosition: one column reference is expected";
        ColumnReference cr = columnReferences.get(0);
        /*
        ** If it's an index, find the base column position in the index
        ** and translate it to an index column position.
        */
        if(bestCD!=null && bestCD.isIndex()){
            columnPosition=cr.getSource().getColumnPosition();
            columnPosition=bestCD.getIndexDescriptor().
                    getKeyColumnPosition(columnPosition);

            if(SanityManager.DEBUG){
                SanityManager.ASSERT(columnPosition>0,
                        "Base column not found in index");
            }
        } else {
            columnPosition = cr.getSource().getStoragePosition();
        }

        // return the 0-based column position
        return columnPosition-1;
    }

    /**
     * @throws StandardException Thrown on error
     */
    @Override
    public void generateQualMethod(ExpressionClassBuilder acb,
                                   MethodBuilder mb,
                                   Optimizable optTable,
                                   boolean forIndexExpression)
            throws StandardException{
        /* Generate a method that returns the expression */
        MethodBuilder qualMethod=acb.newUserExprFun();

        /*
        ** Generate the expression that's on the opposite side
        ** of the key column
        */
        boolean onLeft = forIndexExpression ? indexExprOnLeft(optTable) : keyColumnOnLeft(optTable);
        if (onLeft) {
            getRightOperand().generateExpression(acb, qualMethod);
        } else {
            getLeftOperand().generateExpression(acb, qualMethod);
        }

        qualMethod.methodReturn();
        qualMethod.complete();

        /* push an expression that evaluates to the GeneratedMethod */
        acb.pushMethodReference(mb, qualMethod);
    }

    /**
     * @see RelationalOperator#generateOrderedNulls
     */
    public void generateOrderedNulls(MethodBuilder mb){
        mb.push(false);
    }

    /**
     * @see RelationalOperator#orderedNulls
     */
    public boolean orderedNulls(){
        return false;
    }

    @Override
    public boolean isQualifier(Optimizable optTable,boolean forPush) throws StandardException{
        /* If this rel op is for an IN-list probe predicate then we never
         * treat it as a qualifer.  The reason is that if we treat it as
         * a qualifier then we could end up generating it as a qualifier,
         * which would lead to the generation of an equality qualifier
         * of the form "col = <val>" (where <val> is the first value in
         * the IN-list).  That would lead to wrong results (missing rows)
         * because that restriction is incorrect.
         */
        if(isInListProbeNode())
            return false;

        FromTable ft;
        ValueNode otherSide=null;
        ColumnReference cr;
        boolean found=false;
        boolean walkSubtree=true;

        ft=(FromTable)optTable;

        if(getLeftOperand() instanceof ColumnReference){
            /*
            ** The left operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getLeftOperand();
            if(valNodeReferencesOptTable(cr,ft,forPush,walkSubtree)){
                otherSide=getRightOperand();
                found=true;
            }
            walkSubtree=false;
        }

        if((!found) && (getRightOperand() instanceof ColumnReference)){
            /*
            ** The right operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getRightOperand();
            if(valNodeReferencesOptTable(cr,ft,forPush,walkSubtree)){
                otherSide=getLeftOperand();
                found=true;
            }
        }

        /* Have we found a ColumnReference on either side? */
        if(!found){
            /*
            ** Neither side is a ColumnReference to the table we're looking
            ** for, so it can't be a Qualifier
            */
            return false;
        }

        /*
        ** One side is a ColumnReference to the correct table.  It is a
        ** Qualifier if the other side does not refer to the table we are
        ** optimizing.
        */
        return !valNodeReferencesOptTable(otherSide,ft,forPush,true);
    }

    public boolean isQualifierForHashableJoin(Optimizable optTable,boolean forPush) throws StandardException{
        /* If this rel op is for an IN-list probe predicate then we never
         * treat it as a qualifer.  The reason is that if we treat it as
         * a qualifier then we could end up generating it as a qualifier,
         * which would lead to the generation of an equality qualifier
         * of the form "col = <val>" (where <val> is the first value in
         * the IN-list).  That would lead to wrong results (missing rows)
         * because that restriction is incorrect.
         */
        if(isInListProbeNode())
            return false;

        FromTable ft;
        ValueNode otherSide=null;
        boolean found=false;
        boolean walkSubtree=true;

        ft=(FromTable)optTable;

        List<ColumnReference> lcr = getLeftOperand().getHashableJoinColumnReference();
        List<ColumnReference> rcr = getRightOperand().getHashableJoinColumnReference();
        if (lcr == null || rcr == null || lcr.size() != 1 || rcr.size() != 1) {
            // In order to have a hashable qualifier, we need to have have a column ref on both sides
            return false;
        }

            /*
            ** The left operand is a column reference.
            ** Is it the correct column?
            */
            if(valNodeReferencesOptTable(lcr.get(0),ft,forPush,walkSubtree)){
                otherSide=getRightOperand();
                found=true;
                walkSubtree=false;
            }

        if(!found){
            /*
            ** The right operand is a column reference.
            ** Is it the correct column?
            */
            if(valNodeReferencesOptTable(rcr.get(0),ft,forPush,walkSubtree)){
                otherSide=getLeftOperand();
                found=true;
            }
        }

        /* Have we found a ColumnReference on either side? */
        if(!found){
            /*
            ** Neither side is a ColumnReference to the table we're looking
            ** for, so it can't be a Qualifier
            */
            return false;
        }

        /*
        ** One side is a ColumnReference to the correct table.  It is a
        ** Qualifier if the other side does not refer to the table we are
        ** optimizing.
        */
        return !valNodeReferencesOptTable(otherSide,ft,forPush,true);
    }

    /**
     * @throws StandardException Thrown on error
     * @see RelationalOperator#isQualifier
     */
    public boolean isOrderedQualifier(int leftTableNumber,int leftColumnNumber,int rightTableNumber,int rightColumnNumber) throws StandardException{
        if(isInListProbeNode() || !(getLeftOperand() instanceof ColumnReference) || !(getRightOperand() instanceof ColumnReference))
            return false;
        ColumnReference left=(ColumnReference)getLeftOperand();
        ColumnReference right=(ColumnReference)getRightOperand();
        return (left.getTableNumber() == leftTableNumber && left.getColumnNumber() == leftColumnNumber &&
                right.getTableNumber() == rightTableNumber && right.getColumnNumber() == rightColumnNumber) ||
                (left.getTableNumber() == rightTableNumber && left.getColumnNumber() == rightColumnNumber &&
                        right.getTableNumber() == leftTableNumber && right.getColumnNumber() == leftColumnNumber);
    }


    public boolean isQualifier(Optimizable optTable,boolean forPush,int leftColumn,int rightColumn) throws StandardException{
        /* If this rel op is for an IN-list probe predicate then we never
         * treat it as a qualifer.  The reason is that if we treat it as
         * a qualifier then we could end up generating it as a qualifier,
         * which would lead to the generation of an equality qualifier
         * of the form "col = <val>" (where <val> is the first value in
         * the IN-list).  That would lead to wrong results (missing rows)
         * because that restriction is incorrect.
         */
        if(isInListProbeNode())
            return false;

        FromTable ft;
        ValueNode otherSide=null;
        ColumnReference cr;
        boolean found=false;
        boolean walkSubtree=true;

        ft=(FromTable)optTable;

        if(getLeftOperand() instanceof ColumnReference){
            /*
            ** The left operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getLeftOperand();
            if(valNodeReferencesOptTable(cr,ft,forPush,walkSubtree)){
                otherSide=getRightOperand();
                found=true;
            }
            walkSubtree=false;
        }

        if((!found) && (getRightOperand() instanceof ColumnReference)){
            /*
            ** The right operand is a column reference.
            ** Is it the correct column?
            */
            cr=(ColumnReference)getRightOperand();
            if(valNodeReferencesOptTable(cr,ft,forPush,walkSubtree)){
                otherSide=getLeftOperand();
                found=true;
            }
        }

        /* Have we found a ColumnReference on either side? */
        if(!found){
            /*
            ** Neither side is a ColumnReference to the table we're looking
            ** for, so it can't be a Qualifier
            */
            return false;
        }

        /*
        ** One side is a ColumnReference to the correct table.  It is a
        ** Qualifier if the other side does not refer to the table we are
        ** optimizing.
        */
        return !valNodeReferencesOptTable(otherSide,ft,forPush,true);
    }

    @Override
    public int getOrderableVariantType(Optimizable optTable)
            throws StandardException{
        /* The Qualifier's orderable is on the opposite side from
         * the key column.
         */
        if(keyColumnOnLeft(optTable)){
            return getRightOperand().getOrderableVariantType();
        }else{
            return getLeftOperand().getOrderableVariantType();
        }
    }

    @Override
    public boolean compareWithKnownConstant(Optimizable optTable,boolean considerParameters){
        ValueNode node;
        if (optTable != null) {
            node = keyColumnOnLeft(optTable) ? getRightOperand() : getLeftOperand();
            return node.isKnownConstant(considerParameters);
        } else {
            return (getRightOperand().isKnownConstant(considerParameters) || getLeftOperand().isKnownConstant(considerParameters));
        }
    }

    @Override
    public DataValueDescriptor getCompareValue(Optimizable optTable)
            throws StandardException{
        ValueNode node;

        /* The value being compared to is on the opposite side from
        ** the key column.
        */
        node=keyColumnOnLeft(optTable)?getRightOperand():getLeftOperand();
        return node.getKnownConstantValue();
    }


    /**
     * Return 50% if this is a comparison with a boolean column, a negative
     * selectivity otherwise.
     */
    protected double booleanSelectivity(Optimizable optTable) throws StandardException{
        TypeId typeId=null;
        double retval=-1.0d;
        int columnSide;

        columnSide=columnOnOneSide(optTable);

        if(columnSide==LEFT)
            typeId=getLeftOperand().getTypeId();
        else if(columnSide==RIGHT)
            typeId=getRightOperand().getTypeId();

        if(typeId!=null && (typeId.getJDBCTypeId()==Types.BIT ||
                typeId.getJDBCTypeId()==Types.BOOLEAN))
            retval=0.5d;

        return retval;
    }

    /**
     * The methods generated for this node all are on Orderable.
     * Overrides this method
     * in BooleanOperatorNode for code generation purposes.
     */
    public String getReceiverInterfaceName(){
        return ClassName.DataValueDescriptor;
    }

    /**
     * See if the node always evaluates to true or false, and return a Boolean
     * constant node if it does.
     *
     * @return a node representing a Boolean constant if the result of the
     * operator is known; otherwise, this operator node
     */
    ValueNode evaluateConstantExpressions() throws StandardException{
        if(getLeftOperand() instanceof ConstantNode &&
                getRightOperand() instanceof ConstantNode){
            ConstantNode leftOp=(ConstantNode)getLeftOperand();
            ConstantNode rightOp=(ConstantNode)getRightOperand();
            DataValueDescriptor leftVal=leftOp.getValue();
            DataValueDescriptor rightVal=rightOp.getValue();

            if(leftVal != null && !leftVal.isNull() && rightVal != null && !rightVal.isNull()){
                int comp=leftVal.compare(rightVal);
                switch(operatorType){
                    case EQUALS_RELOP:
                        return newBool(comp==0);
                    case NOT_EQUALS_RELOP:
                        return newBool(comp!=0);
                    case GREATER_THAN_RELOP:
                        return newBool(comp>0);
                    case GREATER_EQUALS_RELOP:
                        return newBool(comp>=0);
                    case LESS_THAN_RELOP:
                        return newBool(comp<0);
                    case LESS_EQUALS_RELOP:
                        return newBool(comp<=0);
                }
            }
        }

        return this;
    }

    /**
     * Create a Boolean constant node with a specified value.
     *
     * @param b the value of the constant
     * @return a node representing a Boolean constant
     */
    private ValueNode newBool(boolean b) throws StandardException{
        return (ValueNode)getNodeFactory().getNode(
                C_NodeTypes.BOOLEAN_CONSTANT_NODE,
                b,
                getContextManager());
    }

    /**
     * Returns the negation of this operator; negation of Equals is NotEquals.
     */
    BinaryOperatorNode getNegation(ValueNode leftOperand,
                                   ValueNode rightOperand)
            throws StandardException{
        BinaryOperatorNode negation;
        if(SanityManager.DEBUG)
            SanityManager.ASSERT(getTypeServices()!=null,
                    "dataTypeServices is expected to be non-null");
        /* xxxRESOLVE: look into doing this in place instead of allocating a new node */
        negation=(BinaryOperatorNode)
                getNodeFactory().getNode(getNegationNode(),
                        leftOperand,rightOperand,
                        getContextManager());
        negation.setType(getTypeServices());
        return negation;
    }

    /* map current node to its negation */
    private int getNegationNode(){
        switch(getNodeType()){
            case C_NodeTypes.BINARY_EQUALS_OPERATOR_NODE:
                return C_NodeTypes.BINARY_NOT_EQUALS_OPERATOR_NODE;

            case C_NodeTypes.BINARY_GREATER_EQUALS_OPERATOR_NODE:
                return C_NodeTypes.BINARY_LESS_THAN_OPERATOR_NODE;

            case C_NodeTypes.BINARY_GREATER_THAN_OPERATOR_NODE:
                return C_NodeTypes.BINARY_LESS_EQUALS_OPERATOR_NODE;

            case C_NodeTypes.BINARY_LESS_THAN_OPERATOR_NODE:
                return C_NodeTypes.BINARY_GREATER_EQUALS_OPERATOR_NODE;

            case C_NodeTypes.BINARY_LESS_EQUALS_OPERATOR_NODE:
                return C_NodeTypes.BINARY_GREATER_THAN_OPERATOR_NODE;

            case C_NodeTypes.BINARY_NOT_EQUALS_OPERATOR_NODE:
                return C_NodeTypes.BINARY_EQUALS_OPERATOR_NODE;
        }

        if(SanityManager.DEBUG){
            SanityManager.THROWASSERT("getNegationNode called with invalid nodeType: "+getNodeType());
        }

        return -1;
    }

    /**
     * Return an equivalent node with the operands swapped, and possibly with
     * the operator type changed in order to preserve the meaning of the
     * expression.
     */
    BinaryOperatorNode getSwappedEquivalent() throws StandardException{
        BinaryOperatorNode newNode=(BinaryOperatorNode)getNodeFactory().getNode(getNodeTypeForSwap(),
                getRightOperand(),getLeftOperand(),
                getContextManager());
        newNode.setType(getTypeServices());
        return newNode;
    }

    /**
     * Return the node type that must be used in order to construct an
     * equivalent expression if the operands are swapped. For symmetric
     * operators ({@code =} and {@code <>}), the same node type is returned.
     * Otherwise, the direction of the operator is switched in order to
     * preserve the meaning (for instance, a node representing less-than will
     * return the node type for greater-than).
     *
     * @return a node type that preserves the meaning of the expression if
     * the operands are swapped
     */
    private int getNodeTypeForSwap(){
        switch(getNodeType()){
            case C_NodeTypes.BINARY_EQUALS_OPERATOR_NODE:
                return C_NodeTypes.BINARY_EQUALS_OPERATOR_NODE;
            case C_NodeTypes.BINARY_GREATER_EQUALS_OPERATOR_NODE:
                return C_NodeTypes.BINARY_LESS_EQUALS_OPERATOR_NODE;
            case C_NodeTypes.BINARY_GREATER_THAN_OPERATOR_NODE:
                return C_NodeTypes.BINARY_LESS_THAN_OPERATOR_NODE;
            case C_NodeTypes.BINARY_LESS_THAN_OPERATOR_NODE:
                return C_NodeTypes.BINARY_GREATER_THAN_OPERATOR_NODE;
            case C_NodeTypes.BINARY_LESS_EQUALS_OPERATOR_NODE:
                return C_NodeTypes.BINARY_GREATER_EQUALS_OPERATOR_NODE;
            case C_NodeTypes.BINARY_NOT_EQUALS_OPERATOR_NODE:
                return C_NodeTypes.BINARY_NOT_EQUALS_OPERATOR_NODE;
            default:
                if(SanityManager.DEBUG){
                    SanityManager.THROWASSERT(
                            "Invalid nodeType: "+getNodeType());
                }
                return -1;
        }
    }

    /**
     * is this is useful start key? for example a predicate of the from
     * <em>column Lessthan 5</em> is not a useful start key but is a useful stop
     * key. However <em>5 Lessthan column </em> is a useful start key.
     *
     * @param columnOnLeft is true if the column is the left hand side of the
     *                     binary operator.
     */
    protected boolean usefulStartKey(boolean columnOnLeft){
        switch(operatorType){
            case RelationalOperator.EQUALS_RELOP:
                return true;
            case RelationalOperator.NOT_EQUALS_RELOP:
                return false;
            case RelationalOperator.GREATER_THAN_RELOP:
            case RelationalOperator.GREATER_EQUALS_RELOP:
                // col > 1
                return columnOnLeft;
            case RelationalOperator.LESS_THAN_RELOP:
            case RelationalOperator.LESS_EQUALS_RELOP:
                // col < 1
                return !columnOnLeft;
            default:
                return false;
        }


    }

    /**
     * @see RelationalOperator#usefulStopKey
     */
    protected boolean usefulStopKey(boolean columnOnLeft){
        switch(operatorType){
            case RelationalOperator.EQUALS_RELOP:
                return true;
            case RelationalOperator.NOT_EQUALS_RELOP:
                return false;
            case RelationalOperator.GREATER_THAN_RELOP:
            case RelationalOperator.GREATER_EQUALS_RELOP:
                // col > 1
                return !columnOnLeft;
            case RelationalOperator.LESS_EQUALS_RELOP:
            case RelationalOperator.LESS_THAN_RELOP:
                // col < 1
                return columnOnLeft;
            default:
                return false;
        }
    }

    /**
     * @see RelationalOperator#getStartOperator
     */
    public int getStartOperator(Optimizable optTable){
        switch(operatorType){
            case RelationalOperator.EQUALS_RELOP:
            case RelationalOperator.LESS_EQUALS_RELOP:
            case RelationalOperator.GREATER_EQUALS_RELOP:
                return ScanController.GE;
            case RelationalOperator.LESS_THAN_RELOP:
            case RelationalOperator.GREATER_THAN_RELOP:
                return ScanController.GT;
            case RelationalOperator.NOT_EQUALS_RELOP:
                if(SanityManager.DEBUG)
                    SanityManager.THROWASSERT("!= cannot be a start operator");
                return ScanController.NA;
            default:
                return ScanController.NA;

        }
    }

    /**
     * @see RelationalOperator#getStopOperator
     */
    public int getStopOperator(Optimizable optTable){
        switch(operatorType){
            case RelationalOperator.EQUALS_RELOP:
            case RelationalOperator.GREATER_EQUALS_RELOP:
            case RelationalOperator.LESS_EQUALS_RELOP:
                return ScanController.GT;
            case RelationalOperator.LESS_THAN_RELOP:
            case RelationalOperator.GREATER_THAN_RELOP:
                return ScanController.GE;
            case RelationalOperator.NOT_EQUALS_RELOP:
                if(SanityManager.DEBUG)
                    SanityManager.THROWASSERT("!= cannot be a stop operator");
                return ScanController.NA;
            default:
                return ScanController.NA;
        }
    }

    /**
     * @see RelationalOperator#generateOperator
     */
    public void generateOperator(MethodBuilder mb,
                                 Optimizable optTable){
        switch(operatorType){
            case RelationalOperator.EQUALS_RELOP:
                mb.push(Orderable.ORDER_OP_EQUALS);
                break;

            case RelationalOperator.NOT_EQUALS_RELOP:
                mb.push(Orderable.ORDER_OP_EQUALS);
                break;

            case RelationalOperator.LESS_THAN_RELOP:
            case RelationalOperator.GREATER_EQUALS_RELOP:
                mb.push(keyColumnOnLeft(optTable)?
                        Orderable.ORDER_OP_LESSTHAN:Orderable.ORDER_OP_LESSOREQUALS);
                break;
            case RelationalOperator.LESS_EQUALS_RELOP:
            case RelationalOperator.GREATER_THAN_RELOP:
                mb.push(keyColumnOnLeft(optTable)?
                        Orderable.ORDER_OP_LESSOREQUALS:Orderable.ORDER_OP_LESSTHAN);
                break;
            default:
                assert false;

        }
    }

    /**
     * @see RelationalOperator#generateNegate
     */
    @Override
    public void generateNegate(MethodBuilder mb,Optimizable optTable,boolean forIndexExpression)
            throws StandardException
    {
        switch(operatorType){
            case RelationalOperator.EQUALS_RELOP:
                mb.push(false);
                break;
            case RelationalOperator.NOT_EQUALS_RELOP:
                mb.push(true);
                break;
            case RelationalOperator.LESS_THAN_RELOP:
            case RelationalOperator.LESS_EQUALS_RELOP: {
                if (forIndexExpression) {
                    mb.push(!indexExprOnLeft(optTable));
                } else {
                    mb.push(!keyColumnOnLeft(optTable));
                }
                break;
            }
            case RelationalOperator.GREATER_THAN_RELOP:
            case RelationalOperator.GREATER_EQUALS_RELOP: {
                if (forIndexExpression) {
                    mb.push(indexExprOnLeft(optTable));
                } else {
                    mb.push(keyColumnOnLeft(optTable));
                }
                break;
            }
            default:
                assert false;
        }

    }

    /**
     * @see RelationalOperator#getOperator
     */
    @Override
    public int getOperator(){
        return operatorType;
    }

    /**
     *
     * Computes the selectivity of the Binary RelationalOperatorNode.
     *
     * @param optTable
     * @return
     * @throws StandardException
     */
    public double getReferenceSelectivity(Optimizable optTable) throws StandardException {
        if (getLeftOperand() instanceof ColumnReference && getRightOperand() instanceof ColumnReference && optTable instanceof FromBaseTable) {
            ConglomerateDescriptor cdLeft = ((ColumnReference) getLeftOperand()).getBaseConglomerateDescriptor();
            ConglomerateDescriptor cdRight = ((ColumnReference) getRightOperand()).getBaseConglomerateDescriptor();
            if (cdLeft ==null && cdRight==null)
                return -1.0d;
            /* DB-11238 note
             * We have two situations here:
             * 1. Left and right are from the same table X, and we have only table X. That means this predicate is a
             *    single table predicate. We should use the max() formula below, just as before.
             * 2. Left and right are from the same table X, but X is joining itself. In this case, we are estimating
             *    the selectivity of this predicate on optTable, i.e., inner table of the join. We should use only
             *    the side that matches optTable.
             */
            boolean leftFromBaseTable = getLeftOperand().getTableNumber() == optTable.getTableNumber() &&
                    cdLeft != null && cdLeft.equals(((FromBaseTable) optTable).baseConglomerateDescriptor);
            boolean rightFromBaseTable = getRightOperand().getTableNumber() == optTable.getTableNumber() &&
                    cdRight != null && cdRight.equals(((FromBaseTable) optTable).baseConglomerateDescriptor);
            if (leftFromBaseTable && rightFromBaseTable) {
                return Math.max(((ColumnReference) getLeftOperand()).columnReferenceEqualityPredicateSelectivity(),((ColumnReference) getRightOperand()).columnReferenceEqualityPredicateSelectivity());
            } else if (leftFromBaseTable) {
                return ((ColumnReference) getLeftOperand()).columnReferenceEqualityPredicateSelectivity();
            } else if (rightFromBaseTable) {
                return ((ColumnReference) getRightOperand()).columnReferenceEqualityPredicateSelectivity();
            }
        } else if (getLeftOperand() instanceof ColumnReference) {
            // generalize the estimation from ParameterNode to any expression
            double sel = ((ColumnReference) getLeftOperand()).columnReferenceEqualityPredicateSelectivity();
            if (getRightOperand() instanceof ParameterNode) {
                // it is possible that this is a special case that actually represents an inlist condition, then it is better to facter in the
                // number of inlist elements in the selectivity estimation
                int factor = 1;
                if (inListProbeSource != null) {
                    factor = inListProbeSource.getRightOperandList().size();
                }
                sel = factor * sel;
                // avoid the estimation to go over 1, in case it happens, round down to 0.9 to be consistent with the logic in
                // InListSelectivity.getSelectivity()
                if (sel > DEFAULT_INLIST_SELECTIVITY)
                    sel = DEFAULT_INLIST_SELECTIVITY;
            }
            return sel;
        } else if (getRightOperand() instanceof ColumnReference) {
            // generalize the estimation from ParameterNode to any expression
            return ((ColumnReference) getRightOperand()).columnReferenceEqualityPredicateSelectivity();
        }
        return -1.0d;
    }

    /**
     * return the selectivity of this predicate.
     */
    public double selectivity(Optimizable optTable) throws StandardException{
        double retval=booleanSelectivity(optTable);
        if(retval>=0.0d)
            return retval;
        switch(operatorType){
            case RelationalOperator.EQUALS_RELOP:
                double selectivity = getReferenceSelectivity(optTable);
                if (selectivity < 0.0d) // No Stats, lets just guess 10%
                    return DEFAULT_SINGLE_POINT_SELECTIVITY;
                return selectivity;
            case RelationalOperator.NOT_EQUALS_RELOP:
                selectivity = getReferenceSelectivity(optTable);
                if (selectivity < 0.0d) // No Stats, lets just guess 10%
                    return 1 - DEFAULT_SINGLE_POINT_SELECTIVITY;
                else
                    return 1-selectivity;
            case RelationalOperator.LESS_THAN_RELOP:
            case RelationalOperator.LESS_EQUALS_RELOP:
            case RelationalOperator.GREATER_EQUALS_RELOP:
                if(getBetweenSelectivity())
                    return DEFAULT_BETWEEN_SELECTIVITY;
                /* fallthrough -- only */
            case RelationalOperator.GREATER_THAN_RELOP:
                return DEFAULT_RANGE_SELECTIVITY;
        }
        return 0.0;
    }

    /**
     *
     * Key Method for computing join selectivity when a.col1 = b.col1 (BinaryReleationalOperator).
     *
     * ColumnReferences are heavily used to determine when we can use statistics for computations.  It would be nice
     * to remove the instance of bits and focus more on the implementation at the node level (TODO).
     *
     * @param optTable RHS of the join.
     * @param currentCd Conglomerate descriptor being considered in current access path.
     * @param innerRowCount RHS row count. This is the output row count of the RHS subtree.
     * @param outerRowCount LHS row count. This is the output row count of the LHS subtree.
     * @param selectivityJoinType Join type.
     * @return An estimate of join selectivity of this join.
     * @throws StandardException
     */
    @Override
    public double joinSelectivity(Optimizable optTable,
                                  ConglomerateDescriptor currentCd,
                                  long innerRowCount, long outerRowCount, SelectivityUtil.SelectivityJoinType selectivityJoinType) throws StandardException {
        assert optTable != null:"null values passed into predicate joinSelectivity";
        // Binary Relational Operator Node...
        double selectivity;

        if (operandMayHaveStatistics(RIGHT)) {
            ColumnReference right = getColumnOrIndexExprColumn(RIGHT);
            assert right != null;
            if (!right.useRealColumnStatistics()) {
                noStatsColumns.add(right.getSchemaQualifiedColumnName());
            }
            if (selectivityJoinType.equals(SelectivityUtil.SelectivityJoinType.LEFTOUTER)) {
                selectivity = (1.0d - right.nullSelectivity()) / right.nonZeroCardinality(innerRowCount);
            } else if (selectivityJoinType.equals(SelectivityUtil.SelectivityJoinType.FULLOUTER)) {
                // TODO DB-7816, temporarily borrow the selectivity logic from left join, may need to revisit
                selectivity = (1.0d - right.nullSelectivity()) / right.nonZeroCardinality(innerRowCount);
            } else if (operandMayHaveStatistics(LEFT)) {
                ColumnReference left = getColumnOrIndexExprColumn(LEFT);
                assert left != null;
                if (!left.useRealColumnStatistics()) {
                    noStatsColumns.add(left.getSchemaQualifiedColumnName());
                }

                // For the join, cardinality of LHS and RHS are not necessarily the raw numbers from column
                // statistics. We should apply them on LHS and RHS output row counts proportionally.
                double leftColumnIndexSelectivity = left.nonZeroCardinality(outerRowCount) / left.rowCountEstimate();
                double rightColumnIndexSelectivity = right.nonZeroCardinality(innerRowCount) / right.rowCountEstimate();
                double leftNonZeroCardinality = Math.max(1.0, leftColumnIndexSelectivity * outerRowCount);
                double rightNonZeroCardinality = Math.max(1.0, rightColumnIndexSelectivity * innerRowCount);

                selectivity = ((1.0d - left.nullSelectivity()) * (1.0d - right.nullSelectivity())) /
                        Math.min(leftNonZeroCardinality, rightNonZeroCardinality);
                selectivity = selectivityJoinType.equals(SelectivityUtil.SelectivityJoinType.INNER) ?
                        selectivity : 1.0d - selectivity;
                if (optTable instanceof FromTable && ((FromTable) optTable).getExistsTable()) {
                    selectivity = selectivity * leftNonZeroCardinality / outerRowCount;
                    if ((optTable instanceof FromBaseTable) && ((FromBaseTable) optTable).isAntiJoin()) {
                        selectivity = selectivity /(innerRowCount - (double)innerRowCount / rightNonZeroCardinality + 1);
                    }
                }
            } else { // No Left Column Reference
                selectivity = super.joinSelectivity(optTable, currentCd, innerRowCount, outerRowCount, selectivityJoinType);
            }
        } else { // No Right ColumnReference
            selectivity = super.joinSelectivity(optTable, currentCd, innerRowCount, outerRowCount, selectivityJoinType);
        }
        assert selectivity >= 0.0d && selectivity <= 1.0d
                : "selectivity is out of bounds " + selectivity + this + " right-> " + getRightOperand() + " left -> " + getLeftOperand();
        return selectivity;
    }

    private boolean operandMayHaveStatistics(int side) {
        if (side == LEFT) {
            return (getLeftOperand() instanceof ColumnReference && ((ColumnReference) getLeftOperand()).getSource().getTableColumnDescriptor() != null)
                    || (leftMatchIndexExpr >= 0 && leftMatchIndexExprColumnPosition >= 0 && leftMatchIndexExprConglomDesc != null);
        } else if (side == RIGHT) {
            return (getRightOperand() instanceof ColumnReference && ((ColumnReference) getRightOperand()).getSource().getTableColumnDescriptor() != null)
                    || (rightMatchIndexExpr >= 0 && rightMatchIndexExprColumnPosition >= 0 && rightMatchIndexExprConglomDesc != null);
        } else {
            throw new RuntimeException("invalid side argument passed in");
        }
    }

    /* Get a ColumnReference for an index on expression column so that we can get a
     * StoreCostController to get correct statistics. We need this because in cost
     * estimation, index expression are not rewritten yet.
     * Note that the returned ColumnReference should only be used to retrieve
     * statistics because it's column position is set to conglomerate column
     * position of the index expression. It doesn't refer to any child result set
     * columns.
     */
    private ColumnReference getColumnOrIndexExprColumn(int side)
            throws StandardException
    {
        ValueNode operand = side == LEFT ? getLeftOperand() : getRightOperand();
        int tableNumber = side == LEFT ? leftMatchIndexExpr : rightMatchIndexExpr;
        int indexColumnPosition = side == LEFT ? leftMatchIndexExprColumnPosition : rightMatchIndexExprColumnPosition;
        ConglomerateDescriptor conglomDesc = side == LEFT ? leftMatchIndexExprConglomDesc : rightMatchIndexExprConglomDesc;

        IndexRowGenerator irg = conglomDesc == null ? null : conglomDesc.getIndexDescriptor();
        if (irg == null || !irg.isOnExpression() || tableNumber < 0 || indexColumnPosition < 0) {
            if (operand instanceof ColumnReference) {
                return (ColumnReference)operand;
            } else {
                return null;
            }
        }
        List<ColumnReference> baseColumnRefs = operand.getHashableJoinColumnReference();
        assert !baseColumnRefs.isEmpty() : "no column reference found in index expression";
        ColumnReference baseColumnRef = baseColumnRefs.get(0);

        // build a fake ResultColumn
        ResultColumn rc = (ResultColumn) getNodeFactory().getNode(
                C_NodeTypes.RESULT_COLUMN,
                irg.getIndexColumnTypes()[indexColumnPosition],
                operand,
                getContextManager());
        rc.setIndexExpression(operand);
        rc.setReferenced();
        // virtual column IDs are 1-based, set to conglomerate index column position
        // so that we can get column statistics correctly
        rc.setVirtualColumnId(indexColumnPosition + 1);
        rc.setName(conglomDesc.getConglomerateName() + "_col" + rc.getColumnPosition());
        rc.setSourceTableName(baseColumnRef.getSourceTableName());
        rc.setSourceSchemaName(baseColumnRef.getSourceSchemaName());
        rc.setSourceConglomerateNumber(conglomDesc.getConglomerateNumber());
        rc.setSourceConglomerateColumnPosition(indexColumnPosition + 1);

        return rc.getColumnReference(operand);
    }

    public RelationalOperator getTransitiveSearchClause(ColumnReference otherCR) throws StandardException{
        return (RelationalOperator)getNodeFactory().getNode(getNodeType(),otherCR,getRightOperand(),getContextManager());
    }

    public boolean equalsComparisonWithConstantExpression(Optimizable optTable){
        if(operatorType!=EQUALS_RELOP)
            return false;

        boolean retval=false;

        int side=columnOnOneSide(optTable);
        if (side == NEITHER) {
            side = indexExprOnOneSide(optTable);
        }
        if(side==LEFT){
            retval=getRightOperand().isConstantExpression();
        }else if(side==RIGHT){
            retval=getLeftOperand().isConstantExpression();
        }

        return retval;
    }

    @Override
    public double scanSelectivity(Optimizable innerTable) throws StandardException {
        double selectivity = 1.0d;
        ColumnReference innerColumn = null;
        ColumnReference outerColumn = null;

        if (operandMayHaveStatistics(LEFT)) {
            ColumnReference cr = getColumnOrIndexExprColumn(LEFT);
            assert cr != null;
            if (cr.getTableNumber() == innerTable.getTableNumber()) {
                innerColumn = cr;
            } else {
                outerColumn = cr;
            }
        }
        else return selectivity;

        if (operandMayHaveStatistics(RIGHT)) {
            ColumnReference cr = getColumnOrIndexExprColumn(RIGHT);
            assert cr != null;
            if (cr.getTableNumber() == innerTable.getTableNumber()) {
                innerColumn = cr;
            } else {
                outerColumn = cr;
            }
        }
        else return selectivity;

        if (innerColumn == null || outerColumn == null)
            return selectivity;

        StoreCostController innerTableCostController = innerColumn.getStoreCostController();
        StoreCostController outerTableCostController = outerColumn.getStoreCostController();

        final int innerColumnPosition = innerColumn.getColumnPositionForStatistics();
        final int outerColumnPosition = outerColumn.getColumnPositionForStatistics();

        DataValueDescriptor minOuterColumn = null;
        DataValueDescriptor maxOuterColumn = null;
        DataValueDescriptor minInnerColumn = null;
        DataValueDescriptor maxInnerColumn = null;
        if (outerTableCostController != null) {
            long rc = (long)outerTableCostController.baseRowCount();
            if (rc == 0)
                return 0.0d;
            boolean forIndexExpr = outerColumn.isGeneratedToReplaceIndexExpression();
            minOuterColumn = outerTableCostController.minValue(forIndexExpr, outerColumnPosition);
            maxOuterColumn = outerTableCostController.maxValue(forIndexExpr, outerColumnPosition);
        }

        if (innerTableCostController != null) {
            long rc = (long)innerTableCostController.baseRowCount();
            if (rc == 0)
                return 0.0d;
            boolean forIndexExpr = innerColumn.isGeneratedToReplaceIndexExpression();
            minInnerColumn = innerTableCostController.minValue(forIndexExpr, innerColumnPosition);
            maxInnerColumn = innerTableCostController.maxValue(forIndexExpr, innerColumnPosition);
        }

        DataValueDescriptor startKey = getKeyBoundary(minInnerColumn, minOuterColumn, true);
        DataValueDescriptor endKey = getKeyBoundary(maxInnerColumn, maxOuterColumn, false);

        if (startKey!= null && minInnerColumn != null && startKey.compare(minInnerColumn) > 0 ||
                endKey!= null && maxInnerColumn != null && endKey.compare(maxInnerColumn)< 0) {
            selectivity *= innerTableCostController.getSelectivity(innerColumn.isGeneratedToReplaceIndexExpression(),
                    innerColumnPosition, startKey, true, endKey, true, false);
        }
        else if (this.operatorType == EQUALS_RELOP) {
            // Use a more realistic selectivity that takes the
            // inner table RPV into account instead of defaulting
            // to a selectivity of 1.
            double outerCardinality =
                    outerTableCostController.cardinality(
                            outerColumn.isGeneratedToReplaceIndexExpression(),
                            outerColumnPosition);
            double innerCardinality =
                    innerTableCostController.cardinality(
                            innerColumn.isGeneratedToReplaceIndexExpression(),
                            innerColumnPosition);

            // If cardinality values are uninitialized (zero),
            // we can't apply this estimation formula.
            if (outerCardinality != 0.0d && innerCardinality != 0.0d) {
                double tempSelectivity = outerCardinality / innerCardinality;
                selectivity = Math.min(tempSelectivity, 1.0d);
            }
        }

        return selectivity;
    }

    private DataValueDescriptor getKeyBoundary(DataValueDescriptor d1, DataValueDescriptor d2, boolean isStartKey) throws StandardException{

        if (d1 == null || d1.isNull()) {
            if (d2 == null || d2.isNull()) {
                return null;
            }
            else return d2;
        }
        else {
            if (d2 == null || d2.isNull()) {
                return d1;
            }
            else if (d1.compare(d2) > 0) {
                if (isStartKey) return d1;
                else return d2;
            }
            else {
                if (isStartKey) return d2;
                return d1;
            }
        }
    }

    @Override
    public boolean isRelationalOperator(){
        /* If this rel op is for a probe predicate then we do not call
         * it a "relational operator"; it's actually a disguised IN-list
         * operator.
         */
        return !isInListProbeNode();
    }

    @Override
    public boolean isBinaryEqualsOperatorNode(){
        /* If this rel op is for a probe predicate then we do not treat
         * it as an "equals operator"; it's actually a disguised IN-list
         * operator.
         */
        return !isInListProbeNode() && (operatorType==RelationalOperator.EQUALS_RELOP);
    }

    /**
     * @see ValueNode#isInListProbeNode
     * <p/>
     * It's okay for this method to reference inListProbeSource directly
     * because it does not rely on the contents of inListProbeSource's
     * leftOperand, and a caller of this method cannot gain access to
     * inListProbeSource's leftOperand through this method.
     */
    @Override
    public boolean  isInListProbeNode(){
        return (inListProbeSource!=null);
    }

    @Override
    public boolean optimizableEqualityNode(Optimizable optTable,
                                           int columnNumber,
                                           boolean isNullOkay) throws StandardException {
        if (operatorType != EQUALS_RELOP)
            return false;

        /* If this rel op is for a probe predicate then we do not treat
         * it as an equality node; it's actually a disguised IN-list node.
         */
        if (isInListProbeNode())
            return false;

        ColumnReference cr = getColumnOperand(optTable,
                columnNumber);
        if (cr == null)
            return false;

        return !selfComparison(cr,false) && !implicitVarcharComparison();

    }

     @Override
     public boolean optimizableEqualityNode(Optimizable optTable,
                                            ValueNode indexExpr,
                                            boolean isNullOkay) throws StandardException {
         if (operatorType != EQUALS_RELOP)
             return false;

         /* If this rel op is for a probe predicate then we do not treat
          * it as an equality node; it's actually a disguised IN-list node.
          */
         if (isInListProbeNode())
             return false;

         ValueNode expr;
         int side = indexExprOnOneSide(optTable);
         switch(side) {
             case LEFT:
                 expr = getLeftOperand();
                 break;
             case RIGHT:
                 expr = getRightOperand();
                 break;
             case NEITHER:
             default:
                 return false;
         }

         if (!indexExpr.semanticallyEquals(expr)) {
             return false;
         }

         List<ColumnReference> crList = expr.getHashableJoinColumnReference();
         assert crList != null && !crList.isEmpty();
         return !selfComparison(crList.get(0),false) && !implicitVarcharComparison();

     }

    /**
     * Return whether or not this binary relational predicate requires an implicit
     * (var)char conversion.  This is important when considering
     * hash join since this type of equality predicate is not currently
     * supported for a hash join.
     *
     * @return Whether or not an implicit (var)char conversion is required for
     * this binary relational operator.
     * @throws StandardException Thrown on error
     */

    private boolean implicitVarcharComparison()
            throws StandardException {
        TypeId leftType = getLeftOperand().getTypeId();
        TypeId rightType = getRightOperand().getTypeId();

        return leftType.isStringTypeId() && !rightType.isStringTypeId() || rightType.isStringTypeId() && (!leftType.isStringTypeId());

    }

    /* @see BinaryOperatorNode#genSQLJavaSQLTree
     * @see BinaryComparisonOperatorNode#genSQLJavaSQLTree
     */
    public ValueNode genSQLJavaSQLTree() throws StandardException{
        if(operatorType==EQUALS_RELOP)
            return this;

        return super.genSQLJavaSQLTree();
    }

    /**
     * Take a ResultSetNode and return a column reference that is scoped for
     * for the received ResultSetNode, where "scoped" means that the column
     * reference points to a specific column in the RSN.  This is used for
     * remapping predicates from an outer query down to a subquery.
     * <p/>
     * For example, assume we have the following query:
     * <p/>
     * select * from
     * (select i,j from t1 union select i,j from t2) X1,
     * (select a,b from t3 union select a,b from t4) X2
     * where X1.j = X2.b;
     * <p/>
     * Then assume that this BinaryRelationalOperatorNode represents the
     * "X1.j = X2.b" predicate and that the childRSN we received as a
     * parameter represents one of the subqueries to which we want to push
     * the predicate; let's say it's:
     * <p/>
     * select i,j from t1
     * <p/>
     * Then what we want to do in this method is map one of the operands
     * X1.j or X2.b (depending on the 'whichSide' parameter) to the childRSN,
     * if possible.  Note that in our example, "X2.b" should _NOT_ be mapped
     * because it doesn't apply to the childRSN for the subquery "select i,j
     * from t1"; thus we should leave it as it is.  "X1.j", however, _does_
     * need to be scoped, and so this method will return a ColumnReference
     * pointing to "T1.j" (or whatever the corresponding column in T1 is).
     * <p/>
     * ASSUMPTION: We should only get to this method if we know that
     * exactly one operand in the predicate to which this operator belongs
     * can and should be mapped to the received childRSN.
     *
     * @param whichSide        The operand are we trying to scope (LEFT or RIGHT)
     * @param parentRSNsTables Set of all table numbers referenced by
     *                         the ResultSetNode that is _parent_ to the received childRSN.
     *                         We need this to make sure we don't scope the operand to a
     *                         ResultSetNode to which it doesn't apply.
     * @param childRSN         The result set node to which we want to create
     *                         a scoped predicate.
     * @param whichRC          If not -1 then this tells us which ResultColumn
     *                         in the received childRSN we need to use for the scoped predicate;
     *                         if -1 then the column position of the scoped column reference
     *                         will be stored in this array and passed back to the caller.
     * @return A column reference scoped to the received childRSN, if possible.
     * If the operand is a ColumnReference that is not supposed to be scoped,
     * we return a _clone_ of the reference--this is necessary because the
     * reference is going to be pushed to two places (left and right children
     * of the parentRSN) and if both children are referencing the same
     * instance of the column reference, they'll interfere with each other
     * during optimization.
     */
    public ValueNode getScopedOperand(int whichSide,
                                      JBitSet parentRSNsTables,ResultSetNode childRSN,
                                      int[] whichRC) throws StandardException{
        ResultColumn rc;
        ColumnReference cr=
                whichSide==LEFT
                        ?(ColumnReference)getLeftOperand()
                        :(ColumnReference)getRightOperand();

        /* When we scope a predicate we only scope one side of it--the
         * side that is to be evaluated against childRSN.  We figure out
         * if "cr" is that side by using table numbers, as seen below.
         * This means that for every scoped predicate there will be one
         * operand that is scoped and one operand that is not scoped.
         * When we get here for the operand that will not be scoped,
         * we'll just return a clone of that operand.  So in the example
         * mentioned above, the scoped predicate for the left child of
         * X1 would be
         *
         *   T1.j <scoped> = X2.b <clone>
         *
         * That said, the first thing we need to do is see if this
         * ColumnReference is supposed to be scoped for childRSN.  We
         * do that by figuring out what underlying base table the column
         * reference is pointing to and then seeing if that base table
         * is included in the list of table numbers from the parentRSN.
         */
        JBitSet crTables=new JBitSet(parentRSNsTables.size());
        BaseTableNumbersVisitor btnVis=
                new BaseTableNumbersVisitor(crTables);
        cr.accept(btnVis);

        /* If the column reference in question is not intended for
         * the received result set node, just leave the operand as
         * it is (i.e. return a clone).  In the example mentioned at
         * the start of this method, this will happen when the operand
         * is X2.b and childRSN is either "select i,j from t1" or
         * "select i,j from t2", in which case the operand does not
         * apply to childRSN.  When we get here and try to map the
         * "X1.j" operand, though, the following "contains" check will
         * return true and thus we can go ahead and return a scoped
         * version of that operand.
         */
        if(!parentRSNsTables.contains(crTables))
            return cr.getClone();

        /* Find the target ResultColumn in the received result set.  At
         * this point we know that we do in fact need to scope the column
         * reference for childRSN, so go ahead and do it.  The way in
         * which we get the scope target column differs depending on
         * if childRSN corresponds to the left or right child of the
         * UNION node.  Before explaining that, though, note that it's
         * not good enough to just search for the target column by
         * name.  The reason is that it's possible the name provided
         * for the column reference to be scoped doesn't match the
         * name of the actual underlying column.  Ex.
         *
         *  select * from
         *    (select i,j from t1 union select i,j from t2) X1 (x,y),
         *    (select a,b from t3 union select a,b from t4) X2
         *  where X1.x = X2.b;
         *
         * If we were scoping "X1.x" and we searched for "x" in the
         * childRSN "select i,j from t1" we wouldn't find it.
         *
         * It is similarly incorrect to search for the target column
         * by position (DERBY-1633).  This is a bit more subtle, but
         * if the child to which we're scoping is a subquery whose RCL
         * does not match the column ordering of the RCL for cr's source
         * result set, then searching by column position can yield the
         * wrong results, as well.  For a detailed example of how this
         * can happen, see the fix description attached to DERBY-1633.
         *
         * So how do we find the target column, then? As mentioned
         * above, the way in which we get the scope target column
         * differs depending on if childRSN corresponds to the left
         * or right child of the parent UNION node.  And that said,
         * we can tell if we're scoping a left child by looking at
         * "whichRC" argument: if it is -1 then we know we're scoping
         * to the left child of a Union; otherwise we're scoping to
         * the right child.
         */
        if(whichRC[0]==-1){
            /*
             * For the left side we start by figuring out what the source
             * result set and column position for "cr" are.  Then, since
             * a) cr must be pointing to a result column in the parentRSN's
             * ResultColumnList,  b) we know that the parent RSN is a
             * SetOperatorNode (at least for now, since we only get here
             * for Union nodes), and c) SetOpNode's RCLs are built from the
             * left child's RCL (see bindResultColumns() in SetOperatorNode),
             * we know that if we search the child's RCL for a reference
             * whose source result column is the same as cr's source result
             * column, we'll find a match.  Once found, the position of the
             * matching column w.r.t childRSN's RCL will be stored in the
             * whichRC parameter.
             */

            // Find the source result set and source column position of cr.
            int[] sourceColPos= {-1};
            ResultSetNode sourceRSN=cr.getSourceResultSet(sourceColPos);

            if (sourceRSN == null) {
                // if the column is mapped to an expression instead of a ColumnReference, we cannot map further
                // down, but we shouldn't error
                if (cr.getSource() != null) {
                    ResultColumn rcInOperand = cr.getSource();
                    // go through the child RSN and find the matching result column
                    for (int i=0; i<childRSN.getResultColumns().size(); i++)
                        if (rcInOperand == childRSN.getResultColumns().elementAt(i)) {
                            return rcInOperand.getExpression();
                        }
                }

                if (SanityManager.DEBUG) {
                /* We assumed that if we made it here "cr" was pointing
                 * to a base table somewhere down the tree.  If that's
                 * true then sourceRSN won't be null.  Make sure our
                 * assumption was correct.
                 */
                    SanityManager.ASSERT(false,
                            "Failed to find source result set when trying to " +
                                    "scope column reference '" + cr.getTableName() +
                                    "." + cr.getColumnName());
                }
            }

            // Now search for the corresponding ResultColumn in childRSN.
            rc=childRSN.getResultColumns()
                    .getResultColumn(sourceColPos[0],sourceRSN,whichRC);
        }else{
            /*
             * For the right side the story is slightly different.  If we were
             * to search the right child's RCL for a reference whose source
             * result column was the same as cr's, we wouldn't find it.  This
             * is because cr's source result column comes from the left child's
             * RCL and thus the right child doesn't know about it.  That said,
             * though, for set operations like UNION, the left and right RCL's
             * are correlated by position--i.e. the operation occurs between
             * the nth column in the left RCL and the nth column in the right
             * RCL.  So given that we will already have found the scope target
             * in the left child's RCL at the position in whichRC, we know that
             * that scope target for the right child's RCL is simply the
             * whichRC'th column in that RCL.
             */
            rc=childRSN.getResultColumns().getResultColumn(whichRC[0]);
        }

        // rc shouldn't be null; if there was no matching ResultColumn at all,
        // then we shouldn't have made it this far.
        if(SanityManager.DEBUG){
            SanityManager.ASSERT(rc!=null,
                    "Failed to locate scope target result column when trying to "+
                            "scope operand '"+cr.getTableName()+"."+
                            cr.getColumnName()+"'.");
        }

        /* If the ResultColumn we found has an expression that is a
         * ColumnReference, then that column reference has all of the
         * info we need.
         *
         * It is, however, possible that the ResultColumn's expression
         * is NOT a ColumnReference.  For example, the expression would
         * be a constant expression if childRSN represented something
         * like:
         *
         *   select 1, 1 from t1
         *
         * In this case the expression does not directly reference a
         * column in the underlying result set and is therefore
         * "scoped" as far as it can go.  This means that the scoped
         * predicate will not necessarily have column references on
         * both sides, even though the predicate that we're scoping
         * will.  That's not a problem, though, since a predicate with
         * a column reference on one side and a non-ColumnReference
         * on the other is still valid.
         */

        if(rc.getExpression() instanceof ColumnReference){
            /* We create a clone of the column reference and mark
             * the clone as "scoped" so that we can do the right
             * thing when it comes time to remap the predicate;
             * see Predicate.remapScopedPred() for more.
             */
            ColumnReference cRef=(ColumnReference)
                    ((ColumnReference)rc.getExpression()).getClone();
            cRef.markAsScoped();
            return cRef;
        }

        /* Else just return rc's expression.  This means the scoped
         * predicate will have one operand that is _not_ a column
         * reference--but that's okay, so long as we account for
         * that when pushing/remapping the scoped predicate down
         * the query tree (see esp. "isScopedToSourceResultSet()"
         * in Predicate.java).
         */
        return rc.getExpression();
    }

    /**
     * Determine whether or not the received ValueNode (which will
     * usually be a ColumnReference) references either the received
     * optTable or else a base table in the subtree beneath that
     * optTable.
     *
     * @param valNode             The ValueNode that has the reference(s).
     * @param optTable            The table/subtree node to which we're trying
     *                            to find a reference.
     * @param forPush             Whether or not we are searching with the intent
     *                            to push this operator to the target table.
     * @param walkOptTableSubtree Should we walk the subtree beneath
     *                            optTable to find base tables, or not?  Will be false if we've
     *                            already done it for the left operand and now we're here
     *                            for the right operand.
     * @return True if valNode contains a reference to optTable or
     * to a base table in the subtree beneath optTable; false
     * otherwise.
     */
    private boolean valNodeReferencesOptTable(ValueNode valNode,
                                              FromTable optTable,boolean forPush,boolean walkOptTableSubtree){
        
        // Following call will initialize/reset the btnVis,
        // valNodeBaseTables, and optBaseTables fields of this object.
        initBaseTableVisitor(optTable.getReferencedTableMap().size(),
                walkOptTableSubtree);

        boolean found=false;
        try{

            // Find all base tables beneath optTable and load them
            // into this object's optBaseTables map.  This is the
            // list of table numbers we'll search to see if the
            // value node references any tables in the subtree at
            // or beneath optTable.
            if(walkOptTableSubtree)
                buildTableNumList(optTable,forPush);

            // If the valNode references a table number in optTable's referencedTableMap,
            // we know this node references a column from the optTable, no further checking is needed.
            // For a column referencing the output of a set operation, we may not be able to
            // trace back the base table from the column reference's source.
            if (valNode instanceof ColumnReference) {
                if (valNode.getTableNumber() >= 0 && optBaseTables.get(valNode.getTableNumber()))
                    return true;
            }

            // Now get the base table numbers that are in valNode's
            // subtree.  In most cases valNode will be a ColumnReference
            // and this will return a single base table number.
            btnVis.setTableMap(valNodeBaseTables);
            valNode.accept(btnVis);

            // And finally, see if there's anything in common.
            valNodeBaseTables.and(optBaseTables);
            found=(valNodeBaseTables.getFirstSetBit()!=-1);

        }catch(Throwable t){
            if(SanityManager.DEBUG){
                SanityManager.THROWASSERT("Failed when trying to "+
                        "find base table numbers for reference check:", t);
            }
        }

        return found;
    }

    /**
     * Initialize the fields used for retrieving base tables in
     * subtrees, which allows us to do a more extensive search
     * for table references.  If the fields have already been
     * created, then just reset their values.
     *
     * @param numTablesInQuery  Used for creating JBitSets that
     *                          can hold table numbers for the query.
     * @param initOptBaseTables Whether or not we should clear out
     *                          or initialize the optBaseTables bit set.
     */
    private void initBaseTableVisitor(int numTablesInQuery,
                                      boolean initOptBaseTables){
        if(valNodeBaseTables==null)
            valNodeBaseTables=new JBitSet(numTablesInQuery);
        else
            valNodeBaseTables.clearAll();

        if(initOptBaseTables){
            if(optBaseTables==null)
                optBaseTables=new JBitSet(numTablesInQuery);
            else
                optBaseTables.clearAll();
        }

        // Now create the visitor.  We give it valNodeBaseTables
        // here for sake of creation, but this can be overridden
        // (namely, by optBaseTables) by the caller of this method.
        if(btnVis==null)
            btnVis=new BaseTableNumbersVisitor(valNodeBaseTables);
    }

    /**
     * Create a set of table numbers to search when trying to find
     * which (if either) of this operator's operands reference the
     * received target table.  At the minimum this set should contain
     * the target table's own table number.  After that, if we're
     * _not_ attempting to push this operator (or more specifically,
     * the predicate to which this operator belongs) to the target
     * table, we go on to search the subtree beneath the target
     * table and add any base table numbers to the searchable list.
     *
     * @param ft      Target table for which we're building the search
     *                list.
     * @param forPush Whether or not we are searching with the intent
     *                to push this operator to the target table.
     */
    private void buildTableNumList(FromTable ft,boolean forPush)
            throws StandardException{
        // Start with the target table's own table number.  Note
        // that if ft is an instanceof SingleChildResultSet, its
        // table number could be negative.
        if(ft.getTableNumber()>=0)
            optBaseTables.set(ft.getTableNumber());

        if(forPush)
            // nothing else to do.
            return;

        // Add any table numbers from the target table's
        // reference map.
        optBaseTables.or(ft.getReferencedTableMap());

        // The table's reference map is not guaranteed to have
        // all of the tables that are actually used--for example,
        // if the table is a ProjectRestrictNode or a JoinNode
        // with a subquery as a child, the ref map will contain
        // the number for the PRN above the subquery, but it
        // won't contain the table numbers referenced by the
        // subquery.  So here we go through and find ALL base
        // table numbers beneath the target node.
        btnVis.setTableMap(optBaseTables);
        ft.accept(btnVis);
    }

    public boolean hasRowId(){
        boolean ret=false;
        if(getLeftOperand() instanceof ColumnReference){
            ColumnReference cr=(ColumnReference)getLeftOperand();
            if(cr.getColumnName().compareToIgnoreCase("ROWID")==0){
                ret=true;
            }
        }else if(getRightOperand() instanceof ColumnReference){
            ColumnReference cr=(ColumnReference)getRightOperand();
            if(cr.getColumnName().compareToIgnoreCase("ROWID")==0){
                ret=true;
            }
        }
        return ret;
    }

     public int getOuterJoinLevel() {
         return outerJoinLevel;
     }

     public void setOuterJoinLevel(int level) {
         outerJoinLevel = level;
     }

     public HashSet<String> getNoStatsColumns() {
        return noStatsColumns;
     }
}
