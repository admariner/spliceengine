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

package com.splicemachine.derby.stream.function.merge;

import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.impl.sql.execute.operations.*;
import com.splicemachine.derby.stream.iapi.OperationContext;
import com.splicemachine.derby.stream.iterator.merge.AbstractMergeJoinIterator;
import com.splicemachine.derby.stream.iterator.merge.MergeOuterJoinIterator;
import splice.com.google.common.collect.PeekingIterator;

/**
 * Created by jleach on 6/9/15.
 */
public class MergeOuterJoinFlatMapFunction extends AbstractMergeJoinFlatMapFunction {
    public MergeOuterJoinFlatMapFunction() {
        super();
    }

    public MergeOuterJoinFlatMapFunction(OperationContext<JoinOperation> operationContext, boolean useOldMergeJoin) {
        super(operationContext, useOldMergeJoin);
    }

    @Override
    protected AbstractMergeJoinIterator createMergeJoinIterator(PeekingIterator<ExecRow> leftPeekingIterator, PeekingIterator<ExecRow> rightPeekingIterator, int[] leftHashKeys, int[] rightHashKeys, JoinOperation mergeJoinOperation, OperationContext<JoinOperation> operationContext) {
        return new MergeOuterJoinIterator(leftPeekingIterator,
                rightPeekingIterator,
                leftHashKeys, rightHashKeys,
                mergeJoinOperation, operationContext);
    }
}
