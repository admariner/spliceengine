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

package com.splicemachine.derby.stream.spark;

import com.splicemachine.EngineDriver;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.store.access.ColumnOrdering;
import com.splicemachine.db.impl.jdbc.EmbedResultSet40;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.derby.impl.SpliceSpark;
import com.splicemachine.derby.impl.sql.execute.operations.SpliceBaseOperation;
import com.splicemachine.derby.stream.function.LocatedRowToRowFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.si.impl.driver.SIDriver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaRDDLike;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import scala.collection.JavaConversions;

import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.spark.sql.functions.*;

public class SparkUtils {
    public static final Logger LOG = Logger.getLogger(SparkUtils.class);

    private static final int DEFAULT_PARTITIONS = 20;

    public static int getPartitions(JavaRDDLike<?,?> rdd) {
        int rddPartitions = rdd.getNumPartitions();
        return Math.max(rddPartitions, getDefaultPartitions());
    }

    public static int getPartitions(JavaRDDLike<?,?> rdd1, JavaRDDLike<?,?> rdd2) {
        int rddPartitions1 = rdd1.getNumPartitions();
        int rddPartitions2 = rdd2.getNumPartitions();
        int max = Math.max(rddPartitions1, rddPartitions2);
        return Math.max(max, getDefaultPartitions());
    }

    public static JavaPairRDD<ExecRow, ExecRow> getKeyedRDD(JavaRDD<ExecRow> rdd, final int[] keyColumns)
            throws StandardException {
        return rdd.keyBy(new Keyer(keyColumns));
    }

    private static void printRDD(String title, @SuppressWarnings("rawtypes") Iterable it) {
        StringBuilder sb = new StringBuilder(title);
        sb.append(": ");
        boolean first = true;
        for (Object o : it) {
            if (!first) {
                sb.append(",");
            }
            sb.append(o);
            first = false;
        }
        LOG.debug(sb);
    }

    public static void printRDD(String title, JavaRDD<ExecRow> rdd) {
        if (LOG.isDebugEnabled()) {
            printRDD(title, rdd.collect());
        }
    }

    public static void printRDD(String title, JavaPairRDD<ExecRow, ExecRow> rdd) {
        if (LOG.isDebugEnabled()) {
            printRDD(title, rdd.collect());
        }
    }

    public static ExecRow getKey(ExecRow row, int[] keyColumns) throws StandardException {
        ValueRow key = new ValueRow(keyColumns.length);
        int position = 1;
        for (int keyColumn : keyColumns) {
            key.setColumn(position++, row.getColumn(keyColumn + 1));
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Added key, returning (%s, %s) key hash %d", key, row, key.hashCode()));
        }
        return key;
    }

    public static JavaRDD<ExecRow> toSparkRows(JavaRDD<ExecRow> execRows) {
        return execRows.map(new Function<ExecRow, ExecRow>() {
            @Override
            public ExecRow call(ExecRow execRow) throws Exception {
                return execRow;
            }
        });
    }

    public static Iterator<ExecRow> toExecRowsIterator(final Iterator<ExecRow> sparkRowsIterator) {
        return new Iterator<ExecRow>() {
            @Override
            public boolean hasNext() {
                return sparkRowsIterator.hasNext();
            }

            @Override
            public ExecRow next() {
                return sparkRowsIterator.next();
            }

            @Override
            public void remove() {
                sparkRowsIterator.remove();
            }
        };
    }

    public static Iterable<ExecRow> toSparkRowsIterable(Iterable<ExecRow> execRows) {
        return new SparkRowsIterable(execRows);
    }

    @SuppressWarnings("rawtypes")
    public static void setAncestorRDDNames(JavaPairRDD rdd, int levels, String[] newNames, String[] checkNames) {
        assert levels > 0;
        setAncestorRDDNames(rdd.rdd(), levels, newNames, checkNames);
    }

    @SuppressWarnings("rawtypes")
    public static void setAncestorRDDNames(JavaRDD rdd, int levels, String[] newNames, String[] checkNames) {
        assert levels > 0;
        setAncestorRDDNames(rdd.rdd(), levels, newNames, checkNames);
    }

    @SuppressWarnings("rawtypes")
    // TODO (wjk): remove this when we have a better way to change name of RDDs implicitly created within spark
    private static void setAncestorRDDNames(org.apache.spark.rdd.RDD rdd, int levels, String[] newNames, String[] checkNames) {
        assert levels > 0;
        org.apache.spark.rdd.RDD currentRDD = rdd;
        for (int i = 0; i < levels && currentRDD != null; i++) {
            org.apache.spark.rdd.RDD rddAnc =
                    ((org.apache.spark.Dependency) currentRDD.dependencies().head()).rdd();
            if (rddAnc != null) {
                if (checkNames == null || checkNames[i] == null)
                    rddAnc.setName(newNames[i]);
                else if (rddAnc.name().equals(checkNames[i]))
                    rddAnc.setName(newNames[i]);
            }
            currentRDD = rddAnc;
        }
    }

