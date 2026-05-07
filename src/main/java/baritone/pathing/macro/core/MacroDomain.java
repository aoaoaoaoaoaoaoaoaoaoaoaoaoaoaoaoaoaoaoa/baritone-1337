package baritone.pathing.macro.core;

public interface MacroDomain {
  String id();

  void expand(MacroExpansionContext context, MacroLabel label, MacroOptionSink out);
}
