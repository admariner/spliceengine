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

package com.splicemachine.db.impl.sql.compile.subquery;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.impl.ast.CorrelatedColRefCollectingVisitor;
import com.splicemachine.db.impl.sql.compile.BinaryRelationalOperatorNode;
import com.splicemachine.db.impl.sql.compile.ColumnReference;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import splice.com.google.common.base.Predicate;

/**
 * A predicate that evaluates to true if a given BinaryRelationalOperatorNode has a single occurrence
 * of a correlated column at sourceLevel.
 *
 * This can be used to find correlated inequality predicates in a subquery that are referencing one level up.
 */
public class CorrelatedInequalityBronPredicate implements Predicate<BinaryRelationalOperatorNode> {

    private CorrelatedColRefCollectingVisitor correlatedColRefCollectingVisitor;

    public CorrelatedInequalityBronPredicate(int sourceLevel) {
        correlatedColRefCollectingVisitor =
            new CorrelatedColRefCollectingVisitor<>(1, sourceLevel);
    }

    @Override
    public boolean apply(BinaryRelationalOperatorNode bron) {
        try {
            correlatedColRefCollectingVisitor.initialize();
            bron.accept(correlatedColRefCollectingVisitor);
        }
        catch (StandardException e) {
            return false;
        }

        return !correlatedColRefCollectingVisitor.getCollected().isEmpty();
    }

    public ColumnReference popCorrelatedColumn() {
        return (ColumnReference)correlatedColRefCollectingVisitor.popColumn();
    }


}