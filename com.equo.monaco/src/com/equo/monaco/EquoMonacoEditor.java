/****************************************************************************
**
** Copyright (C) 2021 Equo
**
** This file is part of Equo Framework.
**
** Commercial License Usage
** Licensees holding valid commercial Equo licenses may use this file in
** accordance with the commercial license agreement provided with the
** Software or, alternatively, in accordance with the terms contained in
** a written agreement between you and Equo. For licensing terms
** and conditions see https://www.equoplatform.com/terms.
**
** GNU General Public License Usage
** Alternatively, this file may be used under the terms of the GNU
** General Public License version 3 as published by the Free Software
** Foundation. Please review the following
** information to ensure the GNU General Public License requirements will
** be met: https://www.gnu.org/licenses/gpl-3.0.html.
**
****************************************************************************/

package com.equo.monaco;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.chromium.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.equo.comm.api.IEquoCommService;
import com.equo.comm.api.IEquoEventHandler;
import com.equo.filesystem.api.IEquoFileSystem;
import com.equo.logging.client.api.Logger;
import com.equo.logging.client.api.LoggerFactory;
import com.equo.monaco.lsp.CommonLspProxy;
import com.equo.monaco.lsp.LspProxy;
import com.google.gson.JsonObject;

/**
 * Implementation of Equo Editor. Class responsible for all communication with
 * the editor in javascript.
 */
public class EquoMonacoEditor {
  private static Logger logger = LoggerFactory.getLogger(EquoMonacoEditor.class);
  protected IEquoFileSystem equoFileSystem;

  private LspProxy lspProxy = null;
  private static Map<String, List<String>> lspServers = new HashMap<>();
  private static Map<String, String> lspWsServers = new HashMap<>();

  private volatile boolean loaded;

  private final Semaphore lock = new Semaphore(1);

  private Browser browser;
  private String namespace;
  private List<Consumer<Void>> onLoadListeners;
  protected String filePath = "";
  private boolean dispose = false;
  private String fileName = "";
  private WatchService watchService;
  private String rootPath = null;

  protected IEquoEventHandler equoEventHandler;

  private String initialContent;

  public String getFilePath() {
    return filePath;
  }

  public void setRootPath(String rootPath) {
    this.rootPath = rootPath;
  }

  /**
   * Changes the file path of the editor.
   */
  public void setFilePath(String filePath) {
    this.filePath = filePath;
    this.fileName = new File(this.filePath).getName();
    listenChangesPath();
    notifyFilePathChanged();
  }

  /**
   * Constructor used from the widget for Eclipse. It initializes a browser with
   * the editor inside the given Composite.
   */
  public EquoMonacoEditor(Composite parent, int style, IEquoEventHandler handler,
      IEquoCommService commService, IEquoFileSystem equoFileSystem, String editorUrl) {
    this(handler, equoFileSystem);
    browser = new Browser(parent, style);
    String commPort = String.format("&equocommport=%s", String.valueOf(commService.getPort()));
    browser.setUrl("http://" + editorUrl + "?namespace=" + namespace + commPort);
  }

  /**
   * Parameterized constructor.
   */
  public EquoMonacoEditor(IEquoEventHandler handler, IEquoFileSystem equoFileSystem) {
    this.equoEventHandler = handler;
    this.equoFileSystem = equoFileSystem;
    namespace = "editor" + Double.toHexString(Math.random());
    onLoadListeners = new ArrayList<Consumer<Void>>();
    loaded = false;
    registerActions();
  }

  private void registerActions() {
    equoEventHandler.on(namespace + "_disposeEditor", Void.class, runnable -> {
      dispose();
    });
    equoEventHandler.on(namespace + "_doSaveAs", Void.class, runnable -> {
      saveAs();
    });
    equoEventHandler.on(namespace + "_doSave", Void.class, runnable -> {
      save();
    });
    equoEventHandler.on(namespace + "_doReload", Void.class, runnable -> {
      reload();
    });
  }

  public void configRename(Consumer<Void> runnable) {
    equoEventHandler.on(namespace + "_makeRename", Void.class, runnable);
  }

  /**
   * Sends the file content to the editor in javascript.
   */
  public void sendModel(String uri, String content) {
    if (content == null) {
      File file = new File(uri);
      if (!file.exists() || !file.isFile()) {
        return;
      }
      content = equoFileSystem.readFile(file);
      if (content == null) {
        return;
      }
    }
    equoEventHandler.send(namespace + "_modelResolved" + uri, content);
  }

