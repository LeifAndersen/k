package org.kframework.backend.java.symbolic;

import static org.kframework.backend.java.util.TestCaseGenerationSettings.PHASE_ONE_BOUND_FREEVARS;
import static org.kframework.backend.java.util.TestCaseGenerationSettings.PHASE_ONE_BOUND_SUCCESSORS;
import static org.kframework.backend.java.util.TestCaseGenerationSettings.PHASE_ONE_MAX_NUM_FREEVARS;
import static org.kframework.backend.java.util.TestCaseGenerationSettings.PHASE_ONE_MAX_NUM_SUCCESSORS;
import static org.kframework.backend.java.util.TestCaseGenerationSettings.PHASE_ONE_ONLY_OUTPUT_GROUND_TERM;
import static org.kframework.backend.java.util.TestCaseGenerationSettings.PHASE_TWO_MAX_NUM_SUCCESSORS;
import static org.kframework.backend.java.util.TestCaseGenerationSettings.PHASE_TWO_MAX_REWRITE_STEPS;
import static org.kframework.backend.java.util.TestCaseGenerationSettings.TWO_PHASE_GENERATION;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Sets;
import org.kframework.backend.java.builtins.IntToken;
import org.kframework.backend.java.indexing.BottomIndex;
import org.kframework.backend.java.indexing.FreezerIndex;
import org.kframework.backend.java.indexing.Index;
import org.kframework.backend.java.indexing.IndexingPair;
import org.kframework.backend.java.indexing.KLabelIndex;
import org.kframework.backend.java.indexing.TokenIndex;
import org.kframework.backend.java.indexing.TopIndex;
import org.kframework.backend.java.indexing.pathIndex.PathIndex;
import org.kframework.utils.general.IndexingStatistics;
import org.kframework.backend.java.kil.Cell;
import org.kframework.backend.java.kil.CellCollection;
import org.kframework.backend.java.kil.ConstrainedTerm;
import org.kframework.backend.java.kil.Definition;
import org.kframework.backend.java.kil.KLabelConstant;
import org.kframework.backend.java.kil.Rule;
import org.kframework.backend.java.kil.Term;
import org.kframework.backend.java.kil.TermContext;
import org.kframework.backend.java.kil.Variable;
import org.kframework.backend.java.strategies.TransitionCompositeStrategy;
import org.kframework.backend.java.util.TestCaseGenerationUtil;
import org.kframework.krun.K;
import org.kframework.krun.api.SearchType;
import org.kframework.krun.api.io.FileSystem;
import org.kframework.utils.general.GlobalSettings;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 *
 *
 * @author AndreiS
 *
 */
public class SymbolicRewriter {

    private final Definition definition;
    private final TransitionCompositeStrategy strategy
    = new TransitionCompositeStrategy(GlobalSettings.transition);
    private final Stopwatch stopwatch = new Stopwatch();
    private int step;
    private final Stopwatch ruleStopwatch = new Stopwatch();
    private Map<Index, List<Rule>> ruleTable;
    private Map<Index, List<Rule>> heatingRuleTable;
    private Map<Index, List<Rule>> coolingRuleTable;
    private Map<Index, List<Rule>> simulationRuleTable;
    private List<Rule> unindexedRules;
    private final List<ConstrainedTerm> results = new ArrayList<ConstrainedTerm>();
    private final List<Rule> appliedRules = new ArrayList<Rule>();
    private boolean transition;
    private final PluggableKastStructureChecker phase1PluggableKastChecker;
    private final PluggableKastStructureChecker phase2PluggableKastChecker;
    private PathIndex pathIndex;
    
    /*
     * Liyi Li : add simulation rules in the constructor, and allow user to input label [alphaRule] as
     * the indication that the rule will be used as simulation
     */
    public SymbolicRewriter(Definition definition) {
        this.definition = definition;
        
        /* initialize the K AST checker for test generation */
        if (K.do_testgen) {
            phase1PluggableKastChecker = new PluggableKastStructureChecker();
            phase1PluggableKastChecker.register(new CheckingNestedStructureDepth());
            phase1PluggableKastChecker.register(new CheckingLeftAssocConstructs(definition));
            
            phase2PluggableKastChecker = new PluggableKastStructureChecker();
            phase2PluggableKastChecker.register(new CheckingLeftAssocConstructs(definition));
        } else {
            phase1PluggableKastChecker = null;
            phase2PluggableKastChecker = null;
        }

        // Index may be built with or without measurement
        if (K.get_indexing_stats){
            buildIndexWithStats(definition);
        } else{
            buildIndex(definition);
        }
    }

    /**
     * Builds rule index with a very basic Indexing Scheme. Does not measure time.
     *
     * @param definition
     */
    private void buildIndex(Definition definition) {
        if (K.do_indexing) {
            pathIndex = new PathIndex(definition);
        } else {
            buildBasicIndex();
        }
    }

