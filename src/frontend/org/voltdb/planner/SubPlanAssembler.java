/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.planner;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.json_voltpatches.JSONException;
import org.voltdb.VoltType;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.ColumnRef;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Index;
import org.voltdb.catalog.Table;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.ComparisonExpression;
import org.voltdb.expressions.ConstantValueExpression;
import org.voltdb.expressions.ExpressionUtil;
import org.voltdb.expressions.ParameterValueExpression;
import org.voltdb.expressions.TupleValueExpression;
import org.voltdb.planner.AbstractParsedStmt.TablePair;
import org.voltdb.planner.ParsedSelectStmt.ParsedColInfo;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.AbstractScanPlanNode;
import org.voltdb.plannodes.IndexScanPlanNode;
import org.voltdb.plannodes.ReceivePlanNode;
import org.voltdb.plannodes.SendPlanNode;
import org.voltdb.plannodes.SeqScanPlanNode;
import org.voltdb.types.ExpressionType;
import org.voltdb.types.IndexLookupType;
import org.voltdb.types.IndexType;
import org.voltdb.types.SortDirectionType;
import org.voltdb.utils.CatalogUtil;

public abstract class SubPlanAssembler {

    /** The parsed statement structure that has the table and predicate info we need. */
    final AbstractParsedStmt m_parsedStmt;

    /** The catalog's database object which contains tables and access path info */
    final Database m_db;

    /** Describes the specified and inferred partition context. */
    final PartitioningForStatement m_partitioning;

    // This cached value saves work on the assumption that it is only used to return
    // final "leaf node" bindingLists that are never updated "in place",
    // but just get their contents dumped into a summary List that was created
    // inline and NOT initialized here.
    private final static List<AbstractExpression> s_reusableImmutableEmptyBinding =
        new ArrayList<AbstractExpression>();

    SubPlanAssembler(Database db, AbstractParsedStmt parsedStmt, PartitioningForStatement partitioning)
    {
        m_db = db;
        m_parsedStmt = parsedStmt;
        m_partitioning = partitioning;
    }

    /**
     * Called repeatedly to iterate through possible embedable select plans.
     * Returns null when no more plans exist.
     *
     * @return The next plan to solve the subselect or null if no more plans.
     */
    abstract AbstractPlanNode nextPlan();

    /**
     * Given a table (and optionally the next table in the join order), using the
     * set of predicate expressions, figure out all the possible ways to get at
     * the data we want. One way will always be the naive sequential scan.
     *
     * @param table
     *     The table to get the data from.
     * @param nextTable
     *     The next tables in the join order or an empty array if there
     *     are none.
     * @return A list of access paths to access the data in the table.
     */
    protected ArrayList<AccessPath> getRelevantAccessPathsForTable(Table table, Table nextTables[]) {
        ArrayList<AccessPath> paths = new ArrayList<AccessPath>();
        // add the empty seq-scan access path
        AccessPath naivePath = new AccessPath();
        paths.add(naivePath);

        List<AbstractExpression> allExprs = new ArrayList<AbstractExpression>();

        List<AbstractExpression> filterExprs = m_parsedStmt.tableFilterList.get(table);
        if (filterExprs != null) {
            allExprs.addAll(filterExprs);
            naivePath.otherExprs.addAll(filterExprs);
        }

        for (int ii = 0; ii < nextTables.length; ii++) {
            final Table nextTable = nextTables[ii];
            // create a key to search the TablePair->Clause map
            TablePair pair = new TablePair();
            pair.t1 = table;
            pair.t2 = nextTable;
            List<AbstractExpression> joinExprs = m_parsedStmt.joinSelectionList.get(pair);

            if (joinExprs != null) {
                allExprs.addAll(joinExprs);
                naivePath.joinExprs.addAll(joinExprs);
            }
        }

        CatalogMap<Index> indexes = table.getIndexes();

        for (Index index : indexes) {
            AccessPath path = getRelevantAccessPathForIndex(table, allExprs, index);
            if (path != null) {
                paths.add(path);
            }
        }

        return paths;
    }