    public static class SparkRowsIterable implements Iterable<ExecRow>, Iterator<ExecRow> {
        private Iterator<ExecRow> execRows;

        public SparkRowsIterable(Iterable<ExecRow> execRows) {
            this.execRows = execRows.iterator();
        }

        @Override
        public Iterator<ExecRow> iterator() {
            return this;
        }

        @Override
        public boolean hasNext() {
            return execRows.hasNext();
        }

        @Override
        public ExecRow next() {
            return execRows.next();
        }

        @Override
        public void remove() {
            execRows.remove();
        }
    }

    public static class Keyer implements Function<ExecRow, ExecRow> {

        private static final long serialVersionUID = 3988079974858059941L;
        private int[] keyColumns;

        public Keyer() {
        }

        @SuppressFBWarnings("EI_EXPOSE_REP2")
        public Keyer(int[] keyColumns) {
            this.keyColumns = keyColumns;
        }

        @Override
        public ExecRow call(ExecRow row) throws Exception {
            return SparkUtils.getKey(row, keyColumns);
        }
    }

    public static Dataset<Row> resultSetToDF(ResultSet rs) throws StandardException {
        EmbedResultSet40 ers = (EmbedResultSet40) rs;
        com.splicemachine.db.iapi.sql.ResultSet serverSideResultSet = ers.getUnderlyingResultSet();
        SpliceBaseOperation operation = (SpliceBaseOperation) serverSideResultSet;
        DataSetProcessor dsp = EngineDriver.driver().processorFactory().distributedProcessor();
        DataSet<ExecRow> spliceDataSet = operation.getResultDataSet(dsp);
        
        final ResultColumnDescriptor[] columns = serverSideResultSet.getResultDescription().getColumnInfo();
        // Generate the schema based on the ResultColumnDescriptors
        List<StructField> fields = new ArrayList<>();
        for (ResultColumnDescriptor column : columns) {
            fields.add(column.getStructField());
        }
        if( fields.stream().map( f -> f.name() ).distinct().count() != fields.size() ) {  // has duplicate names
            fields = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (ResultColumnDescriptor column : columns) {
                int i = 0;
                String fullname = column.getSourceSchemaName()+"."+column.getSourceTableName()+"."+column.getName();
                String name = fullname;
                while( used.contains(name) ) {
                    i += 1;
                    name = fullname+"_"+i;
                }
                used.add(name);
                StructField sf = column.getStructField();
                fields.add(new StructField(name, sf.dataType(), sf.nullable(), sf.metadata()));
            }
        }
        
        StructType schema = DataTypes.createStructType(fields);
        
        if(spliceDataSet instanceof SparkDataSet) {
            JavaRDD<ExecRow> rdd = ((SparkDataSet)spliceDataSet).rdd;
            return SpliceSpark.getSession().createDataFrame(rdd.map(new LocatedRowToRowFunction()), schema);
        } else {
            return ((NativeSparkDataSet) spliceDataSet).getDataset().toDF( schema.fieldNames() );
        }
    }

    /**
     * Convert Sort Columns, convert to 0-based index
     * @param sortColumns
     * @return
     */


    public static scala.collection.mutable.Buffer<Column> convertSortColumns(ColumnOrdering[] sortColumns){
        return Arrays
                .stream(sortColumns)
                .map(column -> column.getIsAscending() ?
                        (column.getIsNullsOrderedLow() ? asc_nulls_first(ValueRow.getNamedColumn(column.getColumnId()-1)) :
                                                         asc_nulls_last(ValueRow.getNamedColumn(column.getColumnId()-1))) :
                        (column.getIsNullsOrderedLow() ? desc_nulls_last(ValueRow.getNamedColumn(column.getColumnId()-1)) :
                                                         desc_nulls_first(ValueRow.getNamedColumn(column.getColumnId()-1))))
                .collect(Collectors.collectingAndThen(Collectors.toList(), JavaConversions::asScalaBuffer));
    }

    /**
     * Convert partition to Spark dataset columns
     * Ignoring partition
     * @param sortColumns
     * @return
     */

    public static scala.collection.mutable.Buffer<Column> convertPartitions(ColumnOrdering[] sortColumns){
        return Arrays
                .stream(sortColumns)
                .map(column -> col(ValueRow.getNamedColumn(column.getColumnId()-1)))
                .collect(Collectors.collectingAndThen(Collectors.toList(), JavaConversions::asScalaBuffer));
    }


    public static int getDefaultPartitions() {
        SIDriver driver = SIDriver.driver();
        return driver != null ? driver.getConfiguration().getOlapShufflePartitions() : DEFAULT_PARTITIONS;
    }
}