    /**
     * Builds rule index with a very basic Indexing Scheme. Measures index creation time.
     *
     * @param definition
     */
    private void buildIndexWithStats(Definition definition) {
        IndexingStatistics.indexConstructionStopWatch.start();
        if (K.do_indexing) {
            pathIndex = new PathIndex(definition);
        } else {
            buildBasicIndex();
        }
        IndexingStatistics.indexConstructionStopWatch.stop();
    }

    private void buildBasicIndex() {

        /* populate the table of rules rewriting the top configuration */
        List<Index> indices = new ArrayList<Index>();
        indices.add(TopIndex.TOP);
        indices.add(BottomIndex.BOTTOM);
        for (KLabelConstant kLabel : definition.kLabels()) {
            indices.add(new KLabelIndex(kLabel));
            indices.add(new FreezerIndex(kLabel, -1));
            if (!kLabel.productions().isEmpty()) {
                for (int i = 0; i < kLabel.productions().get(0).getArity(); ++i) {
                    indices.add(new FreezerIndex(kLabel, i));
                }
            }
        }
        //for (KLabelConstant frozenKLabel : definition.frozenKLabels()) {
        //    for (int i = 0; i < frozenKLabel.productions().get(0).getArity(); ++i) {
        //        indices.add(new FreezerIndex(frozenKLabel, i));
        //    }
        //}
        for (String sort : definition.builtinSorts()) {
            indices.add(new TokenIndex(sort));
        }

        /* Map each index to a list of rules unifiable with that index */
        /* Heating rules and regular rules have their first index checked */
        /* Cooling rules have their second index checked */
        ImmutableMap.Builder<Index, List<Rule>> mapBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<Index, List<Rule>> heatingMapBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<Index, List<Rule>> coolingMapBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<Index, List<Rule>> simulationMapBuilder = ImmutableMap.builder();

        for (Index index : indices) {
            ImmutableList.Builder<Rule> listBuilder = ImmutableList.builder();
            ImmutableList.Builder<Rule> heatingListBuilder = ImmutableList.builder();
            ImmutableList.Builder<Rule> coolingListBuilder = ImmutableList.builder();
            ImmutableList.Builder<Rule> simulationListBuilder = ImmutableList.builder();

            for (Rule rule : definition.rules()) {
                if (rule.containsAttribute("heat")) {
                    if (index.isUnifiable(rule.indexingPair().first)) {
                        heatingListBuilder.add(rule);
                    }
                } else if (rule.containsAttribute("cool")) {
                    if (index.isUnifiable(rule.indexingPair().second)) {
                        coolingListBuilder.add(rule);
                    }
                } else if(rule.containsAttribute("alphaRule")){
                    if(index.isUnifiable(rule.indexingPair().first)) {
                        simulationListBuilder.add(rule);
                    }

                } else {
                    if (index.isUnifiable(rule.indexingPair().first)) {
                        listBuilder.add(rule);
                    }
                }
            }
            ImmutableList<Rule> rules = listBuilder.build();
            if (!rules.isEmpty()) {
                mapBuilder.put(index, rules);
            }
            rules = heatingListBuilder.build();
            if (!rules.isEmpty()) {
                heatingMapBuilder.put(index, rules);
            }
            rules = coolingListBuilder.build();
            if (!rules.isEmpty()) {
                coolingMapBuilder.put(index, rules);
            }
            rules = simulationListBuilder.build();
            if(!rules.isEmpty()){
                simulationMapBuilder.put(index,rules);
            }
        }
        heatingRuleTable = heatingMapBuilder.build();
        coolingRuleTable = coolingMapBuilder.build();
        ruleTable = mapBuilder.build();
        simulationRuleTable = simulationMapBuilder.build();

        ImmutableList.Builder<Rule> listBuilder = ImmutableList.builder();
        for (Rule rule : definition.rules()) {
            if (!rule.containsKCell()) {
                listBuilder.add(rule);
            }
        }
        unindexedRules = listBuilder.build();
    }

    public ConstrainedTerm rewrite(ConstrainedTerm constrainedTerm, int bound) {
        stopwatch.start();

        for (step = 0; step != bound; ++step) {
            /* get the first solution */
            computeRewriteStep(constrainedTerm, 1);
            ConstrainedTerm result = getTransition(0);
            if (result != null) {
                constrainedTerm = result;
            } else {
                break;
            }
        }

        stopwatch.stop();
        System.err.println("[" + step + ", " + stopwatch + "]");

        return constrainedTerm;
    }

    public ConstrainedTerm rewrite(ConstrainedTerm constrainedTerm) {
        return rewrite(constrainedTerm, -1);
    }

    /* author: Liyi Li
     * a function return all the next steps of a given term
     */
    public ArrayList<ConstrainedTerm> rewriteAll(ConstrainedTerm constrainedTerm){

        computeRewriteStep(constrainedTerm);

        return (ArrayList<ConstrainedTerm>) results;
    }

    /*
     * author: Liyi Li
     * return the rules for simulations only
     */
    public Map<Index, List<Rule>> getSimulationMap(){
        return this.simulationRuleTable;
    }

