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

package com.splicemachine.db.impl.sql.compile;


import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.derby.test.framework.SpliceUnitTest;
import com.splicemachine.derby.test.framework.SpliceWatcher;
import com.splicemachine.homeless.TestUtils;
import com.splicemachine.test.SerialTest;
import com.splicemachine.test_tools.TableCreator;
import org.junit.*;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import splice.com.google.common.collect.ImmutableList;
import splice.com.google.common.collect.Lists;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.splicemachine.test_tools.Rows.row;
import static com.splicemachine.test_tools.Rows.rows;

/**
 * Test predicate with nulls
 */
@RunWith(Parameterized.class)
@Category(SerialTest.class)
public class NullPredicateIT extends SpliceUnitTest {

    private Boolean useSpark;
    private Boolean disablePredicateSimpification;
    private Boolean disableConstantFolding;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        Collection<Object[]> params = Lists.newArrayListWithCapacity(2);
        List<Boolean> values = Arrays.asList(true, false);
        for (boolean useSpark: values) {
            for (boolean disablePredicateSimplification: values) {
                for (boolean disableConstantFolding: values) {
                    params.add(new Object[]{useSpark, disablePredicateSimplification, disableConstantFolding});
                }
            }
        }
        return params;
    }
    private static final String SCHEMA = NullPredicateIT.class.getSimpleName();

    @ClassRule
    public static SpliceSchemaWatcher schemaWatcher = new SpliceSchemaWatcher(SCHEMA);

    @ClassRule
    public static SpliceWatcher classWatcher = new SpliceWatcher(SCHEMA);

    @Rule
    public SpliceWatcher methodWatcher = new SpliceWatcher(SCHEMA);

    @Parameterized.BeforeParam
    public static void beforeParam(boolean useSpark, boolean disablePredicateSimpification, boolean disableConstantFolding) throws Exception {
        classWatcher.execute("CALL SYSCS_UTIL.SYSCS_EMPTY_GLOBAL_STATEMENT_CACHE()");
        classWatcher.execute("CALL SYSCS_UTIL.INVALIDATE_GLOBAL_DICTIONARY_CACHE()");
        classWatcher.execute(format("call syscs_util.syscs_set_global_database_property('derby.database.disablePredicateSimplification', '%s')", disablePredicateSimpification));
        classWatcher.execute(format("call syscs_util.syscs_set_global_database_property('splice.database.disableConstantFolding', '%s')", disableConstantFolding));
    }

    @Parameterized.AfterParam
    public static void afterParam() throws Exception {
        classWatcher.execute("CALL SYSCS_UTIL.SYSCS_EMPTY_GLOBAL_STATEMENT_CACHE()");
        classWatcher.execute("CALL SYSCS_UTIL.INVALIDATE_GLOBAL_DICTIONARY_CACHE()");
        classWatcher.execute("call syscs_util.syscs_set_global_database_property('derby.database.disablePredicateSimplification', null)");
        classWatcher.execute("call syscs_util.syscs_set_global_database_property('splice.database.disableConstantFolding', null)");
    }

    public static void createData(Connection conn, String schemaName) throws Exception {
        new TableCreator(conn)
                .withCreate("create table t(a int, b varchar(5))")
                .withInsert("insert into t values(?,?)")
                .withRows(rows(
                        row(1, "aaa")))
                .create();

        conn.commit();
    }

    @BeforeClass
    public static void createDataSet() throws Exception {
        createData(classWatcher.getOrCreateConnection(), schemaWatcher.toString());
    }

    public NullPredicateIT(Boolean useSpark, Boolean disablePredicateSimpification, Boolean disableConstantFolding) {
        this.useSpark = useSpark;
        this.disablePredicateSimpification = disablePredicateSimpification;
        this.disableConstantFolding = disableConstantFolding;
    }

    @Test
    public void testSimpleNull() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                "where cast(null as boolean)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNullInArithmeticExpression() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                "where cast(null as int) + 1 > 3", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNullInCharacterExpression() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                "where cast(null as varchar(3)) || 'test' = 'bar_test'", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNullInLike() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                "where cast(null as varchar(3)) like 'bar_%%'", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNullInBetween_1() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                "where a between cast(null as int) and 5", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNullInBetween_2() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                "where cast(null as int) between 1 and 5", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNullInInList_1() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                "where a in (cast(null as int), 5)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNullInInList_2() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                "where cast(null as int) in (3, 5)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNotWithNullInInList_1() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                                      "where a not in (cast(null as int), 5)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNotWithNullInInList_2() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                                      "where cast(null as int) not in (3, 5)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNullInJoinCondition() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                " inner join T on cast(null as boolean)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNotWithNullInJoinCondition() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                                      " inner join T on not cast(null as boolean)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testInListWithNullInJoinCondition() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                                      " inner join T on cast(null as integer) in (42)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test
    public void testNotWithInListWithNullInJoinCondition() throws Exception {
        String query = format("select count(*) from T --SPLICE-PROPERTIES useSpark=%s\n" +
                                      " inner join T on cast(null as integer) not in (42)", useSpark);

        String expected = "1 |\n" +
                "----\n" +
                " 0 |";

        try(ResultSet rs = methodWatcher.executeQuery(query)) {
            Assert.assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
    }

    @Test // DB-11605
    public void testNullInCaseWhen() throws Exception {
        String res = "A | B  |\n" +
                "---------\n" +
                " 1 |aaa |";
        methodWatcher.assertStrResult(res, "select * from t --splice-properties useSpark=%s\n where case when a < 0 then null else 2 end > 0", false);
        methodWatcher.assertStrResult(res, "select * from t --splice-properties useSpark=%s\n where case when a > 0 then 2 else null end > 0", false);
    }

    @Test
    public void testNullBehindFunction() throws Exception {
        testQuery("select * from t --splice-properties useSpark=%s\n where cast(null as integer) not in (3,4)", "", methodWatcher);
        testQuery("select * from t --splice-properties useSpark=%s\n where upper(cast(null as varchar(10))) = 'a'", "", methodWatcher);
        testQuery("select * from t --splice-properties useSpark=%s\n where days(cast(null as timestamp)) = 50", "", methodWatcher);
        testQuery("select * from t --splice-properties useSpark=%s\n where day(cast(null as timestamp)) = 50", "", methodWatcher);
    }
}
