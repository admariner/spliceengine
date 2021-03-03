package com.splicemachine.derby.impl.sql.compile.calcite;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.splicemachine.db.catalog.TypeDescriptor;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.sql.compile.CalciteConverter;
import com.splicemachine.db.iapi.sql.compile.ConvertSelectContext;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.impl.sql.compile.*;
import com.splicemachine.utils.Pair;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.Util;

import java.util.*;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * Created by yxia on 8/27/19.
 */
public class CalciteConverterImpl implements CalciteConverter {
    SpliceContext sc;
    RelOptCluster cluster;
    RelBuilder relBuilder;
    RelOptSchema relOptSchema;

    public CalciteConverterImpl(SpliceContext spliceContext, RelOptCluster cluster, RelOptSchema relOptSchema) {
        this.sc = spliceContext;
        this.cluster = cluster;
        this.relOptSchema = relOptSchema;
        relBuilder = RelBuilder.proto(spliceContext).create(cluster, relOptSchema);
    }

    public RelBuilder getRelBuilder() {
        return relBuilder;
    }

    public RelOptCluster getCluster() {
        return cluster;
    }

    public RelNode convertResultSet(ResultSetNode resultSetNode) throws StandardException {
        if (resultSetNode instanceof SelectNode)
            return convertSelect((SelectNode)resultSetNode);
        else if (resultSetNode instanceof RowResultSetNode)
            return convertRowResultSet((RowResultSetNode)resultSetNode);
        return null;
    }

    public RelNode convertSelect(SelectNode selectNode) throws StandardException {
        ConvertSelectContext selectContext = new ConvertSelectContextImpl(selectNode);
        //construct the joins
        convertFromList(selectContext);

        // construct operator for where
        convertWhere(selectContext);

        // consruct operator for the final projection
        convertResultColumnList(selectContext);

        return selectContext.getRelRoot();
    }

    private void saveBaseColumns(FromBaseTable fromBaseTable) {
        ResultColumnList rcl = fromBaseTable.getResultColumns();
        int tableNumber = fromBaseTable.getTableNumber();
        for (int i=0; i < rcl.size(); i++) {
            ResultColumn rc = rcl.elementAt(i);
            if (rc.getExpression() instanceof BaseColumnNode) {
                int columnPosition = rc.getColumnPosition();
                sc.addBaseColumn(Pair.newPair(tableNumber, columnPosition), rc);
            }
        }
    }

    public RelNode convertFromList(ConvertSelectContext selectContext) {
        Map<Integer, Integer> startColPos = new HashMap<>();
        SelectNode selectNode = selectContext.getSelectRoot();
        FromList fromList = selectNode.getFromList();
        RelNode root = null;
        int totalCols = 0;
        for (int i=0; i< fromList.size(); i++) {
            FromTable fromTable = (FromTable) fromList.elementAt(i);
            RelNode oneTable = null;
            if (fromTable instanceof FromBaseTable) {
                oneTable = convertFromBaseTable((FromBaseTable)fromTable);
                startColPos.put(fromTable.getTableNumber(), totalCols);
                totalCols += oneTable.getRowType().getFieldCount();

                // save the result columns corresponding to base column in SpliceContext
                saveBaseColumns((FromBaseTable)fromTable);
            }
            if (root == null)
                root = oneTable;
            else if (oneTable != null) {
                JoinRelType convertedJoinType = JoinRelType.INNER;
                RexNode conditionExp;
                conditionExp =
                        cluster.getRexBuilder().makeLiteral(true);
                root = createJoin(
                                root,
                                oneTable,
                                conditionExp,
                                convertedJoinType);
            }
        }

        selectContext.setStartColPosMap(startColPos);
        selectContext.setRelRoot(root);
        return root;
    }

    public RelNode convertFromBaseTable(FromBaseTable fromBaseTable) {
        TableName tn = fromBaseTable.getTableNameField();
        List<String> names  = new ArrayList<>();
        names.add(tn.getSchemaName());
        names.add(tn.getTableName());
        final RelOptTable relOptTable = relOptSchema.getTableForMember(names);
        if (relOptTable == null) {
            throw RESOURCE.tableNotFound(String.join(".", names)).ex();
        }

        SpliceTable spliceTable = relOptTable.unwrap(SpliceTable.class);
        spliceTable.setFromBaseTableNode(fromBaseTable);
        spliceTable.setTableNumber(fromBaseTable.getTableNumber());
        final RelNode scan = LogicalTableScan.create(cluster, relOptTable, Collections.emptyList());

        return scan;

    }

    public RelNode createJoin(RelNode left, RelNode right, RexNode joinCond, JoinRelType joinType) {
        final Join originalJoin =
                (Join) RelFactories.DEFAULT_JOIN_FACTORY.createJoin(left, right,Collections.emptyList(),
                        joinCond, ImmutableSet.of(), joinType, false);

        return RelOptUtil.pushDownJoinConditions(originalJoin, relBuilder);
    }

