/*
 * Copyright (c) 2012 - 2019 Splice Machine, Inc.
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

package com.splicemachine.replication;

import com.splicemachine.EngineDriver;
import com.splicemachine.db.iapi.error.PublicAPI;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.impl.drda.RemoteUser;
import com.splicemachine.derby.impl.storage.SpliceRegionAdmin;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.procedures.ProcedureUtils;
import org.apache.log4j.Logger;
import org.spark_project.guava.net.HostAndPort;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Created by jyuan on 2/8/19.
 */
public class ReplicationSystemProcedure {

    private static Logger LOG = Logger.getLogger(ReplicationSystemProcedure.class);

    public static void ADD_PEER(short peerId, String clusterKey, ResultSet[] resultSets) throws StandardException, SQLException {
        try {
            ReplicationManager replicationManager = EngineDriver.driver().manager().getReplicationManager();
            replicationManager.addPeer(peerId, clusterKey);
            resultSets[0] = ProcedureUtils.generateResult("Success", String.format("Added %s as peer %d", clusterKey, peerId));
        } catch (Exception e) {
            resultSets[0] = ProcedureUtils.generateResult("Error", e.getLocalizedMessage());
        }
    }

    public static void REMOVE_PEER(short peerId, ResultSet[] resultSets) throws StandardException, SQLException {
        try {
            ReplicationManager replicationManager = EngineDriver.driver().manager().getReplicationManager();
            replicationManager.removePeer(peerId);
            resultSets[0] = ProcedureUtils.generateResult("Success", String.format("Removed peer %d", peerId));
        } catch (Exception e) {
            resultSets[0] = ProcedureUtils.generateResult("Error", e.getLocalizedMessage());
        }
    }


    public static void ENABLE_PEER(short peerId, ResultSet[] resultSets) throws StandardException, SQLException {
        try {
            ReplicationManager replicationManager = EngineDriver.driver().manager().getReplicationManager();
            replicationManager.enablePeer(peerId);
            resultSets[0] = ProcedureUtils.generateResult("Success", String.format("Enabled peer %d", peerId));
        } catch (Exception e) {
            resultSets[0] = ProcedureUtils.generateResult("Error", e.getLocalizedMessage());
        }
    }

    public static void DISABLE_PEER(short peerId, ResultSet[] resultSets) throws StandardException, SQLException {
        try {
            ReplicationManager replicationManager = EngineDriver.driver().manager().getReplicationManager();
            replicationManager.disablePeer(peerId);
            resultSets[0] = ProcedureUtils.generateResult("Success", String.format("Disabled peer %d", peerId));
        } catch (Exception e) {
            resultSets[0] = ProcedureUtils.generateResult("Error", e.getLocalizedMessage());
        }
    }

    public static void ENABLE_TABLE_REPLICATION(String schemaName, String tableName, ResultSet[] resultSets) throws StandardException, SQLException {
        try {
            TableDescriptor td = SpliceRegionAdmin.getTableDescriptor(schemaName, tableName);
            ConglomerateDescriptor[] conglomerateDescriptors = td.getConglomerateDescriptors();
            ReplicationManager replicationManager = EngineDriver.driver().manager().getReplicationManager();
            for (ConglomerateDescriptor cd : conglomerateDescriptors) {
                long conglomerate = cd.getConglomerateNumber();
                replicationManager.enableTableReplication(Long.toString(conglomerate));
            }
            resultSets[0] = ProcedureUtils.generateResult(
                    "Success", String.format("Enabled replication for table %s.%s", schemaName, tableName));
        } catch (Exception e) {
            resultSets[0] = ProcedureUtils.generateResult("Error", e.getLocalizedMessage());
        }
    }

    public static void DISABLE_TABLE_REPLICATION(String schemaName, String tableName, ResultSet[] resultSets) throws StandardException, SQLException {
        try {
            TableDescriptor td = SpliceRegionAdmin.getTableDescriptor(schemaName, tableName);
            ConglomerateDescriptor[] conglomerateDescriptors = td.getConglomerateDescriptors();
            ReplicationManager replicationManager = EngineDriver.driver().manager().getReplicationManager();
            for (ConglomerateDescriptor cd : conglomerateDescriptors) {
                long conglomerate = cd.getConglomerateNumber();
                replicationManager.disableTableReplication(Long.toString(conglomerate));
            }
            resultSets[0] = ProcedureUtils.generateResult(
                    "Success", String.format("Disabled replication for table %s.%s", schemaName, tableName));
        } catch (Exception e) {
            resultSets[0] = ProcedureUtils.generateResult("Error", e.getLocalizedMessage());
        }
    }

    public static void SET_REPLICATION_ROLE(String role, ResultSet[] resultSets) throws StandardException, SQLException {
        try {
            ReplicationManager replicationManager = EngineDriver.driver().manager().getReplicationManager();
            replicationManager.setReplicationRole(role);
            resultSets[0] = ProcedureUtils.generateResult("Success", String.format("set replication role to '%s'", role));
        } catch (Exception e) {
            resultSets[0] = ProcedureUtils.generateResult("Error", e.getLocalizedMessage());
        }
    }

    public static void GET_REPLICATION_ROLE(ResultSet[] resultSets) throws StandardException, SQLException {
        try {
            ReplicationManager replicationManager = EngineDriver.driver().manager().getReplicationManager();
            String role = replicationManager.getReplicationRole();
            resultSets[0] = ProcedureUtils.generateResult("ROLE", String.format("%s", role));
        } catch (Exception e) {
            resultSets[0] = ProcedureUtils.generateResult("Error", e.getLocalizedMessage());
        }
    }
}