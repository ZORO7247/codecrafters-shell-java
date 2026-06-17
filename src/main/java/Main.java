import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (inDoubleQuote && ch == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                        current.append(next);
                        i++;
                    }
                } else {
                    current.append('\\');
                }
            }
            else if (!inSingleQuote && !inDoubleQuote && ch == '\\') {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            }
            else if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            }
            else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            else if (Character.isWhitespace(ch) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            }
            else {
                current.append(ch);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String currentDirectory = System.getProperty("user.dir");

        while (true) {
            System.out.print("$ ");
            String commandLine = sc.nextLine();
            List<String> tokens = parseCommand(commandLine);

            if (tokens.isEmpty()) {
                continue;
            }

            String outputFile = null;
            String errorFile = null;

            // Scan tokens backwards or forwards to find redirection operators
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (token.equals(" >") || token.equals(">") || token.equals("1>")) {
                    if (i + 1 < tokens.size()) {
                        outputFile = tokens.get(i + 1);
                    }
                    tokens = new ArrayList<>(tokens.subList(0, i));
                    break;
                } else if (token.equals("2>")) {
                    if (i + 1 < tokens.size()) {
                        errorFile = tokens.get(i + 1);
                    }
                    tokens = new ArrayList<>(tokens.subList(0, i));
                    break;
                }
            }

            if (tokens.isEmpty()) {
                continue;
            }

            String command = tokens.get(0);

            // exit
            if (command.equals("exit")) {
                break;
            }

            // echo
            else if (command.equals("echo")) {
                StringBuilder output = new StringBuilder();
                for (int i = 1; i < tokens.size(); i++) {
                    if (i > 1) {
                        output.append(" ");
                    }
                    output.append(tokens.get(i));
                }

                if (outputFile != null) {
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of(outputFile),
                            output + System.lineSeparator());
                } else {
                    System.out.println(output);
                }
            }

            // pwd
            else if (command.equals("pwd")) {
                if (outputFile != null) {
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of(outputFile),
                            currentDirectory + System.lineSeparator());
                } else {
                    System.out.println(currentDirectory);
                }
            }

            // cd
            else if (command.equals("cd")) {
                if (tokens.size() < 2) {
                    continue;
                }

                String path = tokens.get(1);
                File targetDir;

                if (path.equals("~")) {
                    targetDir = new File(System.getenv("HOME"));
                } else if (path.startsWith("/")) {
                    targetDir = new File(path);
                } else {
                    targetDir = new File(currentDirectory, path);
                }

                if (targetDir.exists() && targetDir.isDirectory()) {
                    currentDirectory = targetDir.getCanonicalPath();
                } else {
                    String errorMsg = "cd: " + path + ": No such file or directory" + System.lineSeparator();
                    if (errorFile != null) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(errorFile), errorMsg);
                    } else {
                        System.out.print(errorMsg);
                    }
                }
            }

            // type
            else if (command.equals("type")) {
                if (tokens.size() < 2) {
                    continue;
                }

                String cmd = tokens.get(1);
                String result;

                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd") || cmd.equals("cd")) {
                    result = cmd + " is a shell builtin";
                } else {
                    String path = System.getenv("PATH");
                    String[] dirs = path.split(":");
                    result = cmd + ": not found";

                    for (String dir : dirs) {
                        File file = new File(dir, cmd);
                        if (file.exists() && file.canExecute()) {
                            result = cmd + " is " + file.getAbsolutePath();
                            break;
                        }
                    }
                }

                if (outputFile != null) {
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of(outputFile),
                            result + System.lineSeparator());
                } else {
                    System.out.println(result);
                }
            }

            else if (commandLine.contains("|")) {

    String[] parts = commandLine.split("\\|", 2);

    List<String> left = parseCommand(parts[0].trim());
    List<String> right = parseCommand(parts[1].trim());

    try {

        ProcessBuilder pb1 = new ProcessBuilder(left);
        pb1.directory(new File(currentDirectory));

        Process p1 = pb1.start();

        ProcessBuilder pb2 = new ProcessBuilder(right);
        pb2.directory(new File(currentDirectory));

        Process p2 = pb2.start();

        Thread t = new Thread(() -> {
            try (
                var in = p1.getInputStream();
                var out = p2.getOutputStream()
            ) {
                in.transferTo(out);
                out.close();
            } catch (Exception ignored) {
            }
        });

        t.start();

        p2.getInputStream().transferTo(System.out);

        t.join();

        p1.waitFor();
        p2.waitFor();

    } catch (Exception e) {
        System.out.println("pipeline execution failed");
    }
}

            else {
                try {
                    ProcessBuilder pb = new ProcessBuilder(tokens);
                    pb.directory(new File(currentDirectory));

                    // Handle Standard Output Redirection
                    if (outputFile != null) {
                        pb.redirectOutput(new File(outputFile));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    // Handle Standard Error Redirection
                    if (errorFile != null) {
                        pb.redirectError(new File(errorFile));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();
                    process.waitFor();

                } catch (Exception e) {
                    String errorMsg = command + ": command not found" + System.lineSeparator();
                    if (errorFile != null) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(errorFile), errorMsg);
                    } else {
                        System.out.print(errorMsg);
                    }
                }
            }
        }

        sc.close();
    }
}