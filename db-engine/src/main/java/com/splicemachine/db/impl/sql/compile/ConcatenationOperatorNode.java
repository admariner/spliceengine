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

import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;

import com.splicemachine.db.iapi.services.sanity.SanityManager;

import com.splicemachine.db.iapi.error.StandardException;

import com.splicemachine.db.iapi.types.DataValueFactoryImpl;
import com.splicemachine.db.iapi.types.TypeId;

import com.splicemachine.db.iapi.sql.compile.TypeCompiler;
import com.splicemachine.db.iapi.types.StringDataValue;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;

import com.splicemachine.db.iapi.reference.Limits;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.reference.ClassName;
import org.apache.commons.lang3.mutable.MutableInt;

import java.sql.Types;

import java.util.List;

import static com.splicemachine.db.iapi.reference.Limits.DB2_VARCHAR_MAXWIDTH;

/**
 * This node represents a concatenation comparison operator
 * 
 * varying.
 */

public class ConcatenationOperatorNode extends BinaryOperatorNode {
    /**
     * Initializer for a ConcatenationOperatorNode
     *
     * @param leftOperand
     *            The left operand of the concatenation
     * @param rightOperand
     *            The right operand of the concatenation
     */
    public ConcatenationOperatorNode(Object leftOperand, Object rightOperand, ContextManager cm) {
        setContextManager(cm);
        setNodeType(C_NodeTypes.CONCATENATION_OPERATOR_NODE);
        super.init(leftOperand, rightOperand, "||", "concatenate",
                ClassName.ConcatableDataValue, ClassName.ConcatableDataValue);
    }

    /**
     * Check if this node always evaluates to the same value. If so, return
     * a constant node representing the known result.
     *
     * @return a constant node representing the result of this concatenation
     * operation, or {@code this} if the result is not known up front
     */
    ValueNode evaluateConstantExpressions() throws StandardException {
        if (getLeftOperand() instanceof CharConstantNode &&
                getRightOperand() instanceof CharConstantNode) {
            CharConstantNode leftOp = (CharConstantNode) getLeftOperand();
            CharConstantNode rightOp = (CharConstantNode) getRightOperand();
            StringDataValue leftValue = (StringDataValue) leftOp.getValue();
            StringDataValue rightValue = (StringDataValue) rightOp.getValue();

            StringDataValue resultValue;
            DataTypeDescriptor resultDTD = getTypeServices();
            if (resultDTD == null) {
                TypeId resultTypeId =
                    resolveConcatOperationResultType(leftOp.getTypeServices(),
                                                     rightOp.getTypeServices(),
                                                     new MutableInt());
                resultValue = (StringDataValue)
                  DataValueFactoryImpl.getNullDVD(resultTypeId.getTypeFormatId());
            }
            else
                resultValue = (StringDataValue) resultDTD.getNull();

            assert resultValue != null;
            resultValue.concatenate(leftValue, rightValue, resultValue);

            return (ValueNode) getNodeFactory().getNode(
                    C_NodeTypes.CHAR_CONSTANT_NODE,
                    resultValue.getString(),
                    getContextManager());
        }

        return this;
    }