    /**
     * A utility class for returning the results of a match between an indexed expression and a query filter
     * expression that uses it in some form in some useful fashion.
     * The "form" may be an exact match for the expression or some allowed parameterized variant.
     * The "fashion" may be in an equality or range comparison opposite something that can be
     * treated as a (sub)scan-time constant.
     */
    private static class IndexableExpression
    {
        // The matched expression, normalized so that its LHS is the part that matched the indexed expression.
        private final ComparisonExpression m_filter;
        // The parameters, if any, that must be bound to enable use of the index -- these have no effect on the current query,
        // but they effect the applicability of the resulting cached plan to other queries.
        private final List<AbstractExpression> m_bindings;

        public IndexableExpression(ComparisonExpression normalizedExpr, List<AbstractExpression> bindings)
        {
            m_filter = normalizedExpr;
            m_bindings = bindings;
        }

        public AbstractExpression getFilter() { return m_filter; }
        public List<AbstractExpression> getBindings() { return m_bindings; }

        public IndexableExpression extractStartFromPrefixLike() {
            ComparisonExpression gteFilter = m_filter.getGteFilterFromPrefixLike();
            return new IndexableExpression(gteFilter, m_bindings);
        }

        public IndexableExpression extractEndFromPrefixLike() {
            ComparisonExpression ltFilter = m_filter.getLtFilterFromPrefixLike();
            return new IndexableExpression(ltFilter, m_bindings);
        }
    };

