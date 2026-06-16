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

            if (command.startsWith("echo ")) {
                System.out.println(command.substring(5));
            }
            else if (command.startsWith("type ")) {

                String cmd = command.substring(5);

                if (cmd.equals("echo") ||
                    cmd.equals("exit") ||
                    cmd.equals("type")) {

                    System.out.println(cmd + " is a shell builtin");
                } else {
                    System.out.println(cmd + ": not found");
                }
            }
            else {
                System.out.println(command + ": command not found");
            }
        }
    }
}