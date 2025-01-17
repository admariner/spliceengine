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

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static com.splicemachine.db.impl.sql.compile.AndNode.newAndNode;

public class OrNode extends BinaryLogicalOperatorNode {
    /* Is this the 1st OR in the OR chain? */
    private boolean firstOr;

    public OrNode() {}
    public OrNode(ValueNode left, ValueNode right, ContextManager cm) {
        setContextManager(cm);
        setNodeType(C_NodeTypes.OR_NODE);
        init(left, right);
    }

    /**
     * Initializer for an OrNode
     *
     * @param leftOperand    The left operand of the OR
     * @param rightOperand    The right operand of the OR
     */

    public void init(Object leftOperand, Object rightOperand)
    {
        super.init(leftOperand, rightOperand, "or");
        this.shortCircuitValue = true;
        this.operatorType = OR;
    }

    /**
     * Mark this OrNode as the 1st OR in the OR chain.
     * We will consider converting the chain to an IN list
     * during preprocess() if all entries are of the form:
     *        ColumnReference = expression
     */
    void setFirstOr()
    {
        firstOr = true;
    }

    /**
     * Bind this logical operator.  All that has to be done for binding
     * a logical operator is to bind the operands, check that both operands
     * are BooleanDataValue, and set the result type to BooleanDataValue.
     *
     * @param fromList            The query's FROM list
     * @param subqueryList        The subquery list being built as we find SubqueryNodes
     * @param aggregateVector    The aggregate vector being built as we find AggregateNodes
     *
     * @return    The new top of the expression tree.
     *
     * @exception StandardException        Thrown on error
     */
    @Override
    public ValueNode bindExpression(FromList fromList, SubqueryList subqueryList, List<AggregateNode> aggregateVector) throws StandardException {
        super.bindExpression(fromList, subqueryList, aggregateVector);
        postBindFixup();
        return this;
    }

