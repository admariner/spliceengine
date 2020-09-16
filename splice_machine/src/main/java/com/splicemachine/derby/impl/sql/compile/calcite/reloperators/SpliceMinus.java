package com.splicemachine.derby.impl.sql.compile.calcite.reloperators;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.impl.sql.compile.ResultSetNode;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

import java.util.List;

public class SpliceMinus extends Minus implements SpliceRelNode {
    public SpliceMinus(RelOptCluster cluster, RelTraitSet traitSet, List<RelNode> inputs, boolean all ) {
        super(cluster, traitSet, inputs, all);
        assert getConvention() == SpliceRelNode.CONVENTION;
    }

    public SpliceMinus copy(RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
        return new SpliceMinus(getCluster(), traitSet, inputs, all);
    }

    @Override
    public ResultSetNode implement(SpliceImplementor implementor) throws StandardException {
        return null;
    }

    @Override public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        // add dummy cost
        return planner.getCostFactory().makeCost(4, 4, 4);
    }
}
