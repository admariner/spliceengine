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
package com.splicemachine.db.iapi.types;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.stats.ColumnStatisticsImpl;
import com.splicemachine.db.iapi.stats.ItemStatistics;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import org.apache.spark.sql.Row;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 *
 * Test Class for SQLBoolean
 *
 */
public class SQLBooleanTest extends SQLDataValueDescriptorTest {

        @Test
        public void rowValueToDVDValue() throws Exception {
                SQLBoolean bool1 = new SQLBoolean(true);
                SQLBoolean bool2 = new SQLBoolean();
                ExecRow execRow = new ValueRow(2);
                execRow.setColumn(1, bool1);
                execRow.setColumn(2, bool2);
                Assert.assertEquals(true, ((Row) execRow).getBoolean(0));
                Assert.assertTrue(((Row) execRow).isNullAt(1));
                Row sparkRow = execRow.getSparkRow();
                Assert.assertEquals(sparkRow.getBoolean(0), true);
                Assert.assertTrue(sparkRow.isNullAt(1));
        }
        public void testColumnStatistics() throws Exception {
                SQLBoolean value1 = new SQLBoolean();
                ItemStatistics stats = new ColumnStatisticsImpl(value1);
                SQLBoolean sqlBoolean;
                for (int i = 1; i<= 10000; i++) {
                        if (i>=5000 && i < 6000)
                                sqlBoolean = new SQLBoolean();
                        else if (i>=1000 && i< 2000)
                                sqlBoolean = new SQLBoolean(false);
                        else
                                sqlBoolean = new SQLBoolean(i % 2 == 0);
                        stats.update(sqlBoolean);
                }
                stats = serde(stats);
                Assert.assertEquals(1000,stats.nullCount());
                Assert.assertEquals(9000,stats.notNullCount());
                Assert.assertEquals(10000,stats.totalCount());
                Assert.assertEquals(new SQLBoolean(true),stats.maxValue());
                Assert.assertEquals(new SQLBoolean(false),stats.minValue());
                Assert.assertEquals(1000,stats.selectivity(null));
                Assert.assertEquals(1000,stats.selectivity(new SQLBoolean()));
                Assert.assertEquals(4000,stats.selectivity(new SQLBoolean(true)));
                Assert.assertEquals(5000,stats.selectivity(new SQLBoolean(false)));
                Assert.assertEquals(5000.0d,(double) stats.rangeSelectivity(new SQLBoolean(false),new SQLBoolean(true),true,false),RANGE_SELECTIVITY_ERRROR_BOUNDS);
                Assert.assertEquals(5000.0d,(double) stats.rangeSelectivity(new SQLBoolean(),new SQLBoolean(true),true,false),RANGE_SELECTIVITY_ERRROR_BOUNDS);
                Assert.assertEquals(9000.0d,(double) stats.rangeSelectivity(new SQLBoolean(false),new SQLBoolean(),true,false),RANGE_SELECTIVITY_ERRROR_BOUNDS);
        }

        @Test
        public void testExecRowSparkRowConversion() throws StandardException {
                ValueRow execRow = new ValueRow(1);
                execRow.setRowArray(new DataValueDescriptor[]{new SQLBoolean(true)});
                Row row = execRow.getSparkRow();
                Assert.assertEquals(true,row.getBoolean(0));
                ValueRow execRow2 = new ValueRow(1);
                execRow2.setRowArray(new DataValueDescriptor[]{new SQLBoolean()});
                execRow2.getColumn(1).setSparkObject(row.get(0));
                Assert.assertEquals("ExecRow Mismatch",execRow,execRow2);
        }

        @Test
        public void testSelectivityWithParameter() throws Exception {
                /* let only the first 3 rows take different values, all remaining rows use a default value */
                SQLBoolean value1 = new SQLBoolean();
                ItemStatistics stats = new ColumnStatisticsImpl(value1);
                SQLBoolean sqlBoolean;
                sqlBoolean = new SQLBoolean(true);
                stats.update(sqlBoolean);
                sqlBoolean = new SQLBoolean(true);
                stats.update(sqlBoolean);
                sqlBoolean = new SQLBoolean(true);
                stats.update(sqlBoolean);
                for (int i = 3; i < 81920; i++) {
                        sqlBoolean = new SQLBoolean(false);
                        stats.update(sqlBoolean);
                }
                stats = serde(stats);

                /* selectivityExcludingValueIfSkewed() is the function used to compute the electivity of equality
                   predicate with parameterized value
                 */
                double range = stats.selectivityExcludingValueIfSkewed(sqlBoolean);
                Assert.assertTrue(range + " did not match expected value of 1.0d", (range == 3.0d));
        }
}