    private boolean canConvertToInList(ValueNode vn, ArrayList<Integer> columnNumbers,
                                       ArrayList<Integer> compareNumbers,
                                       HashMap<Integer, ColumnReference> columns,
                                       MutableInt tableNumber, MutableBoolean hasInListPred,
                                       boolean firstTime) {
        if (hasInListPred == null)
            hasInListPred = new MutableBoolean(false);

        boolean retVal =
            canConvertToInListHelper(vn, columnNumbers, compareNumbers,
                                     columns, tableNumber, hasInListPred, firstTime);
        if (!retVal)
            return false;

        if (firstTime) {
            if (columnNumbers.size() < 1)
                return false;
            if (columnNumbers.size() > 1 && hasInListPred.isTrue())
                return false;
            Collections.sort(columnNumbers);
        }
        else {
            Collections.sort(compareNumbers);
            retVal = retVal && compareNumbers.equals(columnNumbers);
        }

        return retVal;
    }
    private boolean canConvertToInListHelper(ValueNode vn, ArrayList<Integer> columnNumbers,
                                       ArrayList<Integer> compareNumbers,
                                       HashMap<Integer, ColumnReference> columns,
                                       MutableInt tableNumber, MutableBoolean hasInListPred,
                                       boolean firstTime) {
        boolean convert = false;
        ColumnReference cr = null;

        if (vn instanceof AndNode)
            return canConvertToInListHelper(((AndNode)vn).getLeftOperand(), columnNumbers,
                                      compareNumbers, columns, tableNumber, hasInListPred, firstTime) &&
                canConvertToInListHelper(((AndNode) vn).getRightOperand(), columnNumbers,
                       compareNumbers, columns, tableNumber, hasInListPred, firstTime);
        else if (vn instanceof BooleanConstantNode)
            return (((BooleanConstantNode) vn).isBooleanTrue());
        else
        // Is the operator an =
        if (!vn.isRelationalOperator()) {
            /* If the operator is an IN-list disguised as a relational
             * operator then we can still convert it--we'll just
             * combine the existing IN-list ("left") with the new IN-
             * list values.  So check for that case now.
             */
            
            if (SanityManager.DEBUG) {
                /* At the time of writing the only way a call to
                 * left.isRelationalOperator() would return false for
                 * a BinaryRelationalOperatorNode was if that node
                 * was for an IN-list probe predicate.  That's why we
                 * we can get by with the simple "instanceof" check
                 * below.  But if we're running in SANE mode, do a
                 * quick check to make sure that's still valid.
                 */
                BinaryRelationalOperatorNode bron = null;
                if (vn instanceof BinaryRelationalOperatorNode) {
                    bron = (BinaryRelationalOperatorNode) vn;
                    if (!bron.isInListProbeNode()) {
                        SanityManager.THROWASSERT(
                            "isRelationalOperator() unexpectedly returned "
                                + "false for a BinaryRelationalOperatorNode.");
                    }
                }
            }
            
            convert = (vn instanceof BinaryRelationalOperatorNode);
            if (!convert)
                return false;
        }

        if (!(((RelationalOperator) vn).getOperator() == RelationalOperator.EQUALS_RELOP)) {
            return false;
        }

        BinaryRelationalOperatorNode bron = (BinaryRelationalOperatorNode) vn;
        
        if (bron.getLeftOperand() instanceof ColumnReference) {
            cr = (ColumnReference) bron.getLeftOperand();
            if (bron.isInListProbeNode()) {
                if (columnNumbers.size() > 1)
                    return false;
                else
                    hasInListPred.setTrue();
            }
            if (!bron.getRightOperand().isConstantOrParameterTreeNode())
            return false;
        }
        else if (bron.getRightOperand() instanceof ColumnReference) {
            cr = (ColumnReference) bron.getRightOperand();
            if (bron.isInListProbeNode()) {
                if (columnNumbers.size() > 1)
                    return false;
                else
                    hasInListPred.setTrue();
            }
            if (!bron.getLeftOperand().isConstantOrParameterTreeNode())
                return false;
        }
        else {
            return false;
        }

        if (firstTime) {
            if (tableNumber.intValue() == -1)
                tableNumber.setValue(cr.getTableNumber());
            else if (cr.getTableNumber() != tableNumber.intValue())
                return false;
            if (!columnNumbers.contains(cr.getColumnNumber())) {
                columnNumbers.add(cr.getColumnNumber());
                columns.put(cr.getColumnNumber(), cr);
            }
            if (columnNumbers.size() > 1 &&
                (hasInListPred.isTrue() ||
                 !getCompilerContext().getConvertMultiColumnDNFPredicatesToInList()))
                return false;
        } else if (tableNumber.intValue() != cr.getTableNumber() ||
                   !columnNumbers.contains(cr.getColumnNumber())) {
            return false;
        }
        else
            compareNumbers.add(cr.getColumnNumber());
        return true;
    }
    
    
    private void addNewInListNode(ValueNode vn, HashMap<Integer, Integer> columnMap, ValueNodeList vnl)
        throws StandardException {
        
        HashMap constNodes = new HashMap<Integer, ValueNode>();
        
        boolean multiColumn = false;
        if (columnMap.size() > 1)
            multiColumn = true;
        
        constructNodeForInList(vn, multiColumn, columnMap, vnl, constNodes);
    
        if (columnMap.size() > 1) {
            ValueNodeList constList = new ValueNodeList(getContextManager());
            for (int i = 0; i < columnMap.size(); i++) {
                constList.addValueNode((ValueNode)constNodes.get(i));
            }
            
            ValueNode lcn = new ListValueNode(constList, getContextManager());
            vnl.addValueNode(lcn);
        }
    }
    
