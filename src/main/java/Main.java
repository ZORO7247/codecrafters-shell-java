import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner sc = new Scanner(System.in);

        while (true) {

            System.out.print("$ ");

            String command = sc.nextLine();

            // exit builtin
            if (command.equals("exit")) {
                break;
            }

            // echo builtin
            else if (command.startsWith("echo ")) {
                System.out.println(command.substring(5));
            }

            // pwd builtin
            else if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            }

            // type builtin
            else if (command.startsWith("type ")) {

                String cmd = command.substring(5);

                if (cmd.equals("echo") ||
                    cmd.equals("exit") ||
                    cmd.equals("type") ||
                    cmd.equals("pwd")) {

                    System.out.println(cmd + " is a shell builtin");
                } else {

                    String path = System.getenv("PATH");
                    String[] directories = path.split(":");

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
            }

            // external programs
            else {

                String[] parts = command.split(" ");

                String program = parts[0];

                String path = System.getenv("PATH");
                String[] directories = path.split(":");

                File executable = null;

                for (String dir : directories) {

                    File file = new File(dir, program);

                    if (file.exists() && file.canExecute()) {
                        executable = file;
                        break;
                    }
                }

                if (executable != null) {

                    List<String> cmd = new ArrayList<>();

                    cmd.add(executable.getAbsolutePath());

                    for (int i = 1; i < parts.length; i++) {
                        cmd.add(parts[i]);
                    }

                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.inheritIO();

                    Process process = pb.start();
                    process.waitFor();

                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }

        sc.close();
    }
}