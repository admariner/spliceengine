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
import com.splicemachine.db.iapi.reference.ClassName;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.SQLBit;
import com.splicemachine.db.iapi.types.SQLChar;
import com.splicemachine.db.iapi.types.TypeId;
import com.splicemachine.db.iapi.util.StringUtil;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This node is the superclass  for all binary comparison operators, such as =,
 * <>, <, etc.
 *
 */

public abstract class BinaryComparisonOperatorNode extends BinaryOperatorNode
{
    // Use between selectivity?
    private boolean forQueryRewrite;
    private boolean betweenSelectivity;

    /**
     * Initializer for a BinaryComparisonOperatorNode
     *
     * @param leftOperand    The left operand of the comparison
     * @param rightOperand    The right operand of the comparison
     * @param operator        The name of the operator
     * @param methodName    The name of the method to call in the generated
     *                        class
     */
    public void init(
                Object    leftOperand,
                Object    rightOperand,
                Object        operator,
                Object        methodName)
    {
        super.init(leftOperand, rightOperand, operator, methodName,
                ClassName.DataValueDescriptor, ClassName.DataValueDescriptor);
    }

    /**
     * This node was generated as part of a query rewrite. Bypass the
     * normal comparability checks.
     * @param val  true if this was for a query rewrite
     */
    public void setForQueryRewrite(boolean val)
    {
        forQueryRewrite=val;
    }

    /**
     * Was this node generated in a query rewrite?
     *
     * @return  true if it was generated in a query rewrite.
     */
    public boolean getForQueryRewrite()
    {
        return forQueryRewrite;
    }

    /**
     * Use between selectivity when calculating the selectivity.
     */
    void setBetweenSelectivity()
    {
        betweenSelectivity = true;
    }

    /**
     * Return whether or not to use the between selectivity for this node.
     *
     * @return Whether or not to use the between selectivity for this node.
     */
    boolean getBetweenSelectivity() {
        return betweenSelectivity;
    }

