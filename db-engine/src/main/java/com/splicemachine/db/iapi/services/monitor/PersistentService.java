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

package com.splicemachine.db.iapi.services.monitor;

import com.splicemachine.db.io.StorageFactory;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.Property;

import java.util.Properties;
import java.util.Enumeration;

import java.io.IOException;

/**
    A PersistentService modularises the access to persistent services,
    abstracting out details such as finding the list of services to
    be started at boot time, finding the service.properties file
    and creating and deleting the persistent storage for a service.
<P>
    These modules must only be used by the monitor.
<P>
    Possible examples of implementations are:

    <UL>
    <LI> Store services in a directory in the file system.
    <LI> Store services in a zip file
    <LI> Service data is provided by a web server
    <LI> Service data is stored on the class path.
    </UL>
<P>
    This class also serves as the registry the defined name for all
    the implementations of PersistentService. These need to be kept track
    of as they can be used in JDBC URLS.
<P>
    An implementation of PersistentService can implement ModuleSupportable
    but must not implement ModuleControl. This is because the monitor will
    not execute ModuleControl methods for a PersistentService.
*/

public interface PersistentService {

    /**
        Service stored in a directory.
    */
    String DIRECTORY = "directory";

    /**
        Service stored on the class path (can be in a zip/jar on the class path).
    */
    String CLASSPATH = "classpath";

    /**
        Service stored in a jar/zip archive.
    */
    String JAR = "jar";

    /**
        Service stored in a web server .
    */
    String HTTP = "http";
    String HTTPS = "https";

    /** Service stored in memory only (not persistent), virtual file memory. */
    String INMEMORY = "memory";

    /**
        The typical name for the service's properties file.
    */
    String PROPERTIES_NAME = "service.properties";

    /**
        The root of any stored data.
    */
    String ROOT = Property.PROPERTY_RUNTIME_PREFIX + "serviceDirectory";

    /**
        The type of PersistentService used to boot the service.
    */
    String TYPE = Property.PROPERTY_RUNTIME_PREFIX + "serviceType";

    /**
     *  The name of the service that is being booted
     */
    String SERVICE_NAME = Property.PROPERTY_RUNTIME_PREFIX + "serviceName";

    /**
        Return the type of this service.
    */
    String getType();

    /**
        Return an Enumeration of service names descriptors (Strings) that should be
        be started at boot time by the monitor. The monitor will boot the service if getServiceProperties()
        returns a Properties object and the properties does not indicate the service should not be
        auto-booted.
        <P>
        This method may return null if there are no services that need to be booted automatically at boot time.

        <P>
        The service name returned by the Enumeration must be in its canonical form.
    */
    Enumeration getBootTimeServices();

    /**
        For a service return its service properties, typically from the service.properties
        file.

        @return A Properties object or null if serviceName does not represent a valid service.

        @exception StandardException Service appears valid but the properties cannot be created.
    */
    Properties getServiceProperties(String serviceName, Properties defaultProperties)
        throws StandardException;

    /**
        @exception StandardException Properties cannot be saved.
    */
    void saveServiceProperties(String serviceName,
                               StorageFactory storageFactory,
                               Properties properties,
                               boolean replace)
        throws StandardException;

    /**
       Save to a backup file.
       
        @exception StandardException Properties cannot be saved.
    */
    void saveServiceProperties(String serviceName,
                               Properties properties)
        throws StandardException;

    /**
        Returns the canonical name of the service.

        @exception StandardException Service root cannot be created.
    */
    String createServiceRoot(String name, boolean deleteExisting)
        throws StandardException;

    /**
        Remove a service's root and its contents.
    */
    boolean removeServiceRoot(String serviceName);

    /**
        Convert a service name into its canonical form. Returns null if the name
        cannot be converted into a canonical form.

        @exception No canonical name, name probably invalid
    */
    String getCanonicalServiceName(String name)
        throws StandardException;

    /**
        Return the user form of a service name. This name is only valid within
        this system. The separator character used must be '/'
    */
    String getUserServiceName(String serviceName);


    boolean isSameService(String serviceName1, String serviceName2);

    /**
     * @return true if the PersistentService has a StorageFactory, false if not.
     */
    boolean hasStorageFactory();
    
    /**
     * Get an initialized StorageFactoryInstance
     *
     * @param useHome If true and the database name is not absolute then the database directory will be
     *                relative to the home directory, if one is defined in the properties file.
     * @param databaseName The name of the database (directory). The name does not include the subSubProtocol.
     *                     If null then the storage factory will only be used to deal with the directory containing
     *                     the databases.
     * @param tempDirName The name of the temporary file directory set in properties. If null then a default
     *                    directory should be used. Each database should get a separate temporary file
     *                    directory within this one to avoid collisions.
     * @param uniqueName A unique name that can be used to create the temporary file directory for this database.
     *                   If null then temporary files will not be created in this StorageFactory instance.
     *
     * @return An initialized StorageFactory.
     */
    StorageFactory getStorageFactoryInstance(boolean useHome,
                                             String databaseName,
                                             String tempDirName,
                                             String uniqueName)
        throws StandardException, IOException;
}
