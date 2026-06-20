import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    static class Job {
        int number;
        long pid;
        String command;
        String status;
        Process process;

        Job(int number, long pid, String command, String status, Process process) {
            this.number = number;
            this.pid = pid;
            this.command = command;
            this.status = status;
            this.process = process;
        }
    }

    static Map<String, String> completions = new HashMap<>();
    static List<Job> jobsList = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        File currentDirectory = new File(System.getProperty("user.dir")).getCanonicalFile();

        setTerminalRawMode();
        Runtime.getRuntime().addShutdownHook(new Thread(Main::resetTerminalMode));

        while (true) {
            reapJobs();

            System.out.print("$ ");
            String input = readCommand();
            if (input == null) break;
            String originalCommand = input.trim();

            if (originalCommand.isEmpty()) continue;

            boolean isBackground = false;
            String commandToParse = originalCommand;
            if (commandToParse.endsWith("&")) {
                isBackground = true;
                commandToParse = commandToParse.substring(0, commandToParse.length() - 1).trim();
            }

            String stdoutFile = null;
            boolean stdoutAppend = false;
            String stderrFile = null;
            boolean stderrAppend = false;

            if (!commandToParse.contains("|")) {
                if (commandToParse.contains("2>>")) {
                    String[] redir = commandToParse.split("2>>", 2);
                    commandToParse = redir[0].trim();
                    stderrFile = redir[1].trim();
                    stderrAppend = true;
                } else if (commandToParse.contains("1>>")) {
                    String[] redir = commandToParse.split("1>>", 2);
                    commandToParse = redir[0].trim();
                    stdoutFile = redir[1].trim();
                    stdoutAppend = true;
                } else if (commandToParse.contains(">>")) {
                    String[] redir = commandToParse.split(">>", 2);
                    commandToParse = redir[0].trim();
                    stdoutFile = redir[1].trim();
                    stdoutAppend = true;
                } else if (commandToParse.contains("2>")) {
                    String[] redir = commandToParse.split("2>", 2);
                    commandToParse = redir[0].trim();
                    stderrFile = redir[1].trim();
                } else if (commandToParse.contains("1>")) {
                    String[] redir = commandToParse.split("1>", 2);
                    commandToParse = redir[0].trim();
                    stdoutFile = redir[1].trim();
                } else if (commandToParse.contains(">")) {
                    String[] redir = commandToParse.split(">", 2);
                    commandToParse = redir[0].trim();
                    stdoutFile = redir[1].trim();
                }
            }

            if (commandToParse.contains("|")) {
                String[] pipeCmds = commandToParse.split("\\|");
                boolean containsBuiltin = false;

                for (String cmdPart : pipeCmds) {
                    String[] parsedParts = tokenize(cmdPart.trim());
                    if (parsedParts.length > 0 && isBuiltin(parsedParts[0])) {
                        containsBuiltin = true;
                        break;
                    }
                }

                if (containsBuiltin) {
                    String[] lastParts = tokenize(pipeCmds[pipeCmds.length - 1].trim());
                    if (lastParts.length > 0 && isBuiltin(lastParts[0])) {
                        System.out.print(runBuiltinForPipeline(lastParts, currentDirectory));
                        continue;
                    }
                }

                List<ProcessBuilder> builders = new ArrayList<>();
                for (String cmdPart : pipeCmds) {
                    String[] pipeParts = tokenize(cmdPart.trim());
                    ProcessBuilder pb = new ProcessBuilder(pipeParts);
                    pb.directory(currentDirectory);
                    builders.add(pb);
                }

                try {
                    List<Process> processes = ProcessBuilder.startPipeline(builders);
                    Process lastProcess = processes.get(processes.size() - 1);
                    lastProcess.getInputStream().transferTo(System.out);
                    lastProcess.waitFor();
                } catch (Exception e) {
                    System.out.println("Pipeline execution error: " + e.getMessage());
                }
                continue;
            }

            String[] parts = tokenize(commandToParse);
            if (parts.length == 0) continue;
            String command = parts[0];

            if (isBuiltin(command)) {
                if (command.equals("exit")) break;

                PrintStream redirectedOut = null;
                PrintStream redirectedErr = null;
                PrintStream out = System.out;
                PrintStream err = System.err;

                try {
                    if (stdoutFile != null) {
                        File outputFile = resolveFile(stdoutFile, currentDirectory);
                        redirectedOut = new PrintStream(new java.io.FileOutputStream(outputFile, stdoutAppend));
                        out = redirectedOut;
                    }
                    if (stderrFile != null) {
                        File errorFile = resolveFile(stderrFile, currentDirectory);
                        redirectedErr = new PrintStream(new java.io.FileOutputStream(errorFile, stderrAppend));
                        err = redirectedErr;
                    }

                    if (command.equals("pwd")) {
                        out.println(currentDirectory.getAbsolutePath());
                    } else if (command.equals("cd")) {
                        if (parts.length < 2) continue;
                        String path = parts[1];
                        if (path.equals("~") || path.startsWith("~/")) {
                            String home = System.getenv("HOME");
                            if (home == null) {
                                err.println("cd: " + path + ": No such file or directory");
                                continue;
                            }
                            path = path.equals("~") ? home : home + path.substring(1);
                        }
                        File target = new File(path);
                        if (!target.isAbsolute()) target = new File(currentDirectory, path);

                        if (target.exists() && target.isDirectory()) {
                            currentDirectory = target.getCanonicalFile();
                        } else {
                            err.println("cd: " + parts[1] + ": No such file or directory");
                        }
                    } else if (command.equals("echo")) {
                        for (int i = 1; i < parts.length; i++) {
                            if (i > 1) out.print(" ");
                            out.print(parts[i]);
                        }
                        out.println();
                    } else if (command.equals("type")) {
                        if (parts.length < 2) {
                            out.println("type: missing operand");
                            continue;
                        }
                        String arg = parts[1];
                        if (isBuiltin(arg)) {
                            out.println(arg + " is a shell builtin");
                        } else {
                            File file = findInPath(arg);
                            if (file != null) out.println(arg + " is " + file.getAbsolutePath());
                            else out.println(arg + ": not found");
                        }
                    } else if (command.equals("jobs")) {
                        displayJobsList();
                    } else if (command.equals("complete")) {
                        if (parts.length >= 3) {
                            String flag = parts[1];
                            if (flag.equals("-C") && parts.length >= 4) {
                                String script = parts[2];
                                String cmdToComplete = parts[3];
                                completions.put(cmdToComplete, script);
                            } else if (flag.equals("-r")) {
                                String cmdToComplete = parts[2];
                                completions.remove(cmdToComplete);
                            } else if (flag.equals("-p")) {
                                String cmdToComplete = parts[2];
                                if (completions.containsKey(cmdToComplete)) {
                                    out.println("complete -C " + completions.get(cmdToComplete) + " " + cmdToComplete);
                                } else {
                                    out.println("complete: " + cmdToComplete + ": no completion specification");
                                }
                            }
                        }
                    }
                } finally {
                    if (redirectedOut != null) redirectedOut.close();
                    if (redirectedErr != null) redirectedErr.close();
                }
                continue;
            }

            File exeFile = findInPath(command);
            if (exeFile != null) {
                ProcessBuilder pb = new ProcessBuilder(parts);
                pb.directory(currentDirectory);

                if (stdoutFile != null) {
                    File outF = resolveFile(stdoutFile, currentDirectory);
                    pb.redirectOutput(stdoutAppend ? ProcessBuilder.Redirect.appendTo(outF) : ProcessBuilder.Redirect.to(outF));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                if (stderrFile != null) {
                    File errF = resolveFile(stderrFile, currentDirectory);
                    pb.redirectError(stderrAppend ? ProcessBuilder.Redirect.appendTo(errF) : ProcessBuilder.Redirect.to(errF));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process process = pb.start();

                if (isBackground) {
                    int jobNum = getNextJobNumber();
                    long pid = process.pid();
                    System.out.println("[" + jobNum + "] " + pid);
                    jobsList.add(new Job(jobNum, pid, commandToParse, "Running", process));
                } else {
                    process.waitFor();
                }
            } else {
                System.out.println(command + ": command not found");
            }
        }
    }

    private static void setTerminalRawMode() {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "stty -icanon min 1 -echo");
            pb.inheritIO();
            pb.start().waitFor();
        } catch (Exception e) {}
    }

    private static void resetTerminalMode() {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "stty icanon echo");
            pb.inheritIO();
            pb.start().waitFor();
        } catch (Exception e) {}
    }

    private static String readCommand() throws Exception {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = System.in.read();
            if (b == -1) {
                if (sb.length() == 0) return null;
                return sb.toString();
            }
            char c = (char) b;
            if (c == '\n') {
                System.out.println();
                return sb.toString();
            } else if (c == 127 || c == '\b') {
                if (sb.length() > 0) {
                    sb.deleteCharAt(sb.length() - 1);
                    System.out.print("\b \b");
                }
            } else if (c == '\t') {
                String currentBuffer = sb.toString();
                String baseCmd = "";
                int firstSpace = currentBuffer.indexOf(' ');
                if (firstSpace == -1) {
                    baseCmd = currentBuffer;
                } else {
                    baseCmd = currentBuffer.substring(0, firstSpace);
                }

                if (!baseCmd.isEmpty() && completions.containsKey(baseCmd)) {
                    String script = completions.get(baseCmd);
                    int lastSpace = currentBuffer.lastIndexOf(' ');
                    String wordBeingCompleted = lastSpace == -1 ? currentBuffer : currentBuffer.substring(lastSpace + 1);
                    String previousWord = baseCmd;
                    if (lastSpace != -1) {
                        String beforeLast = currentBuffer.substring(0, lastSpace).trim();
                        int secondLastSpace = beforeLast.lastIndexOf(' ');
                        previousWord = secondLastSpace == -1 ? beforeLast : beforeLast.substring(secondLastSpace + 1);
                        if (previousWord.isEmpty()) previousWord = baseCmd;
                    }

                    try {
                        ProcessBuilder pb = new ProcessBuilder(script, baseCmd, wordBeingCompleted, previousWord);
                        Map<String, String> env = pb.environment();
                        env.put("COMP_LINE", currentBuffer);
                        env.put("COMP_POINT", String.valueOf(currentBuffer.length()));

                        Process p = pb.start();
                        String output = new String(p.getInputStream().readAllBytes()).trim();
                        p.waitFor();

                        if (!output.isEmpty()) {
                            String[] completionsArray = output.split("\n");
                            String lcp = completionsArray[0];
                            for (int i = 1; i < completionsArray.length; i++) {
                                String comp = completionsArray[i];
                                int j = 0;
                                while (j < lcp.length() && j < comp.length() && lcp.charAt(j) == comp.charAt(j)) {
                                    j++;
                                }
                                lcp = lcp.substring(0, j);
                            }

                            if (lcp.startsWith(wordBeingCompleted) && lcp.length() >= wordBeingCompleted.length()) {
                                String diff = lcp.substring(wordBeingCompleted.length());
                                if (completionsArray.length == 1) {
                                    diff += " ";
                                }
                                if (!diff.isEmpty()) {
                                    sb.append(diff);
                                    System.out.print(diff);
                                } else {
                                    System.out.print("\007");
                                }
                            } else {
                                System.out.print("\007");
                            }
                        } else {
                            System.out.print("\007");
                        }
                    } catch (Exception e) {
                        System.out.print("\007");
                    }
                } else {
                    System.out.print("\007");
                }
            } else {
                sb.append(c);
                System.out.print(c);
            }
        }
    }

    private static boolean isBuiltin(String cmd) {
        return cmd.equals("echo") || cmd.equals("type") || cmd.equals("pwd")
                || cmd.equals("cd") || cmd.equals("jobs") || cmd.equals("exit") || cmd.equals("complete");
    }

    private static File resolveFile(String path, File currentDirectory) {
        File file = new File(path);
        if (!file.isAbsolute()) {
            file = new File(currentDirectory, path);
        }
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;
    }

    private static String[] tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && !inSingleQuote && !inDoubleQuote) {
                escaped = true;
                continue;
            }
            if (c == '\\' && inDoubleQuote) {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                        continue;
                    }
                }
                current.append(c);
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens.toArray(new String[0]);
    }

    private static File findInPath(String commandName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String path : pathEnv.split(File.pathSeparator)) {
            File file = new File(path, commandName);
            if (file.exists() && file.canExecute()) return file;
        }
        return null;
    }

    private static int getNextJobNumber() {
        int num = 1;
        while (true) {
            boolean used = false;
            for (Job job : jobsList) {
                if (job.number == num) { used = true; break; }
            }
            if (!used) return num;
            num++;
        }
    }

    private static void reapJobs() {
        for (int i = 0; i < jobsList.size(); i++) {
            Job job = jobsList.get(i);
            if (!job.process.isAlive() && job.status.equals("Running")) {
                job.status = "Done";
                char marker = (i == jobsList.size() - 1) ? '+' : (i == jobsList.size() - 2 ? '-' : ' ');
                System.out.printf("[%d]%c %-24s %s%n", job.number, marker, "Done", job.command);
            }
        }
        jobsList.removeIf(job -> job.status.equals("Done"));
    }

    private static void displayJobsList() {
        List<Job> removeJobs = new ArrayList<>();
        for (int i = 0; i < jobsList.size(); i++) {
            Job job = jobsList.get(i);
            char marker = (i == jobsList.size() - 1) ? '+' : (i == jobsList.size() - 2 ? '-' : ' ');
            if (job.process.isAlive()) {
                System.out.printf("[%d]%c %-24s %s &%n", job.number, marker, "Running", job.command);
            } else {
                System.out.printf("[%d]%c %-24s %s%n", job.number, marker, "Done", job.command);
                removeJobs.add(job);
            }
        }
        jobsList.removeAll(removeJobs);
    }

    private static String runBuiltinForPipeline(String[] parts, File currentDirectory) {
        String cmd = parts[0];
        if (cmd.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) sb.append(" ");
                sb.append(parts[i]);
            }
            return sb.append(System.lineSeparator()).toString();
        }
        if (cmd.equals("pwd")) return currentDirectory.getAbsolutePath() + System.lineSeparator();
        if (cmd.equals("type")) {
            if (parts.length < 2) {
                return "type: missing operand" + System.lineSeparator();
            }
            String arg = parts[1];
            if (isBuiltin(arg)) {
                return arg + " is a shell builtin" + System.lineSeparator();
            } else {
                File file = findInPath(arg);
                if (file != null) {
                    return arg + " is " + file.getAbsolutePath() + System.lineSeparator();
                } else {
                    return arg + ": not found" + System.lineSeparator();
                }
            }
        }
        return "";
    }
}