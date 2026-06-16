else if (command.startsWith("type ")) {

    String cmd = command.substring(5);

    if (cmd.equals("echo") ||
        cmd.equals("exit") ||
        cmd.equals("type")) {

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