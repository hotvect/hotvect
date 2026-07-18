package com.hotvect.algorithmserver;

import picocli.CommandLine;

public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new ServerOptions()).execute(args);
        System.exit(exitCode);
    }
}
