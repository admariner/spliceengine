package com.splicemachine.si;

import com.google.common.io.Closeables;
import com.splicemachine.constants.SIConstants;
import com.splicemachine.constants.bytes.BytesUtil;
import com.splicemachine.si.impl.TransactionStatus;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SIBrowser extends SIConstants {
    public static void main(String[] args) throws IOException {
        HTable transactionTable = null;
        try {
            transactionTable = new HTable(HBaseConfiguration.create(), TRANSACTION_TABLE);
            Scan scan = new Scan();
            ResultScanner scanner = transactionTable.getScanner(scan);
            Iterator<Result> results = scanner.iterator();
            Map<Long, Object> x = new HashMap<Long, Object>();
            Long idToFind = null;
            //idToFind = 62L;
            Result toFind = null;
            while (results.hasNext()) {
                Result r = results.next();
                final long id = getLong(r, Bytes.toBytes(TRANSACTION_ID_COLUMN));
                Long globalCommit = getLong(r, Bytes.toBytes(TRANSACTION_GLOBAL_COMMIT_TIMESTAMP_COLUMN));
                final long beginTimestamp = getLong(r, Bytes.toBytes(TRANSACTION_START_TIMESTAMP_COLUMN));
                TransactionStatus status = getStatus(r, Bytes.toBytes(TRANSACTION_STATUS_COLUMN));
                Long commitTimestamp = getLong(r, Bytes.toBytes(TRANSACTION_COMMIT_TIMESTAMP_COLUMN));
                Long counter = getLong(r, Bytes.toBytes(TRANSACTION_COUNTER_COLUMN));
                Long parent = getLong(r, TRANSACTION_PARENT_COLUMN_BYTES);
                Boolean writes = getBoolean(r, TRANSACTION_ALLOW_WRITES_COLUMN_BYTES);
                Boolean dependent = getBoolean(r, TRANSACTION_DEPENDENT_COLUMN_BYTES);
                Boolean readUncommitted = getBoolean(r, TRANSACTION_READ_UNCOMMITTED_COLUMN_BYTES);
                Boolean readCommitted = getBoolean(r, TRANSACTION_READ_COMMITTED_COLUMN_BYTES);
                String keepAliveValue = getTimestampString(r, Bytes.toBytes(TRANSACTION_KEEP_ALIVE_COLUMN));

                x.put(id, new Object[]{beginTimestamp, parent, writes, status, commitTimestamp, globalCommit, keepAliveValue, dependent, readUncommitted, readCommitted, counter});
                if (idToFind != null && beginTimestamp == idToFind) {
                    toFind = r;
                }
                //System.out.println(beginTimestamp + " " + status + " " + commitTimestamp);
            }

            if (toFind != null) {
                System.out.println("killing transaction " + idToFind);
                Put put = new Put(toFind.getRow());
                KeyValue kv = new KeyValue(toFind.getRow(), TRANSACTION_FAMILY_BYTES, TRANSACTION_STATUS_COLUMN_BYTES,
                        Bytes.toBytes(TransactionStatus.ERROR.ordinal()));
                put.add(kv);
                transactionTable.put(put);
            } else {
                final ArrayList<Long> list = new ArrayList<Long>(x.keySet());
                Collections.sort(list);
                System.out.println("transaction beginTimestamp parent writesAllowed status commitTimestamp globalCommitTimestamp keepAliveTimestamp dependent readUncommitted readCommitted counter");
                for (Long k : list) {
                    Object[] v = (Object[]) x.get(k);
                    System.out.println(k + " " + v[0] + " " + v[1] + " " + v[2] + " " + v[3] + " " + v[4] + " " + v[5] + " " + v[6] + " " + v[7] + " " + v[8] + " " + v[9] + " " + v[10]);
                }

//                dumpTable("conglomerates", "16");
                //dumpTable("SYCOLUMNS_INDEX2", "161");

                //dumpTable("p", "SPLICE_CONGLOMERATE");
//                dumpTable("p2", "1200");
            }
        } catch (IOException e) {
            throw e;
        } finally {
            Closeables.closeQuietly(transactionTable);

        }
    }

    private static TransactionStatus getStatus(Result r, byte[] qualifier) {
        final byte[] statusValue = r.getValue(TRANSACTION_FAMILY_BYTES, qualifier);
        TransactionStatus status = null;
        if (statusValue != null) {
            status = TransactionStatus.values()[Bytes.toInt(statusValue)];
        }
        return status;
    }

    private static Long getLong(Result r, byte[] qualifier) {
        final byte[] value = r.getValue(TRANSACTION_FAMILY_BYTES, qualifier);
        Long result = null;
        if (value != null) {
            result = Bytes.toLong(value);
        }
        return result;
    }

    private static String getTimestampString(Result r, byte[] qualifier) {
        final KeyValue keepAlive = r.getColumnLatest(TRANSACTION_FAMILY_BYTES, qualifier);
        String keepAliveValue = null;
        if (keepAlive != null) {
            keepAliveValue = new Timestamp(keepAlive.getTimestamp()).toString();
        }
        return keepAliveValue;
    }

    private static Boolean getBoolean(Result r, byte[] qualifier) {
        final byte[] writesValue = r.getValue(TRANSACTION_FAMILY_BYTES, qualifier);
        Boolean writes = null;
        if (writesValue != null) {
            writes = Bytes.toBoolean(writesValue);
        }
        return writes;
    }

    private static void dumpTable(String tableName, String tableId) throws IOException {
        HTable cTable = null;
        try {
            Scan scan;
            ResultScanner scanner;
            Iterator<Result> results;
            System.out.println("------------------");
            System.out.println(tableName);
            cTable = new HTable(HBaseConfiguration.create(), tableId);
            scan = new Scan();
            scan.setMaxVersions();
            scanner = cTable.getScanner(scan);
            results = scanner.iterator();
            int i = 0;
            while (results.hasNext()) {
                Result r = results.next();
                final byte[] row = r.getRow();
                final List<KeyValue> list = r.list();
                for (KeyValue kv : list) {
                    final byte[] f = kv.getFamily();
                    String family = Bytes.toString(f);
                    final byte[] q = kv.getQualifier();
                    final byte[] v = kv.getValue();
                    final long ts = kv.getTimestamp();
                    if (Arrays.equals(SNAPSHOT_ISOLATION_FAMILY_BYTES, f)
                            && Arrays.equals(SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES, q)) {
                        Long timestamp = null;
                        if (v.length > 1) {
                            timestamp = Bytes.toLong(v);
                        }
                        System.out.println("timestamp " + timestamp + " @ " + ts);
                    } else {
                        String v2 = Bytes.toString(v);
                        char qualifier = (char) q[0];
                        String byteValue = "" + v.length + "[";
                        for (byte b : v) {
                            if (b >= 32 && b <= 122) {
                                char c = (char) b;
                                byteValue += c;
                            } else {
                                byteValue += b;
                                byteValue += " ";
                            }
                        }
                        byteValue += "]";
                        System.out.println(Bytes.toString(row) + " " + family + " " + qualifier + " @ " + ts + " " + byteValue + " " + v2);
                    }
                }
                i++;
                System.out.println(i + " " + row + " " + BytesUtil.debug(row));
            }
        } catch (IOException e) {
            throw e;
        } finally {
            Closeables.closeQuietly(cTable);
        }
    }
}
