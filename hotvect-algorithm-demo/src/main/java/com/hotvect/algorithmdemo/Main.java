package com.hotvect.algorithmdemo;

import picocli.CommandLine;

public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Options()).execute(args);
        System.exit(exitCode);
    }
}
