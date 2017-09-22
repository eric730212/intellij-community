/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.build;

import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.impl.ToolWindowImpl;
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi;
import com.intellij.ui.content.*;
import com.intellij.util.ContentUtilEx;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.*;
import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.intellij.util.ContentUtilEx.getFullName;

/**
 * @author Vladislav.Soroka
 */
public class BuildContentManagerImpl implements BuildContentManager, ContentManagerListener {

  public static final String Build = "Build";
  public static final String Sync = "Sync";
  public static final String Run = "Run";
  public static final String Debug = "Debug";
  private static final String[] ourPresetOrder = {Build, Sync, Run, Debug};
  private Project myProject;
  private ToolWindow myToolWindow;
  private final List<Runnable> myPostponedRunnables = new ArrayList<>();
  private Map<Content, Pair<Icon, AtomicInteger>> liveContentsMap = ContainerUtil.newConcurrentMap();

  public BuildContentManagerImpl(Project project) {
    init(project);
  }

  private void init(Project project) {
    myProject = project;
    if (project.isDefault()) return;

    final Runnable runnable = () -> {
      if(myProject.isDisposed()) return;

      ToolWindow toolWindow = ToolWindowManager.getInstance(project)
        .registerToolWindow(ToolWindowId.BUILD, true, ToolWindowAnchor.BOTTOM, project, true);
      toolWindow.getComponent().putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, "true");
      toolWindow.setIcon(AllIcons.Actions.Compile);
      myToolWindow = toolWindow;
      myToolWindow.getContentManager().addContentManagerListener(this);
      for (Runnable postponedRunnable : myPostponedRunnables) {
        postponedRunnable.run();
      }
      myPostponedRunnables.clear();
    };