    /**
     * Given a table, a set of predicate expressions and a specific index, find the best way to
     * access the data using the given index, or return null if no good way exists.
     *
     * @param table The table we want data from.
     * @param exprs The set of predicate expressions.
     * @param index The index we want to use to access the data.
     * @return A valid access path using the data or null if none found.
     */
    protected AccessPath getRelevantAccessPathForIndex(Table table, List<AbstractExpression> exprs, Index index)
    {
        // Track the running list of filter expressions that remain as each is either cherry-picked
        // for optimized coverage via the index keys.
        List<AbstractExpression> filtersToCover = new ArrayList<AbstractExpression>();
        filtersToCover.addAll(exprs);

        String exprsjson = index.getExpressionsjson();
        // This list remains null if the index is just on simple columns.
        List<AbstractExpression> indexedExprs = null;
        // This vector of indexed columns remains null if indexedExprs is in use.
        List<ColumnRef> indexedColRefs = null;
        int[] indexedColIds = null;
        int keyComponentCount;
        if (exprsjson.isEmpty()) {
            // Don't bother to build a dummy indexedExprs list for a simple index on columns.
            // Just leave it null and handle this simpler case specially via indexedColRefs or
            // indexedColIds, all along the way.
            indexedColRefs = CatalogUtil.getSortedCatalogItems(index.getColumns(), "index");
            keyComponentCount = indexedColRefs.size();
            indexedColIds = new int[keyComponentCount];
            int ii = 0;
            for (ColumnRef cr : indexedColRefs) {
                indexedColIds[ii++] = cr.getColumn().getIndex();
            }
        } else {
            try {
                // This MAY want to happen once when the plan is loaded from the catalog
                // and cached in a sticky cached index-to-expressions map?
                indexedExprs = AbstractExpression.fromJSONArrayString(exprsjson, null);
                keyComponentCount = indexedExprs.size();
            } catch (JSONException e) {
                e.printStackTrace();
                assert(false);
                return null;
            }
        }

        // Hope for the best -- full coverage with equality matches on every expression in the index.
        AccessPath retval = new AccessPath();
        retval.use = IndexUseType.COVERING_UNIQUE_EQUALITY;
        retval.index = index;

        // Try to use the index scan's inherent ordering to implement the ORDER BY clause.
        // The effects of determineIndexOrdering are reflected in
        // retval.sortDirection and bindingsForOrder.
        // In some borderline cases, the determination to use the index's order is optimistic and
        // provisional; it can be undone later in this function as new info comes to light.
        List<AbstractExpression> bindingsForOrder = new ArrayList<AbstractExpression>();
        determineIndexOrdering(table, keyComponentCount,
                               indexedExprs, indexedColRefs,
                               retval, bindingsForOrder);

        // Use as many covering indexed expressions as possible to optimize comparator expressions that can use them.

        // Start with equality comparisons on as many (prefix) indexed expressions as possible.
        int coveredCount = 0;
        AbstractExpression coveringExpr = null;
        int coveringColId = -1;
        for ( ; coveredCount < keyComponentCount; ++coveredCount) {
            if (indexedExprs == null) {
                coveringColId = indexedColIds[coveredCount];
            } else {
                coveringExpr = indexedExprs.get(coveredCount);
            }
            // Equality filters get first priority.
            IndexableExpression eqExpr = getIndexableExpressionFromFilters(
                ExpressionType.COMPARE_EQUAL, ExpressionType.COMPARE_EQUAL,
                coveringExpr, coveringColId, table, filtersToCover);
            if (eqExpr == null) {
                break;
            }
            AbstractExpression comparator = eqExpr.getFilter();
            retval.indexExprs.add(comparator);
            retval.bindings.addAll(eqExpr.getBindings());
            // A non-empty endExprs has the later side effect of invalidating descending sort order
            // in all cases except the edge case of full coverage equality comparison.
            retval.endExprs.add(comparator);
        }

        // Make short work of the cases of full coverage with equality
        // which happens to be the only use case for non-scannable (i.e. HASH) indexes.
        if (coveredCount == keyComponentCount) {
            // All remaining filters get covered as post-filters
            // to be applied after the "random access" to the exact index key.
            retval.otherExprs.addAll(filtersToCover);
            if (retval.sortDirection != SortDirectionType.INVALID) {
                retval.bindings.addAll(bindingsForOrder);
            }
            return retval;
        }

        if ( ! IndexType.isScannable(index.getType()) ) {
            // Failure to equality-match all expressions in a non-scannable index is unacceptable.
            return null;
        }

        //
        // Scannable indexes provide more options...
        //

        IndexableExpression startingBoundExpr = null;
        IndexableExpression endingBoundExpr = null;
        if ( ! filtersToCover.isEmpty()) {
            // A scannable index allows inequality matches,
            // but only on the indexed expression that was found to be missing a usable equality comparator.

            // Look for a double-ended bound on it.
            // This is always the result of an edge case:
            // "indexed-general-expression LIKE prefix-constant".
            // The simpler case "column LIKE prefix-constant" has already been re-written by the HSQL parser
            // into separate upper and lower bound inequalities.
            IndexableExpression doubleBoundExpr = getIndexableExpressionFromFilters(
                ExpressionType.COMPARE_LIKE, ExpressionType.COMPARE_LIKE,
                coveringExpr, coveringColId, table, filtersToCover);

            // For simplicity of implementation:
            // In some odd edge cases e.g.
            // " FIELD(DOC, 'title') LIKE 'a%' AND FIELD(DOC, 'title') > 'az' ",
            // arbitrarily choose to index-optimize the LIKE expression rather than the inequality
            // ON THAT SAME COLUMN.
            // This MIGHT not always provide the most selective filtering.
            if (doubleBoundExpr != null) {
                startingBoundExpr = doubleBoundExpr.extractStartFromPrefixLike();
                endingBoundExpr = doubleBoundExpr.extractEndFromPrefixLike();
            }
            else {
                // Look for a lower bound.
                startingBoundExpr = getIndexableExpressionFromFilters(
                    ExpressionType.COMPARE_GREATERTHAN, ExpressionType.COMPARE_GREATERTHANOREQUALTO,
                    coveringExpr, coveringColId, table, filtersToCover);

                // Look for an upper bound.
                endingBoundExpr = getIndexableExpressionFromFilters(
                    ExpressionType.COMPARE_LESSTHAN, ExpressionType.COMPARE_LESSTHANOREQUALTO,
                    coveringExpr, coveringColId, table, filtersToCover);
            }
        }

        // Upper and lower bounds get handled differently for scans that produce descending order.
        if (retval.sortDirection == SortDirectionType.DESC) {
            // Descending order is not supported if there are any kind of upper bounds.
            // So, fall back to an order-indeterminate scan result which will get an explicit sort.
            if ((endingBoundExpr != null) || ( ! retval.endExprs.isEmpty())) {
                retval.sortDirection = SortDirectionType.INVALID;
            } else {
                // For a reverse scan, swap the start and end bounds.
                endingBoundExpr = startingBoundExpr;
                startingBoundExpr = null; // = the original endingBoundExpr, known to be null
            }
        }

        if (startingBoundExpr != null) {
            AbstractExpression comparator = startingBoundExpr.getFilter();
            retval.indexExprs.add(comparator);
            retval.bindings.addAll(startingBoundExpr.getBindings());
            if (comparator.getExpressionType() == ExpressionType.COMPARE_GREATERTHAN) {
                retval.lookupType = IndexLookupType.GT;
            } else {
                assert(comparator.getExpressionType() == ExpressionType.COMPARE_GREATERTHANOREQUALTO);
                retval.lookupType = IndexLookupType.GTE;
            }
            retval.use = IndexUseType.INDEX_SCAN;
        }

        if (endingBoundExpr != null) {
            AbstractExpression comparator = endingBoundExpr.getFilter();
            retval.endExprs.add(comparator);
            retval.bindings.addAll(endingBoundExpr.getBindings());
            retval.use = IndexUseType.INDEX_SCAN;
            if (retval.lookupType == IndexLookupType.EQ) {
                // This does not need to be that accurate;
                // anything OTHER than IndexLookupType.EQ is enough to enable a multi-key scan.
                //TODO: work out whether there is any possible use for more precise settings of
                // retval.lookupType, including for descending order cases ???
                retval.lookupType = IndexLookupType.GTE;
            }
        }

        // index not relevant to expression
        if (retval.indexExprs.size() == 0 && retval.endExprs.size() == 0 && retval.sortDirection == SortDirectionType.INVALID) {
            return null;
        }

        // If all of the index key components are not covered by comparisons (but SOME are),
        // then the scan may need to be reconfigured to account for the scan key being padded
        // with null values for the components that are not being filtered.
        //
        if (retval.indexExprs.size() < keyComponentCount) {
            // If IndexUseType has the default value of COVERING_UNIQUE_EQUALITY, then the
            // scan can use GTE instead to match all values, not only the null values, for the
            // unfiltered components -- assuming that any value is considered >= null.
            if (retval.use == IndexUseType.COVERING_UNIQUE_EQUALITY) {
                retval.use = IndexUseType.INDEX_SCAN;
                retval.lookupType = IndexLookupType.GTE;
            }
            // GTE scans can have any number of null key components appended without changing
            // the effective value. So, that leaves GT scans.
            else if (retval.lookupType == IndexLookupType.GT) {
                // GT scans pose a problem in that any compound key in the index that was an exact
                // equality match on the filtered key component(s) and had a non-null value for any
                // remaining component(s) would be mistaken for a match.
                // The current work-around for this is to add (back) the GT condition to the set of
                // "other" filter expressions that get evaluated for each tuple found in the index scan.
                // This will eliminate the initial entries that are equal on the prefix key.
                // This is not as efficient as getting the index scan to start in the "correct" place,
                // but it puts off having to change the EE code.
                // TODO: ENG-3913 describes more ambitious alternative solutions that include:
                //  - padding with MAX values rather than null/MIN values for GT scans.
                //  - adding the GT condition as a special "initialExpr" post-condition
                //    that disables itself as soon as it evaluates to true for any row
                //    -- it would be expected to always evaluate to true after that.
                AbstractExpression comparator = startingBoundExpr.getFilter();
                retval.otherExprs.add(comparator);
            }
        }

        // All remaining filters get covered as post-filters
        // to be applied after the "random access" go at the index.
        retval.otherExprs.addAll(filtersToCover);
        if (retval.sortDirection != SortDirectionType.INVALID) {
            retval.bindings.addAll(bindingsForOrder);
        }
        return retval;
    }