    private void constructNodeForInList(ValueNode vn, boolean multiColumn,
                                        HashMap<Integer, Integer> columnMap, ValueNodeList vnl,
                                        HashMap<Integer, ValueNode> constNodes)
        throws StandardException {
        
        if (vn instanceof AndNode) {
            constructNodeForInList(((AndNode) vn).getLeftOperand(), multiColumn, columnMap, vnl, constNodes);
            constructNodeForInList(((AndNode) vn).getRightOperand(), multiColumn, columnMap, vnl, constNodes);
            return;
        }
        else if (vn instanceof BooleanConstantNode) {
            // Do nothing
            return;
        }
        
        BinaryRelationalOperatorNode bron =
            (BinaryRelationalOperatorNode) vn;
        if (bron.isInListProbeNode() && !multiColumn) {
            /* If we have an OR between multiple IN-lists on the same
             * column then just combine them into a single IN-list.
             * Ex.
             *
             *   select ... from T1 where i in (2, 3) or i in (7, 10)
             *
             * effectively becomes:
             *
             *   select ... from T1 where i in (2, 3, 7, 10).
             */
            vnl.destructiveAppend(
                bron.getInListOp().getRightOperandList());
        } else if (bron.getLeftOperand() instanceof ColumnReference) {
            if (!multiColumn)
                vnl.addValueNode(bron.getRightOperand());
            else {
                constNodes.put(columnMap.get(((ColumnReference) bron.getLeftOperand()).getColumnNumber()),
                               bron.getRightOperand());
            }
        } else {
            if (!multiColumn)
                vnl.addValueNode(bron.getLeftOperand());
            else {
                constNodes.put(columnMap.get(((ColumnReference) bron.getRightOperand()).getColumnNumber()),
                               bron.getLeftOperand());
            }
        }
    }
    
    /**
     * Preprocess an expression tree.  We do a number of transformations
     * here (including subqueries, IN lists, LIKE and BETWEEN) plus
     * subquery flattening.
     * NOTE: This is done before the outer ResultSetNode is preprocessed.
     *
     * @param    numTables            Number of tables in the DML Statement
     * @param    outerFromList        FromList from outer query block
     * @param    outerSubqueryList    SubqueryList from outer query block
     * @param    outerPredicateList    PredicateList from outer query block
     *
     * @return        The modified expression
     *
     * @exception StandardException        Thrown on error
     */
    public ValueNode preprocess(int numTables,
                                FromList outerFromList,
                                SubqueryList outerSubqueryList,
                                PredicateList outerPredicateList)
                    throws StandardException
    {
        super.preprocess(numTables,
                         outerFromList, outerSubqueryList,
                         outerPredicateList);

        /* If this is the first OR in the OR chain then we will
         * consider converting it to an IN list and then performing
         * whatever IN list conversions/optimizations are available.
         * An OR can be converted to an IN list if all of the entries
         * in the chain are of the form:
         *        ColumnReference = x
         *    or:
         *        x = ColumnReference
         * where all ColumnReferences are from the same table.
         *
         * We only convert the OR chain to an IN list if it has been
         * normalized to conjunctive normal form (CNF) first. That is, the
         * shape of the chain must be something like this:
         *
         *               OR
         *              /  \
         *             =    OR
         *                 /  \
         *                =   OR
         *                    / \
         *                   =   FALSE
         *
         * Predicates in WHERE, HAVING and ON clauses will have been
         * normalized by the time we get here. Boolean expressions other
         * places in the query are not necessarily normalized, but they
         * won't benefit from IN list conversion anyway, since they cannot
         * be used as qualifiers in a multi-probe scan, so simply skip the
         * conversion in those cases.
         */
        if (firstOr)
        {
            boolean            convert = true;
            HashMap         columns = new HashMap<Integer, ColumnReference>();
            ArrayList       columnNumbers = new ArrayList<Integer>();
            HashMap         columnMap = new HashMap<Integer, Integer>();
            MutableInt        tableNumber = new MutableInt(-1);
            ValueNode       vn;
            boolean         firstTime = true;
            MutableBoolean  hasInListPred = new MutableBoolean(false);

            for (vn = this;
                    vn instanceof OrNode;
                    vn = ((OrNode) vn).getRightOperand(), firstTime = false)
            {
                OrNode on = (OrNode) vn;
                ValueNode left = on.getLeftOperand();
                ArrayList compareNumbers = new ArrayList<Integer>();
                
                convert = canConvertToInList(left, columnNumbers, compareNumbers, columns,
                                             tableNumber, hasInListPred, firstTime);

                if (!convert)
                    break;
            }

            // DERBY-6363: An OR chain on conjunctive normal form should be
            // terminated by a false BooleanConstantNode. If it is terminated
            // by some other kind of node, it is not on CNF, and it should
            // not be converted to an IN list.
            convert = convert && vn.isBooleanFalse();

            /* So, can we convert the OR chain? */
            if (convert)
            {
                ValueNodeList crList = new ValueNodeList(getContextManager());;

                for (int i = 0; i < columnNumbers.size(); i++) {
                    Integer colNum = (Integer)columnNumbers.get(i);
                    columnMap.put(colNum, i);
                    crList.addValueNode((ValueNode)columns.get(colNum));
                }
                
                ValueNodeList vnl = new ValueNodeList(getContextManager());
                // Build the IN list
                for (vn = this;
                        vn instanceof OrNode;
                        vn = ((OrNode) vn).getRightOperand())
                {
                    OrNode on = (OrNode) vn;
                    
                    addNewInListNode(on.getLeftOperand(), columnMap, vnl);
                }

                InListOperatorNode ilon =
                            (InListOperatorNode) getNodeFactory().getNode(
                                            C_NodeTypes.IN_LIST_OPERATOR_NODE,
                                            crList,
                                            vnl,
                                            getContextManager());

                ilon.setType(getTypeServices());

                /* We return the result of preprocess() on the
                 * IN list so that any compilation time transformations
                 * will be done.
                 */
                return ilon.preprocess(numTables,
                         outerFromList, outerSubqueryList,
                         outerPredicateList);
            }
        }

        return this;
    }

