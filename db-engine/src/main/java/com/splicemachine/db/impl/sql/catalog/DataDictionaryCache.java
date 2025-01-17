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

package com.splicemachine.db.impl.sql.catalog;

import com.splicemachine.db.impl.sql.compile.FirstColumnOfIndexStats;
import org.apache.commons.lang3.mutable.MutableBoolean;
import splice.com.google.common.base.Optional;
import com.splicemachine.db.catalog.UUID;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.Property;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.services.property.PropertyUtil;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.depend.DependencyManager;
import com.splicemachine.db.iapi.sql.depend.Dependent;
import com.splicemachine.db.iapi.sql.dictionary.*;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.iapi.store.access.conglomerate.Conglomerate;
import com.splicemachine.db.impl.sql.GenericStatement;
import com.splicemachine.db.impl.sql.GenericStorablePreparedStatement;
import com.splicemachine.utils.ByteSlice;
import com.splicemachine.utils.Pair;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.log4j.Logger;
import splice.com.google.common.cache.CacheBuilder;
import splice.com.google.common.cache.RemovalListener;
import splice.com.google.common.cache.RemovalNotification;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.MXBean;
import javax.management.ObjectName;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 *
 * Cache Holder for making sense of data dictionary caching and concurrency.
 *
 */
public class DataDictionaryCache {
    private static Logger LOG = Logger.getLogger(DataDictionaryCache.class);
    private ManagedCache<UUID,TableDescriptor> oidTdCache;
    private ManagedCache<TableKey,TableDescriptor> nameTdCache;
    private ManagedCache<TableKey,SPSDescriptor> spsNameCache;
    private ManagedCache<String,SequenceUpdater> sequenceGeneratorCache;
    private ManagedCache<PermissionsDescriptor,Optional<PermissionsDescriptor>> permissionsCache;
    private ManagedCache<Long,List<PartitionStatisticsDescriptor>> partitionStatisticsCache;
    private ManagedCache<Long, FirstColumnOfIndexStats> firstColumnStatsCache;
    private ManagedCache<UUID, SPSDescriptor> storedPreparedStatementCache;
    private ManagedCache<Long,Conglomerate> conglomerateCache;
    private ManagedCache<Pair<Long, Long>, Conglomerate> txnAwareConglomerateCache;
    private ManagedCache<Long,ConglomerateDescriptor> conglomerateDescriptorCache;
    private ManagedCache<GenericStatement,StatementCacheValue> statementCache;
    private ManagedCache<String, DatabaseDescriptor> databaseCache;
    private ManagedCache<Pair<UUID, String>,SchemaDescriptor> schemaCache;
    private ManagedCache<UUID, SchemaDescriptor> oidSchemaCache;
    private ManagedCache<String,AliasDescriptor> aliasDescriptorCache;
    private ManagedCache<String,Optional<RoleGrantDescriptor>> roleCache;
    private ManagedCache<String,List<String>> defaultRoleCache;
    private ManagedCache<Pair<String, String>, Optional<RoleGrantDescriptor>> roleGrantCache;
    private ManagedCache<ByteSlice,TokenDescriptor> tokenCache;
    private ManagedCache<String, Optional<String>> propertyCache;
    private ManagedCache<Long, Optional<String>> catalogVersionCache;
    private ManagedCache<UUID, ConstraintDescriptorList> constraintDescriptorListCache;
    private DataDictionary dd;

    @SuppressFBWarnings(value = "MS_PKGPROTECT", justification = "DB-9844")
    private static final String [] cacheNames = new String[] {"oidTdCache", "nameTdCache", "spsNameCache", "sequenceGeneratorCache", "permissionsCache", "partitionStatisticsCache",
            "firstColumnStatsCache", "storedPreparedStatementCache", "conglomerateCache", "statementCache", "databaseCache", "schemaCache", "aliasDescriptorCache", "roleCache", "defaultRoleCache", "roleGrantCache",
            "tokenCache", "propertyCache", "conglomerateDescriptorCache", "oldSchemaCache", "catalogVersionCache", "txnAwareConglomerateCache", "constraintDescriptorListCache"};

