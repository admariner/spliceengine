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

package com.splicemachine.derby.utils;

import com.splicemachine.EngineDriver;
import com.splicemachine.access.api.*;
import com.splicemachine.access.configuration.SQLConfiguration;
import com.splicemachine.access.util.NetworkUtils;
import com.splicemachine.db.catalog.SystemProcedures;
import com.splicemachine.db.iapi.db.PropertyInfo;
import com.splicemachine.db.iapi.error.PublicAPI;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.PropertyHelper;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.services.monitor.ModuleFactory;
import com.splicemachine.db.iapi.services.monitor.Monitor;
import com.splicemachine.db.iapi.services.property.PropertyUtil;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.conn.Authorizer;
import com.splicemachine.db.iapi.sql.conn.ConnectionUtil;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.depend.DependencyManager;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.*;
import com.splicemachine.db.iapi.sql.execute.ExecPreparedStatement;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.iapi.types.*;
import com.splicemachine.db.impl.drda.RemoteUser;
import com.splicemachine.db.impl.jdbc.*;
import com.splicemachine.db.impl.jdbc.ResultSetBuilder.RowBuilder;
import com.splicemachine.db.impl.services.uuid.BasicUUID;
import com.splicemachine.db.impl.sql.GenericActivationHolder;
import com.splicemachine.db.impl.sql.GenericColumnDescriptor;
import com.splicemachine.db.impl.sql.GenericPreparedStatement;
import com.splicemachine.db.impl.sql.catalog.*;
import com.splicemachine.db.impl.sql.execute.IteratorNoPutResultSet;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.ddl.DDLMessage;
import com.splicemachine.derby.ddl.DDLUtils;
import com.splicemachine.derby.iapi.sql.execute.RunningOperation;
import com.splicemachine.derby.impl.store.access.SpliceTransactionManager;
import com.splicemachine.derby.stream.ActivationHolder;
import com.splicemachine.hbase.JMXThreadPool;
import com.splicemachine.hbase.jmx.JMXUtils;
import com.splicemachine.pipeline.ErrorState;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.pipeline.SimpleActivation;
import com.splicemachine.procedures.ProcedureUtils;
import com.splicemachine.procedures.external.DistributedGetSchemaExternalJob;
import com.splicemachine.procedures.external.GetSchemaExternalResult;
import com.splicemachine.protobuf.ProtoUtil;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.storage.*;
import com.splicemachine.system.CsvOptions;
import com.splicemachine.utils.DbEngineUtils;
import com.splicemachine.utils.Pair;
import com.splicemachine.utils.SpliceLogUtils;
import com.splicemachine.utils.logging.Logging;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang.SerializationUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import splice.com.google.common.collect.Lists;
import splice.com.google.common.net.HostAndPort;

import javax.management.MalformedObjectNameException;
import javax.management.remote.JMXConnector;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.splicemachine.db.iapi.sql.StatementType.*;
import static com.splicemachine.db.shared.common.reference.SQLState.*;

/**
 * @author Jeff Cunningham
 *         <p/>
 *         Date: 12/9/13
 */
@SuppressWarnings("unused")
public class SpliceAdmin extends BaseAdminProcedures{
    private static Logger LOG=Logger.getLogger(SpliceAdmin.class);

