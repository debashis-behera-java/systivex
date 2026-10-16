package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evidence sentences for impact paths (extracted from
 * {@link ImpactAnalysisService}).
 *
 * <p>Every sentence derives only from the stored steps: reverse steps
 * explain dependence ("may affect"), forward steps list downstream
 * dependencies as reachability, never confirmed breakage. Relationship types
 * with no documented domain meaning get a transparent
 * structural-reachability sentence — never invented semantics.
 */
final class ImpactExplanation {

    private ImpactExplanation() {}

    /**
     * Human-readable evidence sentence for one recorded path, derived only
     * from the stored steps.
     */
    static String explain(List<StepView> steps) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(explainStep(steps.get(i)));
        }
        return text.toString();
    }

    static String explainStep(StepView step) {
        String stored =
                step.storedSourceRef() + " -" + step.type().name() + "-> " + step.storedTargetRef();
        boolean reverse = step.direction() == ImpactDirection.REVERSE;
        return switch (step.type()) {
            case CALLS -> explainCalls(step, stored, reverse);
            case DEPENDS_ON -> explainDependsOn(step, stored, reverse);
            case CONTAINS -> explainContains(step, stored, reverse);
            case WRITES -> explainWrites(step, stored, reverse);
            case EXPOSES -> explainExposes(step, stored, reverse);
            default -> explainGeneric(step, stored);
        };
    }

    static String displayRef(SystemEntity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getExternalRef() != null ? entity.getExternalRef() : entity.getId().toString();
    }

    /** Stable sort key for evidence paths, over response DTO steps. */
    static String renderKey(List<ImpactResponse.ImpactStep> steps) {
        StringBuilder key = new StringBuilder();
        for (ImpactResponse.ImpactStep step : steps) {
            key.append(step.sourceExternalRef())
                    .append('|')
                    .append(step.type().name())
                    .append('|')
                    .append(step.direction().name())
                    .append('|')
                    .append(step.targetExternalRef())
                    .append(';');
        }
        return key.toString();
    }

    private static String explainCalls(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.traversedTargetDisplay()
                    + " calls "
                    + step.traversedSourceDisplay()
                    + " ("
                    + stored
                    + "): "
                    + step.traversedTargetDisplay()
                    + " depends on "
                    + step.traversedSourceDisplay()
                    + ", so a change in "
                    + step.traversedSourceDisplay()
                    + " may affect "
                    + step.traversedTargetDisplay()
                    + ".";
        }
        return step.traversedSourceDisplay()
                + " calls "
                + step.traversedTargetDisplay()
                + " ("
                + stored
                + "): "
                + step.traversedTargetDisplay()
                + " is a downstream dependency of "
                + step.traversedSourceDisplay()
                + ", not confirmed breakage.";
    }

    private static String explainDependsOn(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.traversedTargetDisplay()
                    + " depends on "
                    + step.traversedSourceDisplay()
                    + " ("
                    + stored
                    + "): a change in "
                    + step.traversedSourceDisplay()
                    + " may affect "
                    + step.traversedTargetDisplay()
                    + ".";
        }
        return step.traversedSourceDisplay()
                + " depends on "
                + step.traversedTargetDisplay()
                + " ("
                + stored
                + "): "
                + step.traversedTargetDisplay()
                + " is a direct dependency of "
                + step.traversedSourceDisplay()
                + ".";
    }

    private static String explainContains(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.storedSourceDisplay()
                    + " contains "
                    + step.storedTargetDisplay()
                    + " ("
                    + stored
                    + "): a change in "
                    + step.storedTargetDisplay()
                    + " is visible in the scope of "
                    + step.storedSourceDisplay()
                    + ".";
        }
        return step.storedSourceDisplay()
                + " contains "
                + step.storedTargetDisplay()
                + " ("
                + stored
                + "): a change in "
                + step.storedSourceDisplay()
                + " may reach "
                + step.storedTargetDisplay()
                + ".";
    }

    private static String explainWrites(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.traversedTargetDisplay()
                    + " writes "
                    + step.traversedSourceDisplay()
                    + " ("
                    + stored
                    + "): a change in "
                    + step.traversedSourceDisplay()
                    + " may affect "
                    + step.traversedTargetDisplay()
                    + ".";
        }
        return step.traversedSourceDisplay()
                + " writes "
                + step.traversedTargetDisplay()
                + " ("
                + stored
                + "): "
                + step.traversedTargetDisplay()
                + " is written by "
                + step.traversedSourceDisplay()
                + ".";
    }

    private static String explainExposes(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.storedSourceDisplay()
                    + " exposes "
                    + step.storedTargetDisplay()
                    + " ("
                    + stored
                    + "): a change in "
                    + step.storedTargetDisplay()
                    + " traces to its owning service "
                    + step.storedSourceDisplay()
                    + ".";
        }
        return step.storedSourceDisplay()
                + " exposes "
                + step.storedTargetDisplay()
                + " ("
                + stored
                + "): a change in "
                + step.storedSourceDisplay()
                + " may change "
                + step.storedTargetDisplay()
                + ".";
    }

    private static String explainGeneric(StepView step, String stored) {
        return step.traversedSourceDisplay()
                + " is linked to "
                + step.traversedTargetDisplay()
                + " via "
                + step.type().name()
                + " (stored "
                + stored
                + ", traversed "
                + step.direction().name()
                + "): no domain meaning is defined for this relationship type;"
                + " included as structural reachability only, not as confirmed impact.";
    }

    /**
     * One traversed step. {@code source/target} follow the traversal order
     * (where the walk came from / went to); {@code storedSource/storedTarget}
     * keep the persisted edge direction so explanations stay honest.
     */
    record StepView(
            UUID sourceId,
            String sourceRef,
            UUID targetId,
            String targetRef,
            UUID storedSourceId,
            String storedSourceRef,
            UUID storedTargetId,
            String storedTargetRef,
            SystemRelationshipType type,
            ImpactDirection direction) {

        static StepView of(
                SystemRelationship edge,
                ImpactDirection direction,
                Map<UUID, SystemEntity> index) {
            UUID storedSource = edge.getSource().getId();
            UUID storedTarget = edge.getTarget().getId();
            String storedSourceRef = displayRef(index.get(storedSource));
            String storedTargetRef = displayRef(index.get(storedTarget));
            boolean forward = direction == ImpactDirection.FORWARD;
            return new StepView(
                    forward ? storedSource : storedTarget,
                    forward ? storedSourceRef : storedTargetRef,
                    forward ? storedTarget : storedSource,
                    forward ? storedTargetRef : storedSourceRef,
                    storedSource,
                    storedSourceRef,
                    storedTarget,
                    storedTargetRef,
                    edge.getRelationshipType(),
                    direction);
        }

        String traversedSourceDisplay() {
            return sourceRef != null ? sourceRef : sourceId.toString();
        }

        String traversedTargetDisplay() {
            return targetRef != null ? targetRef : targetId.toString();
        }

        String storedSourceDisplay() {
            return storedSourceRef != null ? storedSourceRef : storedSourceId.toString();
        }

        String storedTargetDisplay() {
            return storedTargetRef != null ? storedTargetRef : storedTargetId.toString();
        }
    }
}