    public static List<String> getCacheNames() {
        return Collections.unmodifiableList(Arrays.asList(cacheNames));
    }
    private int getCacheSize(Properties startParams, String propertyName, int defaultValue) throws StandardException {
        String value = startParams.getProperty(propertyName);
        return PropertyUtil.intPropertyValue(propertyName, value,
                0, Integer.MAX_VALUE, defaultValue);
    }

    public DataDictionaryCache(Properties startParams,DataDictionary dd) throws StandardException {
        /* These properties are database properties which can set by
        SYSCS_UTIL.SYSCS_SET_GLOBAL_DATABASE_PROPERTY, but will only take effect after the
        database restarted.
         */
        int tdCacheSize = getCacheSize(startParams, Property.LANG_TD_CACHE_SIZE,
                Property.LANG_TD_CACHE_SIZE_DEFAULT);
        int spsCacheSize = getCacheSize(startParams, Property.LANG_SPS_CACHE_SIZE,
                Property.LANG_SPS_CACHE_SIZE_DEFAULT);
        int seqgenCacheSize = getCacheSize(startParams, Property.LANG_SEQGEN_CACHE_SIZE,
                Property.LANG_SEQGEN_CACHE_SIZE_DEFAULT);
        int permissionsCacheSize = getCacheSize(startParams, Property.LANG_PERMISSIONS_CACHE_SIZE,
                Property.LANG_PERMISSIONS_CACHE_SIZE_DEFAULT);
        int partstatCacheSize = getCacheSize(startParams, Property.LANG_PARTSTAT_CACHE_SIZE,
                Property.LANG_PARTSTAT_CACHE_SIZE_DEFAULT);
        int conglomerateCacheSize = getCacheSize(startParams,
                Property.LANG_CONGLOMERATE_CACHE_SIZE,
                Property.LANG_CONGLOMERATE_CACHE_SIZE_DEFAULT);
        int conglomerateDescriptorCacheSize = getCacheSize(startParams,
                Property.LANG_CONGLOMERATE_DESCRIPTOR_CACHE_SIZE,
                Property.LANG_CONGLOMERATE_DESCRIPTOR_CACHE_SIZE_DEFAULT);
        int statementCacheSize = getCacheSize(startParams, Property.LANG_STATEMENT_CACHE_SIZE,
                Property.LANG_STATEMENT_CACHE_SIZE_DEFAULT);
        int databaseCacheSize = getCacheSize(startParams, Property.LANG_DATABASE_CACHE_SIZE,
                Property.LANG_DATABASE_CACHE_SIZE_DEFAULT);
        int schemaCacheSize = getCacheSize(startParams, Property.LANG_SCHEMA_CACHE_SIZE,
                Property.LANG_SCHEMA_CACHE_SIZE_DEFAULT);
        int aliasDescriptorCacheSize = getCacheSize(startParams,
                Property.LANG_ALIAS_DESCRIPTOR_CACHE_SIZE,
                Property.LANG_ALIAS_DESCRIPTOR_CACHE_SIZE_DEFAULT);
        int roleCacheSize = getCacheSize(startParams, Property.LANG_ROLE_CACHE_SIZE,
                Property.LANG_ROLE_CACHE_SIZE_DEFAULT);
        int defaultRoleCacheSize = getCacheSize(startParams, Property.LANG_DEFAULT_ROLE_CACHE_SIZE,
                Property.LANG_DEFAULT_ROLE_CACHE_SIZE_DEFAULT);
        int roleGrantCacheSize = getCacheSize(startParams, Property.LANG_ROLE_GRANT_CACHE_SIZE,
                Property.LANG_ROLE_GRANT_CACHE_SIZE_DEFAULT);
        int tokenCacheSize = getCacheSize(startParams, Property.LANG_TOKEN_CACHE_SIZE,
                Property.LANG_TOKEN_CACHE_SIZE_DEFAULT);
        int propertyCacheSize = getCacheSize(startParams, Property.LANG_PROPERTY_CACHE_SIZE,
                Property.LANG_PROPERTY_CACHE_SIZE_DEFAULT);
        int catalogVersionCacheSize = getCacheSize(startParams, Property.LANG_PROPERTY_CACHE_SIZE,
                Property.LANG_PROPERTY_CACHE_SIZE_DEFAULT);
        int constraintDescriptorListCacheSize = getCacheSize(startParams, Property.LANG_CONSTRAINT_CACHE_SIZE,
                Property.LANG_CONSTRAINT_CACHE_SIZE_DEFAULT);

        RemovalListener<Object,Dependent> dependentInvalidator = new RemovalListener<Object, Dependent>() {
            @Override
            public void onRemoval(RemovalNotification<Object, Dependent> removalNotification) {
                LanguageConnectionContext lcc=(LanguageConnectionContext)
                        ContextService.getContextOrNull(LanguageConnectionContext.CONTEXT_ID);
                try {
                    removalNotification.getValue().makeInvalid(DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
                } catch (StandardException e) {
                    LOG.error("Failed to invalidate " + removalNotification.getValue(), e);
                }
            }
        };
        oidTdCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(tdCacheSize).build(), tdCacheSize);
        nameTdCache = new ManagedCache<>(CacheBuilder.newBuilder().maximumSize(tdCacheSize).build(), tdCacheSize);
        if(spsCacheSize>0){
            spsNameCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(spsCacheSize).removalListener(dependentInvalidator).build(), spsCacheSize);
            storedPreparedStatementCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(spsCacheSize).removalListener(dependentInvalidator).build(), spsCacheSize);
        }
        sequenceGeneratorCache=new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(seqgenCacheSize).build(), seqgenCacheSize);
        partitionStatisticsCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats()
                .maximumSize(partstatCacheSize).build(), partstatCacheSize);
        firstColumnStatsCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats()
                .maximumSize(partstatCacheSize).build(), partstatCacheSize);
        conglomerateCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats()
                .maximumSize(conglomerateCacheSize).build(), conglomerateCacheSize);
        txnAwareConglomerateCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats()
                .maximumSize(conglomerateCacheSize).build(), conglomerateCacheSize);
        conglomerateDescriptorCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats()
                .maximumSize(conglomerateDescriptorCacheSize).build(), conglomerateDescriptorCacheSize);
        statementCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize
                (statementCacheSize).removalListener(dependentInvalidator).build(), statementCacheSize);
        databaseCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(
                databaseCacheSize).build(), databaseCacheSize);
        schemaCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(
                schemaCacheSize).build(), schemaCacheSize);
        oidSchemaCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(
                schemaCacheSize).build(), schemaCacheSize);
        aliasDescriptorCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats()
                .maximumSize(aliasDescriptorCacheSize).build(), aliasDescriptorCacheSize);
        roleCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(
                roleCacheSize).build(), roleCacheSize);
        tokenCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(
                tokenCacheSize).build(), tokenCacheSize);
        permissionsCache=new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize(
                permissionsCacheSize).build(), permissionsCacheSize);
        defaultRoleCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize
                (defaultRoleCacheSize).build(), defaultRoleCacheSize);
        roleGrantCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize
                (roleGrantCacheSize).build(), roleGrantCacheSize);
        propertyCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize
                (propertyCacheSize).build(), propertyCacheSize);
        catalogVersionCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize
                (catalogVersionCacheSize).build(), catalogVersionCacheSize);
        constraintDescriptorListCache = new ManagedCache<>(CacheBuilder.newBuilder().recordStats().maximumSize
                (constraintDescriptorListCacheSize).build(), constraintDescriptorListCacheSize);
        this.dd = dd;
    }

    public TableDescriptor nameTdCacheFind(TableKey tableKey) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("nameTdCacheFind " + tableKey);
        return nameTdCache.getIfPresent(tableKey);
    }

    public void nameTdCacheAdd(TableKey tableKey, TableDescriptor td) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("nameTdCacheAdd " + tableKey + " : " + td);
        nameTdCache.put(tableKey,td);
        oidTdCache.put(td.getUUID(),td);
    }

    public TableDescriptor nameTdCacheRemove(TableKey tableKey) throws StandardException {
        TableDescriptor td = nameTdCache.getIfPresent(tableKey);
        if (LOG.isDebugEnabled())
            LOG.debug("nameTdCacheInvalidate " + tableKey + (td != null ? " found" : " null"));
        nameTdCache.invalidate(tableKey);
        return td;
    }

    public void clearNameTdCache() throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("clearNameTdCache ");
        nameTdCache.invalidateAll();
    }

    public TableDescriptor oidTdCacheFind(UUID tableID) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        TableDescriptor td =  oidTdCache.getIfPresent(tableID);
        if (LOG.isDebugEnabled())
            LOG.debug("oidTdCacheFind " + tableID + (td != null ? " found" : " null"));
        if (td!=null) // bind in previous command might have set
            td.setReferencedColumnMap(null);
        return td;
    }

    public void oidTdCacheAdd(UUID tableID, TableDescriptor td) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("oidTdCacheAdd " + tableID + " : " + td);
        oidTdCache.put(tableID,td);
    }

    public TableDescriptor oidTdCacheRemove(UUID tableID) throws StandardException {
        TableDescriptor td = oidTdCache.getIfPresent(tableID);
        if (LOG.isDebugEnabled())
            LOG.debug("oidTdCacheRemove " + tableID + (td != null ? " found" : " null"));
        oidTdCache.invalidate(tableID);
        return td;
    }

    public void clearOidTdCache() throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("clearOidTdCache ");
        oidTdCache.invalidateAll();
    }

    public List<PartitionStatisticsDescriptor> partitionStatisticsCacheFind(Long conglomID) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("partitionStatisticsCacheFind " + conglomID);
        return partitionStatisticsCache.getIfPresent(conglomID);
    }

    public void partitionStatisticsCacheAdd(Long conglomID, List<PartitionStatisticsDescriptor> list) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("partitionStatisticsCacheAdd " + conglomID);
        partitionStatisticsCache.put(conglomID, list);
    }

    public void partitionStatisticsCacheRemove(Long conglomID) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("partitionStatisticsCacheRemove " + conglomID);
        partitionStatisticsCache.invalidate(conglomID);
        firstColumnStatsCacheRemove(conglomID);
    }

    public FirstColumnOfIndexStats firstColumnStatsCacheFind(Long conglomID) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("firstColumnStatsCacheFind " + conglomID);
        return firstColumnStatsCache.getIfPresent(conglomID);
    }

    public void firstColumnStatsCacheAdd(Long conglomID, FirstColumnOfIndexStats firstColStats) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("firstColumnStatsCacheAdd " + conglomID);
        firstColumnStatsCache.put(conglomID, firstColStats);
    }

    private void firstColumnStatsCacheRemove(Long conglomID) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("firstColumnStatsCacheRemove " + conglomID);
        firstColumnStatsCache.invalidate(conglomID);
    }

    public void permissionCacheAdd(PermissionsDescriptor key, Optional<PermissionsDescriptor> optional) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("permissionCacheAdd " + key);
        if (key != null) {
            permissionsCache.put(key, optional);
        }
    }

    public void permissionCacheRemove(PermissionsDescriptor desc) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("permissionCacheRemove " + desc);
        permissionsCache.invalidate(desc);
    }

    public void clearPermissionCache() throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("clearPermissionsCache ");
        permissionsCache.invalidateAll();

    }

    @SuppressFBWarnings(value = "NP_OPTIONAL_RETURN_NULL", justification = "DB-9844")
    public Optional<PermissionsDescriptor> permissionCacheFind(PermissionsDescriptor desc) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("permissionCacheFind " + desc);
        return permissionsCache.getIfPresent(desc);
    }

    public SPSDescriptor spsNameCacheFind(TableKey tableKey) throws StandardException {
        if (!dd.canUseSPSCache())
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("spsNameCacheFind " + tableKey);
        return spsNameCache.getIfPresent(tableKey);
    }

    public void spsNameCacheAdd(TableKey tableKey, SPSDescriptor sps) throws StandardException {
        if (!dd.canUseSPSCache())
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("spsNameCacheAdd tableKey=" + tableKey + " descriptor="+sps);
        spsNameCache.put(tableKey, sps);
    }

    public void clearSpsNameCache() throws StandardException {
        spsNameCache.invalidateAll();
    }
    public void storedPreparedStatementCacheAdd(SPSDescriptor desc) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("storedPreparedStatementCacheAdd " + desc);
        storedPreparedStatementCache.put(desc.getUUID(), desc);
    }

    public SPSDescriptor storedPreparedStatementCacheFind(UUID uuid) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("storedPreparedStatementCacheFind " + uuid);
        return storedPreparedStatementCache.getIfPresent(uuid);
    }

    public void clearStoredPreparedStatementCache() {
        storedPreparedStatementCache.invalidateAll();
    }

    private long getDriverTaskTxnId() {
        LanguageConnectionContext lcc =
            (LanguageConnectionContext)ContextService.getCurrentContextManager().
                                getContext(LanguageConnectionContext.CONTEXT_ID);
        if (lcc == null)
            return -1;
        return lcc.getActiveStateTxId();
    }

    private Conglomerate txnAwareConglomerateCacheFind(Long conglomId) throws StandardException {
        if (dd.useTxnAwareCache()) {
            long txnId = getDriverTaskTxnId();
            if (txnId == -1)
                return null;
            return txnAwareConglomerateCacheFind(new Pair(txnId,conglomId));
        }
        return null;
    }

    public Conglomerate conglomerateCacheFind(TransactionController xactMgr,Long conglomId) throws StandardException {
        if (!dd.canReadCache(xactMgr) && conglomId>=DataDictionary.FIRST_USER_TABLE_NUMBER) {
            // Use cache even if dd says we can't as long as it's a system table (conglomID is < FIRST_USER_TABLE_NUMBER)
            Conglomerate conglomerate = txnAwareConglomerateCacheFind(conglomId);
            if (conglomerate != null)
                return conglomerate;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("conglomerateCacheFind " + conglomId);

        return conglomerateCache.getIfPresent(conglomId);
    }

    public Conglomerate conglomerateCacheFind(Long conglomId) throws StandardException {
        return conglomerateCacheFind(null,conglomId);
    }

    public void conglomerateCacheAdd(Long conglomId, Conglomerate conglomerate,TransactionController xactMgr) throws StandardException {
        if (!dd.canWriteCache(xactMgr)) {
            if (dd.useTxnAwareCache()) {
                long txnId = getDriverTaskTxnId();
                if (txnId == -1)
                    return;
                txnAwareConglomerateCacheAdd(new Pair(txnId, conglomId), conglomerate);
            }

            return;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("conglomerateCacheAdd " + conglomId + " : " + conglomerate);
        conglomerateCache.put(conglomId,conglomerate);
    }

    public void conglomerateCacheAdd(Long conglomId, Conglomerate conglomerate) throws StandardException {
        conglomerateCacheAdd(conglomId, conglomerate,null);
    }

    public Conglomerate txnAwareConglomerateCacheFind(Pair<Long,Long> pair) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("txnAwareConglomerateCacheFind: " + pair);
        return txnAwareConglomerateCache.getIfPresent(pair);
    }

    public void txnAwareConglomerateCacheAdd(Pair<Long, Long> pair, Conglomerate conglomerate) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("txnAwareConglomerateCacheAdd: " + pair + " : " + conglomerate);
        txnAwareConglomerateCache.put(pair,conglomerate);
    }

    public void conglomerateCacheRemove(Long conglomId) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("conglomerateCacheRemove " + conglomId);
        conglomerateCache.invalidate(conglomId);
    }

    public DatabaseDescriptor databaseCacheFind(String dbName, TransactionController tc) throws StandardException {
        if (!dd.canReadCache(tc))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("databaseCacheFind " + dbName);
        return databaseCache.getIfPresent(dbName);
    }

    public void databaseCacheAdd(String dbName, DatabaseDescriptor descriptor, TransactionController tc) throws StandardException {
        if (!dd.canWriteCache(tc))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("databaseCacheAdd" + dbName + " : " + descriptor);
        databaseCache.put(dbName, descriptor);
    }

    public void databaseCacheRemove(String databaseName) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("databaseCacheRemove " + databaseName);
        databaseCache.invalidate(databaseName);
    }

    public SchemaDescriptor schemaCacheFind(UUID dbId, String schemaName, TransactionController tc) throws StandardException {
        if (!dd.canReadCache(tc))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("schemaCacheFind " + dbId + ":" + schemaName);
        return schemaCache.getIfPresent(new Pair<>(dbId, schemaName));
    }

    public void schemaCacheAdd(UUID dbId, String schemaName, SchemaDescriptor descriptor, TransactionController tc) throws StandardException {
        if (!dd.canWriteCache(tc))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("schemaCacheAdd " + dbId + ":" + schemaName + " : " + descriptor);
        schemaCache.put(new Pair<>(dbId, schemaName), descriptor);
    }

    public void schemaCacheRemove(UUID dbId, String schemaName) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("schemaCacheRemove " + dbId + ":" + schemaName);
        schemaCache.invalidate(new Pair<>(dbId, schemaName));
    }

    public SchemaDescriptor oidSchemaCacheFind(UUID schemaID, TransactionController tc) throws StandardException {
        if (!dd.canReadCache(tc))
            return null;
        SchemaDescriptor sd = oidSchemaCache.getIfPresent(schemaID);
        if (LOG.isDebugEnabled())
            LOG.debug("oidSchemaCacheFind " + schemaID + (sd !=null ? ": found" : ": null"));
        return sd;
    }

    public void oidSchemaCacheAdd(UUID schemaID, SchemaDescriptor descriptor, TransactionController tc) throws StandardException {
        if (!dd.canWriteCache(tc))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("oidSchemaCacheAdd " + schemaID + " : " + descriptor);
        oidSchemaCache.put(schemaID, descriptor);
    }

    public void oidSchemaCacheRemove(UUID schemaID) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("oidSchemaCacheRemove " + schemaID);
        oidSchemaCache.invalidate(schemaID);
    }

    public void clearOidSchemaCache() throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("clearOidSchemaCache ");
        oidSchemaCache.invalidateAll();
    }

    public void storedPreparedStatementCacheRemove(SPSDescriptor desc) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("storedPreparedStatementCacheRemove " + desc);
        storedPreparedStatementCache.invalidate(desc.getUUID());
    }

    public void sequenceGeneratorCacheClearAll() throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("sequenceGeneratorCacheClearAll");
        sequenceGeneratorCache.invalidateAll();
    }

    public SequenceUpdater sequenceGeneratorCacheFind(String uuid) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("sequenceGeneratorCacheFind " + uuid);
        return sequenceGeneratorCache.getIfPresent(uuid);
    }

    public void clearAll() {
        if (LOG.isDebugEnabled())
            LOG.debug("clearAll");
        oidTdCache.invalidateAll();
        nameTdCache.invalidateAll();
        spsNameCache.invalidateAll();
        sequenceGeneratorCache.invalidateAll();
        permissionsCache.invalidateAll();
        partitionStatisticsCache.invalidateAll();
        firstColumnStatsCache.invalidateAll();
        storedPreparedStatementCache.invalidateAll();
        databaseCache.invalidateAll();
        schemaCache.invalidateAll();
        oidSchemaCache.invalidateAll();
        statementCache.invalidateAll();
        roleCache.invalidateAll();
        defaultRoleCache.invalidateAll();
        roleGrantCache.invalidateAll();
        tokenCache.invalidateAll();
        propertyCache.invalidateAll();
        conglomerateCache.invalidateAll();
        conglomerateDescriptorCache.invalidateAll();
        aliasDescriptorCache.invalidateAll();
        catalogVersionCache.invalidateAll();
        txnAwareConglomerateCache.invalidateAll();
        constraintDescriptorListCache.invalidateAll();
    }

    public void clearAliasCache() {
        aliasDescriptorCache.invalidateAll();
    }

    public void clearSchemaCache(){
        schemaCache.invalidateAll();
    }

    public void statementCacheRemove(GenericStatement gs) throws StandardException {
        if (LOG.isDebugEnabled()) {
            StatementCacheValue value = statementCache.getIfPresent(gs);
            LOG.debug("statementCacheRemove " + gs.toString() +(value != null ? " found" : " null"));
        }
        statementCache.invalidate(gs);
    }

    public void clearStatementCache() {
        if (LOG.isDebugEnabled())
            LOG.debug("clearStatementCache ");
        statementCache.invalidateAll();
    }

    public List<Pair<String, Timestamp>> cachedStatements() throws StandardException {
        List<Pair<String, Timestamp>> result = new ArrayList<>();
        statementCache.asMap().forEach((key, value) -> result.add(new Pair<String, Timestamp>(key.getStatementText(), value.getTimestamp())));
        return result;
    }

    public GenericStorablePreparedStatement cacheIfAbsent(GenericStatement gs) throws StandardException {
        if (dd.canReadCache(null)) {
            StatementCacheValue value = statementCache.getIfPresent(gs);
            if (value != null) {
                return value.getStatement();
            }
            if (dd.canWriteCache(null)) {
                try {
                    value = statementCache.getManagedCache().get(gs, () -> new StatementCacheValue(new GenericStorablePreparedStatement(gs)));
                    return value.getStatement();
                } catch (ExecutionException ex) {
                    // ignore unlikely exception
                }
            }
        }
        return new GenericStorablePreparedStatement(gs);
    }

    public void roleCacheAdd(String roleName, Optional<RoleGrantDescriptor> optional) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("roleCacheAdd " + roleName);
        roleCache.put(roleName,optional);
    }

    @SuppressFBWarnings(value = "NP_OPTIONAL_RETURN_NULL", justification = "DB-9844")
    public Optional<RoleGrantDescriptor> roleCacheFind(String roleName) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("roleCacheFind " + roleName);
        return roleCache.getIfPresent(roleName);
    }

    public void roleCacheRemove(String roleName) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("roleCacheRemove " + roleName);
        roleCache.invalidate(roleName);
    }

    public void defaultRoleCacheAdd(String userName, List<String> roleList) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("defaultRoleCacheAdd " + userName);
        defaultRoleCache.put(userName,roleList);
    }

    public List<String> defaultRoleCacheFind(String userName) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("defaultRoleCacheFind " + userName);
        return defaultRoleCache.getIfPresent(userName);
    }

    public void defaultRoleCacheRemove(String userName) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("defaultRoleCacheRemove " + userName);
        defaultRoleCache.invalidate(userName);
    }

    public void clearDefaultRoleCache() {
        if (LOG.isDebugEnabled())
            LOG.debug("clearDefaultRoleCache ");
        defaultRoleCache.invalidateAll();
    }

    public void roleGrantCacheAdd(Pair<String, String> key, Optional<RoleGrantDescriptor> optional) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("roleGrantCacheAdd " + key);
        roleGrantCache.put(key,optional);
    }

    @SuppressFBWarnings(value = "NP_OPTIONAL_RETURN_NULL", justification = "DB-9844")
    public Optional<RoleGrantDescriptor> roleGrantCacheFind(Pair<String, String> key) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("roleGrantCacheFind " + key);
        return roleGrantCache.getIfPresent(key);
    }

    public void roleGrantCacheRemove(Pair<String, String> key) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("roleGrantCacheRemove " + key);
        roleGrantCache.invalidate(key);
    }

    public void clearRoleGrantCache() {
        if (LOG.isDebugEnabled())
            LOG.debug("clearRoleGrantCache ");
        roleGrantCache.invalidateAll();
    }

    public void tokenCacheAdd(byte[] token, TokenDescriptor tokenDescriptor) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        tokenCache.put(ByteSlice.wrap(token),tokenDescriptor);
    }

    public TokenDescriptor tokenCacheFind(byte[] token) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        return tokenCache.getIfPresent(ByteSlice.wrap(token));
    }

    public void tokenCacheRemove(byte[] token) throws StandardException {
        tokenCache.invalidate(ByteSlice.wrap(token));
    }

    public ManagedCache<String, Optional<String>> getPropertyCache() {
        return this.propertyCache;
    }

    public void setPropertyCache(ManagedCache<String, Optional<String>> propertyCache) {
        this.propertyCache = propertyCache;
    }

    public void propertyCacheAdd(String key, Optional<String> optional) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("propertyCacheAdd " + key);
        propertyCache.put(key,optional);
    }

    public Optional<String> propertyCacheFind(String key) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("propertyCacheFind " + key);
        return propertyCache.getIfPresent(key);
    }

    public void propertyCacheRemove(String key) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("propertyCacheRemove " + key);
        propertyCache.invalidate(key);
    }

    public void clearPropertyCache() {
        if (LOG.isDebugEnabled())
            LOG.debug("clearPropertyCache ");
        propertyCache.invalidateAll();
    }

    public void catalogVersionCacheAdd(Long key, Optional<String> optional) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("catalogVersionCacheAdd " + key);
        catalogVersionCache.put(key,optional);
    }

    public Optional<String> catalogVersionCacheFind(Long key) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("catalogVersionCacheFind " + key);
        return catalogVersionCache.getIfPresent(key);
    }

    public void catalogVersionCacheRemove(Long key) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("catalogVersionCacheRemove " + key);
        catalogVersionCache.invalidate(key);
    }

    public void clearCatalogVersionCache() {
        if (LOG.isDebugEnabled())
            LOG.debug("clearCatalogVersionCache");
        catalogVersionCache.invalidateAll();
    }

    public ConglomerateDescriptor conglomerateDescriptorCacheFind(long conglomId) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        
        if (LOG.isDebugEnabled())
            LOG.debug("conglomerateDescriptorCacheFind " + conglomId);
        return conglomerateDescriptorCache.getIfPresent(conglomId);
    }


    public void conglomerateDescriptorCacheAdd(Long conglomId, ConglomerateDescriptor conglomerate) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("conglomerateDescriptorCacheAdd " + conglomId + " : " + conglomerate);
        conglomerateDescriptorCache.put(conglomId,conglomerate);
    }

    public void conglomerateDescriptorCacheRemove(Long conglomId) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("conglomerateDescriptorCacheRemove " + conglomId);
        conglomerateDescriptorCache.invalidate(conglomId);
    }

    public void constraintDescriptorListCacheAdd(UUID id, ConstraintDescriptorList item) throws StandardException {
        if (!dd.canWriteCache(null))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("constraintDescriptorListCacheAdd " + id);
        constraintDescriptorListCache.put(id,item);
    }

    public ConstraintDescriptorList constraintDescriptorListCacheFind(UUID id) throws StandardException {
        if (!dd.canReadCache(null))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("constraintDescriptorListCacheFind " + id);
        return constraintDescriptorListCache.getIfPresent(id);
    }

    public void constraintDescriptorListCacheRemove(UUID id) throws StandardException {
        if (LOG.isDebugEnabled())
            LOG.debug("constraintDescriptorListCacheRemove " + id);
        constraintDescriptorListCache.invalidate(id);
    }

    public void clearConstraintDescriptorListCache() {
        if (LOG.isDebugEnabled())
            LOG.debug("clearConstraintDescriptorListCache ");
        constraintDescriptorListCache.invalidateAll();
    }

    @MXBean
    @SuppressWarnings("UnusedDeclaration")
    public interface DataDictionaryCacheIFace {

    }

    public void registerJMX(MBeanServer mbs) throws Exception{
        try{
            ManagedCache [] mc = new ManagedCache[] {oidTdCache, nameTdCache, spsNameCache, sequenceGeneratorCache, permissionsCache, partitionStatisticsCache, firstColumnStatsCache, storedPreparedStatementCache,
                    conglomerateCache, statementCache, databaseCache, schemaCache, aliasDescriptorCache, roleCache, defaultRoleCache, roleGrantCache, tokenCache, propertyCache, conglomerateDescriptorCache,
                    oidSchemaCache, catalogVersionCache, txnAwareConglomerateCache, constraintDescriptorListCache};
            //Passing in objects from mc array and names of objects from cacheNames array (static above)
            for(int i = 0; i < mc.length; i++){
                ObjectName cacheName = new ObjectName("com.splicemachine.db.impl.sql.catalog:type="+cacheNames[i]);
                mbs.registerMBean(mc[i],cacheName);
            }
            ObjectName totCache = new ObjectName("com.splicemachine.db.impl.sql.catalog:type=TotalManagedCache");
            TotalManagedCache tm = new TotalManagedCache(Arrays.asList(mc));
            mbs.registerMBean(tm, totCache);
        }catch(InstanceAlreadyExistsException ignored){
            /*
             * For most purposes, this should never happen. However, it's possible to happen
             * when you are booting a regionserver and master in the same JVM (e.g. for testing purposes); Since
             * we can only really have one version of the software on a single node at one time, we just ignore
             * this exception and don't worry about it too much.
             */
        }

    }

}