    /**
     * Try to use the index scan's inherent ordering to implement the ORDER BY clause.
     * The most common scenario for this optimization is when the ORDER BY "columns"
     * (actually a list of columns OR expressions) corresponds to a prefix or a complete
     * match of a tree index's key components (also columns/expressions),
     * in the same order from major to minor.
     * For example, if a table has a tree index on columns "(A, B)", then
     * "ORDER BY A" or "ORDER BY A, B" are considered a match
     * but NOT "ORDER BY A, C" or "ORDER BY A, B, C" or "ORDER BY B" or "ORDER BY B, A".
     *
     * TODO: In theory, we COULD leverage index ordering when the index covers only a prefix of
     * the ORDER BY list, such as the "ORDER BY A, B, C" case listed above.
     * But that still requires an ORDER BY plan node.
     * To gain any substantial advantage, the ORDER BY plan node would have to be smart enough
     * to apply just an incremental "minor" sort on "C" to subsets of the result "grouped by"
     * equal A and B values.  The ORDER BY plan node is not yet that smart.
     * So, for now, this case is handled by tagging the index output as not sorted,
     * leaving the ORDER BY to do the full job.
     *
     * There are some additional considerations that might disqualify a match.
     * A match also requires that all columns are ordered in the same direction.
     * For example, if a table has a tree index on columns "(A, B, C)", then
     * "ORDER BY A, B" or "ORDER BY A DESC, B DESC, C DESC" are considered a match
     * but not "ORDER BY A, B DESC" or "ORDER BY A DESC, B".
     *
     * TODO: Currently only ascending key index definitions are supported
     * -- the DESC keyword is not supported in the index creation DDL.
     * If that is ever enabled, the checks here may need to be generalized
     * to maintain the current level of support for only exact matching or
     * "exact reverse" matching of the ASC/DESC qualifiers on all columns,
     * but no other cases.
     *
     * Caveat: "Reverse scans", that is, support for descending ORDER BYs using ascending key
     * indexes only work when the index scan can start at the very end of the index
     * (to work backwards).
     * That means no equality conditions or upper bound conditions can be allowed that would
     * interfere with the start of the backward scan.
     * To minimize re-work, those query qualifications are not checked here.
     * It is easier to tentatively claim the reverse sort order of the index output, here,
     * and later invalidate that sortDirection upon detecting that the reverse scan
     * is not supportable.
     *
     * Some special cases are supported in addition to the simple match of all the ORDER BY "columns":
     *
     * It is possible for an ORDER BY "column" to have a parameterized form that neither strictly
     * equals nor contradicts an index key component.
     * For example, an index might be defined on a particular character of a column "(substr(A, 1, 1))".
     * This trivially matches "ORDER BY substr(A, 1, 1)".
     * It trivially refuses to match "ORDER BY substr(A, 2, 1)" or even "ORDER BY substr(A, 2, ?)"
     * The more interesting case is "ORDER BY substr(A, ?, 1)" where a match
     * must be predicated on the user specifying the correct value "1" for the parameter.
     * This is handled by allowing the optimization but by generating and returning a
     * "parameter binding" that describes its inherent usage restriction.
     * Such parameterized plans are used for ad hoc statements only; it is easy to validate
     * immediately that they have been passed the correct parameter value.
     * Compiled stored procedure statements need to be more specific about constants
     * used in index expressions, even if that means compiling a separate statement with a
     * constant hard-coded in the place of the parameter to purposely match the indexed expression.
     *
     *TODO: A case not accounted for, here, is an ORDER BY list that uses a combination of
     * columns/expressions from different tables -- the most common missed case would be
     * when the major ORDER BY columns are from an outer table (index scan) of a join (NLIJ)
     * and the minor columns from its inner table index scan.
     * This would have to be detected from a wider perspective than that of a single table/index.
     *
     * @param table              only used here to validate base table names of ORDER BY columns' .
     * @param keyComponentCount  the length of indexedExprs or indexedColRefs,
     *                           ONE of which must be valid
     * @param indexedExprs       expressions for key components in the general case
     * @param indexedColRefs     column references for key components in the simpler case
     * @param retval the eventual result of getRelevantAccessPathForIndex,
     *               the bearer of a (tentative) sortDirection determined here
     * @param bindingsForOrder   restrictions on parameter settings that are prerequisite to the
     *                           any ordering optimization determined here
     */
    private void determineIndexOrdering(Table table, int keyComponentCount,
            List<AbstractExpression> indexedExprs, List<ColumnRef> indexedColRefs,
            AccessPath retval,
            List<AbstractExpression> bindingsForOrder)
    {
        if ( ! (m_parsedStmt instanceof ParsedSelectStmt)) {
            return;
        }
        ParsedSelectStmt parsedSelectStmt = (ParsedSelectStmt) m_parsedStmt;
        int countOrderBys = parsedSelectStmt.orderColumns.size();
        // There need to be enough indexed expressions to provide full ORDER BY coverage.
        if (countOrderBys > 0 && countOrderBys <= keyComponentCount) {
            boolean ascending = parsedSelectStmt.orderColumns.get(0).ascending;
            retval.sortDirection = ascending ? SortDirectionType.ASC : SortDirectionType.DESC;
            int jj = 0;
            for (ParsedColInfo colInfo : parsedSelectStmt.orderColumns) {
                if (colInfo.ascending == ascending) {
                    // Explicitly advance to the each indexed expression/column
                    // to match them with the query's "ORDER BY" expressions.
                    if (indexedExprs == null) {
                        if (colInfo.expression instanceof TupleValueExpression) {
                            ColumnRef nextColRef = indexedColRefs.get(jj++);
                            //TODO: match the TVE attributes as they may potentially be more
                            // reliable than these colInfo attributes?
                            if (colInfo.tableName.equals(table.getTypeName()) &&
                                colInfo.columnName.equals(nextColRef.getColumn().getTypeName())) {
                                continue;
                            }
                        }
                    } else {
                        assert(jj < indexedExprs.size());
                        AbstractExpression nextExpr = nextExpr = indexedExprs.get(jj++);
                        List<AbstractExpression> moreBindings =
                            colInfo.expression.bindingToIndexedExpression(nextExpr);
                        // Non-null bindings (even an empty list) denotes a match.
                        if (moreBindings != null) {
                            bindingsForOrder.addAll(moreBindings);
                            continue;
                        }
                    }
                }
                // The ORDER BY column did not match the established ascending/descending
                // pattern OR did not match the next index key component.
                // This is an outright failure case.
                // Undo any tentative result settings.
                retval.sortDirection = SortDirectionType.INVALID;
                bindingsForOrder.clear(); // suddenly irrelevant
                return;
            }
        }
    }