    /*
     * author: Liyi Li
     * return the rules for simulations only
     */
    private List<Rule> getSimulationRules(Term term) {
        List<Rule> rules = new ArrayList<Rule>();
        for (IndexingPair pair : term.getIndexingPairs(definition)) {
            if (simulationRuleTable.get(pair.first) != null) {
                rules.addAll(simulationRuleTable.get(pair.first));
            }
        }
        return rules;
    }

    /**
     * Gets the rules that could be applied to a given term according to the
     * rule indexing mechanism.
     *
     * @param term
     *            the given term
     * @return a list of rules that could be applied
     */
    private List<Rule> getRules(Term term) {
        List<Rule> rules = new ArrayList<>();
        if (K.get_indexing_stats){
            IndexingStatistics.getRulesForTermStopWatch.reset();
            IndexingStatistics.getRulesForTermStopWatch.start();
        }

        if (K.do_indexing) {
//            pathIndex.getRulesForTerm(term);
//            rules.addAll(getNonIndexedRules(term));
            rules.addAll(pathIndex.getRulesForTerm(term));
        } else {
            rules.addAll(getNonIndexedRules(term));
        }

        if (K.get_indexing_stats){
            IndexingStatistics.rulesSelectedAtEachStep.add(rules.size());
            long elapsed = IndexingStatistics.getRulesForTermStopWatch.stop().elapsed(TimeUnit.MICROSECONDS);
            IndexingStatistics.timesForRuleSelection.add(elapsed);
        }
        return rules;
    }

    private List<Rule> getNonIndexedRules(Term term) {
        Set<Rule> rules = new LinkedHashSet<>();

        for (IndexingPair pair : term.getIndexingPairs(definition)) {
            if (ruleTable.get(pair.first) != null) {
                rules.addAll(ruleTable.get(pair.first));
            }
            if (heatingRuleTable.get(pair.first) != null) {
                rules.addAll(heatingRuleTable.get(pair.first));
            }
            if (coolingRuleTable.get(pair.second) != null) {
                rules.addAll(coolingRuleTable.get(pair.second));
            }
        }
        rules.addAll(unindexedRules);
        return new ArrayList<>(rules);
    }

    private ConstrainedTerm getTransition(int n) {
        return n < results.size() ? results.get(n) : null;
    }

    /*
     * author : Liyi Li
     * computer steps by rules of simulation
     */
    @SuppressWarnings("unchecked")
    public ConstrainedTerm computeSimulationStep(ConstrainedTerm constrainedTerm) {
        // Applying a strategy to a list of rules divides the rules up into
        // equivalence classes of rules. We iterate through these equivalence
        // classes one at a time, seeing which one contains rules we can apply.
        //        System.out.println(LookupCell.find(constrainedTerm.term(),"k"));
        strategy.reset(getSimulationRules(constrainedTerm.term()));
        while (strategy.hasNext()) {
            transition = strategy.nextIsTransition();
            List<Rule> rules = ((List<Rule>)strategy.next());

            for (Rule rule : rules) {
                ruleStopwatch.reset();
                ruleStopwatch.start();

                SymbolicConstraint leftHandSideConstraint = new SymbolicConstraint(
                        constrainedTerm.termContext());
                leftHandSideConstraint.addAll(rule.requires());
                            
                CellCollection newTemp = new CellCollection();
                
                newTemp.cellMap().put(((Cell<Term>)rule.leftHandSide()).getLabel(), (Cell<Term>)rule.leftHandSide());
                
                Cell<Term> newRuleTerm = new Cell<Term>("generatedTop",newTemp);

                ConstrainedTerm leftHandSideTerm = new ConstrainedTerm(
                        newRuleTerm,
                        rule.lookups().getSymbolicConstraint(constrainedTerm.termContext()),
                        leftHandSideConstraint,
                        constrainedTerm.termContext());

                SymbolicConstraint constraint = constrainedTerm.matchImplies(leftHandSideTerm);
                if (constraint == null) {
                    continue;
                }
                constraint.addAll(rule.ensures());

                /* rename rule variables in the constraints */
                Map<Variable, Variable> freshSubstitution = constraint.rename(rule.variableSet());

                Term result = rule.rightHandSide();
                /* rename rule variables in the rule RHS */
                result = result.substituteWithBinders(freshSubstitution, constrainedTerm.termContext());
                /* apply the constraints substitution on the rule RHS */
                result = result.substituteWithBinders(constraint.substitution(), constrainedTerm.termContext());
                /* evaluate pending functions in the rule RHS */
                //result = result.evaluate(constrainedTerm.termContext());
                /* eliminate anonymous variables */
                constraint.eliminateAnonymousVariables();

                /* return first solution */
                return new ConstrainedTerm(result, constraint, constrainedTerm.termContext());
            }
            
        }
        //System.out.println("Result: " + results.toString());
        //System.out.println();
        
        return null;
    }

