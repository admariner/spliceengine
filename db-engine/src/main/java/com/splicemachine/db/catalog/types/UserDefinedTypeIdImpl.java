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

package com.splicemachine.db.catalog.types;

import com.splicemachine.db.iapi.services.io.StoredFormatIds;
import com.splicemachine.db.iapi.util.IdUtil;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.impl.sql.CatalogMessage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.ObjectOutput;
import java.io.ObjectInput;
import java.io.IOException;

/**
 * <p>
 * This type id describes a user defined type. There are 2 kinds of user defined
 * types in Derby:
 * </p>
 *
 * <ul>
 * <li><b>Old-fashioned</b> - In the original Cloudscape code, it was possible
 * to declare a column's type to be the name of a Java class. Unlike ANSI
 * UDTs, these user defined types were not schema objects themselves and they
 * didn't have schema-qualified names. Some of the system tables have columns
 * whose datatypes are old-fashioned user defined types. E.g., SYS.SYSALIASES.ALIASINFO.</li>
 * <li><b>ANSI</b> - As part of the work on
 * <a href="https://issues.apache.org/jira/browse/DERBY-651">DERBY-651</a>,
 * we added ANSI UDTs. These are user defined types which are declared via the
 * CREATE TYPE statement. These have schema-qualified names. The CREATE TYPE
 * statement basically binds a schema-qualified name to the name of a Java class.</li>
 * </ul>
 */
@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS",justification = "Intentional")
public class UserDefinedTypeIdImpl extends BaseTypeIdImpl
{
	/********************************************************
	**
	**	This class implements Formatable. That means that it
	**	can write itself to and from a formatted stream. If
	**	you add more fields to this class, make sure that you
	**	also write/read them with the writeExternal()/readExternal()
	**	methods.
	**
	**	If, inbetween releases, you add more fields to this class,
	**	then you should bump the version number emitted by the getTypeFormatId()
	**	method.
	**
	********************************************************/

	protected String className;

	/**
	 * Public niladic constructor. Needed for Formatable interface to work.
	 *
	 */
	public	UserDefinedTypeIdImpl() { super(); }

	/**
	 * Constructor for a UserDefinedTypeIdImpl. The SQLTypeName of a UserDefinedType
	 * is assumed to be its className for Derby-only UserDefinedTypes. For
	 * actual user created UDTs, the SQLTypeName is a schema qualified name.
	 *
	 * @param className	The SQL name of the type
	 */

	public UserDefinedTypeIdImpl(String className) throws StandardException
	{
        //
        // If the name begins with a quote, then it is the schema-qualified name
        // of a UDT. Parse the name.
        //
        if ( className.charAt( 0 ) == '"' )
        {
            String[] nameParts = IdUtil.parseMultiPartSQLIdentifier( className );
            
            schemaName = nameParts[ 0 ];
            unqualifiedName = nameParts[ 1 ];
            className = null;
        }
        else
        {
            schemaName = null;
            unqualifiedName = className;
            this.className = className;
        }
        
        JDBCTypeId = java.sql.Types.JAVA_OBJECT;
	}

	/**
	 * Constructor for a UDT.
	 *
	 * @param schemaName	Schema that the UDT lives in.
	 * @param unqualifiedName	The name of the type inside that schema.
	 * @param className	The Java class  bound to the SQL type.
	 */

	public UserDefinedTypeIdImpl(String schemaName, String unqualifiedName, String className)
	{
		super( schemaName, unqualifiedName );
		this.className = className;
		JDBCTypeId = java.sql.Types.JAVA_OBJECT;
	}

	public UserDefinedTypeIdImpl(CatalogMessage.BaseTypeIdImpl typeId) {
		init(typeId);
	}

	/** Return the java class name for this type */
	public String	getClassName()
	{
		return className;
	}

	/** Does this type id represent a user type? */
	public boolean userType()
	{
		return true;
	}
    
	/** Has this user type been bound? */
	public boolean isBound() { return !(className == null); }

	@Override
	protected void init(CatalogMessage.BaseTypeIdImpl typeId) {
		super.init(typeId);
		CatalogMessage.UserDefinedTypeIdImpl userDefinedTypeId
				= typeId.getExtension(CatalogMessage.UserDefinedTypeIdImpl.userDefinedTypeImpl);
		this.className = userDefinedTypeId.getClassName();
		this.JDBCTypeId = java.sql.Types.JAVA_OBJECT;
	}

	@Override
	protected void readExternalOld( ObjectInput in ) throws IOException, ClassNotFoundException {
		super.readExternalOld( in );
		className = in.readUTF();
		JDBCTypeId = java.sql.Types.JAVA_OBJECT;
	}

	/**
	 * Write this object to a stream of stored objects.
	 *
	 * @param out write bytes here.
	 *
	 * @exception IOException		thrown on error
	 */
	@Override
	public void writeExternal( ObjectOutput out )
			throws IOException
	{
		// If the class name is null, then an internal error has occurred. We
		// are trying to persist a UDT descriptor which has not been bound yet
		if ( className == null )
		{
			throw new IOException( "Internal error: class name for user defined type has not been determined yet." );
		}

		super.writeExternal(out);
	}

	@Override
	protected void writeExternalOld( ObjectOutput out ) throws IOException {
		super.writeExternalOld( out );

		// If the class name is null, then an internal error has occurred. We
		// are trying to persist a UDT descriptor which has not been bound yet
		if ( className == null )
		{
			throw new IOException( "Internal error: class name for user defined type has not been determined yet." );
		}
		out.writeUTF( className );
	}
	/**
	 * Get the formatID which corresponds to this class.
	 *
	 *	@return	the formatID of this class
	 */
	public	int	getTypeFormatId()	{ return StoredFormatIds.USERDEFINED_TYPE_ID_IMPL_V3; }

	@Override
	public CatalogMessage.BaseTypeIdImpl toProtobuf() {
		CatalogMessage.UserDefinedTypeIdImpl userDefinedTypeId = CatalogMessage.UserDefinedTypeIdImpl.newBuilder()
				.setClassName(className)
				.build();

		CatalogMessage.BaseTypeIdImpl baseTypeId = super.toProtobuf();
		CatalogMessage.BaseTypeIdImpl.Builder builder = CatalogMessage.BaseTypeIdImpl.newBuilder().mergeFrom(baseTypeId);
		builder.setType(CatalogMessage.BaseTypeIdImpl.Type.UserDefinedTypeIdImpl)
				.setExtension(CatalogMessage.UserDefinedTypeIdImpl.userDefinedTypeImpl, userDefinedTypeId);
		return builder.build();
	}
}
