package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.stream.Stream;

public enum EntityClassById implements IDatatypeFor<EntityType> {
  INSTANCE;

  @Override
  public EntityType get(IDatatypeContext ctx) throws CommandException {
    Identifier id = Identifier.parse(ctx.getConsumer().getString());
    EntityType entity;
    if ((entity = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null)) == null) {
      throw new IllegalArgumentException("no entity found by that id");
    }
    return entity;
  }

  @Override
  public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
    return new TabCompleteHelper().append(BuiltInRegistries.ENTITY_TYPE.stream().map(Object::toString)).filterPrefixNamespaced(ctx.getConsumer().getString()).sortAlphabetically().stream();
  }
}