    /**
     * For a given filter expression, return a normalized version of it that is always a comparison operator whose
     * left-hand-side references the table specified and whose right-hand-side does not.
     * Returns null if no such formulation of the filter expression is possible.
     * For example, "WHERE F_ID = 2" would return it input intact if F_ID is in the table passed in.
     * For join expressions like, "WHERE F_ID = Q_ID", it would also return the input expression if F_ID is in the table
     * but Q_ID is not. If only Q_ID were defined for the table, it would return an expression for (Q_ID = F_ID).
     * If both Q_ID and F_ID were defined on the table, null would be returned.
     * Ideally, the left-hand-side expression is intended to be an indexed expression on the table using the current
     * index. To help reduce false positives, the (base) columns and/or indexed expressions of the index are also
     * provided to help further reduce non-null returns in uninteresting cases.
     *
     * @param targetComparator An allowed comparison operator -- its reverse is allowed in reversed expressions
     * @param altTargetComparator An alternatively allowed comparison operator -- its reverse is allowed in reversed expressions
     * @param coveringExpr The indexed expression on the table's column that might match a query filter, possibly null.
     * @param coveringColId When coveringExpr is null, the id of the indexed column might match a query filter.
     * @param table The table on which the indexed expression is based
     * @param filtersToCover query conditions that may contain the desired filter
     * @return An IndexableExpression -- really just a pairing of a normalized form of expr with the
     * potentially indexed expression on the left-hand-side and the potential index key expression on
     * the right of a comparison operator, and a list of parameter bindings that are required for the
     * index scan to be applicable.
     * -- or null if there is no filter that matches the indexed expression
     */
    private IndexableExpression getIndexableExpressionFromFilters(
        ExpressionType targetComparator, ExpressionType altTargetComparator,
        AbstractExpression coveringExpr, int coveringColId, Table table,
        List<AbstractExpression> filtersToCover)
    {
        List<AbstractExpression> binding = null;
        AbstractExpression indexableExpr = null;
        AbstractExpression otherExpr = null;
        ComparisonExpression normalizedExpr = null;
        for (AbstractExpression filter : filtersToCover) {
            // Expression type must be resolvable by an index scan
            if ((filter.getExpressionType() == targetComparator) ||
                (filter.getExpressionType() == altTargetComparator)) {
                normalizedExpr = (ComparisonExpression) filter;
                indexableExpr = filter.getLeft();
                otherExpr = filter.getRight();
                binding = bindingIfValidIndexedFilterOperand(table, indexableExpr, otherExpr,
                                                             coveringExpr, coveringColId);
                if (binding != null) {
                    // Additional restrictions apply to LIKE pattern arguments
                    if (targetComparator == ExpressionType.COMPARE_LIKE) {
                        if (otherExpr instanceof ParameterValueExpression) {
                            ParameterValueExpression pve = (ParameterValueExpression)otherExpr;
                            // Can't use an index for parameterized LIKE filters, e.g. "T1.column LIKE ?"
                            // UNLESS the parameter was artificially substituted for a user-specified constant
                            // AND that constant was a prefix pattern.
                            // In that case, the parameter has to be added to the bound list for this index/statement.
                            ConstantValueExpression cve = pve.getOriginalValue();
                            if (cve == null || ! cve.isPrefixPatternString()) {
                                binding = null; // the filter is not usable, so the binding is invalid
                                continue;
                            }
                            // Remember that the binding list returned by bindingIfValidIndexedFilterOperand above
                            // is often a "shared object" and is intended to be treated as immutable.
                            // To add a parameter to it, first copy the List.
                            List<AbstractExpression> moreBinding = new ArrayList<AbstractExpression>(binding);
                            moreBinding.add(pve);
                            binding = moreBinding;
                        } else if (otherExpr instanceof ConstantValueExpression) {
                            // Can't use an index for non-prefix LIKE filters, e.g. " T1.column LIKE '%ish' "
                            ConstantValueExpression cve = (ConstantValueExpression)otherExpr;
                            if ( ! cve.isPrefixPatternString()) {
                                // The constant is not an index-friendly prefix pattern.
                                binding = null; // the filter is not usable, so the binding is invalid
                                continue;
                            }
                        } else {
                            // Other cases are not indexable, e.g. " T1.column LIKE T2.column "
                            binding = null; // the filter is not usable, so the binding is invalid
                            continue;
                        }
                    }
                    filtersToCover.remove(filter);
                    break;
                }
            }
            if ((filter.getExpressionType() == ComparisonExpression.reverses.get(targetComparator)) ||
                (filter.getExpressionType() == ComparisonExpression.reverses.get(altTargetComparator))) {
                normalizedExpr = (ComparisonExpression) filter;
                normalizedExpr = normalizedExpr.reverseOperator();
                indexableExpr = filter.getRight();
                otherExpr = filter.getLeft();
                binding = bindingIfValidIndexedFilterOperand(table, indexableExpr, otherExpr,
                                                             coveringExpr, coveringColId);
                if (binding != null) {
                    filtersToCover.remove(filter);
                    break;
                }
            }
        }

        if (binding == null) {
            // ran out of candidate filters.
            return null;
        }
        return new IndexableExpression(normalizedExpr, binding);
    }

