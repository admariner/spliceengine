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

import com.splicemachine.db.iapi.types.TypeId;
import com.splicemachine.db.iapi.types.DateTimeDataValue;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;

import com.splicemachine.db.iapi.sql.compile.TypeCompiler;

import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.error.StandardException;

import com.splicemachine.db.iapi.services.sanity.SanityManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.sql.Types;

import java.util.List;

import static com.splicemachine.db.iapi.types.DateTimeDataValue.*;

/**
 * This node represents a unary extract operator, used to extract
 * a field from a date/time. The field value is returned as an integer.
 *
 */
@SuppressFBWarnings(value = "HE_INHERITS_EQUALS_USE_HASHCODE", justification="DB-9277")
public class ExtractOperatorNode extends UnaryOperatorNode {

    static private final String fieldName[] = {
        "YEAR", "QUARTER", "MONTH", "MONTHNAME", "WEEK", "WEEKDAY", "DAYOFWEEK", "WEEKDAYNAME", "DAYOFYEAR", "DAY", "HOUR", "MINUTE", "SECOND"
    };
    static private final String fieldMethod[] = {
        "getYear","getQuarter","getMonth","getMonthName","getWeek","getWeekDay", "getUSWeekDay", "getWeekDayName","getDayOfYear","getDate","getHours","getMinutes","getSecondsAndFractionOfSecondAsDouble"
    };

    static private final long fieldMaxCardinality[] = {
            5L, 4L, 12L, 12L, 52L, 7L, 7L, 7L, 365L, 31L, 24L, 60L, 60L
    };

    private int extractField;

    /**
     * Initializer for a ExtractOperatorNode
     *
     * @param field        The field to extract
     * @param operand    The operand
     */
    public void init(Object field, Object operand) {
        extractField = (Integer) field;
        super.init( operand,
                    "EXTRACT "+fieldName[extractField],
                    fieldMethod[extractField] );
    }

    /**
     * Bind this operator
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
                                    List<AggregateNode> aggregateVector) throws StandardException  {
        int    operandType;
        TypeId opTypeId;

        bindOperand(fromList, subqueryList, aggregateVector);

        opTypeId = getOperand().getTypeId();
        operandType = opTypeId.getJDBCTypeId();

        /*
        ** Cast the operand, if necessary, - this function is allowed only on
        ** date/time types.  By default, we cast to DATE if extracting
        ** YEAR, MONTH or DAY and to TIME if extracting HOUR, MINUTE or
        ** SECOND.
        */
        if (opTypeId.isStringTypeId())
        {
            TypeCompiler tc = getOperand().getTypeCompiler();
            int castType = (extractField < HOUR_FIELD) ? Types.DATE : Types.TIME;
            castOperandAndBindCast(DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                    castType, true,
                    tc.getCastToCharWidth(getOperand().getTypeServices(), getCompilerContext())));

            opTypeId = getOperand().getTypeId();
            operandType = opTypeId.getJDBCTypeId();
        }

        if ( ! ( ( operandType == Types.DATE )
               || ( operandType == Types.TIME )
               || ( operandType == Types.TIMESTAMP )
            )    ) {
            throw StandardException.newException(SQLState.LANG_UNARY_FUNCTION_BAD_TYPE,
                        "EXTRACT "+fieldName[extractField],
                        opTypeId.getSQLTypeName());
        }

        /*
            If the type is DATE, ensure the field is okay.
         */
        if ( (operandType == Types.DATE)
             && (extractField > DateTimeDataValue.DAY_FIELD) ) {
            throw StandardException.newException(SQLState.LANG_UNARY_FUNCTION_BAD_TYPE,
                        "EXTRACT "+fieldName[extractField],
                        opTypeId.getSQLTypeName());
        }

        /*
            If the type is TIME, ensure the field is okay.
         */
        if ( (operandType == Types.TIME)
             && (extractField < DateTimeDataValue.HOUR_FIELD) ) {
            throw StandardException.newException(SQLState.LANG_UNARY_FUNCTION_BAD_TYPE,
                        "EXTRACT "+fieldName[extractField],
                        opTypeId.getSQLTypeName());
        }

        /*
        ** The result type of extract is int,
        ** unless it is TIMESTAMP and SECOND, in which case
        ** for now it is DOUBLE but eventually it will need to
        ** be DECIMAL(11,9).
        */
        if ( (operandType == Types.TIMESTAMP)
             && (extractField == DateTimeDataValue.SECOND_FIELD) ) {
            setType(new DataTypeDescriptor(
                            TypeId.getBuiltInTypeId(Types.DOUBLE),
                            getOperand().getTypeServices().isNullable()
                        )
                );
        } else if (extractField == MONTHNAME_FIELD || extractField == DateTimeDataValue.WEEKDAYNAME_FIELD) {
            // name fields return varchar
            setType(new DataTypeDescriptor(
                    TypeId.CHAR_ID,
                        getOperand().getTypeServices().isNullable(),
                    14  // longest day name is in Portuguese (13); longest month name is in Greek (12)
                    )
            );
        } else {
            setType(new DataTypeDescriptor(
                            TypeId.INTEGER_ID,
                            getOperand().getTypeServices().isNullable()
                        )
                );
        }

        return this;
    }

    @Override
    void bindParameter() throws StandardException
    {
        getOperand().setType(DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.CHAR, true));
    }

    public String toString() {
        if (SanityManager.DEBUG)
        {
            return "fieldName: " + fieldName[extractField] + "\n" +
                super.toString();
        }
        else
        {
            return "";
        }
    }

    public String sparkFunctionName() throws StandardException{
        if (extractField == MONTHNAME_FIELD)
            throw StandardException.newException(SQLState.LANG_DOES_NOT_IMPLEMENT);
        return fieldName[extractField];
    }

    @Override
    public long nonZeroCardinality(long numberOfRows) {
        if (extractField == SECOND_FIELD && getOperand().getTypeId().getJDBCTypeId() == Types.TIMESTAMP) {
            return numberOfRows;
        }
        return Math.min(fieldMaxCardinality[extractField], numberOfRows);
    }

    @Override
    public double getBaseOperationCost() throws StandardException {
        double lowerCost = super.getBaseOperationCost();
        double localCost = SIMPLE_OP_COST * (getOperand() == null ? 1.0 : 2.0);
        double callCost = SIMPLE_OP_COST * FN_CALL_COST_FACTOR;
        return lowerCost + localCost + callCost;
    }
}
