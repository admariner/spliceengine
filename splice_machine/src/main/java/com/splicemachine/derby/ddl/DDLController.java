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

package com.splicemachine.derby.ddl;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.ddl.DDLMessage;

public interface DDLController {

    /**
     * Notify remote nodes that a DDL change is in progress (but not yet committed).
     */
    String notifyMetadataChange(DDLMessage.DDLChange change) throws StandardException;

    /**
     * Notify remote nodes that the DDL change has been committed.
     */
    void finishMetadataChange(String changeId) throws StandardException;

}