    private boolean isOperandDependentOnTable(AbstractExpression expr, Table table) {
        for (TupleValueExpression tve : ExpressionUtil.getTupleValueExpressions(expr)) {
            //TODO: This clumsy testing of table names regardless of table aliases is
            // EXACTLY why we can't have nice things like self-joins.
            if (table.getTypeName().equals(tve.getTableName()))
            {
                return true;
            }
        }
        return false;
    }

    private List<AbstractExpression> bindingIfValidIndexedFilterOperand(Table table,
        AbstractExpression indexableExpr, AbstractExpression otherExpr,
        AbstractExpression coveringExpr, int coveringColId)
    {
        // Do some preliminary disqualifications.

        VoltType keyType = indexableExpr.getValueType();
        VoltType otherType = otherExpr.getValueType();
        // EE index key comparator should not lose precision when casting keys to indexed type.
        // Do not choose an index that requires such a cast.
        if ( ! keyType.canExactlyRepresentAnyValueOf(otherType)) {
            return null;
        }
        // Left and right operands must not be from the same table,
        // e.g. where t.a = t.b is not indexable with the current technology.
        if (isOperandDependentOnTable(otherExpr, table)) {
           return null;
        }

        if (coveringExpr == null) {
            // Match only the table's column that has the coveringColId
            if ((indexableExpr.getExpressionType() != ExpressionType.VALUE_TUPLE)) {
                return null;
            }
            TupleValueExpression tve = (TupleValueExpression) indexableExpr;
            // Handle a simple indexed column identified by its column id.
            if ((coveringColId == tve.getColumnIndex()) &&
                //FIXME: This clumsy testing of table names regardless of table aliases is
                // EXACTLY why we can't have nice things like self-joins.
                (table.getTypeName().equals(tve.getTableName()))) {
                // A column match never requires parameter binding. Return an empty list.
                return s_reusableImmutableEmptyBinding;
            }
            return null;
        }
        // Do a possibly more extensive match with coveringExpr which MAY require bound parameters.
        List<AbstractExpression> binding = indexableExpr.bindingToIndexedExpression(coveringExpr);
        return binding;
    }


