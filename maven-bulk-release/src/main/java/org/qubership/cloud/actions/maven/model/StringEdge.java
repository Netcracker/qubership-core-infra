package org.qubership.cloud.actions.maven.model;

import org.jgrapht.graph.DefaultEdge;

public class StringEdge extends DefaultEdge {
    @Override
    public String getSource() {
        return (String) super.getSource();
    }

    @Override
    public String getTarget() {
        return (String) super.getTarget();
    }

}