    /**
     * Eliminate NotNodes in the current query block.  We traverse the tree,
     * inverting ANDs and ORs and eliminating NOTs as we go.  We stop at
     * ComparisonOperators and boolean expressions.  We invert
     * ComparisonOperators and replace boolean expressions with
     * boolean expression = false.
     * NOTE: Since we do not recurse under ComparisonOperators, there
     * still could be NotNodes left in the tree.
     *
     * @param    underNotNode        Whether or not we are under a NotNode.
     *
     *
     * @return        The modified expression
     *
     * @exception StandardException        Thrown on error
     */
    ValueNode eliminateNots(boolean underNotNode)
                    throws StandardException
    {
        setLeftOperand(getLeftOperand().eliminateNots(underNotNode));
        setRightOperand(getRightOperand().eliminateNots(underNotNode));
        if (! underNotNode)
        {
            return this;
        }

        /* Convert the OrNode to an AndNode */
        AndNode andNode = new AndNode(getLeftOperand(), getRightOperand(), getContextManager());
        andNode.setType(getTypeServices());
        return andNode;
    }

    /**
     * Finish putting an expression into conjunctive normal
     * form.  An expression tree in conjunctive normal form meets
     * the following criteria:
     *        o  If the expression tree is not null,
     *           the top level will be a chain of AndNodes terminating
     *           in a true BooleanConstantNode.
     *        o  The left child of an AndNode will never be an AndNode.
     *        o  Any right-linked chain that includes an AndNode will
     *           be entirely composed of AndNodes terminated by a true BooleanConstantNode.
     *        o  The left child of an OrNode will never be an OrNode.
     *        o  Any right-linked chain that includes an OrNode will
     *           be entirely composed of OrNodes terminated by a false BooleanConstantNode.
     *        o  ValueNodes other than AndNodes and OrNodes are considered
     *           leaf nodes for purposes of expression normalization.
     *           In other words, we won't do any normalization under
     *           those nodes.
     *
     * In addition, we track whether or not we are under a top level AndNode.
     * SubqueryNodes need to know this for subquery flattening.
     *
     * @param    underTopAndNode        Whether or not we are under a top level AndNode.
     *
     *
     * @return        The modified expression
     *
     * @exception StandardException        Thrown on error
     */
    public ValueNode changeToCNF(boolean underTopAndNode)
                    throws StandardException
    {
        OrNode curOr = this;

        /* If rightOperand is an AndNode, then we must generate an
         * OrNode above it.
         */
        if (getRightOperand() instanceof AndNode)
        {
            BooleanConstantNode    falseNode;

            falseNode = new BooleanConstantNode(Boolean.FALSE,getContextManager());
            setRightOperand(new OrNode(getRightOperand(), falseNode, getContextManager()));
            ((OrNode) getRightOperand()).postBindFixup();
        }

        /* We need to ensure that the right chain is terminated by
         * a false BooleanConstantNode.
         */
        while (curOr.getRightOperand() instanceof OrNode)
        {
            curOr = (OrNode) curOr.getRightOperand();
        }

        /* Add the false BooleanConstantNode if not there yet */
        if (!(curOr.getRightOperand().isBooleanFalse()))
        {
            BooleanConstantNode    falseNode;

            falseNode = new BooleanConstantNode(Boolean.FALSE,getContextManager());
            curOr.setRightOperand( new OrNode(curOr.getRightOperand(), falseNode,
                                                getContextManager()));
            ((OrNode) curOr.getRightOperand()).postBindFixup();
        }

        normalize();

        /* Finally, we continue to normalize the left and right subtrees. */
        setLeftOperand(getLeftOperand().changeToCNF(false));
        setRightOperand(getRightOperand().changeToCNF(false));

        // See if we can convert this OR into an AND for
        // a more complete conversion to CNF.
        if (underTopAndNode) {
            ValueNode newOperator = dnfToCNF();
            if (newOperator != null)
                return newOperator;
        }
        return this;
    }