    private void computeRewriteStep(ConstrainedTerm constrainedTerm, int successorBound) {
        if (K.get_indexing_stats){
            IndexingStatistics.rewriteStepStopWatch.reset();
            IndexingStatistics.rewriteStepStopWatch.start();
        }
        results.clear();
        appliedRules.clear();

        if (successorBound == 0) {
            return;
        }

        // Applying a strategy to a list of rules divides the rules up into
        // equivalence classes of rules. We iterate through these equivalence
        // classes one at a time, seeing which one contains rules we can apply.
        //        System.out.println(LookupCell.find(constrainedTerm.term(),"k"));
        strategy.reset(getRules(constrainedTerm.term()));

        while (strategy.hasNext()) {
            if (K.get_indexing_stats){
                IndexingStatistics.rewritingStopWatch.reset();
                IndexingStatistics.rewritingStopWatch.start();
            }
            transition = strategy.nextIsTransition();
            ArrayList<Rule> rules = new ArrayList<Rule>(strategy.next());
//            System.out.println("rules.size: "+rules.size());
            for (Rule rule : rules) {
                ruleStopwatch.reset();
                ruleStopwatch.start();

                SymbolicConstraint leftHandSideConstraint = new SymbolicConstraint(
                        constrainedTerm.termContext());
                leftHandSideConstraint.addAll(rule.requires());
                for (Variable variable : rule.freshVariables()) {
                    leftHandSideConstraint.add(variable, IntToken.fresh());
                }

                ConstrainedTerm leftHandSide = new ConstrainedTerm(
                        rule.leftHandSide(),
                        rule.lookups().getSymbolicConstraint(constrainedTerm.termContext()),
                        leftHandSideConstraint,
                        constrainedTerm.termContext());

                for (SymbolicConstraint constraint1 : constrainedTerm.unify(leftHandSide)) {
                    /*
                     * TODO(YilongL): had to comment out the following assertion
                     * because logik.k uses unification even in concrete
                     * execution mode
                     */
//                    if (K.do_concrete_exec) {
//                        assert constraint1.isMatching(leftHandSide) : "Pattern matching expected in concrete execution mode";
//                    }

                    constraint1.orientSubstitution(rule.leftHandSide().variableSet());
                    constraint1.addAll(rule.ensures());
                    
                    Term result = rule.rightHandSide();

                    /* the RHS of the rule has introduced new variables */
                    if (rule.hasUnboundedVariables()) {
                        /* rename rule variables in the constraints */
                        Map<Variable, Variable> freshSubstitution = constraint1.rename(rule.variableSet());
                        /* rename rule variables in the rule RHS */
                        result = result.substituteWithBinders(freshSubstitution, constrainedTerm.termContext());
                    }
                    
                    /* apply the constraints substitution on the rule RHS */
                    result = result.substituteAndEvaluate(
                            constraint1.substitution(),
                            constrainedTerm.termContext());
                    /* eliminate anonymous variables */
                    constraint1.eliminateAnonymousVariables();

                    /*
                    System.err.println("rule \n\t" + rule);
                    System.err.println("result term\n\t" + result);
                    System.err.println("result constraint\n\t" + constraint1);
                    System.err.println("============================================================");
                     */

                    /* compute all results */
                    ConstrainedTerm newCnstrTerm = new ConstrainedTerm(result,
                            constraint1, constrainedTerm.termContext());
                    // TODO(YilongL): the following assertion is not always true; fix it
//                    if (K.do_concrete_exec) {
//                        assert newCnstrTerm.isGround();
//                    }
                    results.add(newCnstrTerm);
                    appliedRules.add(rule);
                    if (K.get_indexing_stats){
                        IndexingStatistics.rewritingStopWatch.stop();
                        IndexingStatistics.timesForRewriting.add(
                                IndexingStatistics.rewritingStopWatch.elapsed(TimeUnit.MICROSECONDS));
                    }
                    if (results.size() == successorBound) {
                        if (K.get_indexing_stats) {
                            IndexingStatistics.rewriteStepStopWatch.stop();
                            long elapsed =
                                    IndexingStatistics.rewriteStepStopWatch.elapsed(TimeUnit.MICROSECONDS);
                            IndexingStatistics.timesForRewriteSteps.add(elapsed);
                        }
                        return;
                    }
                }
            }
            // If we've found matching results from one equivalence class then
            // we are done, as we can't match rules from two equivalence classes
            // in the same step.
            if (results.size() > 0) {
                //TODO(OwolabiL): Remove duplication
                if (K.get_indexing_stats){
                    IndexingStatistics.rewriteStepStopWatch.stop();
                    long elapsed =
                            IndexingStatistics.rewriteStepStopWatch.elapsed(TimeUnit.MICROSECONDS);
                    IndexingStatistics.timesForRewriteSteps.add(elapsed);
                }
                return;
            }
        }
        //System.out.println("Result: " + results.toString());
        //System.out.println();
    }

    private void computeRewriteStep(ConstrainedTerm constrainedTerm) {
        computeRewriteStep(constrainedTerm, -1);
    }

