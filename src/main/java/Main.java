import java.io.File;
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

            // type builtin
            else if (command.startsWith("type ")) {

                String cmd = command.substring(5);

                // check builtins
                if (cmd.equals("echo") ||
                    cmd.equals("exit") ||
                    cmd.equals("type")) {

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

            // invalid command
            else {
                System.out.println(command + ": command not found");
            }
        }

        sc.close();
    }
}