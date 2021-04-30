package com.splicemachine.qpt;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class SQLSignatureTest {
    String id, sql;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                {"SmycO3Pq", "SELECT * FROM T"},
                {"SdlgNm1h", "SELECT * FROM M WHERE A = 4"},
                {"D7MH7bp7", "DELETE FROM TABLE A WHERE X = 'hello'"},
                {"IFCR4#o9", "INSERT INTO TABLE VALUES (1, 2, 3)"},
                {"SVlBDtjG", "select c1, c2, c3 from mytable where c2 < 27 and c3 = 'MAR'"},
                {"SVlBDtjG", "select c1, c2, c3 from mytable where c2 < 400 and c3='phred'"},
                {"SQr24xjG", "select c1, c2, c3 from mytable where c2 < ? and c3=?"},
                // make sure we don't crash on invalid SQL
                {"#TzMRkCw", "this is not valid SQL"},
                {"#TWaI000", ".%//\\nslkdjh---"},
                {"#0000000", ""},
                {"#0000000", null},
                {"#0000000", CommonTest.repeat(" ", 1000)},
                {"I0G4OMva", CommonTest.repeat("INSERT INTO 123 ", 1000)},
                // DB-11189
                {"U6PGPIVi",
                        "UPDATE VRESTART_CONTROL SET COMM_TIME=CURRENT TIMESTAMP,COMM_COUNT=COMM_COUNT+1," +
                                "RESTART_DATA='<byte[]>' WHERE PGMNAME_HOST='DBAJOD4 ' AND PGMID='COR0JOD4  ';"},

        });
    }
    public SQLSignatureTest(String id, String sql ) {
        this.id = id;
        this.sql = sql;
    }

    @Test
    public void test() throws IOException {
        Assert.assertEquals(id, SQLStatement.getSqlStatement(sql).getId() );
    }
}