    /**
     * Apply a specification rule
     */
    private ConstrainedTerm applyRule(ConstrainedTerm constrainedTerm, List<Rule> rules) {
        for (Rule rule : rules) {
            ruleStopwatch.reset();
            ruleStopwatch.start();

            SymbolicConstraint leftHandSideConstraint = new SymbolicConstraint(
                    constrainedTerm.termContext());
            leftHandSideConstraint.addAll(rule.requires());

            ConstrainedTerm leftHandSideTerm = new ConstrainedTerm(
                    rule.leftHandSide(),
                    rule.lookups().getSymbolicConstraint(constrainedTerm.termContext()),
                    leftHandSideConstraint,
                    constrainedTerm.termContext());

            SymbolicConstraint constraint = constrainedTerm.matchImplies(leftHandSideTerm);
            if (constraint == null) {
                continue;
            }
            constraint.addAll(rule.ensures());

            /* rename rule variables in the constraints */
            Map<Variable, Variable> freshSubstitution = constraint.rename(rule.variableSet());

            Term result = rule.rightHandSide();
            /* rename rule variables in the rule RHS */
            result = result.substituteWithBinders(freshSubstitution, constrainedTerm.termContext());
            /* apply the constraints substitution on the rule RHS */
            result = result.substituteWithBinders(constraint.substitution(), constrainedTerm.termContext());
            /* evaluate pending functions in the rule RHS */
            result = result.evaluate(constrainedTerm.termContext());
            /* eliminate anonymous variables */
            constraint.eliminateAnonymousVariables();

            /* return first solution */
            return new ConstrainedTerm(result, constraint, constrainedTerm.termContext());
        }

        return null;
    }

    // Unifies the term with the pattern, and returns a map from variables in
    // the pattern to the terms they unify with. Returns null if the term
    // can't be unified with the pattern.
    private Map<Variable, Term> getSubstitutionMap(ConstrainedTerm term, Rule pattern) {
        // Create the initial constraints based on the pattern
        SymbolicConstraint termConstraint = new SymbolicConstraint(term.termContext());
        termConstraint.addAll(pattern.requires());
        for (Variable var : pattern.freshVariables()) {
            termConstraint.add(var, IntToken.fresh());
        }

        // Create a constrained term from the left hand side of the pattern.
        ConstrainedTerm lhs = new ConstrainedTerm(
                pattern.leftHandSide(),
                pattern.lookups().getSymbolicConstraint(term.termContext()),
                termConstraint,
                term.termContext());

        // Collect the variables we are interested in finding
        VariableVisitor visitor = new VariableVisitor();
        lhs.accept(visitor);

        Collection<SymbolicConstraint> constraints = term.unify(lhs);
        if (constraints.isEmpty()) {
            return null;
        }

        // Build a substitution map containing the variables in the pattern from
        // the substitution constraints given by unification.
        Map<Variable, Term> map = new HashMap<Variable, Term>();
        for (SymbolicConstraint constraint : constraints) {
            if (!constraint.isSubstitution()) {
                return null;
            }
            constraint.orientSubstitution(visitor.getVariableSet());
            for (Variable variable : visitor.getVariableSet()) {
                Term value = constraint.substitution().get(variable);
                if (value == null) {
                    return null;
                }
                map.put(variable, new Cell<Term>("generatedTop", value));
            }
        }
        return map;
    }

    /**
     *
     * @param initialTerm
     * @param targetTerm not implemented yet
     * @param rules not implemented yet
     * @param pattern the pattern we are searching for
     * @param bound a negative value specifies no bound
     * @param depth a negative value specifies no bound
     * @param searchType defines when we will attempt to match the pattern

     * @return a list of substitution mappings for results that matched the pattern
     */
    public List<Map<Variable,Term>> search(
            ConstrainedTerm initialTerm,
            ConstrainedTerm targetTerm,
            List<Rule> rules,
            Rule pattern,
            int bound,
            int depth,
            SearchType searchType) {
        stopwatch.start();

        List<Map<Variable,Term>> searchResults = new ArrayList<Map<Variable,Term>>();
        Set<ConstrainedTerm> visited = new HashSet<ConstrainedTerm>();

        // If depth is 0 then we are just trying to match the pattern.
        // A more clean solution would require a bit of a rework to how patterns
        // are handled in krun.Main when not doing search.
        if (depth == 0) {
            Map<Variable, Term> map = getSubstitutionMap(initialTerm, pattern);
            if (map != null) {
                searchResults.add(map);
            }
            stopwatch.stop();
            System.err.println("[" + visited.size() + "states, " + step + "steps, " + stopwatch + "]");
            return searchResults;
        }

        // The search queues will map terms to their depth in terms of transitions.
        Map<ConstrainedTerm,Integer> queue = new LinkedHashMap<ConstrainedTerm,Integer>();
        Map<ConstrainedTerm,Integer> nextQueue = new LinkedHashMap<ConstrainedTerm,Integer>();

        visited.add(initialTerm);
        queue.put(initialTerm, 0);

        if (searchType == SearchType.ONE) {
            depth = 1;
        }
        if (searchType == SearchType.STAR) {
            Map<Variable, Term> map = getSubstitutionMap(initialTerm, pattern);
            if (map != null) {
                searchResults.add(map);
            }
        }

        label:
            for (step = 0; !queue.isEmpty(); ++step) {
                for (Map.Entry<ConstrainedTerm, Integer> entry : queue.entrySet()) {
                    ConstrainedTerm term = entry.getKey();
                    Integer currentDepth = entry.getValue();
                    computeRewriteStep(term);
//                    System.out.println(step);
//                    System.err.println(term);
//                    for (ConstrainedTerm r : results) {
//                        System.out.println(r);
//                    }

                    if (results.isEmpty() && searchType == SearchType.FINAL) {
                        Map<Variable, Term> map = getSubstitutionMap(term, pattern);
                        if (map != null) {
                            searchResults.add(map);
                            if (searchResults.size() == bound) {
                                break label;
                            }
                        }
                    }

                    for (ConstrainedTerm result : results) {
                        if (!transition) {
                            nextQueue.put(result, currentDepth);
                            break;
                        } else {
                            // Continue searching if we haven't reached our target
                            // depth and we haven't already visited this state.
                            if (currentDepth + 1 != depth && visited.add(result)) {
                                nextQueue.put(result, currentDepth + 1);
                            }
                            // If we aren't searching for only final results, then
                            // also add this as a result if it matches the pattern.
                            if (searchType != SearchType.FINAL || currentDepth + 1 == depth) {
                                Map<Variable, Term> map = getSubstitutionMap(result, pattern);
                                if (map != null) {
                                    searchResults.add(map);
                                    if (searchResults.size() == bound) {
                                        break label;
                                    }
                                }
                            }
                        }
                    }
                }
//                System.out.println("+++++++++++++++++++++++");

                /* swap the queues */
                Map<ConstrainedTerm, Integer> temp;
                temp = queue;
                queue = nextQueue;
                nextQueue = temp;
                nextQueue.clear();
            }

        stopwatch.stop();
        System.err.println("[" + visited.size() + "states, " + step + "steps, " + stopwatch + "]");

        return searchResults;
    }

