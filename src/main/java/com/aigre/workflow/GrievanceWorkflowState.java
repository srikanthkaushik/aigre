package com.aigre.workflow;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.Optional;

/**
 * State is deliberately flattened to primitive-typed values (String/Double/Boolean), not the
 * {@code ClassificationResult} record itself -- LangGraph4j's checkpoint cloning goes through
 * {@code ObjectStreamStateSerializer} (Java serialization), and keeping the state a plain data
 * bag avoids making domain records implement Serializable just for this graph-internal artifact.
 *
 * predictedDepartment/predictedCategory/predictedPriority are the LLM's raw classify output and
 * never change after that node runs -- they back the grievances.department_predicted column.
 * finalDepartment/finalCategory/finalPriority start as copies of the predicted values and are the
 * only fields the human_review node overwrites -- they back department_confirmed (only set when
 * humanReviewed is true) and the category/priority columns.
 */
public class GrievanceWorkflowState extends AgentState {

    public GrievanceWorkflowState(Map<String, Object> initData) {
        super(initData);
    }

    public String grievanceId() {
        return this.<String>value("grievanceId").orElseThrow();
    }

    public String rawText() {
        return this.<String>value("rawText").orElseThrow();
    }

    public Optional<String> predictedDepartment() {
        return value("predictedDepartment");
    }

    public Optional<String> finalDepartment() {
        return value("finalDepartment");
    }

    public Optional<String> finalCategory() {
        return value("finalCategory");
    }

    public Optional<String> finalPriority() {
        return value("finalPriority");
    }

    public double confidence() {
        return this.<Double>value("confidence").orElse(-1.0);
    }

    public Optional<String> sentimentLabel() {
        return value("sentimentLabel");
    }

    public double sentimentScore() {
        return this.<Double>value("sentimentScore").orElse(0.0);
    }

    public boolean actionable() {
        return this.<Boolean>value("actionable").orElse(false);
    }

    public Optional<String> reasoning() {
        return value("reasoning");
    }

    public Optional<String> route() {
        return value("route");
    }

    public Optional<String> reviewedDepartment() {
        return value("reviewedDepartment");
    }

    public Optional<String> reviewedCategory() {
        return value("reviewedCategory");
    }

    public Optional<String> reviewedPriority() {
        return value("reviewedPriority");
    }

    public Optional<String> reviewNote() {
        return value("reviewNote");
    }

    public Optional<String> reviewedBy() {
        return value("reviewedBy");
    }

    public boolean humanReviewed() {
        return this.<Boolean>value("humanReviewed").orElse(false);
    }
}
