/*
 * Copyright 2020 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.daemon;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Version;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.jetbrains.lang.dart.ide.devtools.DartDevToolsService;
import com.jetbrains.lang.dart.ide.toolingDaemon.DartToolingDaemonService;
import com.jetbrains.lang.dart.sdk.DartSdk;
import io.flutter.FlutterUtils;
import io.flutter.bazel.Workspace;
import io.flutter.bazel.WorkspaceCache;
import io.flutter.console.FlutterConsoles;
import io.flutter.dart.DtdUtils;
import io.flutter.sdk.FlutterCommand;
import io.flutter.sdk.FlutterSdk;
import io.flutter.sdk.FlutterSdkUtil;
import io.flutter.utils.JsonUtils;
import io.flutter.utils.MostlySilentColoredProcessHandler;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class DevToolsService {
  private static final Logger LOG = Logger.getInstance(DevToolsService.class);
  public static final String LOCAL_DEVTOOLS_DIR = "flutter.local.devtools.dir";
  public static final String LOCAL_DEVTOOLS_ARGS = "flutter.local.devtools.args";

  private static class DevToolsServiceListener implements DaemonEvent.Listener {
  }

  @NotNull private final Project project;
  private DaemonApi daemonApi;
  private ProcessHandler process;
  private AtomicReference<CompletableFuture<DevToolsInstance>> devToolsFutureRef = new AtomicReference<>(null);

  @NotNull
  public static DevToolsService getInstance(@NotNull final Project project) {
    return Objects.requireNonNull(project.getService(DevToolsService.class));
  }

  private DevToolsService(@NotNull final Project project) {
    this.project = project;
  }

  public CompletableFuture<DevToolsInstance> getDevToolsInstance() {
    // Create instance if it doesn't exist yet, or if the previous attempt failed.
    if (devToolsFutureRef.compareAndSet(null, new CompletableFuture<>())) {
      startServer();
    }

    if (devToolsFutureRef.updateAndGet((future) -> {
      if (future.isCompletedExceptionally()) {
        return null;
      }
      else {
        return future;
      }
    }) == null) {
      devToolsFutureRef.set(new CompletableFuture<>());
      startServer();
    }

    return devToolsFutureRef.get();
  }

  public CompletableFuture<DevToolsInstance> getDevToolsInstanceWithForcedRestart() {
    final CompletableFuture<DevToolsInstance> futureInstance = devToolsFutureRef.updateAndGet((future) -> {
      if (future.isCompletedExceptionally() || future.isCancelled()) {
        return null;
      }
      else {
        return future;
      }
    });

    if (futureInstance == null) {
      devToolsFutureRef.set(new CompletableFuture<>());
      startServer();
    }
    else if (!futureInstance.isDone()) {
      futureInstance.cancel(true);
      devToolsFutureRef.set(new CompletableFuture<>());
      startServer();
    }

    return devToolsFutureRef.get();
  }

  private void startServer() {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      final FlutterSdk sdk = FlutterSdk.getFlutterSdk(project);

      boolean dartDevToolsSupported = false;
      final DartSdk dartSdk = DartSdk.getDartSdk(project);
      if (dartSdk != null) {
        final Version version = Version.parseVersion(dartSdk.getVersion());
        assert version != null;
        dartDevToolsSupported = version.compareTo(2, 15, 0) >= 0;
      }

      if (dartDevToolsSupported) {
        // This condition means we can use `dart devtools` to start.
        final WorkspaceCache workspaceCache = WorkspaceCache.getInstance(project);
        if (workspaceCache.isBazel()) {
          // This is only for internal usages.
          setUpWithDart(createCommand(workspaceCache.get().getRoot().getPath(), workspaceCache.get().getDevToolsScript(),
                                      ImmutableList.of("--machine")));
        }
        else {
          final String localDevToolsDir = Registry.stringValue(LOCAL_DEVTOOLS_DIR);
          if (!localDevToolsDir.isEmpty()) {
            // This is only for development to check integration with a locally run DevTools server.
            // To enable, follow the instructions in:
            // https://github.com/flutter/flutter-intellij/blob/master/CONTRIBUTING.md#developing-with-local-devtools
            final DtdUtils dtdUtils = new DtdUtils();
            try {
              final DartToolingDaemonService dtdService = dtdUtils.readyDtdService(project).get();
              final String dtdUri = dtdService.getUri();

              final List<String> args = new ArrayList<>();
              args.add("serve");
              args.add("--machine");
              args.add("--dtd-uri=" + dtdUri);
              final String localDevToolsArgs = Registry.stringValue(LOCAL_DEVTOOLS_ARGS);
              if (!localDevToolsArgs.isEmpty()) {
                args.addAll(Arrays.stream(localDevToolsArgs.split(" ")).toList());
              }

              setUpInDevMode(createCommand(localDevToolsDir, "dt", args));
            }
            catch (InterruptedException | java.util.concurrent.ExecutionException e) {
              throw new RuntimeException(e);
            }
            return;
          }

          // The Dart plugin should start DevTools with DTD, so try to use this instance of DevTools before trying to start another.
          final String dartPluginUri = DartDevToolsService.getInstance(project).getDevToolsHostAndPort();
          if (dartPluginUri != null) {
            String[] parts = dartPluginUri.split(":");
            String host = parts[0];
            Integer port = Integer.parseInt(parts[1]);
            if (host != null && port != null) {
              devToolsFutureRef.get().complete(new DevToolsInstance(host, port));
              return;
            }
          }

          setUpWithDart(createCommand(DartSdk.getDartSdk(project).getHomePath(),
                                      DartSdk.getDartSdk(project).getHomePath() + File.separatorChar + "bin" + File.separatorChar + "dart",
                                      ImmutableList.of("devtools", "--machine")));
        }
      }
      else {
        setUpWithDaemon();
      }
    });
  }

  private void setUpInDevMode(GeneralCommandLine command) {
    try {
      this.process = new MostlySilentColoredProcessHandler(command);
      this.process.addProcessListener(new ProcessAdapter() {
        @Override
        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
          final String text = event.getText().trim();

          // Keep this printout so developers can see DevTools startup output in idea.log.
          System.out.println("DevTools startup: " + text);
          tryParseStartupText(text);
        }
      });
      process.startNotify();
    }
    catch (ExecutionException e) {
      logExceptionAndComplete(e);
    }
  }

  private void setUpWithDart(GeneralCommandLine command) {
    try {
      this.process = new MostlySilentColoredProcessHandler(command);
      this.process.addProcessListener(new ProcessAdapter() {
        @Override
        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
          tryParseStartupText(event.getText().trim());
        }
      });
      process.startNotify();

      ProjectManager.getInstance().addProjectManagerListener(project, new ProjectManagerListener() {
        @Override
        public void projectClosing(@NotNull Project project) {
          devToolsFutureRef.set(null);
          process.destroyProcess();
        }
      });
    }
    catch (ExecutionException e) {
      logExceptionAndComplete(e);
    }
  }

  private void tryParseStartupText(@NotNull String text) {
    if (text.startsWith("{") && text.endsWith("}")) {
      try {
        final JsonElement element = JsonUtils.parseString(text);

        final JsonObject obj = element.getAsJsonObject();

        if (Objects.equals(JsonUtils.getStringMember(obj, "event"), "server.started")) {
          final JsonObject params = obj.getAsJsonObject("params");
          final String host = JsonUtils.getStringMember(params, "host");
          final int port = JsonUtils.getIntMember(params, "port");

          if (port != -1) {
            devToolsFutureRef.get().complete(new DevToolsInstance(host, port));
          }
          else {
            logExceptionAndComplete("DevTools port was invalid");
          }
        }
      }
      catch (JsonSyntaxException e) {
        logExceptionAndComplete(e);
      }
    }
  }

  private void setUpWithDaemon() {
    try {
      final GeneralCommandLine command = chooseCommand(project);
      if (command == null) {
        logExceptionAndComplete("Unable to find daemon command for project");
        return;
      }
      this.process = new MostlySilentColoredProcessHandler(command);
      daemonApi = new DaemonApi(process);
      daemonApi.listen(process, new DevToolsServiceListener());
      daemonApi.devToolsServe().thenAccept((DaemonApi.DevToolsAddress address) -> {
        if (!project.isOpen()) {
          // We should skip starting DevTools (and doing any UI work) if the project has been closed.
          return;
        }
        if (address == null) {
          logExceptionAndComplete("DevTools address was null");
        }
        else {
          devToolsFutureRef.get().complete(new DevToolsInstance(address.host, address.port));
        }
      });
    }
    catch (ExecutionException e) {
      logExceptionAndComplete(e);
    }

    ProjectManager.getInstance().addProjectManagerListener(project, new ProjectManagerListener() {
      @Override
      public void projectClosing(@NotNull Project project) {
        devToolsFutureRef.set(null);

        try {
          daemonApi.daemonShutdown().get(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException | java.util.concurrent.ExecutionException | TimeoutException e) {
          LOG.error("DevTools daemon did not shut down normally: " + e);
          if (!process.isProcessTerminated()) {
            process.destroyProcess();
          }
        }
      }
    });
  }

  private CompletableFuture<Boolean> pubActivateDevTools(FlutterSdk sdk) {
    final FlutterCommand command = sdk.flutterPub(null, "global", "activate", "devtools");

    final CompletableFuture<Boolean> result = new CompletableFuture<>();

    final Process process = command.start((ProcessOutput output) -> {
      if (output.getExitCode() != 0) {
        final String message = (output.getStdout() + "\n" + output.getStderr()).trim();
        FlutterConsoles.displayMessage(project, null, message, true);
      }
    }, null);

    try {
      final int resultCode = process.waitFor();
      result.complete(resultCode == 0);
    }
    catch (RuntimeException | InterruptedException re) {
      if (!result.isDone()) {
        result.complete(false);
      }
    }

    return result;
  }

  private void logExceptionAndComplete(String message) {
    logExceptionAndComplete(new Exception(message));
  }

  private void logExceptionAndComplete(Exception exception) {
    LOG.info(exception);
    final CompletableFuture<DevToolsInstance> future = devToolsFutureRef.get();
    if (future != null) {
      future.completeExceptionally(exception);
    }
  }

  private static GeneralCommandLine chooseCommand(@NotNull final Project project) {
    // Use daemon script if this is a bazel project.
    final Workspace workspace = WorkspaceCache.getInstance(project).get();
    if (workspace != null) {
      final String script = workspace.getDaemonScript();
      if (script != null) {
        return createCommand(workspace.getRoot().getPath(), script, ImmutableList.of());
      }
    }

    // Otherwise, use the Flutter SDK.
    final FlutterSdk sdk = FlutterSdk.getFlutterSdk(project);
    if (sdk == null) {
      return null;
    }

    try {
      final String path = FlutterSdkUtil.pathToFlutterTool(sdk.getHomePath());
      return createCommand(sdk.getHomePath(), path, ImmutableList.of("daemon"));
    }
    catch (ExecutionException e) {
      FlutterUtils.warn(LOG, "Unable to calculate command to start Flutter daemon", e);
      return null;
    }
  }

  private static GeneralCommandLine createCommand(String workDir, String command, List<String> arguments) {
    final GeneralCommandLine result = new GeneralCommandLine().withWorkDirectory(workDir);
    result.setCharset(StandardCharsets.UTF_8);
    result.setExePath(FileUtil.toSystemDependentName(command));
    result.withEnvironment(FlutterSdkUtil.FLUTTER_HOST_ENV, (new FlutterSdkUtil()).getFlutterHostEnvValue());

    for (String argument : arguments) {
      result.addParameter(argument);
    }

    return result;
  }
}