    /**
     *
     * @param initialTerm
     * @param targetTerm not implemented yet
     * @param rules not implemented yet
     * @param bound a negative value specifies no bound
     * @param depth a negative value specifies no bound
     * @return
     */
    public List<ConstrainedTerm> generate(
            ConstrainedTerm initialTerm,
            ConstrainedTerm targetTerm,
            List<Rule> rules,
            int bound,
            int depth) {
        stopwatch.start();

        List<ConstrainedTerm> testgenResults = new ArrayList<ConstrainedTerm>();
        Set<ConstrainedTerm> visited = new HashSet<ConstrainedTerm>();
        List<ConstrainedTerm> queue = new ArrayList<ConstrainedTerm>();
        List<ConstrainedTerm> nextQueue = new ArrayList<ConstrainedTerm>();
        List<Rule> nextQueueOfRules = new ArrayList<Rule>();

        visited.add(initialTerm);
        queue.add(initialTerm);
        
        label:
        for (step = 0; !queue.isEmpty() && step != depth; ++step) {
            System.out.printf("testgen #step = %s, size = %s\n", step, queue.size());
            
            Map<String, Integer> ruleDistStats = new HashMap<>();
            nextQueueOfRules.clear();

            for (ConstrainedTerm term : queue) {
                computeRewriteStep(term);
                
                /* first eliminate terms that fail the K AST checker */
                performKastStructureCheck(phase1PluggableKastChecker, initialTerm);
                /* then eliminate terms that have too many free variables */
                if (PHASE_ONE_BOUND_FREEVARS) {
                    eliminateTermsWithNumOfFreeVarsGT(PHASE_ONE_MAX_NUM_FREEVARS);
                }
                /* finally eliminate shadowed rules */
                eliminateShadowedRewriteSteps();

                TestCaseGenerationUtil.updateRuleDistStats(ruleDistStats, appliedRules);
                
                if (results.isEmpty()) {
                    /* final term */
                    testgenResults.add(term);
                    if (testgenResults.size() == bound) {
                        break label;
                    }
                    
                    // TODO(YilongL): how to determine if this final term is
                    // proper result or junk? should it be user-defined or
                    // provided by developers?
//                    Cell<?> kCell = LookupCell.find(term, "k");
////                    System.err.println(kCell.getContent());
//                    if (kCell.getContent().toString().length() <= 10) {
//                        testgenResults.add(term);
//                        if (testgenResults.size() == bound) {
//                            break label;
//                        }
//                    }
                }

                for (int i = 0; getTransition(i) != null; ++i) {
                    if (visited.add(getTransition(i))) {
                        nextQueue.add(getTransition(i));
                        nextQueueOfRules.add(appliedRules.get(i));
                    }
                }
            }
            
            System.out.println("rule distribution stats: " + ruleDistStats);            
            
            /* debugging: test generation runs into a (local) dead end */
//            if (nextQueue.isEmpty()) {
//                System.err.printf("The state queue drains out...\n)");
//                System.err.println("last round :");
//                for (ConstrainedTerm term : queue) {
//                    System.err.println(term);
//                }
//            }
            
            /* swap the queues */
            List<ConstrainedTerm> temp;
            temp = queue;
            if (PHASE_ONE_BOUND_SUCCESSORS) {
//                queue = TestCaseGenerationUtil.getArbitraryStates(nextQueue,
//                        PHASE_ONE_MAX_NUM_SUCCESSORS);
                queue = TestCaseGenerationUtil.getStatesByRR(nextQueue,
                        nextQueueOfRules, PHASE_ONE_MAX_NUM_SUCCESSORS);
            } else {
                queue = nextQueue;
            }
            nextQueue = temp;
            nextQueue.clear();
        }

        /* add the configurations on the depth frontier */
        while (!queue.isEmpty() && testgenResults.size() != bound) {
            ConstrainedTerm cnstrTerm = queue.remove(0);

            if (TWO_PHASE_GENERATION) {
                // TODO(YilongL): how to detect and warn the user that this term
                // may involve infinite rewrites?
                ConstrainedTerm grndTerm = getFirstReachableGroundTerm(cnstrTerm, PHASE_TWO_MAX_REWRITE_STEPS);

//                System.out.printf("cnstrTerm = %s\ngrndTerm = %s\n", cnstrTerm, grndTerm);

                if (grndTerm != null) {
                    testgenResults.add(grndTerm);
                }
            } else {
                if (PHASE_ONE_ONLY_OUTPUT_GROUND_TERM) {
                    computeRewriteStep(cnstrTerm, 1);
                    if (results.isEmpty()) {
                        testgenResults.add(cnstrTerm);
                    }
                } else {
                    testgenResults.add(cnstrTerm);
                }
            }
        }

        stopwatch.stop();
        System.err.println("[" + visited.size() + "states, " + step + "steps, " + stopwatch + "]");

        return testgenResults;
    }

