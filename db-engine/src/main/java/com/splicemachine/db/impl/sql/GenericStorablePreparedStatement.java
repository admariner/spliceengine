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

package com.splicemachine.db.impl.sql;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.splicemachine.db.catalog.types.TypeMessage;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.services.io.ArrayUtil;
import com.splicemachine.db.iapi.services.io.DataInputUtil;
import com.splicemachine.db.iapi.services.io.Formatable;
import com.splicemachine.db.iapi.services.io.StoredFormatIds;
import com.splicemachine.db.iapi.services.loader.ClassFactory;
import com.splicemachine.db.iapi.services.loader.GeneratedClass;
import com.splicemachine.db.iapi.sql.ResultDescription;
import com.splicemachine.db.iapi.sql.Statement;
import com.splicemachine.db.iapi.sql.StorablePreparedStatement;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.execute.ConstantAction;
import com.splicemachine.db.iapi.sql.execute.ExecPreparedStatement;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.ProtobufUtils;
import com.splicemachine.db.iapi.util.ByteArray;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.*;

/**
 * Prepared statement that can be made persistent.
 */
public class GenericStorablePreparedStatement extends GenericPreparedStatement
        implements Formatable, StorablePreparedStatement {

    // formatable
    private ByteArray byteCode;
    private String className;

    /**
     * Default constructor, for formatable only.
     */
    public GenericStorablePreparedStatement() {
        super();
    }

    public GenericStorablePreparedStatement(Statement stmt) {
        super(stmt);
    }

    /**
     * Get our byte code array.  Used by others to save off our byte code for us.
     *
     * @return the byte code saver
     */
    @Override
    public ByteArray getByteCodeSaver() {
        if (byteCode == null) {
            byteCode = new ByteArray();
        }
        return byteCode;
    }

    /**
     * Get and load the activation class.  Will always return a loaded/valid class or null if the class cannot be loaded.
     *
     * @return the generated class, or null if the class cannot be loaded
     */
    @Override
    public GeneratedClass getActivationClass() throws StandardException {
        if (activationClass == null) {
            loadGeneratedClass();
        }
        return activationClass;
    }

    @Override
    public void setActivationClass(GeneratedClass ac) {

        super.setActivationClass(ac);
        if (ac != null) {
            className = ac.getName();

            // see if this is an pre-compiled class
            if (byteCode != null && byteCode.getArray() == null)
                byteCode = null;
        }
    }

    /////////////////////////////////////////////////////////////
    //
    // STORABLEPREPAREDSTATEMENT INTERFACE
    //
    /////////////////////////////////////////////////////////////

    /**
     * Load up the class from the saved bytes.
     */
    @Override
    public void loadGeneratedClass() throws StandardException {
        LanguageConnectionContext lcc = (LanguageConnectionContext) ContextService.getContext
                (LanguageConnectionContext.CONTEXT_ID);
        ClassFactory classFactory = lcc.getLanguageConnectionFactory().getClassFactory();
        GeneratedClass gc = classFactory.loadGeneratedClass(className, byteCode);
       /* No special try catch logic to write out bad classes here.  We don't expect any problems, and in any
        * event, we don't have the class builder available here. */
        setActivationClass(gc);
    }

    @Override
    public ExecPreparedStatement getClone() throws StandardException {
        GenericStorablePreparedStatement clone = new GenericStorablePreparedStatement(statement);
        shallowClone(clone);
        clone.className = this.className;
        clone.byteCode = this.byteCode;
        return clone;
    }

    /////////////////////////////////////////////////////////////
    //
    // EXTERNALIZABLE INTERFACE
    //
    /////////////////////////////////////////////////////////////

    @Override
    public void writeExternal( ObjectOutput out ) throws IOException {
        if (DataInputUtil.shouldWriteOldFormat()) {
            writeExternalOld(out);
        }
        else {
            writeExternalNew(out);
        }
    }
    protected void writeExternalNew(ObjectOutput out) throws IOException {
        CatalogMessage.GenericStorablePreparedStatement statement = toProtobuf();
        ArrayUtil.writeByteArray(out, statement.toByteArray());
    }

    public CatalogMessage.GenericStorablePreparedStatement toProtobuf() throws IOException{
        CatalogMessage.GenericStorablePreparedStatement.Builder builder =
                CatalogMessage.GenericStorablePreparedStatement.newBuilder();
        CursorInfo cursorInfo = (CursorInfo)getCursorInfo();
        builder.setCursorInfo(cursorInfo.toProtobuf())
                .setNeedsSavepoint(needsSavepoint())
                .setIsAtomic(isAtomic);
        if (executionConstants != null) {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 ObjectOutputStream os = new ObjectOutputStream(bos)) {
                os.writeObject(executionConstants);
                os.flush();
                byte[] bs = bos.toByteArray();
                builder.setExecutionConstants(ByteString.copyFrom(bs));
            }
        }
        if (resultDesc != null) {
            builder.setResultDescription(((GenericResultDescription)resultDesc).toProtobuf());
        }
        if (savedObjects != null) {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 ObjectOutputStream os = new ObjectOutputStream(bos)) {
                ArrayUtil.writeArrayLength(os, savedObjects);
                ArrayUtil.writeArrayItems(os, savedObjects);
                os.flush();
                byte[] bs = bos.toByteArray();
                builder.setSavedObjects(ByteString.copyFrom(bs));
            }
        }
        if (className != null) {
            builder.setClassName(className);
        }
        if (byteCode != null) {
            builder.setByteCode(byteCode.toProtobuf());
        }
        if (paramTypeDescriptors != null) {
            for (int i = 0; i < paramTypeDescriptors.length; ++i) {
                if (paramTypeDescriptors[i] != null) {
                    builder.addParamTypeDescriptors(paramTypeDescriptors[i].toProtobuf());
                }
            }
        }
        return builder.build();
    }
    @SuppressFBWarnings("DMI_NONSERIALIZABLE_OBJECT_WRITTEN") // todo in DB-10583
    protected void writeExternalOld(ObjectOutput out) throws IOException {

        // DANGER: do NOT change this serialization unless you have an upgrade script, see DB-10566
        out.writeObject(getCursorInfo());
        out.writeBoolean(needsSavepoint());
        out.writeBoolean(isAtomic);
        out.writeObject(executionConstants); // DB-10583
        out.writeObject(resultDesc);

        // savedObjects may be null
        if (savedObjects == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            ArrayUtil.writeArrayLength(out, savedObjects);
            ArrayUtil.writeArrayItems(out, savedObjects);
        }

        /*  Write out the class name and byte code if we have them.  They might be null if we don't want to write out
         * the plan, and would prefer it just write out null (e.g. we know the plan is invalid). */
        out.writeObject(className);
        out.writeBoolean(byteCode != null);
        if (byteCode != null)
            byteCode.writeExternal(out);

        out.writeBoolean(paramTypeDescriptors != null);
        if (paramTypeDescriptors != null) {
            ArrayUtil.writeArrayLength(out, paramTypeDescriptors);
            ArrayUtil.writeArrayItems(out, paramTypeDescriptors);
        }
    }

    @Override
    public void readExternal( ObjectInput in ) throws IOException, ClassNotFoundException {
        if (DataInputUtil.shouldReadOldFormat()) {
            readExternalOld(in);
        }
        else {
            readExternalNew(in);
        }
    }

    protected void readExternalNew(ObjectInput in) throws IOException {
        byte[] bs = ArrayUtil.readByteArray(in);
        ExtensionRegistry extensionRegistry = ExtensionRegistry.newInstance();
        extensionRegistry.add(CatalogMessage.InsertConstantOperation.insertConstantOperation);
        extensionRegistry.add(CatalogMessage.UpdateConstantOperation.updateConstantOperation);
        extensionRegistry.add(CatalogMessage.DeleteConstantOperation.deleteConstantOperation);
        extensionRegistry.add(TypeMessage.SpliceConglomerate.spliceConglomerate);
        extensionRegistry.add(TypeMessage.HBaseConglomerate.hbaseConglomerate);
        extensionRegistry.add(TypeMessage.IndexConglomerate.indexConglomerate);
        CatalogMessage.GenericStorablePreparedStatement statement =
                CatalogMessage.GenericStorablePreparedStatement.parseFrom(bs, extensionRegistry);
        init(statement);
    }

    private void init(CatalogMessage.GenericStorablePreparedStatement statement) throws IOException {
        if (statement.hasCursorInfo()) {
            setCursorInfo(ProtobufUtils.fromProtobuf(statement.getCursorInfo()));
        }
        setNeedsSavepoint(statement.getNeedsSavepoint());
        isAtomic = statement.getIsAtomic();
        if (statement.hasExecutionConstants()) {
            byte[] ba = statement.getExecutionConstants().toByteArray();
            try (ByteArrayInputStream bis = new ByteArrayInputStream(ba);
                 ObjectInputStream ois = new ObjectInputStream(bis)) {
                executionConstants = (ConstantAction)ois.readObject();
            }
            catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        if (statement.hasResultDescription()) {
            resultDesc = ProtobufUtils.fromProtobuf(statement.getResultDescription());
        }
        if (statement.hasSavedObjects()) {
            byte[] ba = statement.getSavedObjects().toByteArray();
            try (ByteArrayInputStream bis = new ByteArrayInputStream(ba);
                 ObjectInputStream ois = new ObjectInputStream(bis)) {
                savedObjects = new Object[ArrayUtil.readArrayLength(ois)];
                ArrayUtil.readArrayItems(ois, savedObjects);
            }
            catch (ClassNotFoundException e) {
                throw new IOException(e);
            }

        }
        className = statement.hasClassName() ? statement.getClassName() : null;
        if (statement.hasByteCode()) {
            byteCode = ProtobufUtils.fromProtobuf(statement.getByteCode());
        }

        int length = statement.getParamTypeDescriptorsCount();
        if (length > 0) {
            paramTypeDescriptors = new DataTypeDescriptor[length];
            for (int i = 0; i < length; ++i) {
                paramTypeDescriptors[i] = ProtobufUtils.fromProtobuf(statement.getParamTypeDescriptors(i));
            }
        }
    }

    protected void readExternalOld(ObjectInput in) throws IOException, ClassNotFoundException {
        setCursorInfo((CursorInfo) in.readObject());
        setNeedsSavepoint(in.readBoolean());
        isAtomic = (in.readBoolean());
        executionConstants = (ConstantAction) in.readObject();
        resultDesc = (ResultDescription) in.readObject();

        if (in.readBoolean()) {
            savedObjects = new Object[ArrayUtil.readArrayLength(in)];
            ArrayUtil.readArrayItems(in, savedObjects);
        }

        className = (String) in.readObject();
        if (in.readBoolean()) {
            byteCode = new ByteArray();
            byteCode.readExternal(in);
        } else
            byteCode = null;

        if (in.readBoolean()) {
            paramTypeDescriptors = new DataTypeDescriptor[ArrayUtil.readArrayLength(in)];
            ArrayUtil.readArrayItems(in, paramTypeDescriptors);
        }
    }

    /////////////////////////////////////////////////////////////
    //
    // FORMATABLE INTERFACE
    //
    /////////////////////////////////////////////////////////////

    /**
     * Get the formatID which corresponds to this class.
     */
    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.STORABLE_PREPARED_STATEMENT_V01_ID;
    }

    /////////////////////////////////////////////////////////////
    //
    // MISC
    //
    /////////////////////////////////////////////////////////////

    @Override
    public boolean isStorable() {
        return true;
    }

    @Override
    public String toString() {
        String acn;
        if (activationClass == null)
            acn = "null";
        else
            acn = activationClass.getName();

        return "GenericStorablePreparedStatement activationClassName=" + acn + " className=" + className;
    }


}