  /**
   * Sets a runnable to be executed when the editor asks for file content.
   */
  public void configGetModel(Consumer<String> runnable) {
    equoEventHandler.on(namespace + "_getContentOf", JsonObject.class, changes -> {
      runnable.accept(changes.get("path").getAsString());
    });
  }

  /**
   * Makes initialization of the editor.
   * @param content  initial content
   * @param filePath path of the loaded file
   * @param rootPath a root path necessary to let the language server know where
   *                 to look for references
   * @param lsp      a proxy to interact with a language server. Can be null
   */
  public void reInitialize(String content, String filePath, String rootPath, LspProxy lsp) {
    if (filePath.startsWith("file:")) {
      setFilePath(filePath.substring(5));
    } else {
      setFilePath(filePath);
    }
    String lspPath = null;
    if (lsp != null) {
      this.lspProxy = lsp;
      lspPath = "ws://127.0.0.1:" + lsp.getPort();
      this.lspProxy.startServer();
    }
    setRootPath(rootPath);

    Map<String, String> editorData = new HashMap<String, String>();
    editorData.put("text", content);
    editorData.put("name", this.filePath);
    editorData.put("lspPath", lspPath);
    if (this.rootPath != null) {
      editorData.put("rootUri", "file://" + this.rootPath);
    }
    equoEventHandler.send(this.namespace + "_doReinitialization", editorData);
  }

  /**
   * Initializes the editor.
   */
  public void initialize(String contents, String fileName, String filePath) {
    this.filePath = filePath;
    this.fileName = fileName;
    listenChangesPath();
    handleCreateEditor(contents, null, false);
  }

  protected void createEditor(String content, String filePath, LspProxy lsp) {
    this.initialContent = content;
    if (filePath.startsWith("file:")) {
      if (filePath.length() > 7 && filePath.charAt(7) == ':') {
        // Case of Windows URLs whose URIs are "file:/[LETTER]:/..."
        setFilePath(filePath.substring(6));
      } else {
        setFilePath(filePath.substring(5));
      }
    } else {
      setFilePath(filePath);
    }
    String lspPathAux = null;
    if (this.lspProxy != null) {
      this.lspProxy.stopServer();
    }
    final boolean thereIsLS = (lsp != null);
    if (thereIsLS) {
      this.lspProxy = lsp;
      lspPathAux = "ws://127.0.0.1:" + lsp.getPort();
    }
    final String lspPath = lspPathAux;
    equoEventHandler.on("_createEditor", JsonObject.class, payload -> {
      handleCreateEditor(content, lspPath, thereIsLS);
    });
  }

  protected String getLspServerForFile(String fileName) {
    String extension = "";
    int i = fileName.lastIndexOf('.');
    if (i > 0) {
      extension = fileName.substring(i + 1);
    }
    List<String> lspProgram = lspServers.getOrDefault(extension, null);
    if (lspProgram != null) {
      this.lspProxy = new CommonLspProxy(lspProgram);
      return "ws://127.0.0.1:" + this.lspProxy.getPort();
    }
    return lspWsServers.getOrDefault(extension, null);
  }

  protected void handleCreateEditor(String contents, String fixedLspPath, boolean bindEclipseLsp) {
    String lspPath = (fixedLspPath != null) ? fixedLspPath : getLspServerForFile(this.fileName);
    if (lspPath != null && this.lspProxy != null) {
      try {
        new Thread(() -> lspProxy.startServer()).start();
      } catch (Exception e) {
        logger.error("Error starting lsp proxy", e);
      }
    }
    Map<String, Object> editorData = new HashMap<>();
    editorData.put("text", contents);
    editorData.put("name", this.filePath);
    editorData.put("namespace", namespace);
    editorData.put("lspPath", lspPath);
    editorData.put("bindEclipseLsp", bindEclipseLsp);
    if (this.rootPath != null) {
      editorData.put("rootUri", "file://" + this.rootPath);
    }
    equoEventHandler.send("_doCreateEditor", editorData);
    loaded = true;
    for (Consumer<Void> onLoadListener : onLoadListeners) {
      onLoadListener.accept(null);
    }
    onLoadListeners.clear();

  }

  protected void addOnLoadListener(Consumer<Void> listener) {
    if (!loaded) {
      onLoadListeners.add(listener);
    } else {
      listener.accept(null);
    }
  }