    private void eliminateTermsWithNumOfFreeVarsGT(int maxNumOfFreeVars) {
        List<ConstrainedTerm> tmpResults = new ArrayList<ConstrainedTerm>(results);
        List<Rule> tmpAppliedRules = new ArrayList<Rule>(appliedRules);
        results.clear();
        appliedRules.clear();
        for (int i = 0; i < tmpResults.size(); i++) {
            if (TestCaseGenerationUtil.getNumOfFreeVars(tmpResults.get(i),
                    definition.context()) <= maxNumOfFreeVars) {
                results.add(tmpResults.get(i));
                appliedRules.add(tmpAppliedRules.get(i));
            }
        }
    }

    /**
     * Eliminates rewrite steps obtained from applying rules that are shadowed
     * by its preceding rules for test generation.
     */
    private void eliminateShadowedRewriteSteps() {
        assert K.do_testgen;
        
        Set<String> shadowedLabels = new HashSet<String>();
        
        for (Rule rule : appliedRules) {
            String label = rule.getAttribute("testgen-precede"); 
            if (label != null) {
                shadowedLabels.add(label);
            }
        }
        
        List<ConstrainedTerm> tmpResults = new ArrayList<ConstrainedTerm>(results);
        List<Rule> tmpAppliedRules = new ArrayList<Rule>(appliedRules);
        results.clear();
        appliedRules.clear();
        for (int i = 0; i < tmpResults.size(); i++) {
            if (!shadowedLabels.contains(tmpAppliedRules.get(i).label())) {
                results.add(tmpResults.get(i));
                appliedRules.add(tmpAppliedRules.get(i));
            }
        }
    }

    private void performKastStructureCheck(PluggableKastStructureChecker checker, ConstrainedTerm initTerm) {
        List<ConstrainedTerm> tmpResults = new ArrayList<ConstrainedTerm>(results);
        List<Rule> tmpAppliedRules = new ArrayList<Rule>(appliedRules);
        results.clear();
        appliedRules.clear();
        for (int i = 0; i < tmpResults.size(); i++) {
            /* substitute the initial term to get a partially instantiated pgm */
            Term pgm = initTerm.term().substituteWithBinders(
                    tmpResults.get(i).constraint().substitution(),
                    initTerm.termContext());
            
            checker.reset();
            pgm.accept(checker);
            if (checker.isSuccess()) {
                results.add(tmpResults.get(i));
                appliedRules.add(tmpAppliedRules.get(i));
//                System.out.print("Pass");
//            } else {
//                System.err.print("Fail");
            }
//            System.out.printf("partial pgm: %s\n", pgm);
        }
    }    
    
