package org.kframework.backend.java.builtins;

import org.kframework.backend.java.kil.*;
import org.kframework.backend.java.symbolic.SymbolicConstraint;
import org.kframework.backend.java.symbolic.SymbolicUnifier;
import org.kframework.kil.matchers.MatcherException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Table of {@code public static} methods for builtin meta K operations.
 * 
 * @author AndreiS
 */
public class MetaK {

    /**
     * Checks if two given {@link Term}s can be unified.
     * 
     * @param term1
     *            the first term
     * @param term2
     *            the second term
     * @param context
     *            the term context
     * @return {@link BoolToken#TRUE} if the two terms can be unified;
     *         otherwise, {@link BoolToken#FALSE}
     */
    public static BoolToken unifiable(Term term1, Term term2, TermContext context) {
//        Term freshTerm1 = term1.substitute(Variable.getFreshSubstitution(term1.variableSet()), context);
//        Term freshTerm2 = term2.substitute(Variable.getFreshSubstitution(term2.variableSet()), context);
        SymbolicConstraint constraint = new SymbolicConstraint(context);
        SymbolicUnifier unifier = new SymbolicUnifier(constraint, context);
        try {
            unifier.unify(term1, term2);
        } catch (MatcherException e) {
            return BoolToken.FALSE;
        }
        return BoolToken.TRUE;
    }

    /**
     * Renames {@link Variable}s of a given {@link Term} if they appear also in
     * a given {@link BuiltinSet} of {@link MetaVariable}s.
     * 
     * 
     * @param term
     *            the given term
     * @param builtinSet
     *            the given set of meta variables
     * @param context
     *            the term context
     * @return the resulting term if the renaming succeeds; or the original term
     *         if the given {@code BuiltinSet} has a frame or contains not only
     *         {@code MetaVariable}s
     */
    public static Term rename(Term term, BuiltinSet builtinSet, TermContext context) {
        if (builtinSet.hasFrame() /* || !builtinSet.operations().isEmpty() */) {
            return term;
        }

        Set<Variable> variables = new HashSet<Variable>();
        for (Term element : builtinSet.elements()) {
            if (!(element instanceof MetaVariable)) {
                return term;
            }

            variables.add(new Variable((MetaVariable) element));
        }

        return term.substitute(Variable.getFreshSubstitution(variables), context);
    }

    /**
     * Renames all {@link Variable}s inside a given {@link Term} to unique fresh names.
     * 
     * @param term
     *            the given term
     * @param context
     *            the term context
     * @return the resulting term after renaming
     */
    public static Term renameVariables(Term term, TermContext context) {
        Set<Variable> variables = term.variableSet();
        return term.substitute(Variable.getFreshSubstitution(variables), context);
    }

    /**
     * Returns all {@link Variable}s inside a given {@link Term} as a
     * {@link BuiltinSet} of {@link MetaVariable}s.
     * 
     * @param term
     *            the given term
     * @param context
     *            the term context
     * @return a {@code BuiltinSet} of {@code MetaVariable}s
     */
    public static BuiltinSet variables(Term term, TermContext context) {
        Set<Term> metaVariables = new HashSet<Term>();
        for (Variable variable : term.variableSet()) {
            metaVariables.add(new MetaVariable(variable));
        }
        return new BuiltinSet(metaVariables);
    }
    
    /**
     * Returns all {@link Variable}s inside a given {@link Term} as a
     * {@link BuiltinSet}.
     * 
     * @param term
     *            the given term
     * @param context
     *            the term context
     * @return a {@code BuiltinSet} of {@code Variable}s
     */
    public static BuiltinSet trueVariables(Term term, TermContext context) {
        Set<Variable> trueVariables = term.variableSet();
        return new BuiltinSet(trueVariables);
    }

    public static BuiltinMap variablesMap(Term term, TermContext context) {
        Set<Variable> variables = term.variableSet();
        Map<MetaVariable, Variable> result = new HashMap<>(variables.size());
        for (Variable variable : variables) {
            assert variable instanceof Variable : "this function only applies on variables";
            result.put(new MetaVariable(variable), variable);
        }
        return BuiltinMap.of(result, null);
    }

    /**
     * Returns the first or the second {@link Term} according to the value of
     * the {@link BoolToken}.
     * 
     * @param boolToken
     *            the boolean token
     * @param t
     *            the first term
     * @param e
     *            the second term
     * @param context
     *            the term context
     * @return the first term if the {@code BoolToken} represents true;
     *         otherwise, the second term
     */
    public static Term ite(BoolToken boolToken, Term t, Term e, TermContext context) {
        if (boolToken.booleanValue()) return t;
        return e;
    }

    /**
     * Returns the K label of a specified {@link KItem}.
     * 
     * @param kItem
     *            the specified {@code KItem}
     * @param context
     *            the term context
     * @return the K label
     */
    public static KLabel getKLabel(KItem kItem, TermContext context) {
        // TODO(AndreiS): handle KLabel variables
        return (KLabel) kItem.kLabel();
    }
}
