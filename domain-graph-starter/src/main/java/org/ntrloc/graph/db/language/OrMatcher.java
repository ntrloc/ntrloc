package org.ntrloc.graph.db.language;

import java.util.List;

public class OrMatcher extends Matcher {

    private List<Matcher> clauses;

    public List<Matcher> getClauses() {
        return clauses;
    }

    public void setClauses(List<Matcher> clauses) {
        this.clauses = clauses;
    }

}