    public RelNode convertWhere(ConvertSelectContext selectContext) throws StandardException {
        ValueNode whereClause = selectContext.getSelectRoot().getWhereClause();

        if (whereClause != null) {
            relBuilder.push(selectContext.getRelRoot());
            RexNode convertedCondition = whereClause.convertExpression(this, selectContext);

            selectContext.setRelRoot(relBuilder.filter(convertedCondition).build());
        }
        return selectContext.getRelRoot();
    }

    public void convertResultColumnList(ConvertSelectContext selectContext) throws StandardException {
        relBuilder.push(selectContext.getRelRoot());
        final ImmutableList.Builder<RexNode> fields = ImmutableList.builder();
        ResultColumnList rcl = selectContext.getSelectRoot().getResultColumns();
        for (int i=0; i<rcl.size(); i++) {
            ResultColumn rc = rcl.elementAt(i);
            fields.add(convertExpression(rc.getExpression(), selectContext));
        }

        selectContext.setRelRoot(relBuilder.project(fields.build()).build());
        return;
    }


    public RexNode convertJoinCondition(ConditionalNode joinCond, JoinConditionType type, RelNode left, RelNode right) {
        return null;
    }

    public RexNode convertExpression(ValueNode node,
                                     ConvertSelectContext selectContext) throws StandardException {
        return node.convertExpression(this, selectContext);
    }

    public SqlOperator mapRelationalOperatorToCalciteSqlOperator(int operator) throws StandardException {
        switch (operator) {
            case RelationalOperator.EQUALS_RELOP:
                return SqlStdOperatorTable.EQUALS;
            case RelationalOperator.NOT_EQUALS_RELOP:
                return SqlStdOperatorTable.NOT_EQUALS;
            case RelationalOperator.GREATER_THAN_RELOP:
                return SqlStdOperatorTable.GREATER_THAN;
            case RelationalOperator.GREATER_EQUALS_RELOP:
                return SqlStdOperatorTable.GREATER_THAN_OR_EQUAL;
            case RelationalOperator.LESS_THAN_RELOP:
                return SqlStdOperatorTable.LESS_THAN;
            case RelationalOperator.LESS_EQUALS_RELOP:
                return SqlStdOperatorTable.LESS_THAN_OR_EQUAL;
            case RelationalOperator.IS_NOT_NULL_RELOP:
                return SqlStdOperatorTable.IS_NOT_NULL;
            case RelationalOperator.IS_NULL_RELOP:
                return SqlStdOperatorTable.IS_NULL;
            default:
                throw StandardException.newException(SQLState.LANG_INVADLID_CONVERSION, operator);
        }
    }

    public RelNode convertRowResultSet(RowResultSetNode rowResultSetNode) throws StandardException {
        ResultColumnList rcl = rowResultSetNode.getResultColumns();
        Object[] values = new Object[rcl.size()];
        String[] names = new String[rcl.size()];
        for (int i=0; i<rcl.size(); i++) {
            ResultColumn rc = rcl.elementAt(i);
            ValueNode valueNode = rc.getExpression();

            // TODO: how to handle expresion
            assert valueNode instanceof ConstantNode: "does not support non-constant expression yet";
            Object constantObject = ((ConstantNode) valueNode).getValue().getObject();
            values[i] = constantObject;
            names[i] = rc.getName();
        }

        RelNode relNode = relBuilder.values(names, values).build();
        return relNode;
    }

    public RelNode getValuesStmtForPlan(RelNode root) {
        Object[] values = new Object[1];
        String[] names = new String[1];
        values[0] = RelOptUtil.toString(root);
        names[0] = "Plan";
        return relBuilder.values(names, values).build();
    }

    public RelDataType mapToRelDataType(DataTypeDescriptor dtd) {
        TypeDescriptor typeDescriptor = dtd.getCatalogType();
        int jdbcTypeId = typeDescriptor.getJDBCTypeId();
        int precision = typeDescriptor.getPrecision();
        // precision of String type in Calcite is the length of the string type, which maps to the maximumWidth in Splice
        if (dtd.getTypeId().isStringTypeId()) {
            precision = typeDescriptor.getMaximumWidth();
        }
        int scale = typeDescriptor.getScale();
        boolean nullable = typeDescriptor.isNullable();
        return sqlType(jdbcTypeId, precision, scale, nullable);
    }

    private RelDataType sqlType(int dataType, int precision, int scale, boolean nullable) {
        RelDataTypeFactory typeFactory = cluster.getTypeFactory();
        RelDataType relDataType;
        // Fall back to ANY if type is unknown
        final SqlTypeName sqlTypeName =
                Util.first(SqlTypeName.getNameForJdbcType(dataType), SqlTypeName.ANY);
        if (precision >= 0
                && scale >= 0
                && sqlTypeName.allowsPrecScale(true, true)) {
            relDataType = typeFactory.createSqlType(sqlTypeName, precision, scale);
        } else if (precision >= 0 && sqlTypeName.allowsPrecNoScale()) {
            relDataType = typeFactory.createSqlType(sqlTypeName, precision);
        } else {
            assert sqlTypeName.allowsNoPrecNoScale();
            relDataType = typeFactory.createSqlType(sqlTypeName);
        }
        return typeFactory.createTypeWithNullability(relDataType, nullable);
    }
}
