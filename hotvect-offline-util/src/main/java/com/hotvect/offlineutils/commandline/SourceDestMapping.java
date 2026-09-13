package com.hotvect.offlineutils.commandline;

import java.io.File;
import java.util.List;

public record SourceDestMapping(List<File> sources, File dest) {}
