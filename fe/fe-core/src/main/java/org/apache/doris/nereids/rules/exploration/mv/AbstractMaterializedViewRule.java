// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.exploration.mv;

import org.apache.doris.catalog.MTMV;
import org.apache.doris.catalog.Partition;
import org.apache.doris.catalog.PartitionInfo;
import org.apache.doris.catalog.PartitionType;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.common.Pair;
import org.apache.doris.mtmv.BaseTableInfo;
import org.apache.doris.mtmv.MTMVPartitionInfo;
import org.apache.doris.mtmv.MTMVUtil;
import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.jobs.executor.Rewriter;
import org.apache.doris.nereids.memo.GroupExpression;
import org.apache.doris.nereids.rules.exploration.ExplorationRuleFactory;
import org.apache.doris.nereids.rules.exploration.mv.Predicates.SplitPredicate;
import org.apache.doris.nereids.rules.exploration.mv.mapping.EquivalenceClassSetMapping;
import org.apache.doris.nereids.rules.exploration.mv.mapping.ExpressionMapping;
import org.apache.doris.nereids.rules.exploration.mv.mapping.RelationMapping;
import org.apache.doris.nereids.rules.exploration.mv.mapping.SlotMapping;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.EqualTo;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.functions.scalar.NonNullable;
import org.apache.doris.nereids.trees.expressions.functions.scalar.Nullable;
import org.apache.doris.nereids.trees.expressions.literal.BooleanLiteral;
import org.apache.doris.nereids.trees.expressions.literal.Literal;
import org.apache.doris.nereids.trees.plans.JoinType;
import org.apache.doris.nereids.trees.plans.ObjectId;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.algebra.CatalogRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.util.ExpressionUtils;
import org.apache.doris.nereids.util.TypeUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The abstract class for all materialized view rules
 */
public abstract class AbstractMaterializedViewRule implements ExplorationRuleFactory {
    public static final HashSet<JoinType> SUPPORTED_JOIN_TYPE_SET = Sets.newHashSet(JoinType.INNER_JOIN,
            JoinType.LEFT_OUTER_JOIN);

