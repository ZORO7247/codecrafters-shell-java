import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class Main {
    private static final String HOME = "~";
    private static final String PATH = "PATH";
    private static Path pwd = Paths.get(System.getProperty("user.dir"));

    public static void main(String[] args) throws Exception {

    java.util.Scanner scanner = new java.util.Scanner(System.in);

    while (true) {

        System.out.print("$ ");

        if (!scanner.hasNextLine()) {
            break;
        }

        String line = scanner.nextLine();

        if (line == null || line.isEmpty()) {
            continue;
        }

        if (line.contains("|")) {
            runPipeline(line);
        } else {
            Command command = parse(line);
            run(command);
        }
    }
}

    private static void runPipeline(String line) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", line);
        pb.inheritIO();
        Process p = pb.start();
        p.waitFor();
    }

    enum CommandName {
        exit,
        echo,
        type,
        pwd,
        cd;

        static CommandName of(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    static class Command {
        final String command;
        final String[] args;
        final String[] commandWithArgs;
        final RedirectType redirectType;
        final String redirectTo;

        Command(
                String command,
                String[] args,
                String[] commandWithArgs,
                RedirectType redirectType,
                String redirectTo) {
            this.command = command;
            this.args = args;
            this.commandWithArgs = commandWithArgs;
            this.redirectType = redirectType;
            this.redirectTo = redirectTo;
        }
    }

    static class Redirect {
        final RedirectType redirectType;
        final int redirectAt;

        Redirect(RedirectType redirectType, int redirectAt) {
            this.redirectType = redirectType;
            this.redirectAt = redirectAt;
        }
    }

    private enum RedirectType {
        stdout,
        stderr,
        stdout_append,
        stderr_append
    }

    private enum QuteMode {
        singleQuote,
        doubleQuote
    }

    private static Command parse(String command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }

        List<String> split = splitCommand(command);
        if (split.isEmpty()) {
            throw new IllegalArgumentException("command cannot be empty");
        }

        String[] splitArray = split.toArray(new String[0]);

        if (splitArray.length == 1) {
            return new Command(split.get(0), new String[0], splitArray, null, "");
        }

        Redirect redirect = getRedirect(splitArray);
        int redirectAt = redirect.redirectAt;
        String[] args = Arrays.copyOfRange(splitArray, 1, redirectAt);
        String[] commandWithArgs = Arrays.copyOf(splitArray, redirectAt);
        String redirectTo = redirect.redirectType != null ? splitArray[redirectAt + 1] : "";

        return new Command(split.get(0), args, commandWithArgs, redirect.redirectType, redirectTo);
    }

    private static Redirect getRedirect(String[] split) {
        int redirectAt = split.length;
        RedirectType type = null;
        for (int i = 0; i < split.length; i++) {
            String s = split[i];
            if (s.equals(">") || s.equals("1>")) {
                redirectAt = i;
                type = RedirectType.stdout;
                break;
            }
            if (s.equals("2>")) {
                redirectAt = i;
                type = RedirectType.stderr;
                break;
            }
            if (s.equals(">>") || s.equals("1>>")) {
                redirectAt = i;
                type = RedirectType.stdout_append;
                break;
            }
            if (s.equals("2>>")) {
                redirectAt = i;
                type = RedirectType.stderr_append;
                break;
            }
        }
        return new Redirect(type, redirectAt);
    }

    private static List<String> splitCommand(String command) {
        List<String> result = new ArrayList<String>();
        StringBuilder temp = new StringBuilder();
        QuteMode quteMode = null;
        boolean escape = false;

        for (char ch : command.toCharArray()) {
            if (quteMode == QuteMode.singleQuote) {
                if (ch == '\'') {
                    quteMode = null;
                } else {
                    temp.append(ch);
                }
            } else if (quteMode == QuteMode.doubleQuote) {
                if (escape) {
                    if (ch != '"' && ch != '\\' && ch != '$' && ch != '`') {
                        temp.append('\\');
                    }
                    temp.append(ch);
                    escape = false;
                } else {
                    if (ch == '"') {
                        quteMode = null;
                    } else if (ch == '\\') {
                        escape = true;
                    } else {
                        temp.append(ch);
                    }
                }
            } else {
                if (escape) {
                    temp.append(ch);
                    escape = false;
                } else {
                    if (ch == '\'') {
                        quteMode = QuteMode.singleQuote;
                    } else if (ch == '"') {
                        quteMode = QuteMode.doubleQuote;
                    } else if (ch == ' ') {
                        addTemp(result, temp);
                    } else if (ch == '\\') {
                        escape = true;
                    } else {
                        temp.append(ch);
                    }
                }
            }
        }

        if (quteMode != null) {
            throw new IllegalArgumentException("Unclosed quote.");
        }

        addTemp(result, temp);
        return result;
    }

    private static void addTemp(List<String> result, StringBuilder temp) {
        if (temp.length() > 0) {
            result.add(temp.toString());
            temp.setLength(0);
        }
    }

    private static void run(Command command) throws IOException, InterruptedException {
        CommandName commandName = CommandName.of(command.command);

        if (Objects.isNull(commandName)) {
            runNotBuiltin(command);
            return;
        }

        switch (commandName) {
            case exit:
                int status = 0;
                if (command.args.length != 0) {
                    status = Integer.parseInt(command.args[0]);
                }
                System.exit(status);
                break;
            case echo:
                runEcho(command);
                break;
            case type:
                runType(command);
                break;
            case pwd:
                System.out.println(pwd);
                break;
            case cd:
                runCd(command);
                break;
        }
    }

    private static void runEcho(Command command) throws IOException {
        String message = String.join(" ", command.args);
        if (command.redirectType != null) {
            Path path = Paths.get(command.redirectTo);
            switch (command.redirectType) {
                case stdout:
                    byte[] bytes = String.format("%s%n", message).getBytes();
                    Files.write(
                            path,
                            bytes,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    break;
                case stderr:
                    Files.write(
                            path,
                            new byte[0],
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    System.out.println(message);
                    break;
                case stdout_append:
                    byte[] bytes2 = String.format("%s%n", message).getBytes();
                    Files.write(path, bytes2, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    break;
                case stderr_append:
                    Files.write(
                            path,
                            new byte[0],
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    System.out.println(message);
                    break;
            }
        } else {
            System.out.println(message);
        }
    }

    private static void runCd(Command command) {
        if (command.args.length == 0) {
            return;
        }
        String targetPath = command.args[0];
        String separator = System.getProperty("file.separator");
        if (targetPath.equals(HOME) || targetPath.startsWith(HOME + separator)) {
            String homeDir = System.getenv("HOME");
            if (homeDir != null) {
                targetPath = targetPath.replaceFirst(HOME, homeDir);
            }
        }

        Path newPath = pwd.resolve(targetPath).normalize();
        if (!Files.isDirectory(newPath)) {
            String error = String.format("cd: %s: No such file or directory", newPath);
            System.out.println(error);
        } else {
            pwd = newPath;
        }
    }

    private static void runNotBuiltin(Command command) throws IOException, InterruptedException {
        String executable = findExecutable(command.command);
        if (executable != null) {
            ProcessBuilder processBuilder = new ProcessBuilder(command.commandWithArgs);
            RedirectType redirectType = command.redirectType;
            if (redirectType != null) {
                File file = Paths.get(command.redirectTo).toFile();
                switch (redirectType) {
                    case stdout:
                        processBuilder.redirectOutput(file);
                        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                        break;
                    case stderr:
                        processBuilder.redirectError(file);
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        break;
                    case stdout_append:
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
                        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                        break;
                    case stderr_append:
                        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(file));
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        break;
                }
            } else {
                processBuilder.inheritIO();
            }
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {}
        } else {
            String error = String.format("%s: command not found", command.command);
            System.out.println(error);
        }
    }

    private static void runType(Command command) {
        if (command.args.length == 0) {
            System.out.println("type command requires an argument");
            return;
        }
        String arg0 = command.args[0];
        CommandName toType = CommandName.of(arg0);
        if (toType == null) {
            String executable = findExecutable(arg0);
            if (executable != null) {
                String message = String.format("%s is %s", arg0, executable);
                System.out.println(message);
            } else {
                String error = String.format("%s: not found", arg0);
                System.out.println(error);
            }
        } else {
            String message = String.format("%s is a shell builtin", toType);
            System.out.println(message);
        }
    }

    private static String findExecutable(String commandName) {
        String pathEnv = System.getenv(PATH);
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        String[] directories = pathEnv.split(System.getProperty("path.separator"));

        for (String dir : directories) {
            if (dir == null || dir.isEmpty()) {
                continue;
            }
            Path filePath = Paths.get(dir, commandName);
            if (Files.isExecutable(filePath) && Files.isRegularFile(filePath)) {
                return filePath.toAbsolutePath().toString();
            }
        }

        return null;
    }
}