    /**
     * Insert a send receive pair above the supplied scanNode.
     * @param scanNode that needs to be distributed
     * @return return the newly created receive node (which is linked to the new sends)
     */
    protected AbstractPlanNode addSendReceivePair(AbstractPlanNode scanNode) {

        SendPlanNode sendNode = new SendPlanNode();
        // this will make the child planfragment be sent to all partitions
        sendNode.isMultiPartition = true;
        sendNode.addAndLinkChild(scanNode);

        ReceivePlanNode recvNode = new ReceivePlanNode();
        recvNode.addAndLinkChild(sendNode);

        // receive node requires the schema of its output table
        recvNode.generateOutputSchema(m_db);
        return recvNode;
    }

    /**
     * Given an access path, build the single-site or distributed plan that will
     * assess the data from the table according to the path.
     *
     * @param table The table to get data from.
     * @param path The access path to access the data in the table (index/scan/etc).
     * @return The root of a plan graph to get the data.
     */
    protected AbstractPlanNode getAccessPlanForTable(Table table, AccessPath path) {
        assert(table != null);
        assert(path != null);

        AbstractScanPlanNode scanNode = null;
        // if no path is a sequential scan, call a subroutine for that
        if (path.index == null)
        {
            scanNode = getScanAccessPlanForTable(table, path.otherExprs);
        }
        else
        {
            scanNode = getIndexAccessPlanForTable(table, path);
        }
        // set the scan columns for this scan node based on the parsed SQL,
        // if any
        if (m_parsedStmt.scanColumns != null)
        {
            scanNode.setScanColumns(m_parsedStmt.scanColumns.get(table.getTypeName()));
        }
        scanNode.generateOutputSchema(m_db);
        return scanNode;
    }