    public void normalize() {
        /* If leftOperand is an OrNode, then we modify the tree from:
         *
         *                this
         *               /    \
         *            Or2        Nodex
         *           /    \        ...
         *        left2    right2
         *
         *    to:
         *
         *                        this
         *                       /    \
         *    left2.changeToCNF()         Or2
         *                            /    \
         *        right2.changeToCNF()     Nodex.changeToCNF()
         *
         *    NOTE: We could easily switch places between left2.changeToCNF() and
         *  right2.changeToCNF().
         */

        while (getLeftOperand() instanceof OrNode)
        {
            ValueNode newLeft;
            OrNode      oldLeft;
            OrNode      newRight;
            ValueNode oldRight;

            /* For "clarity", we first get the new and old operands */
            newLeft = ((OrNode) getLeftOperand()).getLeftOperand();
            oldLeft = (OrNode) getLeftOperand();
            newRight = (OrNode) getLeftOperand();
            oldRight = getRightOperand();

            /* We then twiddle the tree to match the above diagram */
            setLeftOperand(newLeft);
            setRightOperand(newRight);
            newRight.setLeftOperand(oldLeft.getRightOperand());
            newRight.setRightOperand(oldRight);
        }
    }

    private ValueNode dnfToCNF() throws StandardException {
        // Collect the children in the chain of linked OR nodes.
        // Any child AND nodes can be combined via the distributive property.
        List<List<ValueNode>> linkedChildren = new ArrayList<>();
        collectChildLists(getLeftOperand(), true, linkedChildren);
        collectChildLists(getRightOperand(), true, linkedChildren);
        long numDerivedPredicates = getNumDerivedPredicates(linkedChildren);

        // If number of derived predicates we would build is 1 or less (means there are no ANDs),
        // or larger than the maximum allowed, don't perform the expansion.
        long maxPredicates = getCompilerContext().getMaxDerivedCNFPredicates();
        if (numDerivedPredicates <= 1 ||
            numDerivedPredicates > maxPredicates)
            return null;
        return buildCNFPredicates(linkedChildren, null, 0, null, null);
    }