    @SuppressFBWarnings("IIL_PREPARE_STATEMENT_IN_LOOP") // intentional (different servers)
    public static void SYSCS_SET_LOGGER_LEVEL(final String loggerName, final String logLevel) throws SQLException{
        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString())) {
                try (PreparedStatement ps = connection.prepareStatement("call SYSCS_UTIL.SYSCS_SET_LOGGER_LEVEL_LOCAL(?, ?)")) {
                    ps.setString(1, loggerName);
                    ps.setString(2, logLevel);
                    ps.execute();
                }
            }
        }
    }
    public static void SYSCS_SET_LOGGER_LEVEL_LOCAL(final String loggerName,final String logLevel) throws SQLException{
        Logging logging;
        try {
            logging = JMXUtils.getLocalMBeanProxy(JMXUtils.LOGGING_MANAGEMENT, Logging.class);
        }catch(MalformedObjectNameException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
        logging.setLoggerLevel(loggerName,logLevel);
    }

    public static void SYSCS_GET_LOGGER_LEVEL_LOCAL(final String loggerName,final ResultSet[] resultSet) throws SQLException{
        Logging logging;
        try {
            logging = JMXUtils.getLocalMBeanProxy(JMXUtils.LOGGING_MANAGEMENT, Logging.class);
        }catch(MalformedObjectNameException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        StringBuilder sb=new StringBuilder("select * from (values ");
        String loggerLevel=logging.getLoggerLevel(loggerName);
        sb.append(String.format("('%s')",loggerLevel));
        sb.append(") foo (logLevel)");
        resultSet[0]=executeStatement(sb);
    }

    @SuppressFBWarnings("IIL_PREPARE_STATEMENT_IN_LOOP") // intentional (different servers)
    public static void SYSCS_GET_CACHED_STATEMENTS(final ResultSet[] resultSet) throws SQLException{
        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        int maxLength = 0;
        int serverNameLength = 0;
        Map<String, List<Pair<String,Timestamp>>> cachedServerToStatementsMap = new HashMap<>();
        for (HostAndPort server : servers) {
            List<Pair<String, Timestamp>> statements = new ArrayList<>();
            try (Connection connection = RemoteUser.getConnection(server.toString());
                 PreparedStatement ps = connection.prepareStatement("call SYSCS_UTIL.SYSCS_GET_CACHED_STATEMENTS_LOCAL()");
                 ResultSet rs = ps.executeQuery()) {
                while(rs.next()) {
                    String statement = rs.getString(1);
                    maxLength = Math.max(maxLength, statement.length());
                    Timestamp timestamp = rs.getTimestamp(2);
                    statements.add(new Pair<String, Timestamp>(statement, timestamp));
                }
            }
            serverNameLength = Math.max(serverNameLength, server.toString().length());
            cachedServerToStatementsMap.put(server.toString(), statements);
        }
        ResultHelper resultHelper = new ResultHelper();
        ResultHelper.VarcharColumn col1 = resultHelper.addVarchar("SERVER", Math.max(serverNameLength, 20));
        ResultHelper.VarcharColumn col2 = resultHelper.addVarchar("CACHED_STATEMENT", Math.max(maxLength, 20));
        ResultHelper.TimestampColumn col3 = resultHelper.addTimestamp("ADDITION_TIMESTAMP", 30);

        for(Map.Entry<String, List<Pair<String, Timestamp>>> serverToStatement : cachedServerToStatementsMap.entrySet()) {
            for(Pair<String, Timestamp> pair : serverToStatement.getValue()) {
                resultHelper.newRow();
                col1.set(serverToStatement.getKey());
                col2.set(pair.getFirst());
                try {
                    col3.set(new DateTime(pair.getSecond()));
                } catch (StandardException se) {
                    throw PublicAPI.wrapStandardException(se);
                }
            }
        }
        resultSet[0] = resultHelper.getResultSet();
    }

    public static void SYSCS_GET_CACHED_STATEMENTS_LOCAL(final ResultSet[] resultSet) throws SQLException {
        LanguageConnectionContext lcc = (LanguageConnectionContext) ContextService.getContext(LanguageConnectionContext.CONTEXT_ID);
        assert lcc != null;
        DataDictionary dd = lcc.getDataDictionary();
        List<Pair<String, Timestamp>> result;

        try {
            result = dd.getDataDictionaryCache().cachedStatements();
        } catch (StandardException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        ResultHelper resultHelper = new ResultHelper();
        ResultHelper.VarcharColumn col1 = resultHelper.addVarchar("CACHED_STATEMENT", Math.max(result.stream()
                                                                                                .map(Pair::getFirst)
                                                                                                .max(Comparator.comparingInt(String::length))
                                                                                                .orElse("")
                                                                                                .length(), 20));
        ResultHelper.TimestampColumn col2 = resultHelper.addTimestamp("ADDITION_TIMESTAMP", 30);

        for(Pair<String, Timestamp> cachedStatement : result) {
            resultHelper.newRow();
            col1.set(cachedStatement.getFirst());
            try {
                col2.set(new DateTime(cachedStatement.getSecond()));
            } catch (StandardException se) {
                throw PublicAPI.wrapStandardException(se);
            }
        }
        resultSet[0] = resultHelper.getResultSet();
    }

    @SuppressFBWarnings("IIL_PREPARE_STATEMENT_IN_LOOP") // intentional (different servers)
    public static void SYSCS_GET_LOGGER_LEVEL(final String loggerName,final ResultSet[] resultSet) throws SQLException{
        List<String> loggerLevels = new ArrayList<>();

        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString())) {
                try (PreparedStatement ps = connection.prepareStatement("call SYSCS_UTIL.SYSCS_GET_LOGGER_LEVEL_LOCAL(?)")) {
                    ps.setString(1, loggerName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            loggerLevels.add(rs.getString(1));
                        }
                    }
                };
            }
        }
        List<ExecRow> rows = new ArrayList<>();
        for (String logger : loggerLevels) {
            ExecRow row = new ValueRow(1);
            row.setColumn(1, new SQLVarchar(logger));
            rows.add(row);
        }

        GenericColumnDescriptor[] descriptors = {
                new GenericColumnDescriptor("LOG_LEVEL", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 120)),
        };

        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, descriptors, lastActivation);
        try {
            resultsToWrap.openCore();
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
        resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
    }


    public static void SYSCS_GET_LOGGERS_LOCAL(final ResultSet[] resultSet) throws SQLException {
        Logging logging;
        try {
            logging = JMXUtils.getLocalMXBeanProxy(JMXUtils.LOGGING_MANAGEMENT, Logging.class);
        }catch(MalformedObjectNameException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        StringBuilder sb=new StringBuilder("select * from (values ");
        HashSet<String> loggerNames = new HashSet<>();
        loggerNames.addAll(logging.getLoggerNames());

        ArrayList<String> loggers = new ArrayList<>(loggerNames);
        Collections.sort(loggers);
        for(String logger : loggers){
            sb.append(String.format("('%s')",logger));
            sb.append(", ");
        }
        if(sb.charAt(sb.length()-2)==','){
            sb.setLength(sb.length()-2);
        }
        sb.append(") foo (spliceLogger)");
        resultSet[0]=executeStatement(sb);
    }

    public static void SYSCS_GET_LOGGERS(final ResultSet[] resultSet) throws SQLException{
        Set<String> loggers = new HashSet<>();

        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString())) {
                try (Statement stmt = connection.createStatement()) {
                    try (ResultSet rs = stmt.executeQuery("call SYSCS_UTIL.SYSCS_GET_LOGGERS_LOCAL()")) {
                        while (rs.next()) {
                            loggers.add(rs.getString(1));
                        }
                    }
                }
            }
        }

        ArrayList<String> loggerNames = new ArrayList<>(loggers);
        Collections.sort(loggerNames);

        List<ExecRow> rows = new ArrayList<>();
        for (String logger : loggerNames) {
            ExecRow row = new ValueRow(1);
            row.setColumn(1, new SQLVarchar(logger));
            rows.add(row);
        }

        GenericColumnDescriptor[] descriptors = {
                new GenericColumnDescriptor("SPLICE_LOGGER", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 120)),
        };

        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, descriptors, lastActivation);
        try {
            resultsToWrap.openCore();
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
        resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
    }

    public static void SYSCS_GET_ACTIVE_SERVERS(ResultSet[] resultSet) throws SQLException{
        StringBuilder sb=new StringBuilder("select * from (values ");
        int i=0;
        for(PartitionServer serverName : getLoad()){
            if(i!=0){
                sb.append(", ");
            }
            sb.append(String.format("('%s',%d,%d)",
                    serverName.getHostname(),
                    serverName.getPort(),
                    serverName.getStartupTimestamp()));
            i++;
        }
        sb.append(") foo (hostname, port, startcode)");
        resultSet[0]=executeStatement(sb);
    }

    public static void SYSCS_GET_VERSION_INFO(final ResultSet[] resultSet) throws SQLException{
        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        List<ExecRow> rows = new ArrayList<>();
        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString())) {
                try (Statement stmt = connection.createStatement()) {
                    try (ResultSet rs = stmt.executeQuery("call SYSCS_UTIL.SYSCS_GET_VERSION_INFO_LOCAL()")) {
                        while (rs.next()) {
                            ExecRow row = new ValueRow(5);

                            row.setColumn(1, new SQLVarchar(rs.getString(1)));
                            row.setColumn(2, new SQLVarchar(rs.getString(2)));
                            row.setColumn(3, new SQLVarchar(rs.getString(3)));
                            row.setColumn(4, new SQLVarchar(rs.getString(4)));
                            row.setColumn(5, new SQLVarchar(rs.getString(5)));
                            rows.add(row);
                        }
                    }
                }
            }
        }

        GenericColumnDescriptor[] descriptors = {
                new GenericColumnDescriptor("HOSTNAME", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 120)),
                new GenericColumnDescriptor("RELEASE", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 40)),
                new GenericColumnDescriptor("IMPLEMENTATION_VERSION", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 40)),
                new GenericColumnDescriptor("BUILD_TIME", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 40)),
                new GenericColumnDescriptor("URL", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 120)),
        };


        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, descriptors, lastActivation);
        try {
            resultsToWrap.openCore();
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
        resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
    }

    public static void SYSCS_GET_VERSION_INFO_LOCAL(final ResultSet[] resultSet) throws SQLException{
        DatabaseVersion version;
        try {
            version = JMXUtils.getLocalMBeanProxy(JMXUtils.SPLICEMACHINE_VERSION, DatabaseVersion.class);
        }catch(MalformedObjectNameException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        } catch (IOException e) {
                throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        SConfiguration config=EngineDriver.driver().getConfiguration();
        String hostname = NetworkUtils.getHostname(config);
        int port = config.getNetworkBindPort();

        StringBuilder sb=new StringBuilder("select * from (values ");
        sb.append(String.format("('%s','%s','%s','%s','%s')",
                hostname + ":" + port,
                version.getRelease(),
                version.getImplementationVersion(),
                version.getBuildTime(),
                version.getURL()));
        sb.append(") foo (hostname, release, implementationVersion, buildTime, url)");
        resultSet[0]=executeStatement(sb);
    }

    public static void SYSCS_GET_WRITE_INTAKE_INFO(ResultSet[] resultSets) throws SQLException{
        PipelineAdmin.SYSCS_GET_WRITE_INTAKE_INFO(resultSets);
    }

    private static final ResultColumnDescriptor[] EXEC_SERVICE_COLUMNS= {
            new GenericColumnDescriptor("Host",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("CurrentPoolSize",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER)),
            new GenericColumnDescriptor("CurrentlyAvailableThreads",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER)),
            new GenericColumnDescriptor("CurrentlyExecutingThreads",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER)),
            new GenericColumnDescriptor("LargestPoolSize",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER)),
            new GenericColumnDescriptor("MaximumPoolSize",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER)),
            new GenericColumnDescriptor("PendingTasks",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER)),
            new GenericColumnDescriptor("ThreadKeepAliveTime",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("TotalCompletedTasks",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("TotalRejectedTasks", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("TotalScheduledTasks",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
    };
    private static final ResultColumnDescriptor[] MANAGED_CACHE_COLUMNS= {
            new GenericColumnDescriptor("Host",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("name",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("Size",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("MissCount",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("MissRate",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DOUBLE)),
            new GenericColumnDescriptor("HitCount",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("HitRate",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DOUBLE)),
    };
    private static final ResultColumnDescriptor[] TOTAL_MANAGED_CACHE_COLUMNS= {
            new GenericColumnDescriptor("name",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("Size",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("MissCount",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("MissRate",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DOUBLE)),
            new GenericColumnDescriptor("HitCount",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("HitRate",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DOUBLE)),
    };

    public static void SYSCS_GET_EXEC_SERVICE_INFO(final ResultSet[] resultSet) throws SQLException {
        operate(new BaseAdminProcedures.JMXServerOperation() {
            @Override
            public void operate(List<Pair<String, JMXConnector>> connections) throws MalformedObjectNameException, IOException, SQLException {
                List<JMXThreadPool> executorService = JMXUtils.getExecutorService(connections);
                ExecRow template = buildExecRow(EXEC_SERVICE_COLUMNS);
                List<ExecRow> rows = Lists.newArrayListWithExpectedSize(executorService.size());
                int i=0;
                for (JMXThreadPool ex : executorService) {
                    template.resetRowArray();
                    DataValueDescriptor[] dvds = template.getRowArray();
                    try{
                        dvds[0].setValue(connections.get(i).getFirst());
                        dvds[1].setValue(ex.getCurrentPoolSize());
                        dvds[2].setValue(ex.getCurrentlyAvailableThreads());
                        dvds[3].setValue(ex.getCurrentlyExecutingThreads());
                        dvds[4].setValue(ex.getLargestPoolSize());
                        dvds[5].setValue(ex.getMaximumPoolSize());
                        dvds[6].setValue(ex.getPendingTasks());
                        dvds[7].setValue(ex.getThreadKeepAliveTime());
                        dvds[8].setValue(ex.getTotalCompletedTasks());
                        dvds[9].setValue(ex.getTotalRejectedTasks());
                        dvds[10].setValue(ex.getTotalScheduledTasks());
                    }catch(StandardException se){
                        throw PublicAPI.wrapStandardException(se);
                    }
                    rows.add(template.getClone());
                    i++;
                }

                EmbedConnection defaultConn = (EmbedConnection) getDefaultConn();
                Activation lastActivation = defaultConn.getLanguageConnection().getLastActivation();
                IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, EXEC_SERVICE_COLUMNS,lastActivation);
                try {
                    resultsToWrap.openCore();
                } catch (StandardException e) {
                    throw PublicAPI.wrapStandardException(e);
                }
                EmbedResultSet ers = new EmbedResultSet40(defaultConn, resultsToWrap,false,null,true);
                resultSet[0] = ers;
            }
        });
    }

    //For cache
    public static void SYSCS_GET_CACHE_INFO(final ResultSet[] resultSet) throws SQLException {
        operate(new BaseAdminProcedures.JMXServerOperation() {
            @Override
            public void operate(List<Pair<String, JMXConnector>> connections) throws MalformedObjectNameException, IOException, SQLException {
                List<ManagedCacheMBean> managedCaches = JMXUtils.getManagedCache(connections, DataDictionaryCache.getCacheNames());
                ExecRow template = buildExecRow(MANAGED_CACHE_COLUMNS);
                List<ExecRow> rows = Lists.newArrayListWithExpectedSize(managedCaches.size());
                int i=0;
                int j=0;
                for (ManagedCacheMBean ex : managedCaches) {
                    template.resetRowArray();
                    DataValueDescriptor[] dvds = template.getRowArray();
                    try{
                        dvds[0].setValue(connections.get(j).getFirst());
                        dvds[1].setValue(DataDictionaryCache.getCacheNames().get(i%DataDictionaryCache.getCacheNames().size()));
                        dvds[2].setValue(ex.getSize());
                        dvds[3].setValue(ex.getMissCount());
                        dvds[4].setValue(ex.getMissRate());
                        dvds[5].setValue(ex.getHitCount());
                        dvds[6].setValue(ex.getHitRate());
                    }catch(StandardException se){
                        throw PublicAPI.wrapStandardException(se);
                    }
                    rows.add(template.getClone());
                    i++;
                    j = i >= DataDictionaryCache.getCacheNames().size()?1:0;
                }

                EmbedConnection defaultConn = (EmbedConnection) getDefaultConn();
                Activation lastActivation = defaultConn.getLanguageConnection().getLastActivation();
                IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, MANAGED_CACHE_COLUMNS,lastActivation);
                try {
                    resultsToWrap.openCore();
                } catch (StandardException e) {
                    throw PublicAPI.wrapStandardException(e);
                }
                EmbedResultSet ers = new EmbedResultSet40(defaultConn, resultsToWrap,false,null,true);
                resultSet[0] = ers;
            }
        });
    }

    //For total cache stats
    public static void SYSCS_GET_TOTAL_CACHE_INFO(final ResultSet[] resultSet) throws SQLException {
        operate(new BaseAdminProcedures.JMXServerOperation() {
            @Override
            public void operate(List<Pair<String, JMXConnector>> connections) throws MalformedObjectNameException, IOException, SQLException {
                List<ManagedCacheMBean> tm = JMXUtils.getTotalManagedCache(connections);
                ExecRow template = buildExecRow(TOTAL_MANAGED_CACHE_COLUMNS);
                List<ExecRow> rows = Lists.newArrayListWithExpectedSize(tm.size());
                int i = 0;
                for(ManagedCacheMBean mBean : tm) {
                    template.resetRowArray();
                    DataValueDescriptor[] dvds = template.getRowArray();
                    try {
                        dvds[0].setValue(connections.get(i).getFirst());
                        dvds[1].setValue(mBean.getSize());
                        dvds[2].setValue(mBean.getMissCount());
                        dvds[3].setValue(mBean.getMissRate());
                        dvds[4].setValue(mBean.getHitCount());
                        dvds[5].setValue(mBean.getHitRate());
                    } catch (StandardException se) {
                        throw PublicAPI.wrapStandardException(se);
                    }
                    i++;
                    rows.add(template.getClone());
                }
                EmbedConnection defaultConn = (EmbedConnection) getDefaultConn();
                Activation lastActivation = defaultConn.getLanguageConnection().getLastActivation();
                IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, TOTAL_MANAGED_CACHE_COLUMNS,lastActivation);
                try {
                    resultsToWrap.openCore();
                } catch (StandardException e) {
                    throw PublicAPI.wrapStandardException(e);
                }
                EmbedResultSet ers = new EmbedResultSet40(defaultConn, resultsToWrap,false,null,true);
                resultSet[0] = ers;
            }
        });
    }


    public static void SYSCS_KILL_TRANSACTION(final long transactionId) throws SQLException{
        /*
         * We have to leave this method in place, because Derby will actually STORE a string
         * reference to this method in database tables--removing it will therefore break
         * backwards compatibility. However, the logic has been moved to TransactionAdmin
         */
        TransactionAdmin.killTransaction(transactionId);
    }

    public static void SYSCS_KILL_STALE_TRANSACTIONS(final long maximumTransactionId) throws SQLException{
        /*
         * We have to leave this method in place, because Derby will actually STORE a string
         * reference to this method in database tables--removing it will therefore break
         * backwards compatibility. However, the logic has been moved to TransactionAdmin
         */
        TransactionAdmin.killAllActiveTransactions(maximumTransactionId);
    }

    public static void SYSCS_GET_REGION_SERVER_TASK_INFO(final ResultSet[] resultSet) throws StandardException, SQLException {
        // Be explicit about throwing runtime exception if this deleted procedure is called,
        // since it was so commonly used prior to k2. This could only happen if someone
        // upgraded from Lassen to K2 but didn't run the required upgrade script or run
        // SYSCS_UPDATE_ALL_SYSTEM_PROCEDURES.
        throw new UnsupportedOperationException(
                "SYSCS_GET_REGION_SERVER_TASK_INFO has been permanently desupported in Splice Machine."
        );
    }

    public static void SYSCS_GET_REGION_SERVER_CONFIG_INFO(final String configRoot,final int showDisagreementsOnly,final ResultSet[] resultSet) throws StandardException, SQLException{
        Map<String,DatabaseVersion> dbVersions = EngineDriver.driver().dbAdministrator().getClusterDatabaseVersions();
        boolean matchName=(configRoot!=null && !configRoot.isEmpty());
        int hostIdx=0;
        String hostName;
        ResultSetBuilder rsBuilder;
        RowBuilder rowBuilder;

        try{
            rsBuilder=new ResultSetBuilder();
            rsBuilder.getColumnBuilder()
                    .addColumn("HOST_NAME",Types.VARCHAR,32)
                    .addColumn("CONFIG_NAME",Types.VARCHAR,128)
                    .addColumn("CONFIG_VALUE",Types.VARCHAR,128);

            rowBuilder=rsBuilder.getRowBuilder();
            // We arbitrarily pick DatabaseVersion MBean even though
            // we do not fetch anything from it. We just use it as our
            // mechanism for our region server context.
            SortedMap<String, String> configMap=new TreeMap<>();

            SConfiguration config=EngineDriver.driver().getConfiguration();
            Map<String,Object> configRootMap = config.getConfigMap();

            for(Map.Entry<String,DatabaseVersion> databaseVersion : dbVersions.entrySet()){
                hostName=databaseVersion.getKey();
                configMap.clear();
                for(Map.Entry<String,Object> conf : configRootMap.entrySet()){
                    configMap.put(conf.getKey(), conf.getValue().toString());
                }

                // Iterate through sorted configs and add to result set
                Set<Entry<String, String>> configSet=configMap.entrySet();
                for(Entry<String, String> configEntry : configSet){
                    rowBuilder.getDvd(0).setValue(hostName);
                    rowBuilder.getDvd(1).setValue(configEntry.getKey());
                    rowBuilder.getDvd(2).setValue(configEntry.getValue());
                    rowBuilder.addRow();
                }
                hostIdx++;
            }

            resultSet[0]=rsBuilder.buildResultSet((EmbedConnection)getDefaultConn());

            configMap.clear();

        }catch(StandardException se){
            throw PublicAPI.wrapStandardException(se);
        }
    }

    public static void SYSCS_GET_REGION_SERVER_STATS_INFO(final ResultSet[] resultSet) throws SQLException{
        Collection<PartitionServer> load=getLoad();

        ExecRow template=new ValueRow(6);
        template.setRowArray(new DataValueDescriptor[]{
                new SQLVarchar(),new SQLInteger(),new SQLLongint(),new SQLLongint(),
                new SQLLongint(),new SQLLongint(),new SQLReal()
        });
        int i=0;
        List<ExecRow> rows=new ArrayList<>(load.size());
        for(PartitionServer ps:load){
            template.resetRowArray();
            DataValueDescriptor[] dvds=template.getRowArray();
            try{
                PartitionServerLoad psLoad = ps.getLoad();
                int idx=0;
                dvds[idx++].setValue(ps.getHostname());
                Set<PartitionLoad> partitionLoads=psLoad.getPartitionLoads();
                long storeFileSize = 0L;
                for(PartitionLoad pLoad:partitionLoads){
                    storeFileSize+=pLoad.getStorefileSize();
                }
                dvds[idx++].setValue(partitionLoads.size());
                dvds[idx++].setValue(storeFileSize);
                dvds[idx++].setValue(psLoad.totalWriteRequests());
                dvds[idx++].setValue(psLoad.totalReadRequests());
                dvds[idx++].setValue(psLoad.totalRequests());

            }catch(StandardException se){
                throw PublicAPI.wrapStandardException(se);
            }catch(Exception e){
                throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
            }
            rows.add(template.getClone());
            i++;
        }
        ResultColumnDescriptor[] columnInfo=new ResultColumnDescriptor[6];
        int idx=0;
        columnInfo[idx++]=new GenericColumnDescriptor("host",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR));
        columnInfo[idx++]=new GenericColumnDescriptor("regionCount",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT));
        columnInfo[idx++]=new GenericColumnDescriptor("storeFileSize",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT));
        columnInfo[idx++]=new GenericColumnDescriptor("writeRequestCount",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT));
        columnInfo[idx++]=new GenericColumnDescriptor("readRequestCount",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT));
        columnInfo[idx++]=new GenericColumnDescriptor("totalRequestCount",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT));
        EmbedConnection defaultConn=(EmbedConnection)BaseAdminProcedures.getDefaultConn();
        Activation lastActivation=defaultConn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap=new IteratorNoPutResultSet(rows,columnInfo,lastActivation);
        try{
            resultsToWrap.openCore();
        }catch(StandardException e){
            throw PublicAPI.wrapStandardException(e);
        }
        EmbedResultSet ers=new EmbedResultSet40(defaultConn,resultsToWrap,false,null,true);

        resultSet[0]=ers;
    }

    public static void SYSCS_GET_REQUESTS(ResultSet[] resultSet) throws SQLException{
        StringBuilder sb=new StringBuilder("select * from (values ");
        int i=0;
        Collection<PartitionServer> servers=getLoad();
        boolean isFirst=true;
        for(PartitionServer server : servers){
            if(!isFirst) sb=sb.append(",");
            else isFirst=false;
            try{
                sb=sb.append("('").append(server.getHostname()).append("'")
                        .append(",").append(server.getPort())
                        .append(",").append(server.getLoad().totalRequests())
                        .append(")");
            }catch(IOException e){
                throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
            }

        }
        sb.append(") foo (hostname, port, totalRequests)");
        resultSet[0]=SpliceAdmin.executeStatement(sb);
    }

    public static void SYSCS_PERFORM_MAJOR_COMPACTION_ON_SCHEMA(String schemaName) throws SQLException{
        SYSCS_PERFORM_MAJOR_COMPACTION_ON_TABLE(schemaName,null);
    }

    /**
     * Perform a major compaction
     *
     * @param schemaName the name of the database schema to discriminate the tablename.  If null,
     *                   defaults to the 'APP' schema.
     * @param tableName  the table name on which to run compaction. If null, compaction will be run
     *                   on all tables in the schema.  Note that a given tablename can produce more
     *                   than one table, if the table has an index, for instance.
     * @throws SQLException
     */
    public static void SYSCS_PERFORM_MAJOR_COMPACTION_ON_TABLE(String schemaName, String tableName) throws SQLException {
        LanguageConnectionContext lcc = (LanguageConnectionContext) ContextService.getContext(LanguageConnectionContext.CONTEXT_ID);
        assert lcc != null;
        DataDictionary dd = lcc.getDataDictionary();

        schemaName = EngineUtils.validateSchema(schemaName);
        if (tableName != null) {
            tableName = EngineUtils.validateTable(tableName);
            try {
                TableDescriptor td = DataDictionaryUtils.getTableDescriptor(lcc, schemaName, tableName);
                tableName = td.getName();
            }
            catch (StandardException e) {
                throw new SQLException(e);
            }
        }

        // sys query for table conglomerate for in schema
        PartitionFactory tableFactory = SIDriver.driver().getTableFactory();
        schemaName = EngineUtils.validateSchema(schemaName);
        tableName = tableName == null ? null : EngineUtils.validateTable(tableName);
        for (long conglomID : getConglomNumbers(getDefaultConn(), schemaName, tableName)) {
            try {
                ConglomerateDescriptor cd = dd.getConglomerateDescriptor(conglomID);
                if (cd != null) {
                    TableDescriptor td = dd.getTableDescriptor(cd.getTableID());
                    // External tables can't be compacted.
                    if (td != null && td.isExternal())
                        continue;
                }
            } catch (StandardException e) {
                throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
            }
            try (Partition partition = tableFactory.getTable(Long.toString(conglomID))) {
                partition.flush();
                partition.compact(true);
            } catch (IOException e) {
                throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
            }
        }
    }

    /**
     * Perform a flush on a table
     *
     * @param schemaName the name of the database schema to discriminate the tablename.  If null,
     *                   defaults to the 'APP' schema.
     * @param tableName  the table name on which to run flush.
     * @throws SQLException
     */
    public static void SYSCS_FLUSH_TABLE(String schemaName,String tableName) throws SQLException{
        LanguageConnectionContext lcc = (LanguageConnectionContext) ContextService.getContext(LanguageConnectionContext.CONTEXT_ID);
        assert lcc != null;

        schemaName = EngineUtils.validateSchema(schemaName);
        tableName = EngineUtils.validateTable(tableName);

        try {
            TableDescriptor td = DataDictionaryUtils.getTableDescriptor(lcc, schemaName, tableName);
            tableName = td.getName();
        }
        catch (StandardException e) {
            throw new SQLException(e);
        }

        // sys query for table conglomerate for in schema
        PartitionFactory tableFactory=SIDriver.driver().getTableFactory();
        schemaName = EngineUtils.validateSchema(schemaName);
        tableName = tableName == null ? null : EngineUtils.validateTable(tableName);
        for(long conglomID : getConglomNumbers(getDefaultConn(),schemaName,tableName)){
            try(Partition partition=tableFactory.getTable(Long.toString(conglomID))){
                partition.flush();
            }catch(IOException e){
                throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
            }
        }
    }

    /**
     * Delete a row from the data dictionary
     *
     * @param conglomerateId the conglomerateId to delete the row from
     * @param rowId  the row id to delete
     * @throws SQLException
     */
    public static void SYSCS_DICTIONARY_DELETE(int conglomerateId, String rowId) throws SQLException{
        if (rowId == null)
            throw new SQLException(StandardException.newException(SQLState.PARAMETER_CANNOT_BE_NULL, "rowId"));

        PartitionFactory tableFactory=SIDriver.driver().getTableFactory();
        LanguageConnectionContext lcc=ConnectionUtil.getCurrentLCC();
        TransactionController tc=lcc.getTransactionExecute();
        TxnOperationFactory opFactory = SIDriver.driver().getOperationFactory();

        try {
            tc.elevate("dictionary");
            DataMutation dataMutation = opFactory.newDataDelete(((SpliceTransactionManager) tc).getActiveStateTxn(), Hex.decodeHex(rowId.toCharArray()));
            try(Partition partition=tableFactory.getTable(Long.toString(conglomerateId))) {
                partition.mutate(dataMutation);
            }
        } catch (DecoderException e) {
            throw new SQLException(StandardException.newException(SQLState.PARAMETER_IS_NOT_HEXADECIMAL, rowId));
        } catch(IOException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        } catch (StandardException e) {
            throw PublicAPI.wrapStandardException(e);
        }
    }
    public static long getOldestActiveTransaction() throws SQLException {
        long oldestActiveTransaction = Long.MAX_VALUE;
        try {
            PartitionAdmin pa = SIDriver.driver().getTableFactory().getAdmin();
            ExecutorService executorService = SIDriver.driver().getExecutorService();
            Collection<PartitionServer> servers = pa.allServers();

            List<Future<Long>> futures = Lists.newArrayList();
            for (PartitionServer server : servers) {
                GetOldestActiveTransactionTask task = SIDriver.driver().getOldestActiveTransactionTaskFactory().get(
                        server.getHostname(), server.getPort(), server.getStartupTimestamp());
                futures.add(executorService.submit(task));
            }
            for (Future<Long> future : futures) {
                long localOldestActive = future.get();
                if (localOldestActive < oldestActiveTransaction)
                    oldestActiveTransaction = localOldestActive;
            }
        } catch (IOException | InterruptedException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        } catch (ExecutionException e) {
            LOG.error("Could not fetch oldestActiveTransaction", e);
            throw PublicAPI.wrapStandardException(StandardException.newException(
                    MISSING_COPROCESSOR_SERVICE,
                    "com.splicemachine.si.data.hbase.coprocessor.SpliceRSRpcServices",
                    "hbase.coprocessor.regionserver.classes"));
        }
        return oldestActiveTransaction;
    }

    public static void VACUUM() throws SQLException{
        long oldestActiveTransaction = getOldestActiveTransaction();
        Vacuum vacuum = new Vacuum(getDefaultConn());
        try{
            vacuum.vacuumDatabase(oldestActiveTransaction);
        }finally{
            vacuum.shutdown();
        }
    }

    private static final ResultColumnDescriptor[] SCHEMA_INFO_COLUMNS=new GenericColumnDescriptor[]{
            new GenericColumnDescriptor("SCHEMANAME",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("TABLENAME",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("REGIONNAME",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("IS_INDEX",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BOOLEAN)),
            new GenericColumnDescriptor("HBASEREGIONS_STORESIZE",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("MEMSTORESIZE",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
            new GenericColumnDescriptor("STOREINDEXSIZE",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT)),
    };

    public static void SYSCS_GET_SCHEMA_INFO(final ResultSet[] resultSet) throws SQLException{
        List<ExecRow> results=new ArrayList<>();
        EmbedConnection defaultConn=(EmbedConnection)SpliceAdmin.getDefaultConn();
        EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)defaultConn.getMetaData();

        try(ResultSet allTablesInSchema=dmd.getSchemasInfo()){

            ExecRow template;
            try{
                DataValueDescriptor[] columns=new DataValueDescriptor[SpliceAdmin.SCHEMA_INFO_COLUMNS.length];
                for(int i=0;i<SpliceAdmin.SCHEMA_INFO_COLUMNS.length;i++){
                    columns[i]=SpliceAdmin.SCHEMA_INFO_COLUMNS[i].getType().getNull();
                }
                template=new ValueRow(columns.length);
                template.setRowArray(columns);
            }catch(StandardException e){
                throw PublicAPI.wrapStandardException(e);
            }

            try(PartitionAdmin admin=SIDriver.driver().getTableFactory().getAdmin()){
                while(allTablesInSchema.next()){
                    String conglom=allTablesInSchema.getObject("CONGLOMERATENUMBER").toString();
                    for(Partition ri : admin.allPartitions(conglom)){
                        String regionName=ri.getName();//Bytes.toString(ri.getRegionName());
                        int storefileSizeMB=0;
                        int memStoreSizeMB=0;
                        int storefileIndexSizeMB=0;
                        int factor = 1024*1024;
                        if(regionName!=null && !regionName.isEmpty()){
                            PartitionLoad regionLoad=ri.getLoad();//regionLoadMap.get(regionName);
                            if(regionLoad!=null){
                                storefileSizeMB= (int) (regionLoad.getStorefileSize()/factor);
                                memStoreSizeMB= (int) (regionLoad.getMemStoreSize()/factor);
                                storefileIndexSizeMB= (int) (regionLoad.getStorefileIndexSize()/factor);
                            }
                        }
                        DataValueDescriptor[] cols=template.getRowArray();
                        try{
                            cols[0].setValue(allTablesInSchema.getString("SCHEMANAME"));
                            cols[1].setValue(allTablesInSchema.getString("TABLENAME"));
                            cols[2].setValue(regionName);
                            cols[3].setValue(allTablesInSchema.getBoolean("ISINDEX"));
                            cols[4].setValue(storefileSizeMB);
                            cols[5].setValue(memStoreSizeMB);
                            cols[6].setValue(storefileIndexSizeMB);
                        }catch(StandardException se){
                            throw PublicAPI.wrapStandardException(se);
                        }
                        results.add(template.getClone());
                    }
                }
            }catch(IOException ioe){
                throw PublicAPI.wrapStandardException(Exceptions.parseException(ioe));
            }
        }

        Activation lastActivation=defaultConn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap=new IteratorNoPutResultSet(results,SpliceAdmin.SCHEMA_INFO_COLUMNS,lastActivation);
        try{
            resultsToWrap.openCore();
            EmbedResultSet ers=new EmbedResultSet40(defaultConn,resultsToWrap,false,null,true);
            resultSet[0]=ers;
        }catch(StandardException se){
            throw PublicAPI.wrapStandardException(se);
        }
    }

    /**
     * Prints all the information related to the execution plans of the stored prepared statements (metadata queries).
     */

    public static void SYSCS_GET_STORED_STATEMENT_PLAN_INFO(ResultSet[] rs) throws SQLException{
        try{
            // Wow...  who knew it was so much work to create a ResultSet?  Ouch!  The following code is annoying.

            LanguageConnectionContext lcc=ConnectionUtil.getCurrentLCC();
            DataDictionary dd=lcc.getDataDictionary();
            List list=dd.getAllSPSDescriptors();
            ArrayList<ExecRow> rows=new ArrayList<>(list.size());

            // Describe the format of the input rows (ExecRow).
            //
            // Columns of "virtual" row:
            //   STMTNAME               VARCHAR
            //   TYPE                   CHAR
            //   VALID                  BOOLEAN
            //   LASTCOMPILED           TIMESTAMP
            //   INITIALLY_COMPILABLE   BOOLEAN
            //   CONSTANTSTATE          BLOB --> VARCHAR showing existence of plan
            DataValueDescriptor[] dvds= {
                    new SQLVarchar(),
                    new SQLChar(),
                    new SQLBoolean(),
                    new SQLTimestamp(),
                    new SQLBoolean(),
                    new SQLVarchar()
            };
            int numCols=dvds.length;
            ExecRow dataTemplate=new ValueRow(numCols);
            dataTemplate.setRowArray(dvds);

            // Transform the descriptors into the rows.
            for(Object aList : list){
                SPSDescriptor spsd=(SPSDescriptor)aList;
                ExecPreparedStatement ps=spsd.getPreparedStatement(false, lcc);
                dvds[0].setValue(spsd.getName());
                dvds[1].setValue(spsd.getTypeAsString());
                dvds[2].setValue(spsd.isValid());
                dvds[3].setValue(spsd.getCompileTime());
                dvds[4].setValue(spsd.initiallyCompilable());
                dvds[5].setValue(spsd.getPreparedStatement(false, lcc)==null?null:"[object]");
                rows.add(dataTemplate.getClone());
            }

            // Describe the format of the output rows (ResultSet).
            ResultColumnDescriptor[] columnInfo=new ResultColumnDescriptor[numCols];
            columnInfo[0]=new GenericColumnDescriptor("STMTNAME",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,60));
            columnInfo[1]=new GenericColumnDescriptor("TYPE",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.CHAR,4));
            columnInfo[2]=new GenericColumnDescriptor("VALID",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BOOLEAN));
            columnInfo[3]=new GenericColumnDescriptor("LASTCOMPILED",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TIMESTAMP));
            columnInfo[4]=new GenericColumnDescriptor("INITIALLY_COMPILABLE",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BOOLEAN));
            columnInfo[5]=new GenericColumnDescriptor("CONSTANTSTATE",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,13));
            EmbedConnection defaultConn=(EmbedConnection)getDefaultConn();
            Activation lastActivation=defaultConn.getLanguageConnection().getLastActivation();
            IteratorNoPutResultSet resultsToWrap=new IteratorNoPutResultSet(rows,columnInfo,lastActivation);
            resultsToWrap.openCore();
            EmbedResultSet ers=new EmbedResultSet40(defaultConn,resultsToWrap,false,null,true);
            rs[0]=ers;
        }catch(StandardException se){
            throw PublicAPI.wrapStandardException(se);
        }
    }

    /**
     * Get the values of all properties for the current connection.
     *
     * @param rs array of result set objects that contains all of the defined properties
     *           for the JVM, service, database, and app.
     * @throws SQLException Standard exception policy.
     **/
    public static void SYSCS_GET_ALL_PROPERTIES(ResultSet[] rs) throws SQLException{
        try{
            LanguageConnectionContext lcc=ConnectionUtil.getCurrentLCC();
            TransactionController tc=lcc.getTransactionExecute();

            // Fetch all the properties.
            Properties jvmProps=addTypeToProperties(System.getProperties(),"JVM",true);
            Properties dbProps=addTypeToProperties(tc.getProperties());  // Includes both database and service properties.
            ModuleFactory monitor=Monitor.getMonitorLite();
            Properties appProps=addTypeToProperties(monitor.getApplicationProperties(),"APP",false);

            // Merge the properties using the correct search order.
            // SEARCH ORDER: JVM, Service, Database, App
            appProps.putAll(dbProps);  // dbProps already has been overwritten with service properties.
            appProps.putAll(jvmProps);
            ArrayList<ExecRow> rows=new ArrayList<>(appProps.size());

            // Describe the format of the input rows (ExecRow).
            //
            // Columns of "virtual" row:
            //   KEY            VARCHAR
            //   VALUE          VARCHAR
            //   TYPE           VARCHAR (JVM, SERVICE, DATABASE, APP)
            DataValueDescriptor[] dvds= {
                    new SQLVarchar(),
                    new SQLVarchar(),
                    new SQLVarchar()
            };
            int numCols=dvds.length;
            ExecRow dataTemplate=new ValueRow(numCols);
            dataTemplate.setRowArray(dvds);

            // Transform the properties into rows.  Sort the properties by key first.
            ArrayList<String> keyList=new ArrayList<>();
            for(Object o:appProps.keySet()){
                if(o instanceof String)
                    keyList.add((String)o);
            }
            Collections.sort(keyList);
            for(String key : keyList){
                String[] typedValue=(String[])appProps.get(key);
                dvds[0].setValue(key);
                dvds[1].setValue(typedValue[0]);
                dvds[2].setValue(typedValue[1]);
                rows.add(dataTemplate.getClone());
            }

            // Describe the format of the output rows (ResultSet).
            ResultColumnDescriptor[] columnInfo=new ResultColumnDescriptor[numCols];
            columnInfo[0]=new GenericColumnDescriptor("KEY",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,50));
            columnInfo[1]=new GenericColumnDescriptor("VALUE",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,40));
            columnInfo[2]=new GenericColumnDescriptor("TYPE",DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,10));
            EmbedConnection defaultConn=(EmbedConnection)getDefaultConn();
            Activation lastActivation=defaultConn.getLanguageConnection().getLastActivation();
            IteratorNoPutResultSet resultsToWrap=new IteratorNoPutResultSet(rows,columnInfo,lastActivation);
            resultsToWrap.openCore();
            EmbedResultSet ers=new EmbedResultSet40(defaultConn,resultsToWrap,false,null,true);
            rs[0]=ers;
        }catch(StandardException se){
            throw PublicAPI.wrapStandardException(se);
        }
    }

    /**
     * Tag each property value with a 'type'.  The structure of the map changes from:
     * {key --> value}
     * to:
     * {key --> [value, type]}
     *
     * @param props           Map of properties to tag with type
     * @param type            Type of property (JVM, SERVICE, DATABASE, APP, etc.)
     * @param derbySpliceOnly If true, only include properties with keys that start with "derby" or "splice".
     * @return the new map of typed properties
     */
    private static Properties addTypeToProperties(Properties props,String type,boolean derbySpliceOnly){
        Properties typedProps=new Properties();
        if(props!=null){
            for(Entry<Object, Object> objectObjectEntry : props.entrySet()){
                Entry prop=(Entry)objectObjectEntry;
                String key=(String)prop.getKey();
                if(key==null) continue;
                if(derbySpliceOnly){
                    String lowerKey=key.toLowerCase();
                    if(!lowerKey.startsWith("derby") && !lowerKey.startsWith("splice")){
                        continue;
                    }
                }
                String[] typedValue=new String[2];
                typedValue[0]=(String)prop.getValue();
                typedValue[1]=type;
                typedProps.put(key,typedValue);
            }
        }
        return typedProps;
    }

    /**
     * Tag each property value with a 'type' of SERVICE or DATABASE.  The structure of the map changes from:
     * {key --> value}
     * to:
     * {key --> [value, type]}
     *
     * @param props Map of properties to tag with type
     * @return the new map of typed properties
     */
    private static Properties addTypeToProperties(Properties props){
        Properties typedProps=new Properties();
        if(props!=null){
            for(Entry<Object, Object> objectObjectEntry : props.entrySet()){
                Entry prop=(Entry)objectObjectEntry;
                String key=(String)prop.getKey();
                String[] typedValue=new String[2];
                typedValue[0]=(String)prop.getValue();
                typedValue[1]=PropertyUtil.isServiceProperty(key)?"SERVICE":"DATABASE";
                typedProps.put(key,typedValue);
            }
        }
        return typedProps;
    }

    private static final String sqlConglomsInSchema=
            "SELECT CONGLOMERATENUMBER FROM SYSVW.SYSCONGLOMERATEINSCHEMAS "+
                    "WHERE SCHEMANAME = ?";

    private static final String sqlConglomsInTable=
            sqlConglomsInSchema+" AND TABLENAME = ?";

    public static String getSqlConglomsInSchema(){
        return sqlConglomsInSchema;
    }

    private static final String sqlGetTablesInSchema= "SELECT TABLEID FROM SYSVW.SYSTABLESVIEW WHERE SCHEMAID = ?";

    private static List<TableDescriptor> getTablesInSchema(DataDictionary dataDictionary,
                                                           Connection connection,
                                                           String schemaId) throws SQLException, StandardException {
        try(PreparedStatement statement = connection.prepareStatement(sqlGetTablesInSchema)) {
            statement.setString(1, schemaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TableDescriptor> tableDescriptors = new ArrayList<>();
                while (resultSet.next()) {
                    tableDescriptors.add(dataDictionary.getTableDescriptor(new BasicUUID(resultSet.getString(1))));
                }
                return tableDescriptors;
            }
        }
    }

    public static String getSqlConglomsInTable(){
        return sqlConglomsInTable;
    }

    /**
     * Be Careful when using this, as it will return conglomerate ids for all the indices of a table
     * as well as the table itself. While the first conglomerate SHOULD be the main table, there
     * really isn't a guarantee, and it shouldn't be relied upon for correctness in all cases.
     */
    @SuppressFBWarnings(value="OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE", justification="Intentional")
    public static long[] getConglomNumbers(Connection conn,String schemaName,String tableName) throws SQLException{
        List<Long> conglomIDs=new ArrayList<>();
        if(schemaName==null)
            // default schema
            schemaName= SQLConfiguration.SPLICE_USER;

        String query;
        boolean isTableNameEmpty;

        if(tableName==null){
            query=getSqlConglomsInSchema();
            isTableNameEmpty=true;
        }else{
            query=getSqlConglomsInTable();
            isTableNameEmpty=false;
        }

        ResultSet rs=null;
        PreparedStatement s=null;
        try{
            s=conn.prepareStatement(query);
            s.setString(1,schemaName);
            if(!isTableNameEmpty){
                s.setString(2,tableName);
            }
            rs=s.executeQuery();
            while(rs.next()){
                conglomIDs.add(rs.getLong(1));
            }

            if(conglomIDs.isEmpty()){
                if(isTableNameEmpty){
                    throw PublicAPI.wrapStandardException(ErrorState.LANG_SCHEMA_DOES_NOT_EXIST.newException(schemaName));
                }
                throw PublicAPI.wrapStandardException(ErrorState.LANG_TABLE_NOT_FOUND.newException(tableName));
            }
        }finally{
            if(rs!=null) rs.close();
            if(s!=null) s.close();
        }
        if(conglomIDs.isEmpty()){
            return new long[0];
        }
        long[] congloms=new long[conglomIDs.size()];
        for(int i=0;i<conglomIDs.size();i++){
            congloms[i]=conglomIDs.get(i);
        }
                /*
                 * An index conglomerate id can be returned by the query before the main table one is,
                 * but it should ALWAYS have a higher conglomerate id, so if we sort the congloms,
                 * we should return the main table before any of its indices.
                 */
        Arrays.sort(congloms);
        return congloms;
    }


    private static class Trip<T,U,V>{
        private final T first;
        private final U second;
        private final V third;

        public Trip(T first,U second,V third){
            this.first=first;
            this.second=second;
            this.third=third;
        }

        public T getFirst(){
            return first;
        }

        public U getSecond(){
            return second;
        }

        public V getThird(){
            return third;
        }

    }

    @SuppressFBWarnings("IIL_PREPARE_STATEMENT_IN_LOOP") // intentional (different servers)
    public static void SYSCS_GET_GLOBAL_DATABASE_PROPERTY(final String key,final ResultSet[] resultSet) throws SQLException{

        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        List<ExecRow> rows = new ArrayList<>();
        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString())) {
                try (PreparedStatement ps = connection.prepareStatement("values SYSCS_UTIL.SYSCS_GET_DATABASE_PROPERTY(?)")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ExecRow row = new ValueRow(2);
                            row.setColumn(1, new SQLVarchar(server.toString()));
                            row.setColumn(2, new SQLVarchar(rs.getString(1)));
                            rows.add(row);
                        }
                    }
                };
            }
        }

        GenericColumnDescriptor[] descriptors = {
                new GenericColumnDescriptor("HOST_NAME", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 120)),
                new GenericColumnDescriptor("PROPERTY_VALUE", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 120)),
        };

        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, descriptors, lastActivation);
        try {
            resultsToWrap.openCore();
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
        resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
    }

    public static void SYSCS_SET_GLOBAL_DATABASE_PROPERTY(final String key, final String value,
                                                          final ResultSet[] resultSet) throws SQLException{
        try {
            LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
            SpliceTransactionManager tc = (SpliceTransactionManager)lcc.getTransactionExecute();
            DataDictionary dd = lcc.getDataDictionary();
            dd.startWriting(lcc);
            String previous = PropertyUtil.getCachedDatabaseProperty(lcc, key);
            PropertyInfo.setDatabaseProperty(key, value);

            DDLMessage.DDLChange ddlChange = ProtoUtil.createSetDatabaseProperty(tc.getActiveStateTxn().getTxnId(), key);
            tc.prepareDataDictionaryChange(DDLUtils.notifyMetadataChange(ddlChange));
            // we need to invalidate the statement caches since we could set parameters that affect query plans.
            SYSCS_INVALIDATE_STORED_STATEMENTS();
            SYSCS_EMPTY_GLOBAL_STATEMENT_CACHE();

            ResultHelper resultHelper = new ResultHelper();

            ResultHelper.VarcharColumn c1 = resultHelper.addVarchar("name", 20);
            ResultHelper.VarcharColumn c2 = resultHelper.addVarchar("value", 150);
            resultHelper.newRow();
            c1.set("PROPERTY_NAME");      c2.set(key);
            resultHelper.newRow();
            c1.set("NEW VALUE");    c2.set(value);
            resultHelper.newRow();
            c1.set("PREVIOUS VALUE");  c2.set(previous);

            if( !PropertyHelper.getAllProperties().contains(key) ) {
                resultHelper.newRow();
                resultHelper.newRow();
                c1.set("!!! WARNING !!!");
                c2.set("Database Property '" + key + "' seems to be unknown!");

                SpliceLogUtils.warn(LOG, "Database Property '" + key + "' was set, but it seems to be unknown");
            }
            else {
                // reserved to add information about properties later
                resultHelper.newRow();
                c1.set("INFO");
                c2.set("");
            }
            resultSet[0] = resultHelper.getResultSet();
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    public static void SYSCS_GET_GLOBAL_DATABASE_PROPERTIES(String filter,
                                                            final boolean showNonNullOnly,
                                                            final ResultSet[] resultSet) throws StandardException, SQLException {
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        ResultHelper resultHelper = new ResultHelper();
        ResultHelper.VarcharColumn colProperty = resultHelper.addVarchar("PROPERTY_NAME", -1);
        ResultHelper.VarcharColumn colValue    = resultHelper.addVarchar("VALUE", -1);
        ResultHelper.VarcharColumn colInfo     = resultHelper.addVarchar("INFO", 50);
        if( filter != null ) {
            filter = DbEngineUtils.getJavaRegexpFilterFromAsteriskFilter(filter);
        }

        for(String property : PropertyHelper.getAllProperties()) {
            if( filter != null && filter.length() > 0 && !property.matches(filter) ) continue;
            String value = PropertyUtil.getCachedDatabaseProperty(lcc, property);
            if( showNonNullOnly && value == null ) continue;
            resultHelper.newRow();
            colProperty.set(property);
            colValue.set(value);
            colInfo.set(""); // reserved to add information about properties later
        }
        resultSet[0] = resultHelper.getResultSet();
    }


    public static void SYSCS_ENABLE_ENTERPRISE(final String value) throws SQLException{
        try {
            EngineDriver.driver().manager().enableEnterprise(value.toCharArray());
            LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
            lcc.getDataDictionary().startWriting(lcc);
            TransactionController tc = lcc.getTransactionExecute();
            DDLMessage.DDLChange ddlChange = ProtoUtil.createRefreshEnterpriseFeatures(((SpliceTransactionManager) tc).getActiveStateTxn().getTxnId());
            // Run Remotely
            tc.prepareDataDictionaryChange(DDLUtils.notifyMetadataChange(ddlChange));
        } catch (Exception e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    public static void SYSCS_EMPTY_GLOBAL_STATEMENT_CACHE() throws SQLException{
        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString());
                 Statement s = connection.createStatement()) {
                    s.execute("call SYSCS_UTIL.SYSCS_EMPTY_STATEMENT_CACHE()");
            }
        }
    }

    public static void SYSCS_GET_TABLE_COUNT(ResultSet[] resultSets) throws StandardException, SQLException{
        try {
            Connection conn = SpliceAdmin.getDefaultConn();
            LanguageConnectionContext lcc = conn.unwrap(EmbedConnection.class).getLanguageConnection();
            SIDriver driver = SIDriver.driver();
            PartitionFactory partitionFactory = driver.getTableFactory();
            PartitionAdmin partitionAdmin = partitionFactory.getAdmin();
            int tableCount = partitionAdmin.getTableCount();

            ResultColumnDescriptor[] rcds = {
                    new GenericColumnDescriptor("NumTables", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER))
            };
            ExecRow template = new ValueRow(1);

            template.setRowArray(new DataValueDescriptor[]{new SQLInteger(tableCount)});
            List<ExecRow> rows = Lists.newArrayList();
            rows.add(template.getClone());
            IteratorNoPutResultSet inprs = new IteratorNoPutResultSet(rows,rcds,lcc.getLastActivation());
            inprs.openCore();
            resultSets[0] = new EmbedResultSet40(conn.unwrap(EmbedConnection.class),inprs,false,null,true);
        } catch (Throwable t) {
            resultSets[0] = ProcedureUtils.generateResult("Error", t.getLocalizedMessage());
            SpliceLogUtils.error(LOG, "Cannot get table count.", t);
        }
    }

    public static void SYSCS_IS_MEM_PLATFORM(ResultSet[] resultSets) throws StandardException, SQLException{
        try {
            Connection conn = SpliceAdmin.getDefaultConn();
            LanguageConnectionContext lcc = conn.unwrap(EmbedConnection.class).getLanguageConnection();
            boolean isMemPlatform = EngineDriver.isMemPlatform();
            ResultColumnDescriptor[] rcds = {
                    new GenericColumnDescriptor("IsMemPlatform", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BOOLEAN))
            };
            ExecRow template = new ValueRow(1);

            template.setRowArray(new DataValueDescriptor[]{new SQLBoolean(isMemPlatform)});
            List<ExecRow> rows = Lists.newArrayList();
            rows.add(template.getClone());
            IteratorNoPutResultSet inprs = new IteratorNoPutResultSet(rows,rcds,lcc.getLastActivation());
            inprs.openCore();
            resultSets[0] = new EmbedResultSet40(conn.unwrap(EmbedConnection.class),inprs,false,null,true);
        } catch (Throwable t) {
            resultSets[0] = ProcedureUtils.generateResult("Error", t.getLocalizedMessage());
            SpliceLogUtils.error(LOG, "Failed to test mem platform status.", t);
        }
    }
    public static void SYSCS_INVALIDATE_STORED_STATEMENTS() throws SQLException{
        SystemProcedures.SYSCS_INVALIDATE_PERSISTED_STORED_STATEMENTS();
        SYSCS_EMPTY_GLOBAL_STORED_STATEMENT_CACHE();
    }

    public static void SYSCS_EMPTY_GLOBAL_STORED_STATEMENT_CACHE() throws SQLException{
        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString());
                 Statement ps = connection.createStatement()) {
                     ps.execute("call SYSCS_UTIL.SYSCS_EMPTY_STORED_STATEMENT_CACHE()");
            }
        }
    }

    private static Collection<PartitionServer> getLoad() throws SQLException{
        try(PartitionAdmin admin=SIDriver.driver().getTableFactory().getAdmin()){
            return admin.allServers();
        }catch(IOException e){
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    public static void GET_ACTIVATION(final String statement, final ResultSet[] resultSet) throws SQLException, StandardException {
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        GenericPreparedStatement gps = (GenericPreparedStatement)lcc.prepareInternalStatement(statement);
        GenericActivationHolder activationHolder = (GenericActivationHolder)gps.getActivation(lcc, false);
        Activation activation = activationHolder.ac;
        ActivationHolder ah = new ActivationHolder(activation, null);
        byte[] activationHolderBytes = SerializationUtils.serialize(ah);
        DataValueDescriptor[] dvds = {
            new SQLBlob()
        };
        int numCols = dvds.length;
        ExecRow dataTemplate = new ValueRow(numCols);
        dataTemplate.setRowArray(dvds);

        List<ExecRow> rows = Lists.newArrayList();
        dvds[0].setValue(activationHolderBytes);
        rows.add(dataTemplate);

        // Describe the format of the output rows (ResultSet).
        ResultColumnDescriptor[]columnInfo = new ResultColumnDescriptor[numCols];
        columnInfo[0] = new GenericColumnDescriptor("ACTIVATION", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BLOB));
        EmbedConnection defaultConn = (EmbedConnection) getDefaultConn();
        Activation lastActivation = defaultConn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, columnInfo, lastActivation);
        resultsToWrap.openCore();
        EmbedResultSet ers = new EmbedResultSet40(defaultConn, resultsToWrap, false, null, true);

        resultSet[0] = ers;
    }

    /**
     * Stored procedure that updates the owner (authorization id) for an existing schema.
     * Only the database owner is allowed to invoke this action.
     */
    public static void SYSCS_UPDATE_SCHEMA_OWNER(String schemaName, String ownerName) throws SQLException {
        if (schemaName == null || schemaName.isEmpty()) throw new SQLException("Invalid null or empty value for 'schemaName'");
        if (ownerName == null || ownerName.isEmpty()) throw new SQLException("Invalid null or empty value for 'ownerName'");
        schemaName = EngineUtils.validateSchema(schemaName);
        ownerName = ownerName.toUpperCase();
        try {
            checkCurrentUserIsDatabaseOwnerAccess();
            EmbedConnection defaultConn = (EmbedConnection)getDefaultConn();
            LanguageConnectionContext lcc = defaultConn.getLanguageConnection();
            SpliceTransactionManager tc = (SpliceTransactionManager)lcc.getTransactionExecute();
            DataDictionary dd = lcc.getDataDictionary();
            dd.startWriting(lcc);
            SchemaDescriptor sd = dd.getSchemaDescriptor(lcc.getDatabaseId(), schemaName, tc, /* raiseError= */true);
            if (dd.getUser(lcc.getDatabaseId(), ownerName) == null) {
                throw StandardException.newException(String.format("User '%s' does not exist.", ownerName));
            }
            ((DataDictionaryImpl)dd).updateSchemaAuth(sd.getDatabaseId().toString(), schemaName, ownerName, tc);
            DDLMessage.DDLChange ddlChange = ProtoUtil.createUpdateSchemaOwner(
                    tc.getActiveStateTxn().getTxnId(),
                    schemaName,
                    ownerName,
                    (BasicUUID)sd.getUUID(),
                    (BasicUUID)sd.getDatabaseId());
            tc.prepareDataDictionaryChange(DDLUtils.notifyMetadataChange(ddlChange));
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    public static void SYSCS_RESTORE_DATABASE_OWNER() throws SQLException{
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        TransactionController tc  = lcc.getTransactionExecute();

        SystemProcedures.updateSystemSchemaAuthorization("SPLICE", tc);
    }

    private static void checkCurrentUserIsDatabaseOwnerAccess() throws Exception
    {
        EmbedConnection defaultConn = (EmbedConnection)getDefaultConn();
        LanguageConnectionContext lcc = defaultConn.getLanguageConnection();
        DataDictionary dd = lcc.getDataDictionary();
        if (dd.usesSqlAuthorization()) {
            String databaseOwner = lcc.getCurrentDatabase().getAuthorizationId();
            String currentUser = lcc.getStatementContext().getSQLSessionContext().getCurrentUser();
            List<String> groupUserlist = lcc.getStatementContext().getSQLSessionContext().getCurrentGroupUser();
            if (!(databaseOwner.equals(currentUser) || (groupUserlist != null && groupUserlist.contains(databaseOwner)))) {
                throw StandardException.newException(SQLState.DBO_ONLY);
            }
        }
    }

    /**
     * Save (or delete) the source text for a user-defined code object in the SYS.SYSSOURCECODE table
     *
     * @param schemaName name of the user that owns the code object
     * @param objectName name of the code object
     * @param objectType user-defined type of the code object (e.g. "Function")
     * @param objectForm user-defined format of the source text (e.g. "Java8@UTF8")
     * @param definerName name of the user saving the object
     * @param sourceCode source text of the code object (or null to request deletion of the code object)
     * @throws SQLException Error saving the sourcecode
     */
    public static void SYSCS_SAVE_SOURCECODE(String schemaName, String objectName, String objectType, String objectForm, String definerName, Blob sourceCode) throws SQLException {
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        TransactionController tc = lcc.getTransactionExecute();
        schemaName = EngineUtils.validateSchema(schemaName);
        try {
            tc.elevate("sourceCode");
            DataDictionary dd = lcc.getDataDictionary();
            SourceCodeDescriptor descriptor = new SourceCodeDescriptor(schemaName, objectName, objectType, objectForm, definerName, new DateTime(), sourceCode);
            dd.saveSourceCode(descriptor, tc);

        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
    }

    public static void SET_PURGE_DELETED_ROWS (String schemaName, String tableName, String enable) throws Exception{
        schemaName = EngineUtils.validateSchema(schemaName);
        tableName = EngineUtils.validateTable(tableName);
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        TransactionController tc  = lcc.getTransactionExecute();
        DataDictionary dd = lcc.getDataDictionary();
        SchemaDescriptor sd = dd.getSchemaDescriptor(null, schemaName, tc, true);
        if (sd == null)
        {
            throw StandardException.newException(SQLState.LANG_SCHEMA_DOES_NOT_EXIST, schemaName);
        }
        TableDescriptor td = DataDictionaryUtils.getTableDescriptor(lcc, schemaName, tableName);

        dd.startWriting(lcc);
        DDLMessage.DDLChange ddlChange = ProtoUtil.createAlterTable(((SpliceTransactionManager) tc).getActiveStateTxn().getTxnId(),
                (BasicUUID) td.getUUID());
        DependencyManager dm = dd.getDependencyManager();
        dm.invalidateFor(td, DependencyManager.ALTER_TABLE, lcc);

        tc.prepareDataDictionaryChange(DDLUtils.notifyMetadataChange(ddlChange));
        boolean b = "TRUE".compareToIgnoreCase(enable) == 0;
        if(td.getMinRetentionPeriod() != null && td.getMinRetentionPeriod() > 0) {
            SpliceLogUtils.warn(LOG, "setting purge deleted rows on table %s which min retention period " +
                    "set to non-negative value, this could lead to incorrect time travel query results", td.getName());
        }
        td.setPurgeDeletedRows(b);
        dd.dropTableDescriptor(td, sd, tc);
        dd.addDescriptor(td, sd, DataDictionary.SYSTABLES_CATALOG_NUM, false, tc, false);
    }

    /**
     * Sets the minimum retention period for a table, or a set of tables in a schema.
     *
     * @param schemaName Name of the schema.
     * @param tableName Name of the table, if NULL, then `retentionPeriod` will be set for all tables in `schema`.
     * @param retentionPeriod Retention period (in seconds), can be null.
     * @throws StandardException If table or schema does not exist.
     * @throws SQLException is table name or schema name is not valid.
     */
    public static void SET_MIN_RETENTION_PERIOD(String schemaName, String tableName, Long retentionPeriod) throws StandardException, SQLException {
        // if schemaName was null => get the current schema.
        schemaName = EngineUtils.validateSchema(schemaName);
        // if tableName is null => set min. retention period for all tables in schemaName.
        tableName = tableName == null ? null : EngineUtils.validateTable(tableName);
        if(retentionPeriod != null && retentionPeriod < 0) {
            throw StandardException.newException(SQLState.LANG_INVALID_VALUE_RANGE, retentionPeriod, "non-negative number");
        }
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        TransactionController tc = lcc.getTransactionExecute();
        DataDictionary dd = lcc.getDataDictionary();
        SchemaDescriptor sd = dd.getSchemaDescriptor(lcc.getDatabaseId(), schemaName, tc, true);
        if (sd == null) {
            throw StandardException.newException(SQLState.LANG_SCHEMA_DOES_NOT_EXIST, schemaName);
        }
        List<TableDescriptor> affectedTables = new ArrayList<>();
        if (tableName != null) {
            TableDescriptor td = dd.getTableDescriptor(tableName, sd, tc);
            if (td == null) {
                throw StandardException.newException(SQLState.TABLE_NOT_FOUND, tableName);
            }
            affectedTables.add(td);
        } else { // set for all tables in schema
            affectedTables.addAll(getTablesInSchema(dd, getDefaultConn(), sd.getUUID().toString()));
        }
        dd.startWriting(lcc);
        for(TableDescriptor td : affectedTables) {
            DDLMessage.DDLChange ddlChange = ProtoUtil.createAlterTable(((SpliceTransactionManager) tc).getActiveStateTxn().getTxnId(), (BasicUUID) td.getUUID());
            DependencyManager dm = dd.getDependencyManager();
            dm.invalidateFor(td, DependencyManager.ALTER_TABLE, lcc);
            tc.prepareDataDictionaryChange(DDLUtils.notifyMetadataChange(ddlChange));
            if(td.purgeDeletedRows()) {
                SpliceLogUtils.warn(LOG, "setting minimum retention period on table %s which has purge deleted " +
                        "rows set to true, this could lead to incorrect time travel query results", td.getName());
            }
            td.setMinRetentionPeriod(retentionPeriod);
            dd.dropTableDescriptor(td, sd, tc);
            dd.addDescriptor(td, sd, DataDictionary.SYSTABLES_CATALOG_NUM, false, tc, false);
        }
    }

    /**
     * Take a snapshot of a schema
     * @param schemaName
     * @param snapshotName
     * @throws Exception
     */
    public static void SNAPSHOT_SCHEMA(String schemaName, String snapshotName) throws Exception
    {
        ensureSnapshot(snapshotName, false);
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        TransactionController tc  = lcc.getTransactionExecute();
        DataDictionary dd = lcc.getDataDictionary();

        schemaName = EngineUtils.validateSchema(schemaName);
        EngineUtils.checkSchemaVisibility(schemaName);

        dd.startWriting(lcc);


        List<String> snapshotList = Lists.newArrayList();
        try
        {
            ResultSet rs = getTablesForSnapshot(schemaName, null);
            snapshot(rs, snapshotName, schemaName, dd, tc, snapshotList);
        }
        catch (Exception e)
        {
            deleteSnapshots(snapshotList);
            throw e;
        }
    }

    public static void SNAPSHOT_TABLE(String schemaName, String tableName, String snapshotName) throws Exception
    {
        ensureSnapshot(snapshotName, false);
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        TransactionController tc  = lcc.getTransactionExecute();
        DataDictionary dd = lcc.getDataDictionary();

        schemaName = EngineUtils.validateSchema(schemaName);
        tableName = EngineUtils.validateTable(tableName);
        EngineUtils.checkSchemaVisibility(schemaName);

        TableDescriptor td = DataDictionaryUtils.getTableDescriptor(lcc, schemaName, tableName);
        if (td.isExternal())
            throw StandardException.newException(SQLState.SNAPSHOT_EXTERNAL_TABLE_UNSUPPORTED, tableName);
        if (td.isTemporary())
            throw StandardException.newException(LANG_NOT_ALLOWED_FOR_TEMP_TABLE, tableName);

        List<String> snapshotList = Lists.newArrayList();
        try {
            dd.startWriting(lcc);

            ResultSet rs = getTablesForSnapshot(schemaName, td.getName());
            snapshot(rs, snapshotName, schemaName, dd, tc, snapshotList);
        }
        catch (Exception e)
        {
            deleteSnapshots(snapshotList);
            throw e;
        }
    }


    /**
     * delete a snapshot
     * @param snapshotName
     * @throws Exception
     */
    public static void DELETE_SNAPSHOT(String snapshotName) throws Exception
    {
        ensureSnapshot(snapshotName, true);
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        TransactionController tc  = lcc.getTransactionExecute();
        DataDictionary dd = lcc.getDataDictionary();
        dd.startWriting(lcc);

        PartitionAdmin admin=SIDriver.driver().getTableFactory().getAdmin();
        Connection connection = getDefaultConn();
        EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)connection.getMetaData();
        try (ResultSet rs = dmd.checkSnapshotExists(snapshotName)) {

            while (rs.next()) {
                long conglomerateNumber = rs.getLong(3);
                String sname = snapshotName + "_" + conglomerateNumber;
                if (LOG.isDebugEnabled()) {
                    SpliceLogUtils.debug(LOG, "deleting snapshot %s for table %d", sname, conglomerateNumber);
                }
                admin.deleteSnapshot(sname);
                dd.deleteSnapshot(snapshotName, conglomerateNumber, tc);

                if (LOG.isDebugEnabled()) {
                    SpliceLogUtils.debug(LOG, "deleted snapshot %s for table %d", sname, conglomerateNumber);
                }
            }
        }
    }

    /**
     * restore a snapshot
     * @param snapshotName
     * @throws Exception
     */
    public static void RESTORE_SNAPSHOT(String snapshotName) throws Exception
    {
        ensureSnapshot(snapshotName, true);
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        TransactionController tc  = lcc.getTransactionExecute();
        DataDictionary dd = lcc.getDataDictionary();
        dd.startWriting(lcc);

        PartitionAdmin admin=SIDriver.driver().getTableFactory().getAdmin();
        Connection connection = getDefaultConn();
        EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)connection.getMetaData();
        try (ResultSet rs = dmd.checkSnapshotExists(snapshotName)) {

            while (rs.next()) {
                String schemaName = rs.getString(1);
                String objectName = rs.getString(2);
                long conglomerateNumber = rs.getLong(3);
                DateTime creationTime = new DateTime(rs.getTimestamp(4));
                DateTime lastRestoreTime = new DateTime(System.currentTimeMillis());
                String sname = snapshotName + "_" + conglomerateNumber;

                if (LOG.isDebugEnabled()) {
                    SpliceLogUtils.debug(LOG, "restoring snapshot %s for table %d", sname, conglomerateNumber);
                }

                admin.disableTable("splice:" + conglomerateNumber);
                admin.restoreSnapshot(sname);
                admin.enableTable("splice:" + conglomerateNumber);
                dd.deleteSnapshot(snapshotName, conglomerateNumber, tc);
                SnapshotDescriptor descriptor = new SnapshotDescriptor(snapshotName, schemaName, objectName,
                        conglomerateNumber, creationTime, lastRestoreTime);
                dd.addSnapshot(descriptor, tc);
                if (LOG.isDebugEnabled()) {
                    SpliceLogUtils.debug(LOG, "restored snapshot %s for table %d", sname, conglomerateNumber);
                }
            }
        }
    }

    private static ResultSet getTablesForSnapshot(String schemaName, String tableName) throws Exception
    {
        EmbedConnection defaultConn=(EmbedConnection)getDefaultConn();
        EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)defaultConn.getMetaData();
        ResultSet rs = dmd.getTablesForSnaphot(schemaName, tableName);
        return rs;
    }

    private static void snapshot(ResultSet rs, String snapshotName, String schemaName,
                                 DataDictionary dd, TransactionController tc, List<String> snapshotList) throws Exception
    {
        PartitionAdmin admin=SIDriver.driver().getTableFactory().getAdmin();
        while(rs.next())
        {
            String objectName = rs.getString(1);
            long conglomerateNumber = rs.getLong(2);
            String sname = snapshotName + "_" + conglomerateNumber;
            DateTime creationTime = new DateTime(System.currentTimeMillis());
            if (LOG.isDebugEnabled())
            {
                SpliceLogUtils.debug(LOG, "creating snapshot %s", sname);
            }
            SnapshotDescriptor descriptor =
                    new SnapshotDescriptor(snapshotName, schemaName, objectName, conglomerateNumber,creationTime, null);
            admin.snapshot(sname, "splice:" + conglomerateNumber);
            dd.addSnapshot(descriptor, tc);
            snapshotList.add(sname);
            if (LOG.isDebugEnabled())
            {
                SpliceLogUtils.debug(LOG, "created snapshot %s", sname);
            }
        }
    }

    private static void ensureSnapshot(String snapshotName, boolean exists) throws StandardException
    {

        if (!snapshotName.matches("[a-zA-Z_0-9][a-zA-Z_0-9-.]*")) {
            throw StandardException.newException(SQLState.SNAPSHOT_NAME_ILLEGAL,snapshotName);
        }
        int count = 0;
        try {
            Connection connection = getDefaultConn();
            EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)connection.getMetaData();
            try (ResultSet rs = dmd.checkSnapshotExists(snapshotName)) {
                if (rs.next()) {
                    count++;
                }
            }
        }
        catch (SQLException e)
        {
            throw StandardException.plainWrapException(e);
        }

        if (exists && count == 0)
        {
            throw StandardException.newException(SQLState.SNAPSHOT_NOT_EXISTS, snapshotName);
        }
        else if (!exists && count > 0)
        {
            throw StandardException.newException(SQLState.SNAPSHOT_EXISTS, snapshotName);
        }
    }

    private static void deleteSnapshots(List<String> snapshotList) throws IOException
    {
        PartitionAdmin admin=SIDriver.driver().getTableFactory().getAdmin();
        for (String snapshot : snapshotList)
        {
            admin.deleteSnapshot(snapshot);
        }
    }

    public static void SYSCS_GET_SESSION_INFO(final ResultSet[] resultSet) throws SQLException{
        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        LanguageConnectionContext lcc = conn.getLanguageConnection();
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();

        int sessionNumber = lcc.getInstanceNumber();

        SConfiguration config=EngineDriver.driver().getConfiguration();
        String hostname = NetworkUtils.getHostname(config);
        int port = config.getNetworkBindPort();

        List<ExecRow> rows = new ArrayList<>(1);
        ExecRow row = new ValueRow(2);
        row.setColumn(1, new SQLVarchar(hostname + ":" + port));
        row.setColumn(2, new SQLInteger(sessionNumber));
        rows.add(row);

        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, new GenericColumnDescriptor[]{
                new GenericColumnDescriptor("HOSTNAME", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 120)),
                new GenericColumnDescriptor("SESSION", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER)),
        },
                lastActivation);
        try {
            resultsToWrap.openCore();
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
        resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
    }

    private static final GenericColumnDescriptor[] runningOpsDescriptors = {
            new GenericColumnDescriptor("UUID", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 40)),
            new GenericColumnDescriptor("USER", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 40)),
            new GenericColumnDescriptor("HOSTNAME", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, 120)),
            new GenericColumnDescriptor("SESSION", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER)),
            new GenericColumnDescriptor("SQL", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR)),
            new GenericColumnDescriptor("SUBMITTED", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,40)),
            new GenericColumnDescriptor("ELAPSED", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,40)),
            new GenericColumnDescriptor("ENGINE", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,40)),
            new GenericColumnDescriptor("JOBTYPE", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,40)),
    };

    public static void SYSCS_GET_RUNNING_OPERATIONS(final ResultSet[] resultSet) throws SQLException {
        List<ExecRow> rows = getRunningOperations();

        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, runningOpsDescriptors, lastActivation);
        try {
            resultsToWrap.openCore();
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
        resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
    }

    public static void SYSCS_GET_OLDEST_ACTIVE_TRANSACTION(ResultSet[] resultSet) throws SQLException{
        long id = getOldestActiveTransaction();

        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();

        List<ExecRow> rows = new ArrayList<>(1);
        ExecRow row = new ValueRow(1);
        row.setColumn(1, new SQLLongint(id));
        GenericColumnDescriptor[] descriptor = new GenericColumnDescriptor[]{
                new GenericColumnDescriptor("transactionId", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT))
        };
        rows.add(row);
        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, descriptor, lastActivation);
        try {
            resultsToWrap.openCore();
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
        resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
    }

    private static List<ExecRow> getRunningOperations() throws SQLException {
        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        List<ExecRow> rows = new ArrayList<>();
        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString())) {
                try (Statement stmt = connection.createStatement()) {
                    try (ResultSet rs = stmt.executeQuery("call SYSCS_UTIL.SYSCS_GET_RUNNING_OPERATIONS_LOCAL()")) {
                        while (rs.next()) {
                            ExecRow row = new ValueRow(9);

                            if ("call SYSCS_UTIL.SYSCS_GET_RUNNING_OPERATIONS_LOCAL()".equalsIgnoreCase(rs.getString(5))) {
                                // Filter out the nested calls to SYSCS_GET_RUNNING_OPERATIONS_LOCAL triggered by this stored procedure
                                continue;
                            }

                            row.setColumn(1, new SQLVarchar(rs.getString(1)));
                            row.setColumn(2, new SQLVarchar(rs.getString(2)));
                            row.setColumn(3, new SQLVarchar(rs.getString(3)));
                            row.setColumn(4, new SQLInteger(rs.getInt(4)));
                            row.setColumn(5, new SQLVarchar(rs.getString(5)));
                            row.setColumn(6, new SQLVarchar(rs.getString(6)));
                            row.setColumn(7, new SQLVarchar(rs.getString(7)));
                            row.setColumn(8, new SQLVarchar(rs.getString(8)));
                            row.setColumn(9, new SQLVarchar(rs.getString(9)));
                            rows.add(row);
                        }
                    }
                }
            }
        }
        return rows;
    }

    public static String getCurrentUserId() throws SQLException {
        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();
        return lastActivation.getLanguageConnectionContext().getCurrentUserId(lastActivation);
    }

    public static void ANALYZE_EXTERNAL_TABLE(String location, final ResultSet[] resultSet) throws IOException, SQLException {

        GetSchemaExternalResult result = DistributedGetSchemaExternalJob.execute(location, getCurrentUserId()+"_analyze",
                    null, false, new CsvOptions(), null, null);

        String[] res = result.getSuggestedSchema("\n").split("\n");
        int maxLen = Arrays.stream(res).map(String::length).max(Integer::compareTo).get();

        ResultHelper resultHelper = new ResultHelper();
        ResultHelper.VarcharColumn col1 = resultHelper.addVarchar("SCHEMA", Math.max(maxLen, 20));

        for(String s : res ) {
            resultHelper.newRow();
            col1.set(s);
        }
        resultSet[0] = resultHelper.getResultSet();
    }

    public static void LIST_DIRECTORY(String location, final ResultSet[] resultSet) throws SQLException, IOException, URISyntaxException {
        DistributedFileSystem  fs = null;
        try {
            fs = SIDriver.driver().getFileSystem(location);
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        try {
            FileInfo fi1 = fs.getInfo(location);
            if( fi1.exists() == false ) {
                throw ErrorState.LANG_FILE_DOES_NOT_EXIST.newException(location);
            }

            FileInfo files[] = fi1.listDir();
            Arrays.sort(files, Comparator.comparing(FileInfo::fileName));

            int maxLen = Arrays.stream(files).map(f -> f.fileName().length()).max(Integer::compareTo).get();
            int pathColLen = Math.max(10, maxLen+2);

            int ownerColLen = Arrays.stream(files).map(f -> f.getUser().length()).max(Integer::compareTo).get();
            int groupColLen = Arrays.stream(files).map(f -> f.getGroup().length()).max(Integer::compareTo).get();

            ResultHelper resultHelper = new ResultHelper();

            ResultHelper.VarcharColumn ownerCol     = resultHelper.addVarchar("OWNER", ownerColLen+1);
            ResultHelper.VarcharColumn groupCol     = resultHelper.addVarchar("GROUP", groupColLen+1);
            ResultHelper.TimestampColumn modtimeCol = resultHelper.addTimestamp("MODTIME", 30);
            ResultHelper.BigintColumn  sizeCol      = resultHelper.addBigint("SIZE", 10);
            ResultHelper.VarcharColumn permCol      = resultHelper.addVarchar("PERM", 12);
            ResultHelper.VarcharColumn pathCol      = resultHelper.addVarchar("PATH", pathColLen );
            for (FileInfo fi : files )
            {
                resultHelper.newRow();
                pathCol.set( fi.fileName() );
                ownerCol.set(fi.getUser());
                groupCol.set(fi.getGroup());
                modtimeCol.set( fi.getModificationTime() == 0 ? null : new DateTime(fi.getModificationTime()) );
                sizeCol.set(fi.size());
                permCol.set((fi.isDirectory() ? "d" : "-") + fi.getPermissionStr());
            }
            resultSet[0] = resultHelper.getResultSet();
        } catch (IOException | StandardException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    public static void SYSCS_GET_RUNNING_OPERATIONS_LOCAL(final ResultSet[] resultSet) throws SQLException{
        try {
            EmbedConnection conn = (EmbedConnection)getDefaultConn();
            Activation lastActivation = conn.getLanguageConnection().getLastActivation();
            LanguageConnectionContext lcc = lastActivation.getLanguageConnectionContext();
            String userId = lcc.getCurrentUserId(lastActivation);
            String dbo = lcc.getCurrentDatabase().getAuthorizationId();
            if (userId != null && userId.equals(dbo)) {
                userId = null;
            }

            List<Pair<UUID, RunningOperation>> operations = EngineDriver.driver().getOperationManager().runningOperations(userId);

            SConfiguration config=EngineDriver.driver().getConfiguration();
            String host_port = NetworkUtils.getHostname(config) + ":" + config.getNetworkBindPort();
        final String timeStampFormat = SQLTimestamp.defaultTimestampFormatString;

            List<ExecRow> rows = new ArrayList<>(operations.size());
            for (Pair<UUID, RunningOperation> pair : operations)
            {
                UUID uuid = pair.getFirst();
                RunningOperation ro = pair.getSecond();
                ExecRow row = new ValueRow(9);
                Activation activation = ro.getOperation().getActivation();
                assert activation.getPreparedStatement() != null:"Prepared Statement is null";
                row.setColumn(1, new SQLVarchar(uuid.toString())); // UUID
                row.setColumn(2, new SQLVarchar(activation.getLanguageConnectionContext().getCurrentUserId(activation))); // USER
                row.setColumn(3, new SQLVarchar(host_port) ); // HOSTNAME
                row.setColumn(4, new SQLInteger(activation.getLanguageConnectionContext().getInstanceNumber())); // SESSION
                ExecPreparedStatement ps = activation.getPreparedStatement();
                row.setColumn(5, new SQLVarchar(ps == null ? null : ps.getSource())); // SQL
                String submittedTime = new SimpleDateFormat(timeStampFormat).format(ro.getSubmittedTime());
                row.setColumn(6, new SQLVarchar(submittedTime)); // SUBMITTED

                row.setColumn(7, new SQLVarchar(getElapsedTimeStr(ro.getSubmittedTime(),new Date()))); // ELAPSED
                row.setColumn(8, new SQLVarchar(ro.getEngineName())); // ENGINE
                row.setColumn(9, new SQLVarchar(ro.getOperation().getScopeName())); // JOBTYPE
                rows.add(row);
            }

            IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, runningOpsDescriptors, lastActivation);
            resultsToWrap.openCore();
            resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
    }

    private static String getElapsedTimeStr(Date begin, Date end)
    {
        long between  = (end.getTime() - begin.getTime()) / 1000;
        long day = between / (24 * 3600);
        long hour = between % (24 * 3600) / 3600;
        long minute = between % 3600 / 60;
        long second = between % 60;
        StringBuilder elapsedStr = new StringBuilder();
        if (day > 0) {
            elapsedStr.append(day + " day(s) ").append(hour + " hour(s) ").append(minute + " min(s) ").append(second + " sec(s)");
        } else if (hour > 0) {
            elapsedStr.append(hour + " hour(s) ").append(minute + " min(s) ").append(second + " sec(s)");
        } else if (minute > 0) {
            elapsedStr.append(minute + " min(s) ").append(second + " sec(s)");
        } else {
            elapsedStr.append(second + " sec(s)");
        }
        return elapsedStr.toString();
    }

    public static void SYSCS_KILL_DRDA_OPERATION(final String token) throws SQLException {
        String[] parts = token.split("#");
        String uuidString = parts[0];
        String hostname = parts[1];
        List<HostAndPort> servers;
        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
        HostAndPort needle = null;
        for (HostAndPort hap : servers) {
            if (hap.toString().equals(hostname)) {
                needle = hap;
                break;
            }
        }
        if (needle == null)
            throw  PublicAPI.wrapStandardException(StandardException.newException(LANG_NO_SUCH_RUNNING_OPERATION, token));

        try (Connection connection = RemoteUser.getConnection(hostname)) {
            try (PreparedStatement ps = connection.prepareStatement("call SYSCS_UTIL.SYSCS_KILL_DRDA_OPERATION_LOCAL(?)")) {
                ps.setString(1, uuidString);
                ps.execute();
            }
        }
    }

    public static void SYSCS_KILL_OPERATION(final String uuidString) throws SQLException {
        ExecRow needle = null;
        for (ExecRow row : getRunningOperations()) {
                try {
                    if (row.getColumn(1).getString().equals(uuidString)) {
                        needle = row;
                        break;
                    }
                } catch (StandardException se) {
                    throw PublicAPI.wrapStandardException(se);
                }
        }
        if (needle == null)
            throw  PublicAPI.wrapStandardException(StandardException.newException(LANG_NO_SUCH_RUNNING_OPERATION, uuidString));

        try {
            String server = needle.getColumn(3).getString();

            try (Connection connection = RemoteUser.getConnection(server)) {
                try (PreparedStatement ps = connection.prepareStatement("call SYSCS_UTIL.SYSCS_KILL_OPERATION_LOCAL(?)")) {
                    ps.setString(1, uuidString);
                    ps.execute();
                }
            }
        } catch (StandardException e) {
            throw PublicAPI.wrapStandardException(e);
        }
    }

    public static void SYSCS_KILL_DRDA_OPERATION_LOCAL(final String rdbIntTkn) throws SQLException{

        try {
            EmbedConnection conn = (EmbedConnection)getDefaultConn();
            LanguageConnectionContext lcc = conn.getLanguageConnection();
            Activation lastActivation = conn.getLanguageConnection().getLastActivation();

            String userId = lcc.getCurrentUserId(lastActivation);

            boolean killed = EngineDriver.driver().getOperationManager().killDRDAOperation(rdbIntTkn, userId);
            if (!killed)
                throw  PublicAPI.wrapStandardException(StandardException.newException(LANG_NO_SUCH_RUNNING_OPERATION, rdbIntTkn));
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }

    }

    public static void SYSCS_KILL_OPERATION_LOCAL(final String uuidString) throws SQLException{

        try {
            EmbedConnection conn = (EmbedConnection)getDefaultConn();
            LanguageConnectionContext lcc = conn.getLanguageConnection();
            Activation lastActivation = conn.getLanguageConnection().getLastActivation();

            String userId = lcc.getCurrentUserId(lastActivation);

            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                throw  PublicAPI.wrapStandardException(StandardException.newException(LANG_INVALID_FUNCTION_ARGUMENT, uuidString, "SYSCS_KILL_OPERATION"));
            }
            boolean killed = EngineDriver.driver().getOperationManager().killOperation(uuid, userId);
            if (!killed)
                throw  PublicAPI.wrapStandardException(StandardException.newException(LANG_NO_SUCH_RUNNING_OPERATION, uuidString));
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        }
    }


    public static void SYSCS_HDFS_OPERATION(final String path, final String operation, final ResultSet[] resultSet) throws SQLException {
        try {
            FilesystemAdmin admin = SIDriver.driver().getFilesystemAdmin();

            String conglomName = admin.extractConglomerate(path);
            int conglomId = Integer.parseInt(conglomName);

            EmbedConnection conn = (EmbedConnection)getDefaultConn();
            LanguageConnectionContext lcc = conn.getLanguageConnection();
            Activation lastActivation = conn.getLanguageConnection().getLastActivation();

            ConglomerateDescriptor conglomerate = lcc.getDataDictionary().getConglomerateDescriptor(conglomId);

            // Check generic table permission
            Activation activation = new SimpleActivation(Arrays.asList(new StatementSchemaPermission(conglomerate.getSchemaID(), Authorizer.ACCESS_PRIV)
                    , new StatementTablePermission(conglomerate.getSchemaID(), conglomerate.getTableID(), Authorizer.SELECT_PRIV)), lcc);
            lcc.getAuthorizer().authorize(activation, Authorizer.SELECT_PRIV);

            // Special check for SYSUSERS which contains the user/passwords
            if (conglomerate.getTableID().toString().equals(SYSUSERSRowFactory.SYSUSERS_UUID)) {
                throw StandardException.newException(SQLState.DBO_ONLY);
            }
            // Special check for SYSTOKENS which contains the user/passwords
            if (conglomerate.getTableID().toString().equals(SYSTOKENSRowFactory.SYSTOKENS_UUID)) {
                throw StandardException.newException(SQLState.DBO_ONLY);
            }

            final GenericColumnDescriptor[] descriptors = {
                    new GenericColumnDescriptor("RESPONSE", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BLOB)),
            };

            List<ExecRow> rows = new ArrayList<>();
            List<byte[]> results = admin.hdfsOperation(path, operation);
            for (byte[] result : results) {
                ExecRow row = new ValueRow(1);
                row.setColumn(1, new SQLBlob(result));
                rows.add(row);
            }
            IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, descriptors, lastActivation);
            resultsToWrap.openCore();
            resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    public static void SYSCS_HBASE_OPERATION(final String tableName, final String operation, final Blob request, final ResultSet[] resultSet) throws SQLException {
        try {
            EmbedConnection conn = (EmbedConnection)getDefaultConn();
            LanguageConnectionContext lcc = conn.getLanguageConnection();
            Activation lastActivation = conn.getLanguageConnection().getLastActivation();

            String[] splits = tableName.split(":");
            if (splits.length != 2) {
                throw StandardException.newException(
                                SQLState.HBASE_OPERATION_ERROR,
                        tableName,
                        operation);
            }
            String schema = splits[0];
            String table = splits[1];

            if (!schema.equals(EngineDriver.driver().getConfiguration().getNamespace())) {
                throw StandardException.newException(
                        SQLState.HBASE_OPERATION_ERROR,
                        tableName,
                        operation);
            }

            long conglomerateId = -1;
            try {
                conglomerateId = Long.parseLong(table);
            } catch (NumberFormatException ex) {
                //ignore
            }


            if (conglomerateId > 16) { // splice:16 doesn't have an entry on the dictionary
                ConglomerateDescriptor conglomerate = lcc.getDataDictionary().getConglomerateDescriptor(conglomerateId);

                // Check generic table permission
                Activation activation = new SimpleActivation(Arrays.asList(new StatementSchemaPermission(conglomerate.getSchemaID(), Authorizer.ACCESS_PRIV)
                        , new StatementTablePermission(conglomerate.getSchemaID(), conglomerate.getTableID(), Authorizer.SELECT_PRIV)), lcc);
                lcc.getAuthorizer().authorize(activation, Authorizer.SELECT_PRIV);

                // Special check for SYSUSERS which contains the user/passwords
                if (conglomerate.getTableID().toString().equals(SYSUSERSRowFactory.SYSUSERS_UUID)) {
                    throw StandardException.newException(SQLState.DBO_ONLY);
                }
                // Special check for SYSTOKENS which contains the user/passwords
                if (conglomerate.getTableID().toString().equals(SYSTOKENSRowFactory.SYSTOKENS_UUID)) {
                    throw StandardException.newException(SQLState.DBO_ONLY);
                }
            }

            final GenericColumnDescriptor[] descriptors = {
                    new GenericColumnDescriptor("RESPONSE", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BLOB)),
            };

            List<ExecRow> rows = new ArrayList<>();

            try(PartitionAdmin admin=SIDriver.driver().getTableFactory().getAdmin()) {
                List<byte[]> results = admin.hbaseOperation(tableName, operation, request != null ? request.getBytes(1, (int) request.length()) : null);
                for (byte[] result : results) {
                    ExecRow row = new ValueRow(1);
                    row.setColumn(1, new SQLBlob(result));
                    rows.add(row);
                }
            }
            IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, descriptors, lastActivation);
            resultsToWrap.openCore();
            resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }


    public static void SYSCS_GET_SPLICE_TOKEN(final String user, // this variable is no longer used
                                              final ResultSet[] resultSet) throws SQLException {
        try {
            EmbedConnection conn = (EmbedConnection)getDefaultConn();
            LanguageConnectionContext lcc = conn.getLanguageConnection();
            Activation lastActivation = conn.getLanguageConnection().getLastActivation();

            DataDictionary dd = lcc.getDataDictionary();
            dd.startWriting(lcc);

            final GenericColumnDescriptor[] descriptors = {
                    new GenericColumnDescriptor("TOKEN", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BINARY)),
                    new GenericColumnDescriptor("EXPIRETIME", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TIMESTAMP)),
                    new GenericColumnDescriptor("MAXIMUMTIME", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TIMESTAMP)),
            };

            List<ExecRow> rows = new ArrayList<>();

            SConfiguration config=EngineDriver.driver().getConfiguration();
            int length = config.getAuthenticationTokenLength();
            int maxLifetime = config.getAuthenticationTokenMaxLifetime();
            int renewInterval = config.getAuthenticationTokenRenewInterval();

            byte[] token = new byte[length];
            new SecureRandom().nextBytes(token);

            String username = lcc.getCurrentUserId(lastActivation);
            DateTime creationTime = new DateTime(System.currentTimeMillis());
            DateTime expireTime = creationTime.plusSeconds(renewInterval);
            DateTime maxTime = creationTime.plusSeconds(maxLifetime);

            TokenDescriptor descriptor =
                    new TokenDescriptor(token, username, creationTime, expireTime, maxTime);
            lcc.getDataDictionary().addToken(descriptor, lcc.getTransactionExecute());

            ExecRow row = new ValueRow(3);
            row.setColumn(1, new SQLBit(token));
            row.setColumn(2, new SQLTimestamp(expireTime));
            row.setColumn(3, new SQLTimestamp(maxTime));
            rows.add(row);

            IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, descriptors, lastActivation);
            resultsToWrap.openCore();
            resultSet[0] = new EmbedResultSet40(conn, resultsToWrap, false, null, true);
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        } catch (Exception e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    public static void SYSCS_CANCEL_SPLICE_TOKEN(final Blob token) throws SQLException {
        try {
            EmbedConnection conn = (EmbedConnection)getDefaultConn();
            LanguageConnectionContext lcc = conn.getLanguageConnection();
            Activation lastActivation = conn.getLanguageConnection().getLastActivation();

            DataDictionary dd = lcc.getDataDictionary();
            dd.startWriting(lcc);

            byte[] tokenBytes = token.getBytes(1, (int) token.length());

            String username = lcc.getCurrentUserId(lastActivation);
            TokenDescriptor descriptor = lcc.getDataDictionary().getToken(tokenBytes);
            if (descriptor == null) {
                return;
            }

            if (!descriptor.getUserName().equals(username)) {
                return;
            }

            lcc.getDataDictionary().deleteToken(tokenBytes);
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        } catch (Exception e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    public static void INVALIDATE_DICTIONARY_CACHE() throws Exception {
        EmbedConnection conn = (EmbedConnection)getDefaultConn();
        LanguageConnectionContext lcc = conn.getLanguageConnection();
        DataDictionary dd = lcc.getDataDictionary();
        dd.getDataDictionaryCache().clearAll();
    }

    public static void INVALIDATE_GLOBAL_DICTIONARY_CACHE() throws Exception {
        List<HostAndPort> servers;

        try {
            servers = EngineDriver.driver().getServiceDiscovery().listServers();
        } catch (IOException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }

        for (HostAndPort server : servers) {
            try (Connection connection = RemoteUser.getConnection(server.toString())) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("call SYSCS_UTIL.INVALIDATE_DICTIONARY_CACHE()");
                }
            }
        }
    }

    @SuppressFBWarnings(value="SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE", justification="Intentional")
    public static void SHOW_CREATE_TABLE(String schemaName, String tableName, ResultSet[] resultSet) throws SQLException, StandardException
    {
        List<String> ddls = SHOW_CREATE_TABLE_CORE(schemaName, tableName, false);
        resultSet[0] = ProcedureUtils.generateResult("DDL", ddls.get(0) + ";");
    }

    public static List<String> SHOW_CREATE_TABLE_CORE(String schemaName, String tableName, boolean separateFK) throws SQLException {
        Connection connection = getDefaultConn();
        schemaName = EngineUtils.validateSchema(schemaName);
        tableName = EngineUtils.validateTable(tableName);
        List<String> ddls = Lists.newArrayList();
        try {
            TableDescriptor td = EngineUtils.verifyTableExists(connection, schemaName, tableName);

            String tableTypeString = "";
            StringBuilder extTblString = new StringBuilder();

            ColumnDescriptorList cdl = td.getColumnDescriptorList();
            //Process external table definition
            if (td.isExternal()) {
                tableTypeString = "EXTERNAL ";

                List<ColumnDescriptor> partitionColumns = cdl.stream()
                        .filter(columnDescriptor -> columnDescriptor.getPartitionPosition() > -1)
                        .sorted(Comparator.comparing(ColumnDescriptor::getPartitionPosition))
                        .collect(Collectors.toList());

                String tmpStr;
                tmpStr = td.getCompression();
                if (tmpStr != null && !tmpStr.equals("none"))
                    extTblString.append("\nCOMPRESSED WITH " + tmpStr);

                // Partitioned Columns
                boolean firstCol = true;
                for (ColumnDescriptor col: partitionColumns) {
                    extTblString.append(firstCol ? "\nPARTITIONED BY (\"" + col.getColumnName()+"\"" : ",\"" + col.getColumnName()+"\"");
                    firstCol = false;
                }

                if (!firstCol)
                    extTblString.append(")");

                // Row Format
                if (td.getDelimited() != null || td.getLines() != null) {
                    extTblString.append("\nROW FORMAT DELIMITED");
                    if ((tmpStr = td.getDelimited()) != null)
                        extTblString.append(" FIELDS TERMINATED BY '" + tmpStr + "'");
                    if ((tmpStr = td.getEscaped()) != null)
                        extTblString.append(" ESCAPED BY '" + tmpStr + "'");
                    if ((tmpStr = td.getLines()) != null)
                        extTblString.append(" LINES TERMINATED BY '" + tmpStr + "'");
                }
                // Storage type
                if ((tmpStr = td.getStoredAs()) != null) {
                    extTblString.append("\nSTORED AS ");
                    switch (tmpStr) {
                        case "T":
                            extTblString.append("TEXTFILE");
                            break;
                        case "P":
                            extTblString.append("PARQUET");
                            break;
                        case "A":
                            extTblString.append("AVRO");
                            break;
                        case "O":
                            extTblString.append("ORC");
                            break;
                        default:
                            throw new SQLException("Invalid stored format");
                    }
                }
                // Location
                if ((tmpStr = td.getLocation()) != null) {
                    extTblString.append("\nLOCATION '" + tmpStr + "'");
                }
            }//End External Table
            else if (td.getTableType() == TableDescriptor.VIEW_TYPE) {
                //Target table is a View
                throw ErrorState.LANG_INVALID_OPERATION_ON_VIEW.newException("SHOW CREATE TABLE", "\"" + schemaName + "\".\"" + tableName + "\"");
            } else if (td.getTableType() == TableDescriptor.SYSTEM_TABLE_TYPE) {
                //Target table is a system table
                throw ErrorState.LANG_NO_USER_DDL_IN_SYSTEM_SCHEMA.newException("SHOW CREATE TABLE", schemaName);
            } else if (td.getTableType() == TableDescriptor.LOCAL_TEMPORARY_TABLE_TYPE) {
                tableTypeString = "LOCAL TEMPORARY ";
            }

            // Get column list, and write DDL for each column.
            StringBuilder colStringBuilder = new StringBuilder("");
            String createColString = "";

            boolean firstCol = true;
            cdl.sort(Comparator.comparing(columnDescriptor -> columnDescriptor.getPosition()));
            for (ColumnDescriptor col: cdl) {
                createColString = createColumn(col);
                colStringBuilder.append(firstCol ? createColString : "," + createColString).append("\n");
                firstCol = false;
            }


            colStringBuilder.append(createConstraint(td, schemaName, tableName, separateFK));

            String DDL = "CREATE " + tableTypeString + "TABLE \"" + schemaName + "\".\"" + tableName + "\" (\n" + colStringBuilder.toString() + ") ";
            String extStr = extTblString.toString();
            if (extStr.length() > 0)
                DDL += extStr;

            ddls.add(DDL);

            if (separateFK) {
                List<String> fks = getForeignKeyConstraints(td, schemaName, tableName);
                ddls.addAll(fks);
            }
            return ddls;
        } catch (StandardException e) {
            throw PublicAPI.wrapStandardException(Exceptions.parseException(e));
        }
    }

    private static String createColumn(ColumnDescriptor columnDescriptor) throws SQLException
    {
        StringBuffer colDef = new StringBuffer();

        colDef.append("\"" + columnDescriptor.getColumnName() + "\"");
        colDef.append(" ");
        String colType = (new UserType(columnDescriptor.getType().getCatalogType())).toString();
        colDef.append(colType);

        Object defaultSerializable;
        if(columnDescriptor.getDefaultInfo() != null) {
            defaultSerializable = columnDescriptor.getDefaultInfo();
        }else{
            defaultSerializable = columnDescriptor.getDefaultValue();
        }
        String defaultText = defaultSerializable == null? null:defaultSerializable.toString();

        if (!reinstateAutoIncrement(columnDescriptor, colDef) &&
                            defaultText != null) {
            if (defaultText.startsWith("GENERATED ALWAYS AS")) {
                colDef.append(" ");
            } else {
                colDef.append(" DEFAULT ");
            }
            colDef.append(defaultText);
        }

        return colDef.toString();
    }

    private static List<String> getForeignKeyConstraints(TableDescriptor td,
                                                         String schemaName,
                                                         String tableName) throws SQLException, StandardException {

        List<String> fks = Lists.newArrayList();
        Map<Integer, ColumnDescriptor> columnDescriptorMap = td.getColumnDescriptorList()
                .stream()
                .collect(Collectors.toMap(ColumnDescriptor::getStoragePosition, columnDescriptor -> columnDescriptor));

        ConstraintDescriptorList constraintDescriptorList = td.getDataDictionary().getConstraintDescriptors(td);
        for (ConstraintDescriptor cd: constraintDescriptorList) {
            int type = cd.getConstraintType();
            if (type == DataDictionary.FOREIGNKEY_CONSTRAINT) {
                StringBuffer fkKeys = new StringBuffer();
                int[] keyColumns = cd.getKeyColumns();
                String fkName = cd.getConstraintName();
                ForeignKeyConstraintDescriptor foreignKeyConstraintDescriptor = (ForeignKeyConstraintDescriptor) cd;
                ConstraintDescriptor referencedCd = foreignKeyConstraintDescriptor.getReferencedConstraint();
                TableDescriptor referencedTableDescriptor = referencedCd.getTableDescriptor();
                int[] referencedKeyColumns = referencedCd.getKeyColumns();
                String refTblName = referencedTableDescriptor.getQualifiedName();
                ColumnDescriptorList referencedTableCDL = referencedTableDescriptor.getColumnDescriptorList();
                Map<Integer, ColumnDescriptor> referencedTableCDM = referencedTableCDL
                        .stream()
                        .collect(Collectors.toMap(ColumnDescriptor::getStoragePosition, columnDescriptor -> columnDescriptor));

                int updateType = foreignKeyConstraintDescriptor.getRaUpdateRule();
                int deleteType = foreignKeyConstraintDescriptor.getRaDeleteRule();

                List<String> referencedColNames = new LinkedList<>();
                List<String> fkColNames = new LinkedList<>();
                for (int index = 0; index < keyColumns.length; index++) {
                    fkColNames.add("\"" + columnDescriptorMap.get(keyColumns[index]).getColumnName() + "\"");
                    referencedColNames.add("\"" + referencedTableCDM.get(referencedKeyColumns[index]).getColumnName() + "\"");
                }
                String s = String.format("ALTER TABLE \"%s\".\"%s\" ADD ", schemaName, tableName);
                fkKeys.append(s + buildForeignKeyConstraint(fkName, refTblName, referencedColNames, fkColNames, updateType, deleteType));
                fks.add(fkKeys.toString() + "\n");
            }
        }
        return fks;
    }
        @SuppressFBWarnings(value="OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE", justification="Intentional")
    private static String createConstraint(TableDescriptor td, String schemaName, String tableName, boolean separateFK) throws SQLException, StandardException {
        Map<Integer, ColumnDescriptor> columnDescriptorMap = td.getColumnDescriptorList()
                .stream()
                .collect(Collectors.toMap(ColumnDescriptor::getStoragePosition, columnDescriptor -> columnDescriptor));
        ConstraintDescriptorList constraintDescriptorList = td.getDataDictionary().getConstraintDescriptors(td);

        StringBuffer chkStr = new StringBuffer();
        StringBuffer uniqueStr = new StringBuffer();
        StringBuffer priKeys = new StringBuffer();
        StringBuffer fkKeys = new StringBuffer();
        for (ConstraintDescriptor cd: constraintDescriptorList) {
            switch (cd.getConstraintType()) {
                //Check
                case DataDictionary.CHECK_CONSTRAINT:
                    if (!cd.isEnabled())
                        break;

                    chkStr.append(", CONSTRAINT " + cd.getConstraintName() + " CHECK " + cd.getConstraintText());
                    break;
                case DataDictionary.PRIMARYKEY_CONSTRAINT:
                    int[] keyColumns = cd.getKeyColumns();
                    boolean pkFirstCol = true;
                    for (int index=0; index<keyColumns.length; index ++) {
                        String colName = columnDescriptorMap.get(keyColumns[index]).getColumnName();
                        priKeys.append(pkFirstCol ? ", CONSTRAINT " + cd.getConstraintName() + " PRIMARY KEY(\"" + colName + "\"": ",\"" + colName + "\"");
                        pkFirstCol = false;
                    }
                    if (!pkFirstCol)
                        priKeys.append(")");
                    break;
                case DataDictionary.UNIQUE_CONSTRAINT:
                    if (!cd.isEnabled())
                        break;
                    keyColumns = cd.getKeyColumns();
                    boolean uniqueFirstCol = true;
                    for (int index=0; index<keyColumns.length; index ++) {
                        String colName = columnDescriptorMap.get(keyColumns[index]).getColumnName();
                        uniqueStr.append(uniqueFirstCol ? ", CONSTRAINT " + cd.getConstraintName() + " UNIQUE (\"" + colName + "\"": ",\"" + colName + "\"");
                        uniqueFirstCol = false;
                    }
                    if (!uniqueFirstCol)
                        uniqueStr.append(")");
                    break;
                case DataDictionary.FOREIGNKEY_CONSTRAINT:
                    if (!separateFK) {
                        keyColumns = cd.getKeyColumns();
                        String fkName = cd.getConstraintName();
                        ForeignKeyConstraintDescriptor foreignKeyConstraintDescriptor = (ForeignKeyConstraintDescriptor) cd;
                        ConstraintDescriptor referencedCd = foreignKeyConstraintDescriptor.getReferencedConstraint();
                        TableDescriptor referencedTableDescriptor = referencedCd.getTableDescriptor();
                        int[] referencedKeyColumns = referencedCd.getKeyColumns();
                        String refTblName = referencedTableDescriptor.getQualifiedName();
                        ColumnDescriptorList referencedTableCDL = referencedTableDescriptor.getColumnDescriptorList();
                        Map<Integer, ColumnDescriptor> referencedTableCDM = referencedTableCDL
                                .stream()
                                .collect(Collectors.toMap(ColumnDescriptor::getStoragePosition, columnDescriptor -> columnDescriptor));

                        int updateType = foreignKeyConstraintDescriptor.getRaUpdateRule();
                        int deleteType = foreignKeyConstraintDescriptor.getRaDeleteRule();

                        List<String> referencedColNames = new LinkedList<>();
                        List<String> fkColNames = new LinkedList<>();
                        for (int index = 0; index < keyColumns.length; index++) {
                            fkColNames.add("\"" + columnDescriptorMap.get(keyColumns[index]).getColumnName() + "\"");
                            referencedColNames.add("\"" + referencedTableCDM.get(referencedKeyColumns[index]).getColumnName() + "\"");
                        }
                        fkKeys.append(", " + buildForeignKeyConstraint(fkName, refTblName, referencedColNames, fkColNames, updateType, deleteType));
                    }
                    break;
                default:
                    break;
            }
        }

        return  chkStr.toString() + uniqueStr.toString() + priKeys.toString() + fkKeys.toString();
    }

    private static String buildColumnsFromList(List<String> cols)
    {
        StringBuffer sb = new StringBuffer("(");
        boolean firstCol = true;
        for (String c : cols) {
            sb.append(firstCol ? "" : ",");
            sb.append(c);
            firstCol = false;
        }
        sb.append(")");
        return sb.toString();
    }

    private static String buildForeignKeyConstraint(String fkName, String refTblName, List<String> pkCols, List<String> fkCols,
                                                    int updateRule, int deleteRule) throws SQLException
    {
        StringBuffer fkStr = new StringBuffer("CONSTRAINT " + fkName + " FOREIGN KEY " + buildColumnsFromList(fkCols));
        fkStr.append(" REFERENCES " + refTblName + buildColumnsFromList(pkCols));

        fkStr.append(" ON UPDATE ");
        switch (updateRule) {
            case RA_RESTRICT:
                fkStr.append("RESTRICT");
                break;
            case RA_NOACTION:
                fkStr.append("NO ACTION");
                break;
            default:  // shouldn't happen
                throw new SQLException("INTERNAL ERROR: unexpected 'on-update' action: " + updateRule);
        }
        fkStr.append(" ON DELETE ");
        switch (deleteRule) {
            case RA_RESTRICT:
                fkStr.append("RESTRICT");
                break;
            case RA_NOACTION:
                fkStr.append("NO ACTION");
                break;
            case RA_CASCADE:
                fkStr.append("CASCADE");
                break;
            case RA_SETNULL:
                fkStr.append("SET NULL");
                break;
            default:  // shouldn't happen
                throw new SQLException("INTERNAL ERROR: unexpected 'on-delete' action: " + deleteRule);
        }
        return fkStr.toString();
    }

    public static boolean reinstateAutoIncrement(ColumnDescriptor columnDescriptor, StringBuffer colDef) throws SQLException {
        if (columnDescriptor.getAutoincInc() != 0) {
            colDef.append(" GENERATED ");
            colDef.append(columnDescriptor.getDefaultInfo() == null ?
                    "ALWAYS " : "BY DEFAULT ");
            colDef.append("AS IDENTITY (START WITH ");
            colDef.append(columnDescriptor.getAutoincStart());
            colDef.append(", INCREMENT BY ");
            colDef.append(columnDescriptor.getAutoincInc());
            colDef.append(")");
            return true;
        }
        return false;
    }

    /**
     * Create or update all system stored procedures.  If the system stored procedure alreadys exists in the data dictionary,
     * the stored procedure will be dropped and then created again.
     *
     * @throws SQLException
     */
    public static void SYSCS_UPDATE_ALL_SYSTEM_PROCEDURES()
            throws SQLException{
        try{
            LanguageConnectionContext lcc=ConnectionUtil.getCurrentLCC();
            TransactionController tc=lcc.getTransactionExecute();
            DataDictionary dd=lcc.getDataDictionary();

            dd.startWriting(lcc);
            TxnView activeTransaction = ((SpliceTransactionManager) tc).getActiveStateTxn();
            DDLMessage.DDLChange ddlChange = ProtoUtil.createUpdateSystemProcedure(activeTransaction.getTxnId());
            tc.prepareDataDictionaryChange(DDLUtils.notifyMetadataChange(ddlChange));

            dd.createOrUpdateAllSystemProcedures(null, tc);

        }catch(StandardException se){
            throw PublicAPI.wrapStandardException(se);
        }
    }

    /**
     * Create or update a system stored procedure.  If the system stored procedure alreadys exists in the data dictionary,
     * the stored procedure will be dropped and then created again.
     *
     * @param schemaName name of the system schema
     * @param procName   name of the system stored procedure
     * @throws SQLException
     */
    public static void SYSCS_UPDATE_SYSTEM_PROCEDURE(String schemaName,String procName)
            throws SQLException{
        try{
            LanguageConnectionContext lcc=ConnectionUtil.getCurrentLCC();
            TransactionController tc=lcc.getTransactionExecute();
            DataDictionary dd=lcc.getDataDictionary();

            /*
            ** Inform the data dictionary that we are about to write to it.
            ** There are several calls to data dictionary "get" methods here
            ** that might be done in "read" mode in the data dictionary, but
            ** it seemed safer to do this whole operation in "write" mode.
            **
            ** We tell the data dictionary we're done writing at the end of
            ** the transaction.
            */
            dd.startWriting(lcc);

            TxnView activeTransaction = ((SpliceTransactionManager) tc).getActiveStateTxn();
            DDLMessage.DDLChange ddlChange = ProtoUtil.createUpdateSystemProcedure(activeTransaction.getTxnId());
            tc.prepareDataDictionaryChange(DDLUtils.notifyMetadataChange(ddlChange));

            schemaName = EngineUtils.validateSchema(schemaName);
            dd.createOrUpdateSystemProcedure(null, schemaName, procName, tc);
        }catch(StandardException se){
            throw PublicAPI.wrapStandardException(se);
        }
    }
}