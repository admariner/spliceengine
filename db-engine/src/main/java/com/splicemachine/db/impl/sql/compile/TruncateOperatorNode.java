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
import com.splicemachine.db.iapi.services.classfile.VMOpcode;
import com.splicemachine.db.iapi.services.compiler.MethodBuilder;
import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.TypeId;

import java.sql.Types;
import java.util.*;

/**
 * The TruncateOperatorNode class implements the trunc[ate]( {date | time} [, string] | {decimal , integer} ) function.
 */

public class TruncateOperatorNode extends BinaryOperatorNode {

    private static final Map<Integer,Pair> TYPES_TO_METHOD_NAMES;
    private static final String TRUNC_DECIMAL = "truncDecimal";
    static {
        TYPES_TO_METHOD_NAMES = new HashMap<>(4);
        TYPES_TO_METHOD_NAMES.put(Types.TIMESTAMP, new Pair(ClassName.DateTimeDataValue, "truncTimestamp"));
        TYPES_TO_METHOD_NAMES.put(Types.DATE, new Pair(ClassName.DateTimeDataValue, "truncDate"));
        TYPES_TO_METHOD_NAMES.put(Types.DECIMAL, new Pair(ClassName.NumberDataValue, TRUNC_DECIMAL));
        TYPES_TO_METHOD_NAMES.put(Types.INTEGER, new Pair(ClassName.NumberDataValue, TRUNC_DECIMAL));
    }

    private String methodClassname;

    private void checkParameterTypes() throws StandardException {
        // do some validation...
        TypeId leftTypeId = getLeftOperand().getTypeId();
        if (leftTypeId != null) {
            int jdbcId = leftTypeId.getJDBCTypeId();
            if (jdbcId == Types.TIMESTAMP || jdbcId == Types.DATE) {
                if (getRightOperand().getTypeId().getJDBCTypeId() != Types.CHAR) {
                    throw StandardException.newException(SQLState.LANG_TRUNCATE_EXPECTED_RIGHTSIDE_CHAR_TYPE, getRightOperand().toString());
                }
            } else if (jdbcId == Types.DECIMAL || jdbcId == Types.INTEGER) {
                TypeId rightTypeId = getRightOperand().getTypeId();
                if (rightTypeId == null || rightTypeId.getJDBCTypeId() != Types.INTEGER) {
                    throw StandardException.newException(SQLState.LANG_TRUNCATE_EXPECTED_RIGHTSIDE_INTEGER_TYPE, getRightOperand().toString());
                }
            } else {
                throw StandardException.newException(SQLState.LANG_TRUNCATE_UNKNOWN_TYPE_OPERAND, getLeftOperand().toString());
            }
        } else if (! (getLeftOperand() instanceof ColumnReference) && ! (getLeftOperand() instanceof CurrentDatetimeOperatorNode)) {
            // A ColumnReference will not have a type until bind time.
            // We put off further ColumnReference validation until then.
            // If we don't get a ColumnReference at this point, we can't handle the operand.
            throw StandardException.newException(SQLState.LANG_TRUNCATE_UNKNOWN_TYPE_OPERAND, getLeftOperand().toString());
        }
    }

    /**
     * Contructor for a TruncateOperatorNode.
     *
     * @param truncOperand The truncOperand
     * @param truncValue The truncation value
     */
    public TruncateOperatorNode(ValueNode truncOperand, ValueNode truncValue, ContextManager contextManager)
            throws StandardException
    {
        setContextManager(contextManager);
        operands = new ArrayList<>(Arrays.asList((ValueNode) truncOperand, (ValueNode) truncValue));
        operator = "truncate";

        checkParameterTypes();
    }

    /**
     * Bind this expression.  This means binding the sub-expressions,
     * as well as figuring out what the return type is for this expression.
     *
     * @param fromList        The FROM list for the query this
     *                expression is in, for binding columns.
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
        bindOperands(fromList, subqueryList, aggregateVector);

        int operandType = getLeftOperand().getTypeId().getJDBCTypeId();
        Pair typeMethod = TYPES_TO_METHOD_NAMES.get(operandType);

        if (typeMethod == null) {
            throw StandardException.newException(SQLState.LANG_TRUNCATE_UNKNOWN_TYPE_OPERAND, getLeftOperand().toString());
        }
        this.methodName = typeMethod.methodName;
        this.methodClassname = typeMethod.className;

        // Default right side to integer (zero) when trunc decimal column ref
        // This is the first place we can verify this because a column ref's type is not known until bind time
        if (this.methodName.equals(TRUNC_DECIMAL) && getRightOperand().getTypeId().getJDBCTypeId() != Types.INTEGER) {
            setRightOperand((ValueNode) getCompilerContext().getNodeFactory().getNode(C_NodeTypes.INT_CONSTANT_NODE, 0, getContextManager()));
        }

        //Set the type if there is a parameter involved here
        if (getLeftOperand().requiresTypeFromContext()) {
            getLeftOperand().setType(DataTypeDescriptor.getBuiltInDataTypeDescriptor(operandType));
        }
        //Set the type if there is a parameter involved here
        if (getRightOperand() != null && getRightOperand().requiresTypeFromContext()) {
            getRightOperand().setType(DataTypeDescriptor.getBuiltInDataTypeDescriptor(getRightOperand().getTypeId().getJDBCTypeId()));
        }

        checkParameterTypes();

        DataTypeDescriptor typeDescriptor = getLeftOperand().getTypeServices();
        if (typeDescriptor == null) {
            typeDescriptor = DataTypeDescriptor.getBuiltInDataTypeDescriptor(operandType);
        }
        setType(typeDescriptor);
        return genSQLJavaSQLTree();
    } // end of bindExpression

    /**
     * Do code generation for this binary operator.
     *
     * @param acb    The ExpressionClassBuilder for the class we're generating
     * @param mb    The method the code to place the code
     *
     *
     * @exception com.splicemachine.db.iapi.error.StandardException        Thrown on error
     */

    public void generateExpression(ExpressionClassBuilder acb,
                                            MethodBuilder mb) throws StandardException {
        acb.pushDataValueFactory(mb);
        getLeftOperand().generateExpression(acb, mb);
        mb.cast(ClassName.DataValueDescriptor);
        getRightOperand().generateExpression(acb, mb);
        mb.cast(ClassName.DataValueDescriptor);
        mb.callMethod(VMOpcode.INVOKEINTERFACE, null, methodName, methodClassname, 2);
    } // end of generateExpression

    private static class Pair {
        final String methodName;
        final String className;

        public Pair(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }
    }

    @Override
    public double getBaseOperationCost() throws StandardException {
        return SIMPLE_OP_COST * FN_CALL_COST_FACTOR + super.getBaseOperationCost();
    }
}