    private long getNumDerivedPredicates(List<List<ValueNode>> linkedChildren) {
        long runningCount = 1;
        for (List<ValueNode> andedPreds:linkedChildren) {
            runningCount *= andedPreds.size();
            // Try to avoid overflow of runningCount.
            if (runningCount > Integer.MAX_VALUE)
                return runningCount;
        }
        return runningCount;
    }

    private ValueNode buildCNFPredicates(List<List<ValueNode>> linkedChildren,
                                         List<OrNode> disjuncts,
                                         int depth,
                                         OrNode currentOrNode,
                                         OrNode firstOrNode) throws StandardException {
        int numberOfOrs = linkedChildren.size() - 1;
        List<ValueNode> predicates = linkedChildren.get(depth);
        OrNode newOrNode;

        if (depth == 0)
            disjuncts = new ArrayList<>();
        for (ValueNode pred:predicates) {
            if (pred instanceof OrNode)
                newOrNode = (OrNode)pred;
            else
                newOrNode = newOrNode(pred);
            if (depth == 0)
                firstOrNode = newOrNode;
            else // Build a right-linked chain of OR nodes
                currentOrNode.setRightOperand(newOrNode);
            if (depth < numberOfOrs) {
                buildCNFPredicates(linkedChildren, disjuncts, depth + 1, newOrNode, firstOrNode);
            }
            else {
                OrNode newOrChain =
                    firstOrNode.shallowCloneORChain();
                disjuncts.add(newOrChain);
            }
        }

        // Now combine the disjunctions.
        if (depth == 0) {
            AndNode firstAnd = null;
            AndNode newAndNode, currentAnd = null;
            for (OrNode orNode:disjuncts) {
                newAndNode = newAndNode(orNode, true);
                if (firstAnd == null)
                    firstAnd = newAndNode;
                else
                    currentAnd.setRightOperand(newAndNode);
                currentAnd = newAndNode;
            }
            try {
                CloneCRsVisitor cloneCRsVisitor = new CloneCRsVisitor();
                firstAnd = (AndNode)firstAnd.accept(cloneCRsVisitor);
            }
            catch (StandardException e) {
                // If unable to clone all ColumnReferences, it is
                // unsafe to use this predicate tree.
                return null;
            }
            return firstAnd;
        }
        return null;
    }

    public static OrNode newOrNode(ValueNode left) throws StandardException {
        ValueNode falseNode=(ValueNode)left.getNodeFactory().getNode(
                C_NodeTypes.BOOLEAN_CONSTANT_NODE,
                Boolean.FALSE,
                left.getContextManager());
        OrNode    orNode;
        orNode = (OrNode) left.getNodeFactory().getNode(
                                                    C_NodeTypes.OR_NODE,
                                                    left,
                                                    falseNode,
                                                    left.getContextManager());
        orNode.postBindFixup();
        return orNode;
    }

    private List<ValueNode> linkNewList(List<List<ValueNode>> linkedChildren) {
        List<ValueNode> andNodeLinkedChildren = new ArrayList<>();
        linkedChildren.add(andNodeLinkedChildren);
        return andNodeLinkedChildren;
    }

