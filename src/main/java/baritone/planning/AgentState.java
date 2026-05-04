package baritone.planning;

public record AgentState(WorldCell anchor, PlannedLocomotion locomotion, ResourceLedger resources, CapabilitySet capabilities, SafetyEnvelope safety, KnowledgeMark knowledge) {
}
