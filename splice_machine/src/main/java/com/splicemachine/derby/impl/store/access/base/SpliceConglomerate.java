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

package com.splicemachine.derby.impl.store.access.base;

import com.splicemachine.access.api.PartitionAdmin;
import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.access.configuration.SQLConfiguration;
import com.splicemachine.db.catalog.types.TypeMessage;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.cache.ClassSize;
import com.splicemachine.db.iapi.store.access.ColumnOrdering;
import com.splicemachine.db.iapi.store.access.StaticCompiledOpenConglomInfo;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.iapi.store.access.conglomerate.Conglomerate;
import com.splicemachine.db.iapi.store.access.conglomerate.TransactionManager;
import com.splicemachine.db.iapi.store.raw.RawStoreFactory;
import com.splicemachine.db.iapi.store.raw.Transaction;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.impl.store.access.conglomerate.ConglomerateUtil;
import com.splicemachine.db.impl.store.access.conglomerate.GenericConglomerate;
import com.splicemachine.derby.impl.store.access.BaseSpliceTransaction;
import com.splicemachine.derby.impl.store.access.btree.IndexConglomerate;
import com.splicemachine.derby.impl.store.access.hbase.HBaseConglomerate;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.datasketches.theta.UpdateSketch;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.log4j.Logger;
import org.apache.spark.sql.Row;

import java.io.IOException;
import java.io.ObjectInput;
import java.util.Properties;


public abstract class SpliceConglomerate extends GenericConglomerate implements Conglomerate, StaticCompiledOpenConglomInfo{
    private static final long serialVersionUID=7583841286945209190l;
    private static final Logger LOG=Logger.getLogger(SpliceConglomerate.class);
    protected int conglom_format_id;
    protected int tmpFlag;
    protected int[] format_ids;
    protected int[] collation_ids;
    protected int[] columnOrdering; // Primary Key Information
    protected int[] keyFormatIds;
    protected boolean hasCollatedTypes;
    protected long nextContainerId=System.currentTimeMillis();
    protected long containerId = -1l;

    protected TxnOperationFactory opFactory;
    protected PartitionFactory partitionFactory;

    public SpliceConglomerate(){
    }