    @Override
    protected void bindParameters() throws StandardException {
        if (getLeftOperand().requiresTypeFromContext()) {
            if (getRightOperand().requiresTypeFromContext()) {
                // DB2 compatible behavior
                DataTypeDescriptor dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, false, 254);
                getLeftOperand().setType(dtd);
                getRightOperand().setType(dtd);
            } else {
                getLeftOperand().setType(getRightOperand().getTypeServices());
            }
        } else if (getRightOperand().requiresTypeFromContext()) {
            getRightOperand().setType(getLeftOperand().getTypeServices());
        }
    }

    /**
     * Bind this comparison operator.  All that has to be done for binding
     * a comparison operator is to bind the operands, check the compatibility
     * of the types, and set the result type to SQLBoolean.
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
    public ValueNode bindExpression(FromList fromList,
                                    SubqueryList subqueryList,
                                    List<AggregateNode> aggregateVector) throws StandardException {
        super.bindExpression(fromList, subqueryList, aggregateVector);

        TypeId leftTypeId = getLeftOperand().getTypeId();
        TypeId rightTypeId = getRightOperand().getTypeId();

        if (leftTypeId.isStringTypeId() && rightTypeId.isStringTypeId() &&
                (leftTypeId.isLongVarcharTypeId() || rightTypeId.isLongVarcharTypeId())) {
            castLeftOperandAndBindCast(DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, true));
            castRightOperandAndBindCast(DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, true));
        }

        if (! leftTypeId.isStringTypeId() && rightTypeId.isStringTypeId())
        {
            setRightOperand(addCastNodeForStringToNonStringComparison(getLeftOperand(), getRightOperand()));
            rightTypeId = getRightOperand().getTypeId();
        }
        else if (! rightTypeId.isStringTypeId() && leftTypeId.isStringTypeId())
        {
            setLeftOperand(addCastNodeForStringToNonStringComparison(getRightOperand(), getLeftOperand()));
            leftTypeId = getLeftOperand().getTypeId();
        }

        if ((leftTypeId.isFixedBitDataTypeId() || leftTypeId.isVarBitDataTypeId()) && rightTypeId.isStringTypeId()) {
            // pad the constant value before casting for fixed bit data type
            if ((getLeftOperand() instanceof ColumnReference) && leftTypeId.isFixedBitDataTypeId() && (getRightOperand() instanceof CharConstantNode)) {
                rightPadCharConstantNode((CharConstantNode) getRightOperand(), (ColumnReference) getLeftOperand());
            }
            // cast String to BitType but keep the original string's length
            castRightOperandAndBindCast(
                new DataTypeDescriptor(leftTypeId, true, getRightOperand().getTypeServices().getMaximumWidth()));
            rightTypeId = getRightOperand().getTypeId();
        } else if ((rightTypeId.isFixedBitDataTypeId() || rightTypeId.isVarBitDataTypeId()) && leftTypeId.isStringTypeId()) {
            // pad the constant value before casting for fixed bit data type
            if ((getRightOperand() instanceof ColumnReference) && rightTypeId.isFixedBitDataTypeId() && (getLeftOperand() instanceof CharConstantNode)) {
                rightPadCharConstantNode((CharConstantNode) getLeftOperand(), (ColumnReference) getRightOperand());
            }

            // cast String to BitType
            castLeftOperandAndBindCast(
                new DataTypeDescriptor(rightTypeId, true, getLeftOperand().getTypeServices().getMaximumWidth()));
            leftTypeId = getLeftOperand().getTypeId();
        }

        if ((leftTypeId.isIntegerNumericTypeId() && rightTypeId.isDecimalTypeId()) ||
                (leftTypeId.isDecimalTypeId() && rightTypeId.isIntegerNumericTypeId())) {

            TypeId decimalTypeId = leftTypeId.isDecimalTypeId() ? leftTypeId : rightTypeId;

            DataTypeDescriptor dty = new DataTypeDescriptor(
                    decimalTypeId,
                    decimalTypeId.getMaximumPrecision(),
                    0,
                    true,
                    decimalTypeId.getMaximumMaximumWidth()
            );

            // If right side is the decimal then promote the left side integer
            if (rightTypeId.isDecimalTypeId()) {
                castLeftOperandAndBindCast(dty);
            } else {
                // Otherwise, left side is the decimal so promote the right side integer
                castRightOperandAndBindCast(dty);
            }

        }
        /* Are we a SQLChar-constant to SQLChar-column-reference binary comparison?  If so go ahead and pad the constant
         * value to match the width of the column type (SQLChar columns have fixed width and and are stored in splice
         * right padded with space characters). Before this fix the start/stop scan keys from derby, for
         * TableScanOperation, would be wrong (missing space padding).*/
        if ((getLeftOperand() instanceof ColumnReference) && leftTypeId.isFixedStringTypeId() && (getRightOperand() instanceof CharConstantNode)) {
            rightPadCharConstantNode((CharConstantNode) getRightOperand(), (ColumnReference) getLeftOperand());
        } else if ((getRightOperand() instanceof ColumnReference) && rightTypeId.isFixedStringTypeId() && (getLeftOperand() instanceof CharConstantNode)) {
            rightPadCharConstantNode((CharConstantNode) getLeftOperand(), (ColumnReference) getRightOperand());
        }

        // do similar padding for fixed bit data type
        if ((getLeftOperand() instanceof ColumnReference) && leftTypeId.isFixedBitDataTypeId() && (getRightOperand() instanceof BitConstantNode)) {
            rightPadBitDataConstantNode((BitConstantNode) getRightOperand(), (ColumnReference) getLeftOperand());
        } else if ((getRightOperand() instanceof ColumnReference) && rightTypeId.isFixedBitDataTypeId() && (getLeftOperand() instanceof BitConstantNode)) {
            rightPadBitDataConstantNode((BitConstantNode) getLeftOperand(), (ColumnReference) getRightOperand());
        }

        // TODO: Enable this code to allow native spark joins involving REALs.
        // REAL/FLOAT does not play nice on Spark, so doing this allows native spark execution.
