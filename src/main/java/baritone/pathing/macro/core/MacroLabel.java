package baritone.pathing.macro.core;

public record MacroLabel(long node, MacroAgentState state, double gScore, MacroCostVector vector, int id) {
}
