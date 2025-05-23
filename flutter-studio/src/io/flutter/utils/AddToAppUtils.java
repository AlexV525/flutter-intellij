/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.utils;

//import static com.android.tools.idea.gradle.project.importing.GradleProjectImporter.ANDROID_PROJECT_TYPE;

import static com.intellij.util.ReflectionUtil.findAssignableField;
import static io.flutter.actions.AttachDebuggerAction.ATTACH_IS_ACTIVE;
import static io.flutter.actions.AttachDebuggerAction.findRunConfig;

import com.android.tools.idea.gradle.project.sync.GradleSyncListener;
import com.android.tools.idea.gradle.project.sync.GradleSyncState;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.engine.DebugProcessListener;
import com.intellij.debugger.impl.DebuggerManagerListener;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.*;
import com.intellij.util.ThreeState;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import io.flutter.FlutterUtils;
import io.flutter.actions.AttachDebuggerAction;
import io.flutter.pub.PubRoot;
import io.flutter.run.SdkAttachConfig;
import io.flutter.sdk.FlutterSdk;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AddToAppUtils {
  //private static final Logger LOG = Logger.getInstance(AddToAppUtils.class);

  private AddToAppUtils() {
  }

  public static boolean initializeAndDetectFlutter(@NotNull Project project) {
    MessageBusConnection connection = project.getMessageBus().connect(project);
    // GRADLE_SYNC_TOPIC is not public in Android Studio 3.5. It is in 3.6. It isn't defined in 3.4.
    //noinspection unchecked
    Topic<GradleSyncListener> topic = getStaticFieldValue(GradleSyncState.class, Topic.class, "GRADLE_SYNC_TOPIC");
    if (topic != null) {
      connection.subscribe(topic, makeSyncListener(project));
    }

    if (!FlutterModuleUtils.hasFlutterModule(project)) {
      connection.subscribe(ModuleListener.TOPIC, new ModuleListener() {
        @Override
        public void modulesAdded(@NotNull Project proj, @NotNull List<? extends Module> modules) {
          for (Module module : modules) {
            if (module == null) continue;
            if (AndroidUtils.FLUTTER_MODULE_NAME.equals(module.getName()) ||
                (FlutterUtils.flutterGradleModuleName(project)).equals(module.getName())) {
              //connection.disconnect(); TODO(messick) Test this deletion!
              AppExecutorUtil.getAppExecutorService().execute(() -> {
                GradleUtils.enableCoeditIfAddToAppDetected(project);
              });
            }
          }

        }
      });
      return false;
    }
    else {
      Collection<ProjectType> projectTypes = ProjectTypeService.getProjectTypes(project);
      if (projectTypes != null) {
        for (ProjectType projectType : projectTypes) {
          if (projectType != null && "Android".equals(projectType.getId())) {
            // This is an add-to-app project.
            connection.subscribe(DebuggerManagerListener.TOPIC, makeAddToAppAttachListener(project));
          }
        }
      }
    }
    return true;
  }

  // Derived from the method in ReflectionUtil, with the addition of setAccessible().
  public static <T> T getStaticFieldValue(@NotNull Class objectClass,
                                          @Nullable("null means any type") Class<T> fieldType,
                                          @NotNull @NonNls String fieldName) {
    try {
      final Field field = findAssignableField(objectClass, fieldType, fieldName);
      if (!Modifier.isStatic(field.getModifiers())) {
        throw new IllegalArgumentException("Field " + objectClass + "." + fieldName + " is not static");
      }
      field.setAccessible(true);
      //noinspection unchecked
      return (T)field.get(null);
    }
    catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }

  @NotNull
  private static GradleSyncListener makeSyncListener(@NotNull Project project) {
    return new GradleSyncListener() {
      @Override
      public void syncSucceeded(@NotNull Project project) {
        GradleUtils.checkDartSupport(project);
      }

      @Override
      public void syncFailed(@NotNull Project project, @NotNull String errorMessage) {
        GradleUtils.checkDartSupport(project);
      }

      @Override
      public void syncSkipped(@NotNull Project project) {
        GradleUtils.checkDartSupport(project);
      }

      public void sourceGenerationFinished(@NotNull Project project) {
      }
    };
  }

  @NotNull
  private static DebuggerManagerListener makeAddToAppAttachListener(@NotNull Project project) {
    return new DebuggerManagerListener() {

      DebugProcessListener dpl = new DebugProcessListener() {
        @Override
        public void processDetached(@NotNull DebugProcess process, boolean closedByUser) {
          ThreeState state = project.getUserData(ATTACH_IS_ACTIVE);
          if (state != null) {
            project.putUserData(ATTACH_IS_ACTIVE, null);
          }
        }

        @Override
        public void processAttached(@NotNull DebugProcess process) {
          if (project.getUserData(ATTACH_IS_ACTIVE) != null) {
            return;
          }
          // Launch flutter attach if a run config can be found.
          @Nullable RunConfiguration runConfig = findRunConfig(project);
          if (runConfig == null) {
            // Either there is no Flutter run config or there are more than one.
            return;
          }
          if (!(runConfig instanceof SdkAttachConfig)) {
            // The selected run config at this point is not Flutter, so we can't start the process automatically.
            return;
          }
          FlutterSdk sdk = FlutterSdk.getFlutterSdk(project);
          if (sdk == null) {
            return;
          }
          // If needed, DataContext could be saved by FlutterReloadManager.beforeActionPerformed() in project user data.
          DataContext context = DataContext.EMPTY_CONTEXT;
          PubRoot pubRoot = ((SdkAttachConfig)runConfig).pubRoot;
          Application app = ApplicationManager.getApplication();
          project.putUserData(ATTACH_IS_ACTIVE, ThreeState.fromBoolean(true));
          // Note: Using block comments to preserve formatting.
          app.invokeLater( /* After the Android launch completes, */
            () -> app.executeOnPooledThread( /* but not on the EDT, */
              () -> app.runReadAction( /* with read access, */
                () -> new AttachDebuggerAction().startCommand(project, sdk, pubRoot, context)))); /* attach. */
        }
      };

      @Override
      public void sessionCreated(DebuggerSession session) {
        session.getProcess().addDebugProcessListener(dpl);
      }

      @Override
      public void sessionRemoved(DebuggerSession session) {
        session.getProcess().removeDebugProcessListener(dpl);
      }
    };
  }
}
