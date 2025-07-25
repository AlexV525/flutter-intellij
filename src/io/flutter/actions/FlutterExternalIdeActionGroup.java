/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import io.flutter.FlutterUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * This action has been removed from the plugin.xml as a required dependent method call {GradleProjectImporter.importAndOpenProjectCore()
 * throws "configureNewProject should be used with new projects only".
 * See https://github.com/flutter/flutter-intellij/issues/7103
 */
public class FlutterExternalIdeActionGroup extends DefaultActionGroup {
  private static boolean isExternalIdeFile(@NotNull AnActionEvent e) {
    @SuppressWarnings("DataFlowIssue") final VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
    if (file == null || !file.exists()) {
      return false;
    }

    final Project project = e.getProject();
    assert (project != null);
    return
      isWithinAndroidDirectory(file, project) ||
      isProjectDirectory(file, project) ||
      isWithinIOsDirectory(file, project) ||
      FlutterUtils.isXcodeProjectFileName(file.getName()) || OpenInAndroidStudioAction.isProjectFileName(file.getName());
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  public static boolean isAndroidDirectory(@NotNull VirtualFile file) {
    return file.isDirectory() && (file.getName().equals("android") || file.getName().equals(".android"));
  }

  public static boolean isIOsDirectory(@NotNull VirtualFile file) {
    return file.isDirectory() && (file.getName().equals("ios") || file.getName().equals(".ios"));
  }

  protected static boolean isWithinAndroidDirectory(@NotNull VirtualFile file, @NotNull Project project) {
    final VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
    if (baseDir == null) {
      return false;
    }
    VirtualFile candidate = file;
    while (candidate != null && !baseDir.equals(candidate)) {
      if (isAndroidDirectory(candidate)) {
        return true;
      }
      candidate = candidate.getParent();
    }
    return false;
  }

  protected static boolean isWithinIOsDirectory(@NotNull VirtualFile file, @NotNull Project project) {
    final VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
    if (baseDir == null) {
      return false;
    }
    VirtualFile candidate = file;
    while (candidate != null && !baseDir.equals(candidate)) {
      if (isIOsDirectory(candidate)) {
        return true;
      }
      candidate = candidate.getParent();
    }
    return false;
  }

  private static boolean isProjectDirectory(@NotNull VirtualFile file, @Nullable Project project) {
    if (!file.isDirectory() || project == null) {
      return false;
    }

    final VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
    return baseDir != null && baseDir.getPath().equals(file.getPath());
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    final Presentation presentation = event.getPresentation();
    final boolean enabled = isExternalIdeFile(event);
    presentation.setEnabled(enabled);
    presentation.setVisible(enabled);
  }
}
