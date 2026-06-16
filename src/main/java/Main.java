import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class Main {
    private static final String[] BUILTINS = {"echo", "exit", "type", "pwd", "cd", "jobs"};
    private static final int BACKSPACE = 127;

    private static String currentDirectory = System.getProperty("user.dir");
    private static final Map<Integer, Job> jobs = new HashMap<>();

    private static class ShellCommand {
        private final List<String> args = new ArrayList<>();
        private Redirect stdout;
        private Redirect stderr;
    }

    private static class ParsedLine {
        private final List<ShellCommand> commands = new ArrayList<>();
        private boolean background;
        private String originalCommand;
    }

    private static class Redirect {
        private final String file;
        private final boolean append;

        private Redirect(String file, boolean append) {
            this.file = file;
            this.append = append;
        }
    }

    private static class Job {
        private final int id;
        private final String commandLine;
        private final Process process;

        private Job(int id, String commandLine, Process process) {
            this.id = id;
            this.commandLine = commandLine;
            this.process = process;
        }
    }

    private static class CompletionContext {
        private final int tokenStart;
        private final String prefix;
        private final boolean commandPosition;

        private CompletionContext(int tokenStart, String prefix, boolean commandPosition) {
            this.tokenStart = tokenStart;
            this.prefix = prefix;
            this.commandPosition = commandPosition;
        }

        private String key() {
            return commandPosition + ":" + tokenStart + ":" + prefix;
        }
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean tokenStarted = false;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (inSingleQuote) {
                tokenStarted = true;
                if (ch == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(ch);
                }
                continue;
            }

            if (inDoubleQuote) {
                tokenStarted = true;
                if (ch == '"') {
                    inDoubleQuote = false;
                } else if (ch == '\\' && i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '$' || next == '`') {
                        current.append(next);
                        i++;
                    } else {
                        current.append(ch);
                    }
                } else {
                    current.append(ch);
                }
                continue;
            }

            if (Character.isWhitespace(ch)) {
                addToken(tokens, current, tokenStarted);
                tokenStarted = false;
            } else if (ch == '\'') {
                inSingleQuote = true;
                tokenStarted = true;
            } else if (ch == '"') {
                inDoubleQuote = true;
                tokenStarted = true;
            } else if (ch == '\\') {
                tokenStarted = true;
                if (i + 1 < input.length()) {
                    current.append(input.charAt(++i));
                }
            } else if (ch == '|') {
                addToken(tokens, current, tokenStarted);
                tokens.add("|");
                tokenStarted = false;
            } else if (ch == '&') {
                addToken(tokens, current, tokenStarted);
                tokens.add("&");
                tokenStarted = false;
            } else if (ch == '>') {
                if (current.toString().equals("1") || current.toString().equals("2")) {
                    String fd = current.toString();
                    current.setLength(0);
                    tokenStarted = false;
                    boolean append = i + 1 < input.length() && input.charAt(i + 1) == '>';
                    if (append) {
                        i++;
                    }
                    tokens.add(fd + (append ? ">>" : ">"));
                } else {
                    addToken(tokens, current, tokenStarted);
                    boolean append = i + 1 < input.length() && input.charAt(i + 1) == '>';
                    if (append) {
                        i++;
                    }
                    tokens.add(append ? ">>" : ">");
                    tokenStarted = false;
                }
            } else {
                tokenStarted = true;
                current.append(ch);
            }
        }

        addToken(tokens, current, tokenStarted);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder current, boolean tokenStarted) {
        if (tokenStarted) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static ParsedLine parseLine(String input) {
        List<String> tokens = tokenize(input);
        ParsedLine parsed = new ParsedLine();
        parsed.originalCommand = input.trim();

        ShellCommand current = new ShellCommand();
        parsed.commands.add(current);

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("|")) {
                if (!current.args.isEmpty()) {
                    current = new ShellCommand();
                    parsed.commands.add(current);
                }
            } else if (token.equals("&") && i == tokens.size() - 1) {
                parsed.background = true;
            } else if (isRedirect(token)) {
                if (i + 1 >= tokens.size()) {
                    break;
                }
                Redirect redirect = new Redirect(tokens.get(++i), token.endsWith(">>"));
                if (token.startsWith("2")) {
                    current.stderr = redirect;
                } else {
                    current.stdout = redirect;
                }
            } else {
                current.args.add(token);
            }
        }

        parsed.commands.removeIf(command -> command.args.isEmpty());
        return parsed;
    }

    private static boolean isRedirect(String token) {
        return token.equals(">") || token.equals("1>") || token.equals("2>")
                || token.equals(">>") || token.equals("1>>") || token.equals("2>>");
    }

    private static boolean isBuiltin(String command) {
        for (String builtin : BUILTINS) {
            if (builtin.equals(command)) {
                return true;
            }
        }
        return false;
    }

    private static void repl() throws Exception {
        InputStream in = System.in;
        String originalTerminalMode = enableCharacterInput();
        boolean echoInput = originalTerminalMode != null;

        try {
            while (true) {
                reapCompletedJobs();
                System.out.print("$ ");
                System.out.flush();

                String line = readLine(in, echoInput);
                if (line == null) {
                    break;
                }

                ParsedLine parsed = parseLine(line);
                if (parsed.commands.isEmpty()) {
                    continue;
                }

                if (parsed.commands.size() == 1) {
                    ShellCommand command = parsed.commands.get(0);
                    if (command.args.get(0).equals("exit")) {
                        break;
                    }
                    if (parsed.background) {
                        startBackgroundJob(command, parsed.originalCommand);
                    } else {
                        executeForeground(command);
                    }
                } else {
                    executePipeline(parsed.commands);
                }
            }
        } finally {
            restoreTerminalMode(originalTerminalMode);
        }
    }

    private static String readLine(InputStream in, boolean echoInput) throws IOException {
        StringBuilder line = new StringBuilder();
        String lastCompletionKey = null;

        while (true) {
            int next = in.read();
            if (next == -1) {
                return line.isEmpty() ? null : line.toString();
            }
            if (next == '\n' || next == '\r') {
                if (echoInput) {
                    System.out.println();
                    System.out.flush();
                }
                return line.toString();
            }
            if (next == '\t') {
                CompletionContext context = completionContext(line);
                completeLine(line, context, echoInput, context.key().equals(lastCompletionKey));
                lastCompletionKey = completionContext(line).key();
                continue;
            }
            if (next == BACKSPACE || next == '\b') {
                lastCompletionKey = null;
                if (!line.isEmpty()) {
                    line.deleteCharAt(line.length() - 1);
                    if (echoInput) {
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                }
                continue;
            }

            lastCompletionKey = null;
            line.append((char) next);
            if (echoInput) {
                System.out.print((char) next);
                System.out.flush();
            }
        }
    }

    private static void completeLine(
            StringBuilder line,
            CompletionContext context,
            boolean echoInput,
            boolean repeatedTab
    ) {
        List<String> matches = context.commandPosition
                ? commandCompletionCandidates(context.prefix)
                : fileCompletionCandidates(context.prefix);

        if (matches.size() == 1) {
            String completion = matches.get(0).substring(context.prefix.length()) + completionSuffix(matches.get(0));
            line.append(completion);
            if (echoInput) {
                System.out.print(completion);
                System.out.flush();
            }
            return;
        }

        if (matches.size() > 1) {
            String commonPrefix = commonPrefix(matches);
            if (commonPrefix.length() > context.prefix.length()) {
                String completion = commonPrefix.substring(context.prefix.length());
                line.append(completion);
                if (echoInput) {
                    System.out.print(completion);
                    System.out.flush();
                }
                return;
            }

            if (repeatedTab && echoInput) {
                System.out.println();
                System.out.println(String.join("  ", matches));
                System.out.print("$ " + line);
                System.out.flush();
                return;
            }
        }

        if (echoInput) {
            System.out.print("\u0007");
            System.out.flush();
        }
    }

    private static CompletionContext completionContext(StringBuilder line) {
        String value = line.toString();
        int tokenStart = value.length();
        while (tokenStart > 0 && !Character.isWhitespace(value.charAt(tokenStart - 1))) {
            tokenStart--;
        }
        String prefix = value.substring(tokenStart);
        boolean commandPosition = value.substring(0, tokenStart).isBlank();
        return new CompletionContext(tokenStart, prefix, commandPosition);
    }

    private static List<String> commandCompletionCandidates(String prefix) {
        TreeSet<String> candidates = new TreeSet<>();
        Arrays.stream(BUILTINS)
                .filter(command -> command.startsWith(prefix))
                .forEach(candidates::add);

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(":")) {
                File directory = new File(dir);
                File[] files = directory.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    String name = file.getName();
                    if (name.startsWith(prefix) && file.isFile() && file.canExecute()) {
                        candidates.add(name);
                    }
                }
            }
        }

        return new ArrayList<>(candidates);
    }

    private static List<String> fileCompletionCandidates(String prefix) {
        TreeSet<String> candidates = new TreeSet<>();
        String directoryPart = "";
        String filePrefix = prefix;
        int slashIndex = prefix.lastIndexOf('/');
        if (slashIndex >= 0) {
            directoryPart = prefix.substring(0, slashIndex + 1);
            filePrefix = prefix.substring(slashIndex + 1);
        }

        File directory = directoryPart.isEmpty()
                ? new File(currentDirectory)
                : resolvePath(directoryPart);
        File[] files = directory.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(filePrefix)) {
                candidates.add(directoryPart + name);
            }
        }
        return new ArrayList<>(candidates);
    }

    private static String completionSuffix(String value) {
        File file = resolvePath(value);
        return file.isDirectory() ? "/" : " ";
    }

    private static File resolvePath(String path) {
        if (path.equals("~")) {
            return new File(System.getenv("HOME"));
        }
        if (path.startsWith("~/")) {
            return new File(System.getenv("HOME"), path.substring(2));
        }
        if (path.startsWith("/")) {
            return new File(path);
        }
        return new File(currentDirectory, path);
    }

    private static String commonPrefix(List<String> values) {
        String prefix = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            String value = values.get(i);
            int length = 0;
            while (length < prefix.length()
                    && length < value.length()
                    && prefix.charAt(length) == value.charAt(length)) {
                length++;
            }
            prefix = prefix.substring(0, length);
        }
        return prefix;
    }

    private static String enableCharacterInput() {
        String originalMode = runStty("-g");
        if (originalMode == null) {
            return null;
        }
        if (runStty("-echo -icanon min 1 time 0") == null) {
            return null;
        }
        return originalMode;
    }

    private static void restoreTerminalMode(String originalMode) {
        if (originalMode != null && !originalMode.isBlank()) {
            runStty(originalMode);
        }
    }

    private static String runStty(String arguments) {
        try {
            Process process = new ProcessBuilder("sh", "-c", "stty " + arguments + " < /dev/tty")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return null;
            }
            return new String(output, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void executeForeground(ShellCommand command) throws Exception {
        if (isBuiltin(command.args.get(0))) {
            try (OutputStream out = outputFor(command.stdout, System.out);
                 OutputStream err = outputFor(command.stderr, System.err)) {
                executeBuiltin(command.args, out, err);
            }
        } else {
            int exitCode = runExternal(command, null, command.stdout, command.stderr, false);
            if (exitCode == -1) {
                writeError(command.args.get(0) + ": command not found\n", command.stderr);
            }
        }
    }

    private static void executePipeline(List<ShellCommand> commands) throws Exception {
        byte[] input = new byte[0];

        for (int i = 0; i < commands.size(); i++) {
            ShellCommand command = commands.get(i);
            boolean last = i == commands.size() - 1;

            if (isBuiltin(command.args.get(0))) {
                ByteArrayOutputStream pipeOut = new ByteArrayOutputStream();
                Redirect stdoutRedirect = last ? command.stdout : null;
                OutputStream out = last && stdoutRedirect == null ? System.out : pipeOut;
                if (stdoutRedirect != null) {
                    out = outputFor(stdoutRedirect, System.out);
                }

                try (OutputStream commandOut = closeOnlyFiles(out);
                     OutputStream err = outputFor(command.stderr, System.err)) {
                    executeBuiltin(command.args, commandOut, err);
                }

                if (!last) {
                    input = pipeOut.toByteArray();
                }
                continue;
            }

            Redirect stdoutRedirect = last ? command.stdout : null;
            int exitCode = runExternal(command, input, stdoutRedirect, command.stderr, !last);
            if (exitCode == -1) {
                writeError(command.args.get(0) + ": command not found\n", command.stderr);
            }
            if (!last) {
                input = lastExternalOutput;
                lastExternalOutput = new byte[0];
            }
        }
    }

    private static byte[] lastExternalOutput = new byte[0];

    private static int runExternal(
            ShellCommand command,
            byte[] input,
            Redirect stdout,
            Redirect stderr,
            boolean captureStdout
    ) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command.args);
        processBuilder.directory(new File(currentDirectory));

        if (stdout != null) {
            processBuilder.redirectOutput(redirectToFile(stdout));
        } else if (!captureStdout) {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        } else {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        }

        if (stderr != null) {
            processBuilder.redirectError(redirectToFile(stderr));
        } else {
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        try {
            Process process = processBuilder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                if (input != null) {
                    stdin.write(input);
                }
            }

            if (captureStdout) {
                lastExternalOutput = process.getInputStream().readAllBytes();
            }

            return process.waitFor();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void startBackgroundJob(ShellCommand command, String commandLine) throws IOException {
        if (isBuiltin(command.args.get(0))) {
            try {
                executeForeground(command);
            } catch (Exception e) {
                writeError(command.args.get(0) + ": command not found\n", command.stderr);
            }
            return;
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command.args);
        processBuilder.directory(new File(currentDirectory));
        if (command.stdout != null) {
            processBuilder.redirectOutput(redirectToFile(command.stdout));
        } else {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
        if (command.stderr != null) {
            processBuilder.redirectError(redirectToFile(command.stderr));
        } else {
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        try {
            Process process = processBuilder.start();
            int id = nextJobId();
            jobs.put(id, new Job(id, commandLine, process));
            System.out.println("[" + id + "] " + process.pid());
        } catch (IOException e) {
            writeError(command.args.get(0) + ": command not found\n", command.stderr);
        }
    }

    private static int nextJobId() {
        int id = 1;
        while (jobs.containsKey(id)) {
            id++;
        }
        return id;
    }

    private static void reapCompletedJobs() {
        List<Integer> completed = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (!job.process.isAlive()) {
                completed.add(job.id);
            }
        }
        for (Integer id : completed) {
            jobs.remove(id);
        }
    }

    private static void executeBuiltin(List<String> args, OutputStream out, OutputStream err) throws Exception {
        String command = args.get(0);

        switch (command) {
            case "echo" -> {
                StringBuilder output = new StringBuilder();
                for (int i = 1; i < args.size(); i++) {
                    if (i > 1) {
                        output.append(' ');
                    }
                    output.append(args.get(i));
                }
                writeTo(out, output + "\n");
            }
            case "pwd" -> writeTo(out, currentDirectory + "\n");
            case "cd" -> changeDirectory(args, err);
            case "type" -> typeCommand(args, out);
            case "jobs" -> listJobs(out);
            case "exit" -> {
            }
            default -> writeTo(err, command + ": command not found\n");
        }
    }

    private static void changeDirectory(List<String> args, OutputStream err) throws IOException {
        if (args.size() < 2) {
            return;
        }

        String path = args.get(1);
        File targetDir;
        if (path.equals("~")) {
            targetDir = new File(System.getenv("HOME"));
        } else if (path.startsWith("~/")) {
            targetDir = new File(System.getenv("HOME"), path.substring(2));
        } else if (path.startsWith("/")) {
            targetDir = new File(path);
        } else {
            targetDir = new File(currentDirectory, path);
        }

        if (targetDir.exists() && targetDir.isDirectory()) {
            currentDirectory = targetDir.getCanonicalPath();
        } else {
            writeTo(err, "cd: " + path + ": No such file or directory\n");
        }
    }

    private static void typeCommand(List<String> args, OutputStream out) throws IOException {
        if (args.size() < 2) {
            return;
        }

        String command = args.get(1);
        if (isBuiltin(command)) {
            writeTo(out, command + " is a shell builtin\n");
            return;
        }

        String path = findExecutable(command);
        if (path == null) {
            writeTo(out, command + ": not found\n");
        } else {
            writeTo(out, command + " is " + path + "\n");
        }
    }

    private static void listJobs(OutputStream out) throws IOException {
        reapCompletedJobs();
        List<Job> sortedJobs = new ArrayList<>(jobs.values());
        sortedJobs.sort(Comparator.comparingInt(job -> job.id));
        for (Job job : sortedJobs) {
            writeTo(out, "[" + job.id + "] Running " + job.commandLine + "\n");
        }
    }

    private static String findExecutable(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }

        for (String dir : path.split(":")) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute() && file.isFile()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static OutputStream outputFor(Redirect redirect, OutputStream fallback) throws IOException {
        if (redirect == null) {
            return closeOnlyFiles(fallback);
        }
        return Files.newOutputStream(Path.of(redirect.file), optionsFor(redirect.append));
    }

    private static OpenOption[] optionsFor(boolean append) {
        if (append) {
            return new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND};
        }
        return new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
    }

    private static ProcessBuilder.Redirect redirectToFile(Redirect redirect) {
        File file = new File(redirect.file);
        return redirect.append
                ? ProcessBuilder.Redirect.appendTo(file)
                : ProcessBuilder.Redirect.to(file);
    }

    private static OutputStream closeOnlyFiles(OutputStream out) {
        if (out == System.out || out == System.err) {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    out.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void flush() throws IOException {
                    out.flush();
                }
            };
        }
        return out;
    }

    private static void writeError(String message, Redirect redirect) throws IOException {
        try (OutputStream err = outputFor(redirect, System.err)) {
            writeTo(err, message);
        }
    }

    private static void writeTo(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void main(String[] args) throws Exception {
        repl();
    }
}
