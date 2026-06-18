import java.util.Scanner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main {
    public static int getNextJobNumber() {
        int num = 1;
        while (true) {
            boolean used = false;
            for (Job job : jobsList) {
                if (job.jobNumber == num) {
                    used = true;
                    break;
                }
            }

            if (!used) {
                return num;
            }
            num++;
        }
    }


    public static void reapJobs() throws Exception {
        int n = jobsList.size();
        List<Job> removeJobs = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            Job job = jobsList.get(i);

            try {
                job.process.exitValue();

                char marker = ' ';
                if (i == n - 1)
                    marker = '+';
                else if (i == n - 2)
                    marker = '-';

                String cmd = job.command;
                if (cmd.endsWith(" &"))
                    cmd = cmd.substring(0, cmd.length() - 2);

                System.out.printf(
                        "[%d]%c  Done                    %s%n",
                        job.jobNumber,
                        marker,
                        cmd
                );

                removeJobs.add(job);

            } catch (IllegalThreadStateException e) {
                // process still running
            }
        }
        jobsList.removeAll(removeJobs);
    }


    static class Job {
        int jobNumber;
        long pid;
        String command;
        Process process;

        Job(int jobNumber, long pid, String command, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    static List<Job> jobsList = new ArrayList<>();

    public static String[] parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && !inSingleQuote && !inDoubleQuote) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            } else if (c == '\\' && inDoubleQuote) {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);

                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                    }
                } else {
                    current.append('\\');
                }
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ' ' && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }


    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        String currentDirectory = System.getProperty("user.dir");
        while (true) {
            reapJobs();
            System.out.print("$ ");
            String command = scanner.nextLine();
            if (command.equals("exit")) {
                break;
            } else if (command.startsWith("echo ")) {
                String[] parts = parseCommand(command);
                String outputFile = null;
                String errorFile = null;
                boolean appendOutput = false;

                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equals(">") ||
                            parts[i].equals("1>") ||
                            parts[i].equals(">>") ||
                            parts[i].equals("1>>")) {

                        if (parts[i].equals(">>") || parts[i].equals("1>>")) {
                            appendOutput = true;
                        }
                        outputFile = parts[i + 1];
                        String[] temp = new String[parts.length - 2];
                        System.arraycopy(parts, 0, temp, 0, i);
                        if (i < temp.length) {
                            System.arraycopy(parts, i + 2, temp, i, temp.length - i);
                        }
                        parts = temp;
                        break;
                    } else if (parts[i].equals("2>") || parts[i].equals("2>>")) {

                        errorFile = parts[i + 1];

                        String[] temp = new String[parts.length - 2];
                        System.arraycopy(parts, 0, temp, 0, i);

                        if (i < temp.length) {
                            System.arraycopy(parts, i + 2, temp, i, temp.length - i);
                        }

                        parts = temp;
                        break;
                    }
                }
                StringBuilder out = new StringBuilder();

                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) out.append(" ");
                    out.append(parts[i]);
                }
                if (errorFile != null) {
                    new File(errorFile).createNewFile();
                }
                if (outputFile == null) {
                    System.out.println(out.toString());
                } else {

                    if (appendOutput) {
                        java.nio.file.Files.writeString(
                                java.nio.file.Paths.get(outputFile),
                                out.toString() + System.lineSeparator(),
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND
                        );
                    } else {
                        java.nio.file.Files.writeString(
                                java.nio.file.Paths.get(outputFile),
                                out.toString() + System.lineSeparator()
                        );
                    }
                }
            } else if (command.equals("pwd")) {
                System.out.println(currentDirectory);
            } else if (command.startsWith("cd ")) {
                String dir = command.substring(3);
                if (dir.equals("~")) {
                    currentDirectory = System.getenv("HOME");
                } else {
                    File folder;
                    if (dir.startsWith("/")) {
                        folder = new File(dir);
                    } else {
                        folder = new File(currentDirectory, dir);
                    }
                    if (folder.exists() && folder.isDirectory()) {
                        currentDirectory = folder.getCanonicalPath();
                    } else {
                        System.out.println("cd: " + dir + ": No such file or directory");
                    }
                }
            } else if (command.equals("jobs")) {

                int n = jobsList.size();
                List<Job> removeJobs = new ArrayList<>();

                for (int i = 0; i < n; i++) {

                    Job job = jobsList.get(i);

                    char marker = ' ';
                    if (i == n - 1)
                        marker = '+';
                    else if (i == n - 2)
                        marker = '-';

                    if (job.process.waitFor(0, TimeUnit.SECONDS)) {

                        String cmd = job.command;
                        if (cmd.endsWith(" &"))
                            cmd = cmd.substring(0, cmd.length() - 2);

                        System.out.printf(
                                "[%d]%c  Done                    %s%n",
                                job.jobNumber,
                                marker,
                                cmd
                        );

                        removeJobs.add(job);

                    } else {

                        System.out.printf(
                                "[%d]%c  Running                 %s%n",
                                job.jobNumber,
                                marker,
                                job.command
                        );
                    }
                }

                jobsList.removeAll(removeJobs);
            } else if (command.startsWith("type ")) {
                String cmd = command.substring(5);
                if (cmd.equals("echo") ||
                        cmd.equals("exit") ||
                        cmd.equals("type") ||
                        cmd.equals("pwd") ||
                        cmd.equals("cd") ||
                        cmd.equals("jobs")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String path = System.getenv("PATH");
                    String[] directories = path.split(File.pathSeparator);
                    boolean found = false;
                    for (String dir : directories) {
                        File file = new File(dir, cmd);
                        if (file.exists() && file.canExecute()) {
                            System.out.println(cmd + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        System.out.println(cmd + ": not found");
                    }
                }
            } else {
                // Handle two-command pipelines
                if (command.contains("|")) {

                    String[] cmds = command.split("\\|", 2);

                    ProcessBuilder pb1 = new ProcessBuilder(parseCommand(cmds[0].trim()));
                    pb1.directory(new File(currentDirectory));
                    pb1.redirectError(ProcessBuilder.Redirect.INHERIT);

                    ProcessBuilder pb2 = new ProcessBuilder(parseCommand(cmds[1].trim()));
                    pb2.directory(new File(currentDirectory));
                    pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
                    pb2.redirectOutput(ProcessBuilder.Redirect.PIPE);

                    List<Process> pipeline =
                            ProcessBuilder.startPipeline(List.of(pb1, pb2));

                    Process last = pipeline.get(1);

                    last.getInputStream().transferTo(System.out);

                    last.waitFor();

                    continue;
                }
                String[] parts = parseCommand(command);
                String outputFile = null;
                String errorFile = null;
                boolean appendOutput = false;
                boolean appendError = false;
                boolean background = false;
                if (parts.length > 0 && parts[parts.length - 1].equals("&")) {
                    background = true;
                    String[] temp = new String[parts.length - 1];
                    System.arraycopy(parts, 0, temp, 0, parts.length - 1);
                    parts = temp;
                }
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equals(">") ||
                            parts[i].equals("1>") ||
                            parts[i].equals(">>") ||
                            parts[i].equals("1>>")) {
                        if (parts[i].equals(">>") || parts[i].equals("1>>")) {
                            appendOutput = true;
                        }
                        outputFile = parts[i + 1];
                        String[] temp = new String[parts.length - 2];
                        System.arraycopy(parts, 0, temp, 0, i);
                        if (i < temp.length) {
                            System.arraycopy(parts, i + 2, temp, i, temp.length - i);
                        }
                        parts = temp;
                        break;
                    } else if (parts[i].equals("2>") || parts[i].equals("2>>")) {
                        if (parts[i].equals("2>>")) {
                            appendError = true;
                        }
                        errorFile = parts[i + 1];
                        String[] temp = new String[parts.length - 2];
                        System.arraycopy(parts, 0, temp, 0, i);
                        if (i < temp.length) {
                            System.arraycopy(parts, i + 2, temp, i, temp.length - i);
                        }
                        parts = temp;
                        break;
                    }
                }
                String program = parts[0];
                String path = System.getenv("PATH");
                String[] directories = path.split(File.pathSeparator);
                boolean found = false;
                for (String dir : directories) {
                    File file = new File(dir, program);
                    if (file.exists() && file.canExecute()) {
                        ProcessBuilder pb = new ProcessBuilder(parts);
                        pb.directory(new File(currentDirectory));
                        if (outputFile != null) {
                            if (appendOutput) {
                                pb.redirectOutput(
                                        ProcessBuilder.Redirect.appendTo(new File(outputFile))
                                );
                            } else {
                                pb.redirectOutput(new File(outputFile));
                            }
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        } else if (errorFile != null) {
                            if (appendError) {
                                pb.redirectError(
                                        ProcessBuilder.Redirect.appendTo(new File(errorFile))
                                );
                            } else {
                                pb.redirectError(new File(errorFile));
                            }
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        } else {
                            pb.inheritIO();
                        }
                        Process process = pb.start();
                        if (background) {
                            int jobNumber = getNextJobNumber();
                            jobsList.add(
                                    new Job(
                                            jobNumber,
                                            process.pid(),
                                            command,
                                            process
                                    )
                            );
                            System.out.println("[" + jobNumber + "] " + process.pid());
                        } else {
                            process.waitFor();
                        }
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    System.out.println(program + ": command not found");
                }
            }
        }
    }
}