    /**
     * overrides BindOperatorNode.bindExpression because concatenation has
     * special requirements for parameter binding.
     *
     * @exception StandardException
     *                thrown on failure
     */
    @Override
    public ValueNode bindExpression(FromList fromList,
                                    SubqueryList subqueryList,
                                    List<AggregateNode> aggregateVector) throws StandardException {
        // deal with binding operands
        setLeftOperand(getLeftOperand().bindExpression(fromList, subqueryList, aggregateVector));
        setRightOperand(getRightOperand().bindExpression(fromList, subqueryList, aggregateVector));

        // deal with operand parameters
        /*
         * Is there a ? parameter on the left? If so, it's type is the type of
         * the other parameter, with maximum length for that type.
         */

        /*
         * In case of ? || ?, both operands should be varchars
         */
        if (getLeftOperand().requiresTypeFromContext() && getRightOperand().requiresTypeFromContext()) {
            getLeftOperand().setType(new DataTypeDescriptor(TypeId.getBuiltInTypeId(Types.VARCHAR), true));
            getRightOperand().setType(new DataTypeDescriptor(TypeId.getBuiltInTypeId(Types.VARCHAR), true));
        } else {
            int requiresType = -1;
            if (getLeftOperand().requiresTypeFromContext())
                requiresType = 0;
            else if (getRightOperand().requiresTypeFromContext())
                requiresType = 1;
            if (requiresType != -1) {
                TypeId type;
                int known = (requiresType + 1) % 2;
                /*
                 * * A ? on one operand gets its type from the other operand
                 */
                if (operands.get(known).getTypeId().isBitTypeId()) {
                    if (operands.get(known).getTypeId().isBlobTypeId())
                        type = TypeId.getBuiltInTypeId(Types.BLOB);
                    else
                        type = TypeId.getBuiltInTypeId(Types.VARBINARY);
                } else {
                    if (operands.get(known).getTypeId().isClobTypeId())
                        type = TypeId.getBuiltInTypeId(Types.CLOB);
                    else
                        type = TypeId.getBuiltInTypeId(Types.VARCHAR);
                }
                assert type != null;
                operands.get(requiresType).setType(new DataTypeDescriptor(type, true));
                if (operands.get(known).getTypeId().isStringTypeId()) {
                    //collation of ? operand should be picked from the context
                    operands.get(requiresType).setCollationInfo(operands.get(known).getTypeServices());
                }
            }
        }

        genSQLJavaSQLTree();

        /*
         * If either the left or right operands are non-string, non-bit types,
         * then we generate an implicit cast to VARCHAR.
         */
        for (int i = 0; i < operands.size(); ++i) {
            TypeCompiler tc = operands.get(i).getTypeCompiler();
            if (!(operands.get(i).getTypeId().isStringTypeId() ||
                    operands.get(i).getTypeId().isBitTypeId())) {
                int width = (tc instanceof UserDefinedTypeCompiler) ? DB2_VARCHAR_MAXWIDTH :
                        tc.getCastToCharWidth(operands.get(i).getTypeServices(), getCompilerContext());
                DataTypeDescriptor dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, true, width);

                castOperandAndBindCast(i, dtd, true);
            }
        }

        /*
         * * Set the result type of this operator based on the operands. * By
         * convention, the left operand gets to decide the result type * of a
         * binary operator.
         */
        setType(resolveConcatOperation(getLeftOperand().getTypeServices(),
                getRightOperand().getTypeServices()));

        /*
         * * Make sure the maximum width set for the result doesn't exceed the
         * result type's maximum width
         */
        if (SanityManager.DEBUG) {
            if (getTypeServices().getMaximumWidth() > getTypeId()
                    .getMaximumMaximumWidth()) {
                SanityManager
                        .THROWASSERT("The maximum length "
                                + getTypeServices().getMaximumWidth()
                                + " for the result type "
                                + getTypeId().getSQLTypeName()
                                + " can't be greater than it's maximum width of result's typeid"
                                + getTypeId().getMaximumMaximumWidth());
            }
        }

        /*
         * * Now that we know the target interface type, set it. This assumes *
         * that both operands have the same interface type, which is a safe *
         * assumption for the concatenation operator.
         */
        this.setLeftRightInterfaceType(getLeftOperand().getTypeCompiler().interfaceName());

