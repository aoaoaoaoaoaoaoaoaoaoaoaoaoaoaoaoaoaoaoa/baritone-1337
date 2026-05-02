package baritone.api.command.exception;

import baritone.api.command.ICommand;
import baritone.api.command.argument.ICommandArgument;

import java.util.List;

import static baritone.api.utils.Helper.HELPER;

public class CommandUnhandledException extends RuntimeException implements ICommandException {

    public CommandUnhandledException(String message) {
        super(message);
    }

    public CommandUnhandledException(Throwable cause) {
        super(cause);
    }

    @Override
    public void handle(ICommand command, List<ICommandArgument> args) {
        HELPER.logUnhandledException(this);
    }
}
