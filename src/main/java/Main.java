import java.io.File;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner sc = new Scanner(System.in);

        while (true) {

            System.out.print("$ ");

            String command = sc.nextLine();

            if (command.equals("exit")) {
                break;
            }

            else if (command.startsWith("echo ")) {
                System.out.println(command.substring(5));
            }

            else if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            }

            else if (command.startsWith("type ")) {

                String cmd = command.substring(5);

                if (cmd.equals("echo") ||
                    cmd.equals("exit") ||
                    cmd.equals("type") ||
                    cmd.equals("pwd")) {

                    System.out.println(cmd + " is a shell builtin");
                }
                else {

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

            else {

                String[] parts = command.split(" ");

                try {

                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.inheritIO();

                    Process process = pb.start();
                    process.waitFor();

                } catch (Exception e) {
                    System.out.println(command + ": command not found");
                }
            }
        }

        sc.close();
    }
}