    /**
     * Get a sequential scan access plan for a table. For multi-site plans/tables,
     * scans at all partitions and sends to one partition.
     *
     * @param table The table to scan.
     * @param exprs The predicate components.
     * @return A scan plan node
     */
    protected AbstractScanPlanNode
    getScanAccessPlanForTable(Table table, ArrayList<AbstractExpression> exprs)
    {
        // build the scan node
        SeqScanPlanNode scanNode = new SeqScanPlanNode();
        scanNode.setTargetTableName(table.getTypeName());

        // build the predicate
        AbstractExpression localWhere = null;
        if ((exprs != null) && (exprs.isEmpty() == false))
        {
            localWhere = ExpressionUtil.combine(exprs);
            scanNode.setPredicate(localWhere);
        }

        return scanNode;
    }

    /**
     * Get a index scan access plan for a table. For multi-site plans/tables,
     * scans at all partitions and sends to one partition.
     *
     * @param table The table to get data from.
     * @param path The access path to access the data in the table (index/scan/etc).
     * @return An index scan plan node
     */
    protected AbstractScanPlanNode getIndexAccessPlanForTable(Table table,
                                                              AccessPath path)
    {
        // now assume this will be an index scan and get the relevant index
        Index index = path.index;
        IndexScanPlanNode scanNode = new IndexScanPlanNode();
        // Build the list of search-keys for the index in question
        // They are the rhs expressions of the normalized indexExpr comparisons.
        for (AbstractExpression expr : path.indexExprs) {
            AbstractExpression expr2 = expr.getRight();
            assert(expr2 != null);
            scanNode.addSearchKeyExpression(expr2);
        }
        // create the IndexScanNode with all its metadata
        scanNode.setCatalogIndex(index);
        scanNode.setKeyIterate(path.keyIterate);
        scanNode.setLookupType(path.lookupType);
        scanNode.setBindings(path.bindings);
        scanNode.setSortDirection(path.sortDirection);
        scanNode.setEndExpression(ExpressionUtil.combine(path.endExprs));
        scanNode.setPredicate(ExpressionUtil.combine(path.otherExprs));

        scanNode.setTargetTableName(table.getTypeName());
        scanNode.setTargetTableAlias(table.getTypeName());
        scanNode.setTargetIndexName(index.getTypeName());
        return scanNode;
    }
}