    if (project.isInitialized()) {
      UIUtil.invokeLaterIfNeeded(runnable);
    }
    else {
      StartupManager.getInstance(project).registerPostStartupActivity(runnable);
    }
  }

  public Promise<Void> runWhenInitialized(final Runnable runnable) {
    if (myToolWindow != null) {
      runnable.run();
      return Promises.resolvedPromise(null);
    }
    else {
      final AsyncPromise<Void> promise = new AsyncPromise<>();
      myPostponedRunnables.add(() -> {
        if(!myProject.isDisposed()) {
          runnable.run();
          promise.setResult(null);
        }
      });
      return promise;
    }
  }

  @Override
  public void addContent(Content content) {
    runWhenInitialized(() -> {
      if (!myToolWindow.isAvailable()) {
        myToolWindow.setAvailable(true, null);
      }
      ContentManager contentManager = myToolWindow.getContentManager();
      final String name = content.getTabName();
      final String category = StringUtil.trimEnd(StringUtil.split(name, " ").get(0), ':');
      int idx = -1;
      for (int i = 0; i < ourPresetOrder.length; i++) {
        final String s = ourPresetOrder[i];
        if (s.equals(category)) {
          idx = i;
          break;
        }
      }
      final Content[] existingContents = contentManager.getContents();
      if (idx != -1) {
        final MultiMap<String, String> existingCategoriesNames = MultiMap.createSmart();
        for (Content existingContent : existingContents) {
          String tabName = existingContent.getTabName();
          existingCategoriesNames.putValue(StringUtil.trimEnd(StringUtil.split(tabName, " ").get(0), ':'), tabName);
        }

        int place = 0;
        for (int i = 0; i < idx; i++) {
          String key = ourPresetOrder[i];
          Collection<String> tabNames = existingCategoriesNames.get(key);
          if (!key.equals(category)) {
            place += tabNames.size();
          }
        }
        contentManager.addContent(content, place);
      }
      else {
        contentManager.addContent(content);
      }

      for (Content existingContent : existingContents) {
        existingContent.setDisplayName(existingContent.getTabName());
      }
      String tabName = content.getTabName();
      updateTabDisplayName(content, tabName);
    });
  }

  public void updateTabDisplayName(Content content, String tabName) {
    String displayName;
    ContentManager contentManager = myToolWindow.getContentManager();
    Content firstContent = contentManager.getContent(0);
    assert firstContent != null;
    if (!Build.equals(firstContent.getTabName())) {
      if (contentManager.getContentCount() > 1) {
        setIdLabelHidden(false);
        displayName = tabName;
      }
      else {
        displayName = Build + ": " + tabName;
      }
    }
    else {
      displayName = tabName;
      setIdLabelHidden(true);
    }

    if (!displayName.equals(content.getDisplayName())) {
      // we are going to adjust display name, so we need to ensure tab name is not retrieved based on display name
      content.setTabName(tabName);
      content.setDisplayName(displayName);
    }
  }

  @Override
  public void removeContent(Content content) {
    ContentManager contentManager = myToolWindow.getContentManager();
    if (contentManager != null && (!contentManager.isDisposed())) {
      contentManager.removeContent(content, true);
    }
  }

  @Override
  public ActionCallback setSelectedContent(Content content,
                                           boolean requestFocus,
                                           boolean forcedFocus,
                                           boolean activate,
                                           Runnable activationCallback) {
    ActionCallback actionCallback = new ActionCallback();
    runWhenInitialized(() -> {
      ActionCallback callback = myToolWindow.getContentManager().setSelectedContent(content, requestFocus, forcedFocus, false);
      callback.notify(actionCallback);
      if (activate) {
        ApplicationManager.getApplication().invokeLater(
          () -> myToolWindow.activate(activationCallback, requestFocus, requestFocus), myProject.getDisposed());
      }
    });
    return actionCallback;
  }

  @Override
  public Content addTabbedContent(@NotNull JComponent contentComponent,
                                  @NotNull String groupPrefix,
                                  @NotNull String tabName,
                                  @Nullable Icon icon,
                                  @Nullable Disposable childDisposable) {
    ContentManager contentManager = myToolWindow.getContentManager();
    ContentUtilEx.addTabbedContent(contentManager, contentComponent, groupPrefix, tabName, false, childDisposable);
    Content content = contentManager.findContent(getFullName(groupPrefix, tabName));
    if (icon != null) {
      TabbedContent tabbedContent = ContentUtilEx.findTabbedContent(contentManager, groupPrefix);
      if (tabbedContent != null) {
        tabbedContent.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
        tabbedContent.setIcon(icon);
      }
    }
    return content;
  }

  public void startBuildNotified(Content content) {
    if(myToolWindow == null) return;

    Pair<Icon, AtomicInteger> pair = liveContentsMap.computeIfAbsent(content, c -> Pair.pair(c.getIcon(), new AtomicInteger(0)));
    pair.second.incrementAndGet();
    content.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
    content.setIcon(ExecutionUtil.getLiveIndicator(pair.first));
    JComponent component = content.getComponent();
    if (component != null) {
      component.invalidate();
    }
    myToolWindow.setIcon(ExecutionUtil.getLiveIndicator(AllIcons.Actions.Compile));
  }

  public void finishBuildNotified(Content content) {
    if(myToolWindow == null) return;

    Pair<Icon, AtomicInteger> pair = liveContentsMap.get(content);
    if (pair != null && pair.second.decrementAndGet() == 0) {
      content.setIcon(pair.first);
      if (pair.first == null) {
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.FALSE);
      }
      liveContentsMap.remove(content);
      if (liveContentsMap.isEmpty()) {
        myToolWindow.setIcon(AllIcons.Actions.Compile);
      }
    }
  }

  private void setIdLabelHidden(boolean hide) {
    JComponent component = myToolWindow.getComponent();
    Object oldValue = component.getClientProperty(ToolWindowContentUi.HIDE_ID_LABEL);
    Object newValue = hide ? "true" : null;
    component.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, newValue);
    if (myToolWindow instanceof ToolWindowImpl) {
      ((ToolWindowImpl)myToolWindow).getContentUI()
        .propertyChange(new PropertyChangeEvent(this, ToolWindowContentUi.HIDE_ID_LABEL, oldValue, newValue));
    }
  }

  @Override
  public void contentAdded(ContentManagerEvent event) {

  }

  @Override
  public void contentRemoved(ContentManagerEvent event) {
    ContentManager contentManager = myToolWindow.getContentManager();
    if (contentManager.getContentCount() == 0) {
      myToolWindow.setAvailable(false, null);
    }
  }

  @Override
  public void contentRemoveQuery(ContentManagerEvent event) {

  }

  @Override
  public void selectionChanged(ContentManagerEvent event) {

  }
}