    protected void create(
            Transaction rawtran,
            long input_containerid,
            DataValueDescriptor[] template,
            ColumnOrdering[] columnOrder,
            int[] keyFormatIds,
            int[] collationIds,
            Properties properties,
            int conglom_format_id,
            int tmpFlag,
            TxnOperationFactory opFactory,
            PartitionFactory partitionFactory) throws StandardException{
        this.opFactory = opFactory;
        this.partitionFactory = partitionFactory;
        if(properties!=null){
            String value=properties.getProperty(RawStoreFactory.MINIMUM_RECORD_SIZE_PARAMETER);
            int minimumRecordSize=(value==null)?RawStoreFactory.MINIMUM_RECORD_SIZE_DEFAULT:Integer.parseInt(value);
            if(minimumRecordSize<RawStoreFactory.MINIMUM_RECORD_SIZE_DEFAULT){
                properties.put(RawStoreFactory.MINIMUM_RECORD_SIZE_PARAMETER,Integer.toString(RawStoreFactory.MINIMUM_RECORD_SIZE_DEFAULT));
            }
        }
        if(columnOrder!=null){
            columnOrdering=new int[columnOrder.length];
            for(int i=0;i<columnOrder.length;i++){
                columnOrdering[i]=columnOrder[i].getColumnId();
            }
        }else{
            columnOrdering=new int[0];
        }
        containerId=input_containerid;
        if((template==null) || (template.length==0)){
            throw StandardException.newException(SQLState.HEAP_COULD_NOT_CREATE_CONGLOMERATE);
        }

        this.format_ids=ConglomerateUtil.createFormatIds(template);
        this.conglom_format_id=conglom_format_id;
        collation_ids=ConglomerateUtil.createCollationIds(format_ids.length,collationIds);
        hasCollatedTypes=hasCollatedColumns(collation_ids);
        this.tmpFlag=tmpFlag;

        this.keyFormatIds = keyFormatIds != null ? keyFormatIds : new int[0];
        try{
            ((BaseSpliceTransaction)rawtran).setActiveState(false,false,null);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    public void boot_create(long containerid,DataValueDescriptor[] template){
        this.containerId = containerid;
        this.format_ids=ConglomerateUtil.createFormatIds(template);
    }

    synchronized long getNextId(){
        if(LOG.isTraceEnabled())
            LOG.trace("getNextId ");
        return nextContainerId++;
    }

    public int estimateMemoryUsage(){
        if(LOG.isTraceEnabled())
            LOG.trace("estimate Memory Usage");
        int sz=getBaseMemoryUsage();
        if(null!=format_ids)
            sz+=format_ids.length*ClassSize.getIntSize();
        return sz;
    }


    public final long getId(){
        if(LOG.isTraceEnabled())
            LOG.trace("getId ");
        return containerId;
    }

    public void setId(long containerId){
       this.containerId = containerId;
    }

    public boolean[] getAscDescInfo(){
        return null;
    }

    public final long getContainerid(){
        return containerId;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP",justification = "Intentional")
    public int[] getFormat_ids(){
        return format_ids;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP",justification = "Intentional")
    public int[] getCollation_ids(){
        return collation_ids;
    }

    public boolean isNull(){
        return containerId == -1l;
    }

    /**
     * Is this conglomerate temporary?
     * <p/>
     *
     * @return whether conglomerate is temporary or not.
     **/
    public boolean isTemporary(){
        if(LOG.isTraceEnabled())
            LOG.trace("isTemporary ");
        return (tmpFlag&TransactionController.IS_TEMPORARY)==TransactionController.IS_TEMPORARY;
    }

    public void restoreToNull(){
        containerId=-1l;
    }

    public String toString(){
        return (containerId==-1l)?"null":""+containerId;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP",justification = "Intentional")
    public int[] getColumnOrdering(){
        return columnOrdering;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",justification = "Intentional")
    public void setColumnOrdering(int[] columnOrdering){
        this.columnOrdering=columnOrdering;
    }

    public abstract int getBaseMemoryUsage();

    public abstract int getTypeFormatId();

    @Override
    public boolean equals(Object o){
        if(this==o) return true;
        if(!(o instanceof SpliceConglomerate)) return false;
        if(!super.equals(o)) return false;

        SpliceConglomerate that=(SpliceConglomerate)o;

        return containerId==that.containerId;

    }

    @Override
    public int hashCode(){
        return (int)(containerId^(containerId>>>32));
    }

    @Override
    public void read(Row unsafeRow, int ordinal) throws StandardException {
        throw new UnsupportedOperationException("Not Implemented");
    }

    @Override
    public void updateThetaSketch(UpdateSketch updateSketch) {
        throw new UnsupportedOperationException("Not Implemented");
    }

    @Override
    public Object getSparkObject() throws StandardException {
        throw new UnsupportedOperationException("Not Implemented");
    }

    @Override
    public void setSparkObject(Object sparkObject) throws StandardException {
        throw new UnsupportedOperationException("Not Implemented");
    }

    /**
     * Drop this hbase conglomerate (what's the relationship with dropping container).
     *
     * @throws StandardException Standard exception policy.
     * @see Conglomerate#drop
     **/
    public void drop(TransactionManager xact_manager) throws StandardException{
        SpliceLogUtils.trace(LOG,"drop with account manager %s",xact_manager);
        try(PartitionAdmin pa=partitionFactory.getAdmin()){
            pa.deleteTable(Long.toString(containerId));
        }catch(IOException e){
            throw Exceptions.parseException(e);
        }
    }

    /**
     * Restore the in-memory representation from the stream.
     * <p/>
     *
     * @throws ClassNotFoundException Thrown if the stored representation
     *                                is serialized and a class named in
     *                                the stream could not be found.
     * @see java.io.Externalizable#readExternal
     **/
    @Override
    public void readExternal(ObjectInput in) throws IOException {
        if (LOG.isTraceEnabled()) {
            SpliceLogUtils.trace(LOG, "localReadExternal");
        }
        try {
            SIDriver driver = SIDriver.driver();
            boolean useNew = false;

            if (driver != null) {
                partitionFactory = driver.getTableFactory();
                opFactory = driver.getOperationFactory();
                if (driver.isEngineStarted()) {
                    // In that case, the upgrade must have happened, so we must have version > 1
                    useNew = true;
                } else {
                    String version = getConglomerateVersion();
                    useNew = (version != null && !version.equals("1"));
                }
            }

            if (useNew) {
                readExternalNew(in);
            } else {
                readExternalOld(in);
            }
        } catch (StandardException e) {
            throw new IOException(e);
        }
    }

    public static SpliceConglomerate fromProtobuf(TypeMessage.SpliceConglomerate spliceConglomerate) {
        TypeMessage.SpliceConglomerate.Type type = spliceConglomerate.getType();
        if (type == TypeMessage.SpliceConglomerate.Type.HBaseConglomerate) {
            TypeMessage.HBaseConglomerate hbaseConglomerate =
                    spliceConglomerate.getExtension(TypeMessage.HBaseConglomerate.hbaseConglomerate);
            return new HBaseConglomerate(hbaseConglomerate);

         }
        else if (type == TypeMessage.SpliceConglomerate.Type.IndexConglomerate) {
            TypeMessage.IndexConglomerate indexConglomerate =
                    spliceConglomerate.getExtension(TypeMessage.IndexConglomerate.indexConglomerate);
            return new IndexConglomerate(indexConglomerate);
        }

        throw new RuntimeException("Unexpected type " + type);
    }

    protected String getConglomerateVersion() throws IOException, StandardException {
        PartitionAdmin admin = partitionFactory.getAdmin();
        return admin.getCatalogVersion(SQLConfiguration.CONGLOMERATE_TABLE_NAME);
    }

    public int[] getKeyFormatIds() {
        return keyFormatIds;
    }

    public void setKeyFormatIds(int[] keyFormatIds) {
        this.keyFormatIds = keyFormatIds;
    }

    @Override
    public void dropColumn(TransactionManager xact_manager,int storagePosition, int position) throws StandardException{
        boolean dropColumnOrdering = false;
        for (int pos : columnOrdering) {
            if (pos == storagePosition - 1) {
                dropColumnOrdering = true;
                break;
            }
        }
        if (dropColumnOrdering) {
            columnOrdering = new int[0];
        }

        if (keyFormatIds == null) {
            keyFormatIds = new int[columnOrdering.length];
            for (int i = 0; i < keyFormatIds.length; ++i) {
                keyFormatIds[i] = format_ids[columnOrdering[i]];
            }
        }
    }
}