    private void collectChildLists(ValueNode node,
                                   boolean underOrNode,
                                   List<List<ValueNode>> linkedChildren) {
        if (underOrNode) {
            if (node instanceof AndNode) {
                collectAndLinkedChildren((AndNode) node, linkNewList(linkedChildren));
            }
            else if (node instanceof OrNode) {
                collectChildLists(((OrNode) node).getLeftOperand(), true, linkedChildren);
                collectChildLists(((OrNode) node).getRightOperand(), true, linkedChildren);
            }
            else {
                // OR'ing a FALSE does not change the outcome, so do not
                // include boolean FALSE.
                if (node instanceof BooleanConstantNode) {
                    BooleanConstantNode bcn = (BooleanConstantNode) node;
                    if (bcn.isBooleanFalse())
                        return;
                }
                linkNewList(linkedChildren).add(node);
            }
        }
    }

    private void collectAndLinkedChildren(AndNode node,
                                          List<ValueNode> andNodeLinkedChildren) {
        if (node.getLeftOperand() instanceof AndNode)
            collectAndLinkedChildren((AndNode)node.getLeftOperand(), andNodeLinkedChildren);
        else
            addAndedPredicate(node.getLeftOperand(), andNodeLinkedChildren);
        if (node.getRightOperand() instanceof AndNode)
            collectAndLinkedChildren((AndNode)node.getRightOperand(), andNodeLinkedChildren);
        else
            addAndedPredicate(node.getRightOperand(), andNodeLinkedChildren);
    }

    private void addAndedPredicate(ValueNode node, List<ValueNode> andNodeLinkedChildren) {
        // AND'ing a TRUE does not change the outcome, so do not
        // include boolean TRUE.
        if (node instanceof BooleanConstantNode) {
            BooleanConstantNode bcn = (BooleanConstantNode) node;
            if (bcn.isBooleanTrue())
                return;
        }
        andNodeLinkedChildren.add(node);
    }

    /**
     * Verify that changeToCNF() did its job correctly.  Verify that:
     *        o  AndNode  - rightOperand is not instanceof OrNode
     *                      leftOperand is not instanceof AndNode
     *        o  OrNode    - rightOperand is not instanceof AndNode
     *                      leftOperand is not instanceof OrNode
     *
     * @return        Boolean which reflects validity of the tree.
     */
    public boolean verifyChangeToCNF()
    {
        boolean isValid = true;

        if (SanityManager.ASSERT) {
            isValid = ((getRightOperand() instanceof OrNode) ||
                    (getRightOperand().isBooleanFalse()));
            if (getRightOperand() instanceof OrNode) {
                isValid = getRightOperand().verifyChangeToCNF();
            }
            isValid = isValid && !(getLeftOperand() instanceof OrNode) && getLeftOperand().verifyChangeToCNF();
        }

        return isValid;
    }

    /**
     * Do bind() by hand for an AndNode that was generated after bind(),
     * eg by putAndsOnTop(). (Set the data type and nullability info.)
     *
     * @exception StandardException        Thrown on error
     */
    void postBindFixup()
                    throws StandardException
    {
        setType(resolveLogicalBinaryOperator(
                            getLeftOperand().getTypeServices(),
                            getRightOperand().getTypeServices()
                                            )
                );
    }

    public OrNode shallowCloneORChain() throws StandardException {
        ValueNode node = this;
        OrNode nextOrNode, currentOr = null;
        OrNode firstNode = null;
        while (node instanceof OrNode) {
            OrNode orNode = (OrNode) node;
            nextOrNode = newOrNode(orNode.getLeftOperand());
            if (firstNode == null)
                firstNode = nextOrNode;
            else
                currentOr.setRightOperand(nextOrNode);

            currentOr = nextOrNode;
            node = orNode.getRightOperand();
        }
        return firstNode;
    }

    @Override
    public boolean isCloneable()
    {
        return true;
    }

    @Override
    public ValueNode getClone() throws StandardException
    {
        ValueNode left = getLeftOperand();
        ValueNode right = getRightOperand();
        OrNode    orNode;
        orNode = (OrNode) left.getNodeFactory().getNode(
                                                    C_NodeTypes.OR_NODE,
                                                    left,
                                                    right,
                                                    left.getContextManager());
        orNode.postBindFixup();
        return orNode;
    }
}
