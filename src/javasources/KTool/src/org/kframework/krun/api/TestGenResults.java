package org.kframework.krun.api;

import java.util.List;
import java.util.Map;

import org.kframework.backend.java.symbolic.SymbolicConstraint;
import org.kframework.backend.unparser.UnparserFilter;
import org.kframework.kil.Term;
import org.kframework.kil.loader.Context;
import org.kframework.krun.K;

import edu.uci.ics.jung.graph.DirectedGraph;

public class TestGenResults {
    private List<TestGenResult> testGenResults;
    private DirectedGraph<KRunState, Transition> graph;
    private boolean isDefaultPattern;

    protected Context context;

    public TestGenResults(List<TestGenResult> results,
            DirectedGraph<KRunState, Transition> graph,
            boolean isDefaultPattern, Context context) {
        this.context = context;
        this.testGenResults = results;
        this.graph = graph;
        this.isDefaultPattern = isDefaultPattern;
    }

    @Override
    public String toString() {
        int n = 1;
        StringBuilder sb = new StringBuilder();
        sb.append("Test generation results:");
        
        for (TestGenResult testGenResult : testGenResults) {
            // TODO(YilongL): how to set state id?
            sb.append("\n\nTest case " + n /*+ ", State " + testGenResult.getState().getStateId()*/ + ":");
            
            UnparserFilter t = new UnparserFilter(true, K.color, K.parens, context);
            Term concretePgm = KRunState.concretize(testGenResult.getGeneratedProgram(), context);
            concretePgm.accept(t);
            // sb.append("\nProgram:\n" + testGenResult.getGeneratedProgram()); // print abstract syntax form
            sb.append("\nProgram:\n" + t.getResult()); // print concrete syntax form
            sb.append("\nResult:");
            Map<String, Term> substitution = testGenResult.getSubstitution();

            if (isDefaultPattern) {
                UnparserFilter unparser = new UnparserFilter(true, K.color, K.parens, context);
                substitution.get("B:Bag").accept(unparser);
                sb.append("\n" + unparser.getResult());
            } else {
                boolean empty = true;

                for (String variable : substitution.keySet()) {
                    UnparserFilter unparser = new UnparserFilter(true, K.color, K.parens, context);
                    sb.append("\n" + variable + " -->");
                    substitution.get(variable).accept(unparser);
                    sb.append("\n" + unparser.getResult());
                    empty = false;
                }
                if (empty) {
                    sb.append("\nEmpty substitution");
                }
            }
            // Temporarily printing the constraints until problems with
            // translation of Term to Z3 are fixed
            sb.append("\nConstraint:\n");
            // temporary hack to eliminate constraints due to the rigidity of
            // term equality; TODO(YilongL): fix it
            String strCnstr = testGenResult.getConstraint().toString();
            strCnstr = strCnstr.replaceAll("'_=/=K_\\(.*?,, '\\{\\}\\(\\.KList\\)\\) =\\? Bool\\(#\"true\"\\) /\\\\ ", "");
            strCnstr = strCnstr.replaceAll(" /\\\\ " + "'_=/=K_\\(.*?,, '\\{\\}\\(\\.KList\\)\\) =\\? Bool\\(#\"true\"\\)", "");
            strCnstr = strCnstr.replaceAll("'_=/=K_\\(.*?,, '\\{\\}\\(\\.KList\\)\\) =\\? Bool\\(#\"true\"\\)", "");
            strCnstr = strCnstr.replace("/\\ ", "/\\\n");
            sb.append(strCnstr);
            
            n++;
        }
        
        if (n == 1) {
            sb.append("\nNo test generation results");
        }
        
        return sb.toString();
    }

    public DirectedGraph<KRunState, Transition> getGraph() {
        return graph;
    }

    public List<TestGenResult> getTestGenResults() {
        return testGenResults;
    }
    
    public boolean isDefaultPattern(){
        
        return this.isDefaultPattern;
    }
}