    /**
     * The abstract template method for query rewrite, it contains the main logic and different query
     * pattern should override the sub logic.
     */
    protected List<Plan> rewrite(Plan queryPlan, CascadesContext cascadesContext) {
        List<MaterializationContext> materializationContexts = cascadesContext.getMaterializationContexts();
        List<Plan> rewriteResults = new ArrayList<>();
        if (materializationContexts.isEmpty()) {
            return rewriteResults;
        }
        List<StructInfo> queryStructInfos = extractStructInfo(queryPlan, cascadesContext);
        // TODO Just Check query queryPlan firstly, support multi later.
        StructInfo queryStructInfo = queryStructInfos.get(0);
        if (!checkPattern(queryStructInfo)) {
            materializationContexts.forEach(ctx -> ctx.recordFailReason(queryStructInfo.getOriginalPlanId(),
                    Pair.of("Query struct info is invalid",
                            String.format("queryPlan is %s", queryPlan.treeString()))));
            return rewriteResults;
        }
        for (MaterializationContext materializationContext : materializationContexts) {
            // already rewrite, bail out
            if (checkIfRewritten(queryPlan, materializationContext)) {
                continue;
            }
            List<StructInfo> viewStructInfos = extractStructInfo(materializationContext.getMvPlan(), cascadesContext);
            if (viewStructInfos.size() > 1) {
                // view struct info should only have one
                materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                        Pair.of("The num of view struct info is more then one",
                                String.format("mv plan is %s", materializationContext.getMvPlan().treeString())));
                return rewriteResults;
            }
            StructInfo viewStructInfo = viewStructInfos.get(0);
            if (!checkPattern(viewStructInfo)) {
                materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                        Pair.of("View struct info is invalid",
                                String.format(", view plan is %s", viewStructInfo.getOriginalPlan().treeString())));
                continue;
            }
            MatchMode matchMode = decideMatchMode(queryStructInfo.getRelations(), viewStructInfo.getRelations());
            if (MatchMode.COMPLETE != matchMode) {
                materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                        Pair.of("Match mode is invalid", String.format("matchMode is %s", matchMode)));
                continue;
            }
            List<RelationMapping> queryToViewTableMappings = RelationMapping.generate(queryStructInfo.getRelations(),
                    viewStructInfo.getRelations());
            // if any relation in query and view can not map, bail out.
            if (queryToViewTableMappings == null) {
                materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                        Pair.of("Query to view table mapping is null", ""));
                return rewriteResults;
            }
            for (RelationMapping queryToViewTableMapping : queryToViewTableMappings) {
                SlotMapping queryToViewSlotMapping = SlotMapping.generate(queryToViewTableMapping);
                if (queryToViewSlotMapping == null) {
                    materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                            Pair.of("Query to view slot mapping is null", ""));
                    continue;
                }
                LogicalCompatibilityContext compatibilityContext = LogicalCompatibilityContext.from(
                        queryToViewTableMapping, queryToViewSlotMapping, queryStructInfo, viewStructInfo);
                ComparisonResult comparisonResult = StructInfo.isGraphLogicalEquals(queryStructInfo, viewStructInfo,
                        compatibilityContext);
                if (comparisonResult.isInvalid()) {
                    materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                            Pair.of("The graph logic between query and view is not consistent",
                                    comparisonResult.getErrorMessage()));
                    continue;
                }
                // TODO: Use set of list? And consider view expr
                List<Expression> pulledUpExpressions = ImmutableList.copyOf(comparisonResult.getQueryExpressions());
                // set pulled up expression to queryStructInfo predicates and update related predicates
                if (!pulledUpExpressions.isEmpty()) {
                    queryStructInfo.addPredicates(pulledUpExpressions);
                }
                SplitPredicate compensatePredicates = predicatesCompensate(queryStructInfo, viewStructInfo,
                        queryToViewSlotMapping, comparisonResult, cascadesContext);
                // Can not compensate, bail out
                if (compensatePredicates.isEmpty()) {
                    materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                            Pair.of("Predicate compensate fail",
                                    String.format("query predicates = %s,\n query equivalenceClass = %s, \n"
                                                    + "view predicates = %s,\n query equivalenceClass = %s\n",
                                            queryStructInfo.getPredicates(),
                                            queryStructInfo.getEquivalenceClass(),
                                            viewStructInfo.getPredicates(),
                                            viewStructInfo.getEquivalenceClass())));
                    continue;
                }
                Plan rewrittenPlan;
                Plan mvScan = materializationContext.getMvScanPlan();
                if (compensatePredicates.isAlwaysTrue()) {
                    rewrittenPlan = mvScan;
                } else {
                    // Try to rewrite compensate predicates by using mv scan
                    List<Expression> rewriteCompensatePredicates = rewriteExpression(compensatePredicates.toList(),
                            queryPlan, materializationContext.getMvExprToMvScanExprMapping(), queryToViewSlotMapping,
                            true);
                    if (rewriteCompensatePredicates.isEmpty()) {
                        materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                                Pair.of("Rewrite compensate predicate by view fail", String.format(
                                        "compensatePredicates = %s,\n mvExprToMvScanExprMapping = %s,\n"
                                                + "queryToViewSlotMapping = %s",
                                        compensatePredicates,
                                        materializationContext.getMvExprToMvScanExprMapping(),
                                        queryToViewSlotMapping)));
                        continue;
                    }
                    rewrittenPlan = new LogicalFilter<>(Sets.newHashSet(rewriteCompensatePredicates), mvScan);
                }
                // Rewrite query by view
                rewrittenPlan = rewriteQueryByView(matchMode, queryStructInfo, viewStructInfo, queryToViewSlotMapping,
                        rewrittenPlan, materializationContext);
                if (rewrittenPlan == null) {
                    continue;
                }
                // run rbo job on mv rewritten plan
                CascadesContext rewrittenPlanContext = CascadesContext.initContext(
                        cascadesContext.getStatementContext(), rewrittenPlan,
                        cascadesContext.getCurrentJobContext().getRequiredProperties());
                Rewriter.getWholeTreeRewriter(rewrittenPlanContext).execute();
                rewrittenPlan = rewrittenPlanContext.getRewritePlan();
                if (!checkOutput(queryPlan, rewrittenPlan, materializationContext)) {
                    continue;
                }
                // check the partitions used by rewritten plan is valid or not
                Set<Long> invalidPartitionsQueryUsed =
                        calcInvalidPartitions(rewrittenPlan, materializationContext, cascadesContext);
                if (!invalidPartitionsQueryUsed.isEmpty()) {
                    materializationContext.recordFailReason(queryStructInfo.getOriginalPlanId(),
                            Pair.of("Check partition query used validation fail",
                                    String.format("the partition used by query is invalid by materialized view,"
                                                    + "invalid partition info query used is %s",
                                            materializationContext.getMTMV().getPartitions().stream()
                                                    .filter(partition ->
                                                            invalidPartitionsQueryUsed.contains(partition.getId()))
                                                    .collect(Collectors.toSet()))));
                    continue;
                }
                recordIfRewritten(queryPlan, materializationContext);
                rewriteResults.add(rewrittenPlan);
            }
        }
        return rewriteResults;
    }

    protected boolean checkOutput(Plan sourcePlan, Plan rewrittenPlan, MaterializationContext materializationContext) {
        if (sourcePlan.getGroupExpression().isPresent() && !rewrittenPlan.getLogicalProperties()
                .equals(sourcePlan.getGroupExpression().get().getOwnerGroup().getLogicalProperties())) {
            ObjectId planObjId = sourcePlan.getGroupExpression().map(GroupExpression::getId)
                    .orElseGet(() -> new ObjectId(-1));
            materializationContext.recordFailReason(planObjId, Pair.of(
                    "RewrittenPlan output logical properties is different with target group",
                    String.format("planOutput logical properties = %s,\n"
                                    + "groupOutput logical properties = %s", rewrittenPlan.getLogicalProperties(),
                            sourcePlan.getGroupExpression().get().getOwnerGroup().getLogicalProperties())));
            return false;
        }
        return true;
    }

    /**
     * Partition will be pruned in query then add the record the partitions to select partitions on
     * catalog relation.
     * Maybe only just some partitions is valid in materialized view, so we should check if the mv can
     * offer the partitions which query used or not.
     */
    protected Set<Long> calcInvalidPartitions(Plan rewrittenPlan, MaterializationContext materializationContext,
            CascadesContext cascadesContext) {
        // check partition is valid or not
        MTMV mtmv = materializationContext.getMTMV();
        PartitionInfo mvPartitionInfo = mtmv.getPartitionInfo();
        if (PartitionType.UNPARTITIONED.equals(mvPartitionInfo.getType())) {
            // if not partition, if rewrite success, it means mv is available
            return ImmutableSet.of();
        }
        // check mv related table partition is valid or not
        MTMVPartitionInfo mvCustomPartitionInfo = mtmv.getMvPartitionInfo();
        BaseTableInfo relatedPartitionTable = mvCustomPartitionInfo.getRelatedTable();
        if (relatedPartitionTable == null) {
            return ImmutableSet.of();
        }
        // get mv valid partitions
        Set<Long> mvDataValidPartitionIdSet = MTMVUtil.getMTMVCanRewritePartitions(mtmv,
                        cascadesContext.getConnectContext()).stream()
                .map(Partition::getId)
                .collect(Collectors.toSet());
        Set<Long> queryUsedPartitionIdSet = rewrittenPlan.collectToList(node -> node instanceof LogicalOlapScan
                        && Objects.equals(((CatalogRelation) node).getTable().getName(), mtmv.getName()))
                .stream()
                .map(node -> ((LogicalOlapScan) node).getSelectedPartitionIds())
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
        queryUsedPartitionIdSet.removeAll(mvDataValidPartitionIdSet);
        return queryUsedPartitionIdSet;
    }

    /**
     * Rewrite query by view, for aggregate or join rewriting should be different inherit class implementation
     */
    protected Plan rewriteQueryByView(MatchMode matchMode, StructInfo queryStructInfo, StructInfo viewStructInfo,
            SlotMapping queryToViewSlotMapping, Plan tempRewritedPlan, MaterializationContext materializationContext) {
        return tempRewritedPlan;
    }

    /**
     * Use target expression to represent the source expression. Visit the source expression,
     * try to replace the source expression with target expression in targetExpressionMapping, if found then
     * replace the source expression by target expression mapping value.
     * Note: make the target expression map key to source based according to targetExpressionNeedSourceBased,
     * if targetExpressionNeedSourceBased is true, we should make it source based.
     * the key expression in targetExpressionMapping should be shuttled. with the method
     * ExpressionUtils.shuttleExpressionWithLineage.
     */
    protected List<Expression> rewriteExpression(List<? extends Expression> sourceExpressionsToWrite, Plan sourcePlan,
            ExpressionMapping targetExpressionMapping, SlotMapping sourceToTargetMapping,
            boolean targetExpressionNeedSourceBased) {
        // Firstly, rewrite the target expression using source with inverse mapping
        // then try to use the target expression to represent the query. if any of source expressions
        // can not be represented by target expressions, return null.
        //
        // example as following:
        //     source                           target
        //        project(slot 1, 2)              project(slot 3, 2, 1)
        //          scan(table)                        scan(table)
        //
        //     transform source to:
        //        project(slot 2, 1)
        //            target
        // generate target to target replacement expression mapping, and change target expression to source based
        List<? extends Expression> sourceShuttledExpressions = ExpressionUtils.shuttleExpressionWithLineage(
                sourceExpressionsToWrite, sourcePlan);
        ExpressionMapping expressionMappingKeySourceBased = targetExpressionNeedSourceBased
                ? targetExpressionMapping.keyPermute(sourceToTargetMapping.inverse()) : targetExpressionMapping;
        // target to target replacement expression mapping, because mv is 1:1 so get first element
        List<Map<Expression, Expression>> flattenExpressionMap = expressionMappingKeySourceBased.flattenMap();
        Map<? extends Expression, ? extends Expression> targetToTargetReplacementMapping = flattenExpressionMap.get(0);

        List<Expression> rewrittenExpressions = new ArrayList<>();
        for (int index = 0; index < sourceShuttledExpressions.size(); index++) {
            Expression expressionShuttledToRewrite = sourceShuttledExpressions.get(index);
            if (expressionShuttledToRewrite instanceof Literal) {
                rewrittenExpressions.add(expressionShuttledToRewrite);
                continue;
            }
            final Set<Object> slotsToRewrite =
                    expressionShuttledToRewrite.collectToSet(expression -> expression instanceof Slot);
            Expression replacedExpression = ExpressionUtils.replace(expressionShuttledToRewrite,
                    targetToTargetReplacementMapping);
            if (replacedExpression.anyMatch(slotsToRewrite::contains)) {
                // if contains any slot to rewrite, which means can not be rewritten by target, bail out
                return ImmutableList.of();
            }
            Expression sourceExpression = sourceExpressionsToWrite.get(index);
            if (sourceExpression instanceof NamedExpression
                    && replacedExpression.nullable() != sourceExpression.nullable()) {
                // if enable join eliminate, query maybe inner join and mv maybe outer join.
                // If the slot is at null generate side, the nullable maybe different between query and view
                // So need to force to consistent.
                replacedExpression = sourceExpression.nullable()
                        ? new Nullable(replacedExpression) : new NonNullable(replacedExpression);
            }
            if (sourceExpression instanceof NamedExpression) {
                NamedExpression sourceNamedExpression = (NamedExpression) sourceExpression;
                replacedExpression = new Alias(sourceNamedExpression.getExprId(), replacedExpression,
                        sourceNamedExpression.getName());
            }
            rewrittenExpressions.add(replacedExpression);
        }
        return rewrittenExpressions;
    }

    protected Expression rewriteExpression(Expression sourceExpressionsToWrite, Plan sourcePlan,
            ExpressionMapping targetExpressionMapping, SlotMapping sourceToTargetMapping,
            boolean targetExpressionNeedSourceBased) {
        List<Expression> expressionToRewrite = new ArrayList<>();
        expressionToRewrite.add(sourceExpressionsToWrite);
        List<Expression> rewrittenExpressions = rewriteExpression(expressionToRewrite, sourcePlan,
                targetExpressionMapping, sourceToTargetMapping, targetExpressionNeedSourceBased);
        if (rewrittenExpressions.isEmpty()) {
            return null;
        }
        return rewrittenExpressions.get(0);
    }

    /**
     * Compensate mv predicates by query predicates, compensate predicate result is query based.
     * Such as a > 5 in mv, and a > 10 in query, the compensatory predicate is a > 10.
     * For another example as following:
     * predicate a = b in mv, and a = b and c = d in query, the compensatory predicate is c = d
     */
    protected SplitPredicate predicatesCompensate(
            StructInfo queryStructInfo,
            StructInfo viewStructInfo,
            SlotMapping queryToViewSlotMapping,
            ComparisonResult comparisonResult,
            CascadesContext cascadesContext
    ) {
        // viewEquivalenceClass to query based
        SlotMapping viewToQuerySlotMapping = queryToViewSlotMapping.inverse();
        final Set<Expression> equalCompensateConjunctions = compensateEquivalence(
                queryStructInfo,
                viewStructInfo,
                viewToQuerySlotMapping,
                comparisonResult);
        // range compensate
        final Set<Expression> rangeCompensatePredicates = compensateRangePredicate(
                queryStructInfo,
                viewStructInfo,
                viewToQuerySlotMapping,
                comparisonResult);
        // residual compensate
        final Set<Expression> residualCompensatePredicates = compensateResidualPredicate(
                queryStructInfo,
                viewStructInfo,
                viewToQuerySlotMapping,
                comparisonResult);
        // if the join type in query and mv plan is different, we should check and add filter on mv to make
        // the mv join type is accord with query
        Set<Set<Slot>> requireNoNullableViewSlot = comparisonResult.getViewNoNullableSlot();
        // check query is use the null reject slot which view comparison need
        if (!requireNoNullableViewSlot.isEmpty()) {
            Set<Expression> queryPulledUpPredicates = queryStructInfo.getPredicates().getPulledUpPredicates();
            Set<Expression> nullRejectPredicates = ExpressionUtils.inferNotNull(queryPulledUpPredicates,
                    cascadesContext);
            if (nullRejectPredicates.isEmpty() || queryPulledUpPredicates.containsAll(nullRejectPredicates)) {
                // query has not null reject predicates, so return
                return SplitPredicate.invalid();
            }
            Set<Expression> queryUsedNeedRejectNullSlotsViewBased = nullRejectPredicates.stream()
                    .map(expression -> TypeUtils.isNotNull(expression).orElse(null))
                    .filter(Objects::nonNull)
                    .map(expr -> ExpressionUtils.replace((Expression) expr,
                            queryToViewSlotMapping.toSlotReferenceMap()))
                    .collect(Collectors.toSet());
            if (requireNoNullableViewSlot.stream().anyMatch(
                    set -> Sets.intersection(set, queryUsedNeedRejectNullSlotsViewBased).isEmpty())) {
                return SplitPredicate.invalid();
            }
        }
        return SplitPredicate.of(ExpressionUtils.and(equalCompensateConjunctions),
                rangeCompensatePredicates.isEmpty() ? BooleanLiteral.of(true)
                        : ExpressionUtils.and(rangeCompensatePredicates),
                residualCompensatePredicates.isEmpty() ? BooleanLiteral.of(true)
                        : ExpressionUtils.and(residualCompensatePredicates));
    }

    protected Set<Expression> compensateEquivalence(StructInfo queryStructInfo,
            StructInfo viewStructInfo,
            SlotMapping viewToQuerySlotMapping,
            ComparisonResult comparisonResult) {
        EquivalenceClass queryEquivalenceClass = queryStructInfo.getEquivalenceClass();
        EquivalenceClass viewEquivalenceClass = viewStructInfo.getEquivalenceClass();
        Map<SlotReference, SlotReference> viewToQuerySlotMap = viewToQuerySlotMapping.toSlotReferenceMap();
        EquivalenceClass viewEquivalenceClassQueryBased = viewEquivalenceClass.permute(viewToQuerySlotMap);
        if (viewEquivalenceClassQueryBased == null) {
            return ImmutableSet.of();
        }
        final Set<Expression> equalCompensateConjunctions = new HashSet<>();
        if (queryEquivalenceClass.isEmpty() && viewEquivalenceClass.isEmpty()) {
            equalCompensateConjunctions.add(BooleanLiteral.of(true));
        }
        if (queryEquivalenceClass.isEmpty()
                && !viewEquivalenceClass.isEmpty()) {
            return ImmutableSet.of();
        }
        EquivalenceClassSetMapping queryToViewEquivalenceMapping =
                EquivalenceClassSetMapping.generate(queryEquivalenceClass, viewEquivalenceClassQueryBased);
        // can not map all target equivalence class, can not compensate
        if (queryToViewEquivalenceMapping.getEquivalenceClassSetMap().size()
                < viewEquivalenceClass.getEquivalenceSetList().size()) {
            return ImmutableSet.of();
        }
        // do equal compensate
        Set<Set<SlotReference>> mappedQueryEquivalenceSet =
                queryToViewEquivalenceMapping.getEquivalenceClassSetMap().keySet();
        queryEquivalenceClass.getEquivalenceSetList().forEach(
                queryEquivalenceSet -> {
                    // compensate the equivalence in query but not in view
                    if (!mappedQueryEquivalenceSet.contains(queryEquivalenceSet)) {
                        Iterator<SlotReference> iterator = queryEquivalenceSet.iterator();
                        SlotReference first = iterator.next();
                        while (iterator.hasNext()) {
                            Expression equals = new EqualTo(first, iterator.next());
                            equalCompensateConjunctions.add(equals);
                        }
                    } else {
                        // compensate the equivalence both in query and view, but query has more equivalence
                        Set<SlotReference> viewEquivalenceSet =
                                queryToViewEquivalenceMapping.getEquivalenceClassSetMap().get(queryEquivalenceSet);
                        Set<SlotReference> copiedQueryEquivalenceSet = new HashSet<>(queryEquivalenceSet);
                        copiedQueryEquivalenceSet.removeAll(viewEquivalenceSet);
                        SlotReference first = viewEquivalenceSet.iterator().next();
                        for (SlotReference slotReference : copiedQueryEquivalenceSet) {
                            Expression equals = new EqualTo(first, slotReference);
                            equalCompensateConjunctions.add(equals);
                        }
                    }
                }
        );
        return equalCompensateConjunctions;
    }

    protected Set<Expression> compensateResidualPredicate(StructInfo queryStructInfo,
            StructInfo viewStructInfo,
            SlotMapping viewToQuerySlotMapping,
            ComparisonResult comparisonResult) {
        SplitPredicate querySplitPredicate = queryStructInfo.getSplitPredicate();
        SplitPredicate viewSplitPredicate = viewStructInfo.getSplitPredicate();
        Expression queryResidualPredicate = querySplitPredicate.getResidualPredicate();
        Expression viewResidualPredicate = viewSplitPredicate.getResidualPredicate();
        Expression viewResidualPredicateQueryBased =
                ExpressionUtils.replace(viewResidualPredicate, viewToQuerySlotMapping.toSlotReferenceMap());
        Set<Expression> queryResidualSet =
                Sets.newHashSet(ExpressionUtils.extractConjunction(queryResidualPredicate));
        Set<Expression> viewResidualQueryBasedSet =
                Sets.newHashSet(ExpressionUtils.extractConjunction(viewResidualPredicateQueryBased));
        // query residual predicate can not contain all view residual predicate when view have residual predicate,
        // bail out
        if (!viewResidualPredicateQueryBased.equals(BooleanLiteral.TRUE)
                && !queryResidualSet.containsAll(viewResidualQueryBasedSet)) {
            return ImmutableSet.of();
        }
        queryResidualSet.removeAll(viewResidualQueryBasedSet);
        return queryResidualSet;
    }

    protected Set<Expression> compensateRangePredicate(StructInfo queryStructInfo,
            StructInfo viewStructInfo,
            SlotMapping viewToQuerySlotMapping,
            ComparisonResult comparisonResult) {
        // TODO range predicates and residual predicates compensate, Simplify implementation.
        SplitPredicate querySplitPredicate = queryStructInfo.getSplitPredicate();
        SplitPredicate viewSplitPredicate = viewStructInfo.getSplitPredicate();

        Expression queryRangePredicate = querySplitPredicate.getRangePredicate();
        Expression viewRangePredicate = viewSplitPredicate.getRangePredicate();
        Expression viewRangePredicateQueryBased =
                ExpressionUtils.replace(viewRangePredicate, viewToQuerySlotMapping.toSlotReferenceMap());

        Set<Expression> queryRangeSet =
                Sets.newHashSet(ExpressionUtils.extractConjunction(queryRangePredicate));
        Set<Expression> viewRangeQueryBasedSet =
                Sets.newHashSet(ExpressionUtils.extractConjunction(viewRangePredicateQueryBased));
        // query range predicate can not contain all view range predicate when view have range predicate, bail out
        if (!viewRangePredicateQueryBased.equals(BooleanLiteral.TRUE)
                && !queryRangeSet.containsAll(viewRangeQueryBasedSet)) {
            return ImmutableSet.of();
        }
        queryRangeSet.removeAll(viewRangeQueryBasedSet);
        return queryRangeSet;
    }

    /**
     * Decide the match mode
     *
     * @see MatchMode
     */
    private MatchMode decideMatchMode(List<CatalogRelation> queryRelations, List<CatalogRelation> viewRelations) {
        List<TableIf> queryTableRefs = queryRelations.stream().map(CatalogRelation::getTable)
                .collect(Collectors.toList());
        List<TableIf> viewTableRefs = viewRelations.stream().map(CatalogRelation::getTable)
                .collect(Collectors.toList());
        boolean sizeSame = viewTableRefs.size() == queryTableRefs.size();
        boolean queryPartial = viewTableRefs.containsAll(queryTableRefs);
        if (!sizeSame && queryPartial) {
            return MatchMode.QUERY_PARTIAL;
        }
        boolean viewPartial = queryTableRefs.containsAll(viewTableRefs);
        if (!sizeSame && viewPartial) {
            return MatchMode.VIEW_PARTIAL;
        }
        if (sizeSame && queryPartial && viewPartial) {
            return MatchMode.COMPLETE;
        }
        return MatchMode.NOT_MATCH;
    }

    /**
     * Extract struct info from plan, support to get struct info from logical plan or plan in group.
     */
    public static List<StructInfo> extractStructInfo(Plan plan, CascadesContext cascadesContext) {
        if (plan.getGroupExpression().isPresent() && !plan.getGroupExpression().get().getOwnerGroup().getStructInfos()
                .isEmpty()) {
            return plan.getGroupExpression().get().getOwnerGroup().getStructInfos();
        } else {
            // build struct info and add them to current group
            List<StructInfo> structInfos = StructInfo.of(plan);
            if (plan.getGroupExpression().isPresent()) {
                plan.getGroupExpression().get().getOwnerGroup().addStructInfo(structInfos);
            }
            return structInfos;
        }
    }

    /**
     * Check the pattern of query or materializedView is supported or not.
     */
    protected boolean checkPattern(StructInfo structInfo) {
        if (structInfo.getRelations().isEmpty()) {
            return false;
        }
        return true;
    }

    protected void recordIfRewritten(Plan plan, MaterializationContext context) {
        context.setSuccess(true);
        if (plan.getGroupExpression().isPresent()) {
            context.addMatchedGroup(plan.getGroupExpression().get().getOwnerGroup().getGroupId());
        }
    }

    protected boolean checkIfRewritten(Plan plan, MaterializationContext context) {
        return plan.getGroupExpression().isPresent()
                && context.alreadyRewrite(plan.getGroupExpression().get().getOwnerGroup().getGroupId());
    }

    /**
     * Query and mv match node
     */
    protected enum MatchMode {
        /**
         * The tables in query are same to the tables in view
         */
        COMPLETE,
        /**
         * The tables in query contains all the tables in view
         */
        VIEW_PARTIAL,
        /**
         * The tables in view contains all the tables in query
         */
        QUERY_PARTIAL,
        /**
         * Except for COMPLETE and VIEW_PARTIAL and QUERY_PARTIAL
         */
        NOT_MATCH
    }
}
