/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.iterative;

import com.facebook.presto.Session;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.PlanNodeCostEstimate;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.plan.PlanNode;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static java.util.Objects.requireNonNull;

@NotThreadSafe
public class NonResolvingCachingLookup
        implements Lookup
{
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;

    private final Map<PlanNode, PlanNodeStatsEstimate> stats = new HashMap<>();
    private final Map<PlanNode, PlanNodeCostEstimate> cummulativeCosts = new HashMap<>();

    public NonResolvingCachingLookup(StatsCalculator statsCalculator, CostCalculator costCalculator)
    {
        this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
        this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
    }

    @Override
    public PlanNode resolve(PlanNode node)
    {
        verify(!(node instanceof GroupReference), "Unexpected GroupReference");
        return node;
    }

    @Override
    public PlanNodeStatsEstimate getStats(PlanNode planNode, Session session, Map<Symbol, Type> types)
    {
        if (!stats.containsKey(planNode)) {
            // cannot use Map.computeIfAbsent due to stats map modification in the mappingFunction callback
            PlanNodeStatsEstimate statsEstimate = statsCalculator.calculateStats(planNode, this, session, types);
            requireNonNull(stats, "computed stats can not be null");
            checkState(stats.put(planNode, statsEstimate) == null, "statistics for " + planNode + " already computed");
        }
        return stats.get(planNode);
    }

    @Override
    public PlanNodeCostEstimate getCumulativeCost(PlanNode planNode, Session session, Map<Symbol, Type> types)
    {
        if (!cummulativeCosts.containsKey(planNode)) {
            // cannot use Map.computeIfAbsent due to costs map modification in the mappingFunction callback
            PlanNodeCostEstimate cost = costCalculator.calculateCumulativeCost(planNode, this, session, types);
            requireNonNull(cummulativeCosts, "computed cost can not be null");
            checkState(cummulativeCosts.put(planNode, cost) == null, "cost for " + planNode + " already computed");
        }
        return cummulativeCosts.get(planNode);
    }
}