//        if (getLeftOperand().getTypeId().isRealTypeId()) {
//            castLeftOperandAndBindCast(DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DOUBLE,
//                                       getLeftOperand().getTypeServices().isNullable()));
//        }
//        if (getRightOperand().getTypeId().isRealTypeId()) {
//            castRightOperandAndBindCast(DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DOUBLE,
//                                       getRightOperand().getTypeServices().isNullable()));
//        }

        /* Test type compatibility and set type info for this node */
        bindComparisonOperator();

        return this;
    }

    private static void rightPadCharConstantNode(CharConstantNode constantNode, ColumnReference columnReference) throws StandardException {
        String stringConstant = constantNode.getString();
        int maxSize = columnReference.getTypeServices().getMaximumWidth();
        String newStringConstant = StringUtil.padRight(stringConstant, SQLChar.PAD, maxSize);
        constantNode.setValue(new SQLChar(newStringConstant));
        DataTypeDescriptor updatedType = new DataTypeDescriptor(
                constantNode.getTypeId(),
                true,
                newStringConstant.length());
        constantNode.setType(updatedType);
    }

    private static byte[] padBitData(byte[] value, byte padVal, int sizeWithPad) {
        if (value == null || value.length >= sizeWithPad)
            return value;

        byte[] newVal = new byte[sizeWithPad];

        System.arraycopy(value, 0, newVal, 0, value.length);
        Arrays.fill(newVal, value.length, sizeWithPad, padVal);

        return newVal;
    }

    private static void rightPadBitDataConstantNode(BitConstantNode constantNode, ColumnReference columnReference) throws StandardException {
        byte[] bitDataConstant = (byte[])constantNode.getConstantValueAsObject();
        int maxSize = columnReference.getTypeServices().getMaximumWidth();
        byte[] newBitDataConstant = padBitData(bitDataConstant, (byte) 0x20, maxSize);
        constantNode.setValue(new SQLBit(newBitDataConstant));
        DataTypeDescriptor updatedType = new DataTypeDescriptor(
                constantNode.getTypeId(),
                true,
                newBitDataConstant.length);
        constantNode.setType(updatedType);
    }

    /**
     * Test the type compatability of the operands and set the type info
     * for this node.  This method is useful both during binding and
     * when we generate nodes within the language module outside of the parser.
     *
     * @exception StandardException        Thrown on error
     */
    public void bindComparisonOperator()
            throws StandardException
    {
        ValueNode left = normalizeOperand(getLeftOperand());
        ValueNode right = normalizeOperand(getRightOperand());

        boolean cmp;
        boolean nullableResult;

        /*
        ** Can the types be compared to each other?  If not, throw an
        ** exception.
        */
        if (left instanceof ValueTupleNode && right instanceof ValueTupleNode) {
            cmp = ((ValueTupleNode) left).typeComparable((ValueTupleNode) right);
            nullableResult = ((ValueTupleNode) left).containsNullableElement() ||
                    ((ValueTupleNode) right).containsNullableElement();
        } else {
            assert !(left instanceof ValueTupleNode || right instanceof ValueTupleNode) : "value tuple compared to non-tuple";
            cmp = left.getTypeServices().comparable(right.getTypeServices());
            nullableResult = left.getTypeServices().isNullable() ||
                    right.getTypeServices().isNullable();
        }
        // Bypass the comparable check if this is a rewrite from the
        // optimizer.  We will assume Mr. Optimizer knows what he is doing.
        if (!cmp && !forQueryRewrite) {
            DataTypeDescriptor leftDTD = left.getTypeServices();
            DataTypeDescriptor rightDTD = right.getTypeServices();
            throw StandardException.newException(SQLState.LANG_NOT_COMPARABLE,
                    leftDTD == null ? "left type" : leftDTD.getSQLTypeNameWithCollation() ,
                    rightDTD == null ? "right type" : rightDTD.getSQLTypeNameWithCollation());
        }

        /*
        ** Set the result type of this comparison operator based on the
        ** operands.  The result type is always SQLBoolean - the only question
        ** is whether it is nullable or not.  If either of the operands is
        ** nullable, the result of the comparison must be nullable, too, so
        ** we can represent the unknown truth value.
        */
        setType(new DataTypeDescriptor(TypeId.BOOLEAN_ID, nullableResult));
    }

    private static ValueNode normalizeOperand(ValueNode operand) throws StandardException {
        if (operand instanceof ValueTupleNode) {
            ValueTupleNode items = (ValueTupleNode) operand;
            if (items.size() == 1) {
                return items.get(0);
            }
        } else if (operand instanceof SubqueryNode) {
            return ((SubqueryNode) operand).getRightOperand();
        }
        return operand;
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
        setLeftOperand(getLeftOperand().preprocess(numTables,
                                             outerFromList, outerSubqueryList,
                                             outerPredicateList));

        /* This is where we start to consider flattening expression subqueries based
         * on a uniqueness condition.  If the right child is a SubqueryNode then
         * it is a potentially flattenable expression subquery.  If we flatten the
         * subquery then we at least need to change the right operand of this
         * comparison.  However, we may want to push the comparison into the subquery
         * itself and replace this outer comparison with TRUE in the tree.  Thus we
         * return rightOperand.preprocess() if the rightOperand is a SubqueryNode.
         * NOTE: SubqueryNode.preprocess() is smart enough to return this node
         * if it is not flattenable.
         * NOTE: We only do this if the subquery has not yet been preprocessed.
         * (A subquery can get preprocessed multiple times if it is a child node
         * in an expression that gets transformed, like BETWEEN.  The subquery
         * remembers whether or not it has been preprocessed and simply returns if
         * it has already been preprocessed.  The return returns the SubqueryNode,
         * so an invalid tree is returned if we set the parent comparison operator
         * when the subquery has already been preprocessed.)
         */
        if ((getRightOperand() instanceof SubqueryNode) &&
            !((SubqueryNode) getRightOperand()).getPreprocessed())
        {
            ((SubqueryNode) getRightOperand()).setParentComparisonOperator(this);
            return getRightOperand().preprocess(numTables,
                                           outerFromList, outerSubqueryList,
                                           outerPredicateList);
        }
        else
        {
            setRightOperand(getRightOperand().preprocess(numTables,
                                                   outerFromList, outerSubqueryList,
                                                   outerPredicateList));
            return this;
        }
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
        if (! underNotNode)
        {
            return this;
        }

        /* Convert the BinaryComparison operator to its negation */
        return getNegation(getLeftOperand(), getRightOperand());
    }

    /**
     * Negate the comparison.
     *
     * @param leftOperand    The left operand of the comparison operator
     * @param rightOperand    The right operand of the comparison operator
     *
     * @return BinaryOperatorNode    The negated expression
     *
     * @exception StandardException        Thrown on error
     */
    abstract BinaryOperatorNode getNegation(ValueNode leftOperand,
                                          ValueNode rightOperand)
                throws StandardException;

    /**
     * <p>
     * Return a node equivalent to this node, but with the left and right
     * operands swapped. The node type may also be changed if the operator
     * is not symmetric.
     * </p>
     *
     * <p>
     * This method may for instance be used to normalize a predicate by
     * moving constants to the right-hand side of the comparison. Example:
     * {@code 1 = A} will be transformed to {@code A = 1}, and {@code 10 < B}
     * will be transformed to {@code B > 10}.
     * </p>
     *
     * @return an equivalent expression with the operands swapped
     * @throws StandardException if an error occurs
     */
    abstract BinaryOperatorNode getSwappedEquivalent() throws StandardException;

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
        /* If our right child is a subquery and we are under a top and node
         * then we want to mark the subquery as under a top and node.
         * That will allow us to consider flattening it.
         */
        if (underTopAndNode && (getRightOperand() instanceof SubqueryNode))
        {
            setRightOperand(getRightOperand().changeToCNF(underTopAndNode));
        }

        return this;
    }

    /** @see BinaryOperatorNode#genSQLJavaSQLTree */
    public ValueNode genSQLJavaSQLTree() throws StandardException
    {
        TypeId leftTypeId = getLeftOperand().getTypeId();

        /* If I have Java types, I need only add java->sql->java if the types
         * are not comparable
         */
        if (leftTypeId.userType())
        {
            if (getLeftOperand().getTypeServices().comparable(getLeftOperand().getTypeServices()
            ))
                return this;

            setLeftOperand(getLeftOperand().genSQLJavaSQLTree());
        }

        TypeId rightTypeId = getRightOperand().getTypeId();

        if (rightTypeId.userType())
        {
            if (getRightOperand().getTypeServices().comparable(getRightOperand().getTypeServices()
            ))
                return this;

            setRightOperand(getRightOperand().genSQLJavaSQLTree());
        }

        return this;
    }

    @Override
    public ValueNode replaceIndexExpression(ResultColumnList childRCL) throws StandardException {
        if (childRCL == null) {
            return this;
        }
        if (getLeftOperand() != null) {
            setLeftOperand(getLeftOperand().replaceIndexExpression(childRCL));
        }
        if (getRightOperand() != null) {
            setRightOperand(getRightOperand().replaceIndexExpression(childRCL));
        }
        return this;
    }

    @Override
    public boolean collectExpressions(Map<Integer, Set<ValueNode>> exprMap) {
        boolean result = true;
        if (getLeftOperand() != null) {
            result = getLeftOperand().collectExpressions(exprMap);
        }
        if (getRightOperand() != null) {
            result = result && getRightOperand().collectExpressions(exprMap);
        }
        return result;
    }

    public void copyFrom(BinaryComparisonOperatorNode other) throws StandardException
    {
        super.copyFrom(other);
        this.forQueryRewrite = other.forQueryRewrite;
        this.betweenSelectivity = other.betweenSelectivity;
    }
}