    /**
     * Searches for a ground term which the given term can reach within a given
     * bound of rewrite steps.
     * <p>
     * Since this method is leveraging heuristics to avoid full-fledged BFS,
     * there is no guarantee to always find an existing ground term.
     *
     * @param initTerm
     *            the given term
     *
     * @param depth
     *            the given bound of rewrite steps; a negative value specifies
     *            no bound
     *
     * @return the first ground term that is found, or null if no ground term is
     *         found
     */
    private ConstrainedTerm getFirstReachableGroundTerm(ConstrainedTerm initTerm, int depth) {
        Set<ConstrainedTerm> visited = new HashSet<ConstrainedTerm>();
        List<ConstrainedTerm> queue = new ArrayList<ConstrainedTerm>();
        List<ConstrainedTerm> nextQueue = new ArrayList<ConstrainedTerm>();

        visited.add(initTerm);
        queue.add(initTerm);

        for (int step = 0; !queue.isEmpty() && step != depth; ++step) {
//            System.out.printf("searching for ground term #step %s\n", step);
            for (ConstrainedTerm term : queue) {
                computeRewriteStep(term);
                performKastStructureCheck(phase2PluggableKastChecker, initTerm);
                eliminateShadowedRewriteSteps();

                if (results.isEmpty()) {
                    /* final term */
                    return term;
                }

                for (int i = 0; getTransition(i) != null; ++i) {
                    if (visited.add(getTransition(i))) {
                        nextQueue.add(getTransition(i));
                    }
                }
            }

            /* swap the queues */
            List<ConstrainedTerm> temp;
            temp = queue;
            queue = TestCaseGenerationUtil.getMostConcreteStates(nextQueue,
                    PHASE_TWO_MAX_NUM_SUCCESSORS, definition.context());
            nextQueue = temp;
            nextQueue.clear();
        }

        while (!queue.isEmpty()) {
            ConstrainedTerm cnstrTerm = queue.remove(0);
            computeRewriteStep(cnstrTerm, 1);
            if (results.isEmpty()) {
                return cnstrTerm;
            }
        }

        return null;
    }

    public List<ConstrainedTerm> prove(List<Rule> rules, FileSystem fs) {
        stopwatch.start();

        List<ConstrainedTerm> proofResults = new ArrayList<ConstrainedTerm>();
        for (Rule rule : rules) {
            /* rename rule variables */
            Map<Variable, Variable> freshSubstitution = Variable.getFreshSubstitution(rule.variableSet());

            TermContext context = TermContext.of(definition, fs);
            SymbolicConstraint sideConstraint = new SymbolicConstraint(context);
            sideConstraint.addAll(rule.requires());
            ConstrainedTerm initialTerm = new ConstrainedTerm(
                    rule.leftHandSide().substituteWithBinders(freshSubstitution, context),
                    rule.lookups().getSymbolicConstraint(context).substituteWithBinders(
                            freshSubstitution,
                            context),
                    sideConstraint.substituteWithBinders(freshSubstitution, context),
                    context);

            ConstrainedTerm targetTerm = new ConstrainedTerm(
                    rule.rightHandSide().substituteWithBinders(freshSubstitution, context),
                    context);

            proofResults.addAll(proveRule(initialTerm, targetTerm, rules));
        }

        stopwatch.stop();
        System.err.println("[" + stopwatch + "]");

        return proofResults;
    }

    public List<ConstrainedTerm> proveRule(
            ConstrainedTerm initialTerm,
            ConstrainedTerm targetTerm,
            List<Rule> rules) {
        List<ConstrainedTerm> proofResults = new ArrayList<ConstrainedTerm>();
        Set<ConstrainedTerm> visited = new HashSet<ConstrainedTerm>();
        List<ConstrainedTerm> queue = new ArrayList<ConstrainedTerm>();
        List<ConstrainedTerm> nextQueue = new ArrayList<ConstrainedTerm>();

        visited.add(initialTerm);
        queue.add(initialTerm);
        boolean guarded = false;
        while (!queue.isEmpty()) {
            for (ConstrainedTerm term : queue) {
                if (term.implies(targetTerm)) {
                    continue;
                }

                if (guarded) {
                    ConstrainedTerm result = applyRule(term, rules);
                    if (result != null) {
                        if (visited.add(result))
                            nextQueue.add(result);
                        continue;
                    }
                }

                computeRewriteStep(term);
                if (results.isEmpty()) {
                    /* final term */
                    proofResults.add(term);
                } else {
                    /* add helper rule */
                    HashSet<Variable> ruleVariables = new HashSet<Variable>(initialTerm.variableSet());
                    ruleVariables.addAll(targetTerm.variableSet());
                    Map<Variable, Variable> freshSubstitution = Variable.getFreshSubstitution(
                            ruleVariables);

                    /*
                    rules.add(new Rule(
                            term.term().substitute(freshSubstitution, definition),
                            targetTerm.term().substitute(freshSubstitution, definition),
                            term.constraint().substitute(freshSubstitution, definition),
                            Collections.<Variable>emptyList(),
                            new SymbolicConstraint(definition).substitute(freshSubstitution, definition),
                            IndexingPair.getIndexingPair(term.term()),
                            new Attributes()));
                     */
                }

                for (int i = 0; getTransition(i) != null; ++i) {
                    if (visited.add(getTransition(i))) {
                        nextQueue.add(getTransition(i));
                    }
                }
            }

            /* swap the queues */
            List<ConstrainedTerm> temp;
            temp = queue;
            queue = nextQueue;
            nextQueue = temp;
            nextQueue.clear();
            guarded = true;

            /*
            for (ConstrainedTerm result : queue) {
                System.err.println(result);
            }
            System.err.println("============================================================");
             */
        }

        return proofResults;
    }

}