  /**
   * Gets the editor contents synchronously.
   * @return editor contents
   */
  public String getContentsSync() {
    if (!loaded) {
      return this.initialContent;
    }
    String[] result = { null };
    if (lock.tryAcquire()) {
      equoEventHandler.on(namespace + "_doGetContents", JsonObject.class, contents -> {
        try {
          result[0] = contents.get("contents").getAsString();
        } finally {
          synchronized (lock) {
            lock.notify();
            lock.release();
          }
        }
      });

      equoEventHandler.send(namespace + "_getContents");
      synchronized (lock) {
        try {
          lock.wait();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
    return result[0];
  }

  /**
   * Gets the editor contents asynchronously.
   * @param runnable will be runned with the editor content passed by parameter
   *                 once obtained.
   */
  public void getContentsAsync(Consumer<String> runnable) {
    equoEventHandler.on(namespace + "_doGetContents", JsonObject.class, contents -> {
      runnable.accept(contents.get("contents").getAsString());
    });
    equoEventHandler.send(namespace + "_getContents");
  }

  public void handleAfterSave() {
    equoEventHandler.send(namespace + "_didSave");
  }

  protected void notifyFilePathChanged() {
    Map<String, String> payload = new HashMap<>();
    payload.put("filePath", filePath);
    payload.put("fileName", new File(filePath).getName());
    equoEventHandler.send(namespace + "_filePathChanged", payload);
  }

  public void undo() {
    equoEventHandler.send(namespace + "_undo");
  }

  public void redo() {
    equoEventHandler.send(namespace + "_redo");
  }

  public void copy() {
    equoEventHandler.send(namespace + "_doCopy");
  }

  public void cut() {
    equoEventHandler.send(namespace + "_doCut");
  }

  public void find() {
    equoEventHandler.send(namespace + "_doFind");
  }

  /**
   * Executes paste action.
   */
  public void paste() {
    if (browser != null) {
      browser.setFocus();
    }
    equoEventHandler.send(namespace + "_doPaste");
  }

  /**
   * Executes selectAll action.
   */
  public void selectAll() {
    if (browser != null) {
      browser.setFocus();
    }
    equoEventHandler.send(namespace + "_doSelectAll");
  }

  /**
   * Opens a dialog asking for a file path and save the content in there.
   */
  public void saveAs() {
    if (equoFileSystem != null) {
      getContentsAsync(content -> {
        Display.getDefault().asyncExec(() -> {
          File file = equoFileSystem.saveFileAs(content);
          if (file != null) {
            filePath = file.getAbsolutePath();
            notifyFilePathChanged();
            handleAfterSave();
            listenChangesPath();
          }
        });
      });
    }
  }

  /**
   * Save editor content in the current file.
   */
  public void save() {
    if (filePath == null || filePath.trim().equals("")) {
      saveAs();
    } else if (equoFileSystem != null) {
      getContentsAsync(content -> {
        Display.getDefault().asyncExec(() -> {
          if (equoFileSystem.saveFile(new File(filePath), content)) {
            handleAfterSave();
          }
        });
      });
    }
  }

  protected boolean registerFileToListen() {
    fileName = Paths.get(filePath).getFileName().toString();
    Path parent = Paths.get(filePath).getParent();
    if (parent == null) {
      return false;
    }
    Path path = Paths.get(parent.toString());
    try {
      watchService = FileSystems.getDefault().newWatchService();
      path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
    return true;
  }

  protected void listenChangesPath() {
    if (filePath == null || filePath.equals("")) {
      return;
    }

    if (watchService != null) {
      try {
        watchService.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    if (!registerFileToListen()) {
      return;
    }

    new Thread() {
      public void run() {
        boolean poll = true;

        while (poll && !dispose) {
          WatchKey key = null;
          try {
            key = watchService.take();
          } catch (InterruptedException e) {
            e.printStackTrace();
          } catch (ClosedWatchServiceException e) {
            break;
          }
          for (WatchEvent<?> event : key.pollEvents()) {
            if (event.context().toString().trim().equals(fileName)) {
              reportChanges();
            }
          }
          poll = key.reset();
        }
      }
    }.start();
  }

  protected void reportChanges() {
    getContentsAsync(content -> {
      Display.getDefault().asyncExec(() -> {
        String fileContent = getFileContent();
        if (fileContent == null || !content.trim().equals(fileContent.trim())) {
          equoEventHandler.send(namespace + "_reportChanges");
        }
      });
    });
  }

  private String getFileContent() {
    if (filePath != null && !filePath.equals("") && equoFileSystem != null) {
      return equoFileSystem.readFile(new File(filePath));
    }
    return null;
  }

  protected void reload() {
    String content = getFileContent();
    if (content != null) {
      equoEventHandler.send(namespace + "_reload", content);
    }
  }

  /**
   * Makes a selection from the given {@code offset} until
   * {@code offset + length}.
   */
  public void selectAndReveal(int offset, int length) {
    Map<String, Integer> data = new HashMap<>();
    data.put("offset", offset);
    data.put("length", length);
    if (loaded) {
      equoEventHandler.send(namespace + "_selectAndReveal", data);
    } else {
      addOnLoadListener(runnable -> {
        equoEventHandler.send(namespace + "_selectAndReveal", data);
      });
    }
  }

  /**
   * Sets a listener for selection changes.
   * @param selectionFunction runnable to be runned with the new selection passed
   *                          by parameter.
   */
  public void configSelection(Consumer<TextSelection> selectionFunction) {
    equoEventHandler.on(namespace + "_selection", JsonObject.class, contents -> {
      TextSelection textSelection =
          new TextSelection(contents.get("offset").getAsInt(), contents.get("length").getAsInt());
      selectionFunction.accept(textSelection);
    });
  }

  public void configFindAllReferences(Consumer<Void> handler) {
    equoEventHandler.on(namespace + "_findAllReferences", Void.class, handler);
  }

  /**
   * Sets listeners for common changes.
   */
  public void subscribeChanges(Consumer<Boolean> dirtyListener, Consumer<Boolean> undoListener,
      Consumer<Boolean> redoListener, Consumer<String> contentChangeListener) {
    equoEventHandler.on(namespace + "_changesNotification", JsonObject.class, changes -> {
      dirtyListener.accept(changes.get("isDirty").getAsBoolean());
      undoListener.accept(changes.get("canUndo").getAsBoolean());
      redoListener.accept(changes.get("canRedo").getAsBoolean());
      contentChangeListener.accept(changes.get("content").getAsString());
    });

    if (loaded) {
      equoEventHandler.send(namespace + "_subscribeModelChanges");
    } else {
      addOnLoadListener(runnable -> {
        equoEventHandler.send(namespace + "_subscribeModelChanges");
      });
    }
  }

  /**
   * Adds a lsp websocket server to be used by the editors on the files with the
   * given extensions.
   * @param fullServerPath The full path to the lsp server. Example:
   *                       ws://127.0.0.1:3000/lspServer
   * @param extensions     A collection of extensions for what the editor will use
   *                       the given lsp server. The extensions must not have the
   *                       initial dot. Example: ["php", "php4"]
   */
  public static void addLspWsServer(String fullServerPath, Collection<String> extensions) {
    for (String extension : extensions) {
      lspWsServers.put(extension, fullServerPath);
    }
  }

  /**
   * Adds a lsp server to be used by the editors on the files with the given
   * extensions.
   * @param executionParameters The parameters needed to start the lsp server
   *                            through stdio. Example: ["html-languageserver",
   *                            "--stdio"]
   * @param extensions          A collection of extensions for what the editor
   *                            will use the given lsp server. The extensions must
   *                            not have the initial dot. Example: ["php", "php4"]
   */
  public static void addLspServer(List<String> executionParameters, Collection<String> extensions) {
    for (String extension : extensions) {
      lspServers.put(extension, executionParameters);
    }
  }

  /**
   * Removes a lsp server assigned to the given extensions.
   * @param extensions A collection of the file extensions for which the
   *                   previously assigned lsp will be removed The extensions must
   *                   not have the initial dot. Example: ["php", "php4"]
   */
  public static void removeLspServer(Collection<String> extensions) {
    for (String extension : extensions) {
      lspServers.remove(extension);
      lspWsServers.remove(extension);
    }
  }

  /**
   * Disposes the editor. Stops the lsp proxy if it was running.
   */
  public void dispose() {
    if (lspProxy != null) {
      lspProxy.stopServer();
    }
    dispose = true;
  }

  /**
   * Sets new content into the editor.
   * @param content The new content to be setted
   * @param asEdit  If true, the content will be setted as an edition and an Undo
   *                operation will be available. If false, the content will be
   *                setted without Undo stack, as if it was the first content
   *                loaded
   */
  public void setContent(String content, boolean asEdit) {
    Map<String, Object> response = new HashMap<>();
    response.put("content", content);
    response.put("asEdit", asEdit);
    if (loaded) {
      equoEventHandler.send(namespace + "_setContent", response);
    } else {
      addOnLoadListener(runnable -> {
        equoEventHandler.send(namespace + "_setContent", response);
      });
    }
  }

}
