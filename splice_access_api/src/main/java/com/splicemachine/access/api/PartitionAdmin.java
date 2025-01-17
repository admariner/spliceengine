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

package com.splicemachine.access.api;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.impl.sql.compile.HashableJoinStrategy;
import com.splicemachine.storage.Partition;
import com.splicemachine.storage.PartitionServer;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Scott Fines
 *         Date: 12/31/15
 */
public interface PartitionAdmin extends AutoCloseable{

    PartitionCreator newPartition() throws IOException;

    void deleteTable(String tableName) throws IOException;

    void splitTable(String tableName,byte[]... splitPoints) throws IOException;

    void splitRegion(byte[] regionName, byte[]... splitPoints) throws IOException;

    void mergeRegions(String regionName1, String regionName2) throws IOException;

    void close() throws IOException;

    Collection<PartitionServer> allServers() throws IOException;

    Iterable<? extends Partition> allPartitions(String tableName) throws IOException;

    Iterable<TableDescriptor> listTables() throws IOException;

    TableDescriptor[] getTableDescriptors(List<String> tables) throws IOException;

    void move(String partition,String server) throws IOException;

    TableDescriptor getTableDescriptor(String table) throws IOException;

    void snapshot(String snapshotName, String tableName) throws IOException;

    void deleteSnapshot(String snapshotName) throws IOException;

    default Set<String> listSnapshots() throws IOException {
        return new HashSet();
    }

    void restoreSnapshot(String snapshotName) throws IOException;

    void disableTable(String tableName) throws IOException;

    void enableTable(String tableName) throws IOException;

    void closeRegion(Partition partition) throws IOException, InterruptedException;

    void assign(Partition partition) throws IOException, InterruptedException;

    boolean tableExists(String tableName) throws IOException;

    List<byte[]> hbaseOperation(String table, String operation, byte[] bytes) throws IOException;

    void markDropped(long conglomId, long txn) throws IOException;

    void enableTableReplication(String tableName) throws IOException;

    void disableTableReplication(String tableName) throws IOException;

    List<ReplicationPeerDescription> getReplicationPeers() throws IOException;

    boolean replicationEnabled(String tableName) throws IOException;

    void setCatalogVersion(String conglomerate, String version) throws IOException;

    String getCatalogVersion(String conglomerate) throws StandardException;

    /**
     * Upgrade Script to update HBase Tables Priorities so that System tables are loaded with higher priorities
     *
     * @return number of tables upgraded
     */

    int upgradeTablePrioritiesFromList(List<String> conglomerateIdList) throws Exception;

    int getTableCount() throws IOException;

    default void createSITable(String tableName) throws StandardException {
        throw new RuntimeException("Not implemented");
    }

    default void cloneSnapshot(String snapshotName, String tableName) throws IOException {
        throw new UnsupportedOperationException("Operation not supported");
    }

    default void truncate(String tableName) throws IOException {
        throw new UnsupportedOperationException("Operation not supported");
    }
}