        // Finally, fold constants so that for example LIKE optimization is
        // able to take advantage of concatenated literals like 'ab' || '%'.
        return this.evaluateConstantExpressions();
    }

    /**
     * Resolve the result type of a concatenation operation.
     *
     * @param leftType [in]
     *            The DataTypeDescriptor of the left operand
     * @param rightType [in]
     *            The DataTypeDescriptor of the right operand
     * @param resultLength [out]
     *            The length of the result type.
     *
     * @return A TypeId indicating the result type of the concatenate
     *         operation.
     *
     * @exception StandardException
     *                BinaryOperatorNotSupported Thrown when a BinaryOperator is
     *                not supported on the operand types.
     */
    public TypeId resolveConcatOperationResultType(DataTypeDescriptor leftType,
                                                   DataTypeDescriptor rightType,
                                                   MutableInt resultLength)
                                              throws StandardException {
        TypeId leftTypeId;
        TypeId rightTypeId;
        TypeId resultTypeId;

        leftTypeId = leftType.getTypeId();
        rightTypeId = rightType.getTypeId();

        /*
         * * Check the right type to be sure it's a concatable. By convention, *
         * we call this method off the TypeId of the left operand, so if * we
         * get here, we know the left operand is a concatable.
         */
        /*
         * * Make sure we haven't been given a char and a * bit to concatenate.
         */

        if (!leftTypeId.isConcatableTypeId()
        || !rightTypeId.isConcatableTypeId()
        || (rightTypeId.isBitTypeId() && leftTypeId.isStringTypeId())
        || (leftTypeId.isBitTypeId() && rightTypeId.isStringTypeId()))
            throw StandardException.newException(
            SQLState.LANG_DB2_FUNCTION_INCOMPATIBLE, "||", "FUNCTION");

        /*
         * * The types aren't the same. The result of the operation is the *
         * type of higher precedence.
         */

        resultTypeId = (leftTypeId.typePrecedence() >= rightTypeId
                        .typePrecedence()) ? leftType.getTypeId() : rightType
                        .getTypeId();

        /* Get the length of the result */
        resultLength.setValue(leftType.getMaximumWidth() + rightType.getMaximumWidth());

        /*
         * * Use following chart to handle overflow * operands CHAR(A) CHAR(B)
         * and A+B <255 then result is CHAR(A+B) * operands CHAR FOR BIT DATA(A)
         * CHAR FOR BIT DATA(B) and A+B <255 then result is CHAR FOR BIT
         * DATA(A+B) * * operands CHAR(A) CHAR(B) and A+B>254 then result is
         * VARCHAR(A+B) * operands CHAR FOR BIT DATA(A) CHAR FOR BIT DATA(B) and
         * A+B>254 then result is VARCHAR FOR BIT DATA(A+B) * * operands CHAR(A)
         * VARCHAR(B) and A+B <4001 then result is VARCHAR(A+B) * operands CHAR
         * FOR BIT DATA(A) VARCHAR FOR BIT DATA(B) and A+B <4001 then result is
         * VARCHAR FOR BIT DATA(A+B) * * operands CHAR(A) VARCHAR(B) and
         * A+B>4000 then result is LONG VARCHAR * operands CHAR FOR BIT DATA(A)
         * VARCHAR FOR BIT DATA(B) and A+B>4000 then result is LONG VARCHAR FOR
         * BIT DATA * * operands CHAR(A) LONG VARCHAR then result is LONG
         * VARCHAR * operands CHAR FOR BIT DATA(A) LONG VARCHAR FOR BIT DATA
         * then result is LONG VARCHAR FOR BIT DATA * * operands VARCHAR(A)
         * VARCHAR(B) and A+B <4001 then result is VARCHAR(A+B) * operands
         * VARCHAR FOR BIT DATA(A) VARCHAR FOR BIT DATA(B) and A+B <4001 then
         * result is VARCHAR FOR BIT DATA(A+B) * * operands VARCHAR(A)
         * VARCHAR(B) and A+B>4000 then result is LONG VARCHAR * operands
         * VARCHAR FOR BIT DATA(A) VARCHAR FOR BIT DATA(B) and A+B>4000 then
         * result is LONG VARCHAR FOR BIT DATA * * operands VARCHAR(A) LONG
         * VARCHAR then result is LONG VARCHAR * operands VARCHAR FOR BIT
         * DATA(A) LONG VARCHAR FOR BIT DATA then result is LONG VARCHAR FOR BIT
         * DATA * * operands LONG VARCHAR, LONG VARCHAR then result is LONG
         * VARCHAR * operands LONG VARCHAR FOR BIT DATA, LONG VARCHAR FOR BIT
         * DATA then result is LONG VARCHAR FOR BIT DATA * * operands CLOB(A),
         * CHAR(B) then result is CLOB(MIN(A+B,2G)) * operands CLOB(A),
         * VARCHAR(B) then result is CLOB(MIN(A+B,2G)) * operands CLOB(A), LONG
         * VARCHAR then result is CLOB(MIN(A+32K,2G)) * operands CLOB(A),
         * CLOB(B) then result is CLOB(MIN(A+B,2G)) * * operands BLOB(A), CHAR
         * FOR BIT DATA(B) then result is BLOB(MIN(A+B,2G)) * operands BLOB(A),
         * VARCHAR FOR BIT DATA(B) then result is BLOB(MIN(A+B,2G)) * operands
         * BLOB(A), LONG VARCHAR FOR BIT DATA then result is BLOB(MIN(A+32K,2G)) *
         * operands BLOB(A), BLOB(B) then result is BLOB(MIN(A+B,2G)) * *
         * operands CHAR(A)/VARCHAR(A)/LONGVARCHAR, LONGVARCHAR and
         * "concatenated string length">32700 does not cause automatic
         * escalation * to LOB for compatibility with previous releases. Any
         * such cases would result in an error at runtime *
         */
        //in the following code, I can assume that left and right operands both
        // will be either char kind
        //of datatypes or they will be both binary kind of datatypes. That's
        // because operand datatypes
        //mismatch has already been handled earlier
        if (leftTypeId.getJDBCTypeId() == Types.CHAR
             || leftTypeId.getJDBCTypeId() == Types.BINARY) {
            switch (rightTypeId.getJDBCTypeId()) {
                case Types.CHAR:
                case Types.BINARY:
                    if (resultLength.getValue() > Limits.DB2_CHAR_MAXWIDTH) {
                        if (rightTypeId.getJDBCTypeId() == Types.CHAR)
                            //operands CHAR(A) CHAR(B) and A+B>254 then result is
                            // VARCHAR(A+B)
                            resultTypeId = TypeId.getBuiltInTypeId(Types.VARCHAR);
                        else
                            //operands CHAR FOR BIT DATA(A) CHAR FOR BIT DATA(B)
                            // and A+B>254 then result is VARCHAR FOR BIT DATA(A+B)
                            resultTypeId = TypeId.getBuiltInTypeId(Types.VARBINARY);
                    }
                    break;

                case Types.VARCHAR:
                case Types.VARBINARY:
                    if (resultLength.getValue() > Limits.DB2_CONCAT_VARCHAR_LENGTH) {
                        if (rightTypeId.getJDBCTypeId() == Types.VARCHAR)
                            //operands CHAR(A) VARCHAR(B) and A+B>4000 then result
                            // is LONG VARCHAR
                            resultTypeId = TypeId.getBuiltInTypeId(Types.LONGVARCHAR);
                        else
                            //operands CHAR FOR BIT DATA(A) VARCHAR FOR BIT DATA(B)
                            // and A+B>4000 then result is LONG VARCHAR FOR BIT DATA
                            resultTypeId = TypeId.getBuiltInTypeId(Types.LONGVARBINARY);
                    }
                    break;

                case Types.CLOB:
                case Types.BLOB:
                    //operands CHAR(A), CLOB(B) then result is CLOB(MIN(A+B,2G))
                    //operands CHAR FOR BIT DATA(A), BLOB(B) then result is
                    // BLOB(MIN(A+B,2G))
                    resultLength.setValue(clobBlobHandling(rightType, leftType));
                    break;
                default:
                    break;
            }
        } else if (leftTypeId.getJDBCTypeId() == Types.VARCHAR) {
            switch (rightTypeId.getJDBCTypeId()) {
                case Types.CHAR: //operands CHAR(A) VARCHAR(B) and A+B>4000 then
                    // result is LONG VARCHAR
                case Types.VARCHAR: //operands VARCHAR(A) VARCHAR(B) and A+B>4000
                    // then result is LONG VARCHAR
                    if (resultLength.getValue() > Limits.DB2_CONCAT_VARCHAR_LENGTH) {
                        resultTypeId = TypeId.getBuiltInTypeId(Types.LONGVARCHAR);
                    }
                    break;

                case Types.CLOB:
                    //operands VARCHAR(A), CLOB(B) then result is CLOB(MIN(A+B,2G))
                    resultLength.setValue(clobBlobHandling(rightType, leftType));
                    break;
                default:
                    break;
            }
        } else if (leftTypeId.getJDBCTypeId() == Types.VARBINARY) {
            switch (rightTypeId.getJDBCTypeId()) {
                case Types.BINARY: //operands CHAR FOR BIT DATA(A) VARCHAR FOR BIT
                    // DATA(B) and A+B>4000 then result is LONG
                    // VARCHAR FOR BIT DATA
                case Types.VARBINARY://operands VARCHAR FOR BIT DATA(A) VARCHAR FOR
                    // BIT DATA(B) and A+B>4000 then result is LONG
                    // VARCHAR FOR BIT DATA
                    if (resultLength.getValue() > Limits.DB2_CONCAT_VARCHAR_LENGTH) {
                        resultTypeId = TypeId.getBuiltInTypeId(Types.LONGVARBINARY);
                    }
                    break;

                case Types.BLOB:
                    //operands VARCHAR FOR BIT DATA(A), BLOB(B) then result is
                    // BLOB(MIN(A+B,2G))
                    resultLength.setValue(clobBlobHandling(rightType, leftType));
                    break;
                default:
                    break;
            }
        } else if (leftTypeId.getJDBCTypeId() == Types.CLOB
                    || leftTypeId.getJDBCTypeId() == Types.BLOB) {
            //operands CLOB(A), CHAR(B) then result is CLOB(MIN(A+B,2G))
            //operands CLOB(A), VARCHAR(B) then result is CLOB(MIN(A+B,2G))
            //operands CLOB(A), LONG VARCHAR then result is CLOB(MIN(A+32K,2G))
            //operands CLOB(A), CLOB(B) then result is CLOB(MIN(A+B,2G))
            //operands BLOB(A), CHAR FOR BIT DATA(B) then result is
            // BLOB(MIN(A+B,2G))
            //operands BLOB(A), VARCHAR FOR BIT DATA(B) then result is
            // BLOB(MIN(A+B,2G))
            //operands BLOB(A), LONG VARCHAR FOR BIT DATA then result is
            // BLOB(MIN(A+32K,2G))
            //operands BLOB(A), BLOB(B) then result is BLOB(MIN(A+B,2G))
            resultLength.setValue(clobBlobHandling(leftType, rightType));
        } else if (rightTypeId.getJDBCTypeId() == Types.CLOB
                    || rightTypeId.getJDBCTypeId() == Types.BLOB) {
            //operands LONG VARCHAR, CLOB(A) then result is CLOB(MIN(A+32K,2G))
            //operands LONG VARCHAR FOR BIT DATA, BLOB(A) then result is
            // BLOB(MIN(A+32K,2G))
            resultLength.setValue(clobBlobHandling(rightType, leftType));
        }

        //bug - 5837. long varchar and long binary can't hold more data than
        // their specific limits. If this length is violated by resulting
        //concatenated string, an exception will be thrown at execute time.
        if (resultTypeId.getJDBCTypeId() == Types.LONGVARCHAR)
            resultLength.setValue(TypeId.LONGVARCHAR_MAXWIDTH);
        else if (resultTypeId.getJDBCTypeId() == Types.LONGVARBINARY)
            resultLength.setValue(TypeId.LONGVARBIT_MAXWIDTH);

        /*
         * * Result Length can't be negative
         */
        if (SanityManager.DEBUG) {
            if (resultLength.getValue() < 0) {
                SanityManager
                .THROWASSERT("There should not be an overflow of maximum length for any result type at this point. Overflow for BLOB/CLOB has already been handled earlier");
            }
        }
        return resultTypeId;
    }
    /**
     * Resolve a concatenation operator
     *
     * @param leftType
     *            The DataTypeDescriptor of the left operand
     * @param rightType
     *            The DataTypeDescriptor of the right operand
     *
     * @return A DataTypeDescriptor telling the result type of the concatenate
     *         operation
     *
     * @exception StandardException
     *                BinaryOperatorNotSupported Thrown when a BinaryOperator is
     *                not supported on the operand types.
     */
    private DataTypeDescriptor resolveConcatOperation(
            DataTypeDescriptor leftType, DataTypeDescriptor rightType)
            throws StandardException {

        boolean nullable;

        /* The result is nullable if either side is nullable */
        nullable = leftType.isNullable() || rightType.isNullable();

        MutableInt    resultLength     = new MutableInt();

        TypeId resultTypeId =
                 resolveConcatOperationResultType(leftType, rightType, resultLength);
        /*
         * * Create a new DataTypeDescriptor that has the correct * type and
         * nullability. * * It's OK to call the implementation of the
         * DataTypeDescriptorFactory * here, because we're in the same package.
         */
        DataTypeDescriptor returnDTD =
          new DataTypeDescriptor(resultTypeId, nullable, resultLength.getValue());

        //Check if collation derivations and collation types of 2 operands
        //match?
        //If they do, then the result of the concatenation will get the smae
        //collation information. But if not, then the collation derivation of
        //the result will be NONE.
        if (leftType.getCollationDerivation() != rightType
                .getCollationDerivation()
                || leftType.getCollationType() != rightType.getCollationType())
            
            returnDTD = returnDTD.getCollatedType(
                    returnDTD.getCollationDerivation(),
                    StringDataValue.COLLATION_DERIVATION_NONE);
        else {
            returnDTD = returnDTD.getCollatedType(
                    leftType.getCollationType(),
                    leftType.getCollationDerivation());
        }
        return returnDTD;
    }

    private static int clobBlobHandling(DataTypeDescriptor clobBlobType,
            DataTypeDescriptor otherType) throws StandardException {
        int resultLength;

        if (otherType.getTypeId().getJDBCTypeId() == Types.LONGVARCHAR
                || otherType.getTypeId().getJDBCTypeId() == Types.LONGVARBINARY) {
            //operands CLOB(A), LONG VARCHAR then result is CLOB(MIN(A+32K,2G))
            //operands BLOB(A), LONG VARCHAR FOR BIT DATA then result is
            // BLOB(MIN(A+32K,2G))
            resultLength = clobBlobType.getMaximumWidth() + 32768;
        } else {
            //operands CLOB(A), CHAR(B) then result is CLOB(MIN(A+B,2G))
            //operands CLOB(A), VARCHAR(B) then result is CLOB(MIN(A+B,2G))
            //operands CLOB(A), CLOB(B) then result is CLOB(MIN(A+B,2G))
            //operands BLOB(A), CHAR FOR BIT DATA(B) then result is
            // BLOB(MIN(A+B,2G))
            //operands BLOB(A), VARCHAR FOR BIT DATA(B) then result is
            // BLOB(MIN(A+B,2G))
            //operands BLOB(A), BLOB(B) then result is BLOB(MIN(A+B,2G))
            resultLength = clobBlobType.getMaximumWidth()
                    + otherType.getMaximumWidth();
        }

        if (resultLength < 1) //this mean A+B or A+32K is bigger than 2G
            return (Integer.MAX_VALUE);
        else
            return (resultLength);

    }

    @Override
    public double getBaseOperationCost() throws StandardException {
        // allocation cost shadows copy cost
        return SIMPLE_OP_COST * ALLOC_COST_FACTOR + super.getBaseOperationCost();
    }
}
