package org.gosulang.gradle.tasks.compile;

import org.apache.tools.ant.taskdefs.condition.Os;
import org.gradle.api.GradleException;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.SimpleWorkResult;
import org.gradle.api.internal.tasks.compile.CompilationFailedException;
import org.gradle.api.internal.tasks.compile.JavaCompileSpec;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.compile.ForkOptions;
import org.gradle.internal.jvm.Jvm;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaExecSpec;
import org.gradle.util.GUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class CommandLineGosuCompiler implements Compiler<DefaultGosuCompileSpec> {
  private static final Logger LOGGER = Logging.getLogger(CommandLineGosuCompiler.class);
  
  private final ProjectInternal _project;
  private final DefaultGosuCompileSpec _spec;
  private final String _projectName;
  
  public CommandLineGosuCompiler( ProjectInternal project, DefaultGosuCompileSpec spec, String projectName ) {
    _project = project;
    _spec = spec;
    _projectName = projectName;
  }
  
  @Override
  public WorkResult execute( DefaultGosuCompileSpec spec ) {
    String startupMsg = "Initializing gosuc compiler";
    if(_projectName.isEmpty()) {
      startupMsg += " for " + _projectName;
    }
    LOGGER.info(startupMsg);

    List<String> gosucArgs = new ArrayList<>();

    File argFile;
    try {
      argFile = createArgFile(_spec);
      gosucArgs.add("@" + argFile.getCanonicalPath().replace(File.separatorChar, '/'));
    } catch (IOException e) {
      LOGGER.error("Error creating argfile with gosuc arguments");
      throw new CompilationFailedException(e);
    }
    
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    ExecResult result = _project.javaexec(javaExecSpec -> {
      javaExecSpec.setWorkingDir((Object) _project.getProjectDir()); // Gradle 4.0 overloads ProcessForkOptions#setWorkingDir; must upcast to Object for backwards compatibility
      setJvmArgs(javaExecSpec, _spec.getGosuCompileOptions().getForkOptions());
      javaExecSpec.setMain("gw.lang.gosuc.cli.CommandLineCompiler")
          .setClasspath(spec.getGosuClasspath().call().plus(_project.files(Jvm.current().getToolsJar())))
          .setArgs((Iterable<?>) gosucArgs); // Gradle 4.0 overloads JavaExecSpec#setArgs; must upcast to Iterable<?> for backwards compatibility
      javaExecSpec.setStandardOutput(stdout);
      javaExecSpec.setErrorOutput(stderr);
      javaExecSpec.setIgnoreExitValue(true); //otherwise fails immediately before displaying output
    });

    int exitCode = result.getExitValue();

    if(exitCode != 0 ) {
      LOGGER.quiet(stdout.toString());
      LOGGER.quiet(stderr.toString());
      if(!_spec.getGosuCompileOptions().isFailOnError()) {
        LOGGER.quiet(String.format("%s completed with errors, but ignoring as 'gosuOptions.failOnError = false' was specified.", _projectName.isEmpty() ? "gosuc" : _projectName));
      } else {
        throw new CompilationFailedException(exitCode);
      }
    } else {
      LOGGER.info(stdout.toString());
      LOGGER.info(stderr.toString());
      LOGGER.info(String.format("%s completed successfully.", _projectName.isEmpty() ? "gosuc" : _projectName));
    }
    
    return new SimpleWorkResult(true);
  }

  private void setJvmArgs(JavaExecSpec spec, ForkOptions forkOptions) {
    if(forkOptions.getMemoryInitialSize() != null && !forkOptions.getMemoryInitialSize().isEmpty()) {
      spec.setMinHeapSize(forkOptions.getMemoryInitialSize());
    }
    if(forkOptions.getMemoryMaximumSize() != null && !forkOptions.getMemoryMaximumSize().isEmpty()) {
      spec.setMaxHeapSize(forkOptions.getMemoryMaximumSize());
    }

    List<String> args = new ArrayList<>();

    //respect JAVA_OPTS, if it exists
    String JAVA_OPTS = System.getenv("JAVA_OPTS");
    if(JAVA_OPTS != null && !JAVA_OPTS.isEmpty()) {
      args.add(JAVA_OPTS);
    }

    args.addAll(forkOptions.getJvmArgs());

    if(Os.isFamily(Os.FAMILY_MAC)) {
      args.add("-Xdock:name=gosuc");
    }

    spec.setJvmArgs((Iterable<?>) args); // Gradle 4.0 overloads JavaForkOptions#setJvmArgs; must upcast to Iterable<?> for backwards compatibility
  }
  
  private File createArgFile(DefaultGosuCompileSpec spec) throws IOException {
    File tempFile = File.createTempFile(CommandLineGosuCompiler.class.getName(), "arguments", spec.getTempDir());

    List<String> fileOutput = new ArrayList<>();

    if(spec.getGosuCompileOptions().isCheckedArithmetic()) {
      fileOutput.add("-checkedArithmetic");
    }
    
    // The classpath used to initialize Gosu; CommandLineCompiler will supplement this with the JRE jars
    fileOutput.add("-classpath");
    fileOutput.add(String.join(File.pathSeparator, GUtil.asPath(spec.getClasspath())));

    fileOutput.add("-d");
    fileOutput.add(spec.getDestinationDir().getAbsolutePath());

    fileOutput.add("-sourcepath");
    fileOutput.add(String.join(File.pathSeparator, GUtil.asPath(spec.getSourceRoots())));

    if(!isWarnings(spec)) {
      fileOutput.add("-nowarn");
    }

    if(spec.getGosuCompileOptions().isVerbose()) {
      fileOutput.add("-verbose");
    }

    if(spec.getGosuCompileOptions().getMaxWarns() != null) {
      fileOutput.add("-maxwarns");
      fileOutput.add(spec.getGosuCompileOptions().getMaxWarns().toString());
    }

    if(spec.getGosuCompileOptions().getMaxErrs() != null) {
      fileOutput.add("-maxerrs");
      fileOutput.add(spec.getGosuCompileOptions().getMaxErrs().toString());
    }
    
    for(File sourceFile : spec.getSource()) {
      fileOutput.add(sourceFile.getPath());
    }

    Files.write(tempFile.toPath(), fileOutput, StandardCharsets.UTF_8);

    return tempFile;
  }

  /**
   * <p>Internal API change in 4.3+; return type of getCompileOptions() changes
   * <p>This helper method will call isWarnings() reflectively
   * @param spec An implementation of JavaCompileSpec (most likely DefaultGosuCompileSpec)
   * @return true if isWarnings() is true, false otherwise
   */
  private boolean isWarnings(JavaCompileSpec spec) {
    try {
      Method getCompileOptions = spec.getClass().getMethod("getCompileOptions");
      Object compileOptions = getCompileOptions.invoke(spec);
      Method isWarnings = compileOptions.getClass().getMethod("isWarnings");
      return (boolean) isWarnings.invoke(compileOptions);
    } catch(NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new GradleException("Unable to apply Gosu plugin", e);
    }
  }
  
}

