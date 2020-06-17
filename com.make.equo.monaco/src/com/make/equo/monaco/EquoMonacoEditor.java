package com.make.equo.monaco;

import static com.make.equo.monaco.util.IMonacoConstants.EQUO_MONACO_CONTRIBUTION_NAME;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import org.eclipse.swt.chromium.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.google.gson.JsonObject;
import com.make.equo.filesystem.api.IEquoFileSystem;
import com.make.equo.monaco.lsp.LspProxy;
import com.make.equo.ws.api.IEquoEventHandler;
import com.make.equo.ws.api.IEquoRunnable;

public class EquoMonacoEditor {
	protected IEquoFileSystem equoFileSystem;

	private static LspProxy lspProxy = new LspProxy();
	private static Map<String, String> lspServers = new HashMap<>();

	private volatile boolean loaded;

	private final Semaphore lock = new Semaphore(1);

	private Browser browser;
	private String namespace;
	private List<IEquoRunnable<Void>> onLoadListeners;
	protected String filePath = "";
	private boolean dispose = false;
	private String fileName ="";

	public String getFilePath() {
		return filePath;
	}

	protected IEquoEventHandler equoEventHandler;

	public EquoMonacoEditor(Composite parent, int style, IEquoEventHandler handler) {
		this(handler);
		browser = new Browser(parent, style);
		browser.setUrl("http://" + EQUO_MONACO_CONTRIBUTION_NAME + "?namespace=" + namespace);
	}
	
	public EquoMonacoEditor(IEquoEventHandler handler, IEquoFileSystem equoFileSystem) {
		this(handler);
		this.equoFileSystem = equoFileSystem;
	}

	public EquoMonacoEditor(IEquoEventHandler handler) {
		this.equoEventHandler = handler;
		namespace = "editor" + Double.toHexString(Math.random());
		onLoadListeners = new ArrayList<IEquoRunnable<Void>>();
		loaded = false;
		registerActions();
	}

	private void registerActions() {
		equoEventHandler.on(namespace + "_disposeEditor", (IEquoRunnable<Void>) runnable -> dispose());
		equoEventHandler.on(namespace + "_doSaveAs", (IEquoRunnable<Void>) runnable -> saveAs());
		equoEventHandler.on(namespace + "_doSave", (IEquoRunnable<Void>) runnable -> save());
		equoEventHandler.on(namespace + "_doReload", (IEquoRunnable<Void>) runnable -> reload());
	}

	public void initialize(String contents, String fileName, String filePath) {
		this.filePath = filePath;
		this.fileName = fileName;
		handleCreateEditor(contents, fileName);
	}

	protected void createEditor(String contents, String fileName) {
		equoEventHandler.on("_createEditor", (JsonObject payload) -> handleCreateEditor(contents, fileName));
	}

	protected String getLspServerForFile(String fileName) {
		String extension = "";
		int i = fileName.lastIndexOf('.');
		if (i > 0) {
			extension = fileName.substring(i + 1);
		}
		return lspServers.getOrDefault(extension, null);
	}

	protected void handleCreateEditor(String contents, String fileName) {
		new Thread(() -> lspProxy.startServer()).start();
		Map<String, String> editorData = new HashMap<String, String>();
		editorData.put("text", contents);
		editorData.put("name", fileName);
		editorData.put("namespace", namespace);
		editorData.put("lspPath", getLspServerForFile(fileName));
		equoEventHandler.send("_doCreateEditor", editorData);
		loaded = true;
		for (IEquoRunnable<Void> onLoadListener : onLoadListeners) {
			onLoadListener.run(null);
		}

		equoEventHandler.on(namespace + "_canPaste", (IEquoRunnable<Void>) runnable -> {
			try {
				Robot robot = new Robot();
				// Simulate a key press
				robot.keyPress(KeyEvent.VK_CONTROL);
				robot.keyPress(KeyEvent.VK_V);
				robot.keyRelease(KeyEvent.VK_V);
				robot.keyRelease(KeyEvent.VK_CONTROL);
			} catch (AWTException e) {
				e.printStackTrace();
			}
		});

		equoEventHandler.on(namespace + "_canSelectAll", (IEquoRunnable<Void>) runnable -> {
			try {
				Robot robot = new Robot();
				// Simulate a key press
				robot.keyPress(KeyEvent.VK_CONTROL);
				robot.keyPress(KeyEvent.VK_A);
				robot.keyRelease(KeyEvent.VK_A);
				robot.keyRelease(KeyEvent.VK_CONTROL);
			} catch (AWTException e) {
				e.printStackTrace();
			}
		});
		if (!fileName.equals("")) {
			new Thread() {
				public void run() {
					listenChangesPath();
				}
			}.start();;
		}
		
	}

	public void addOnLoadListener(IEquoRunnable<Void> listener) {
		if (!loaded) {
			onLoadListeners.add(listener);
		} else {
			listener.run(null);
		}
	}

	public void getContentsSync(IEquoRunnable<String> runnable) {
		if (lock.tryAcquire()) {
			equoEventHandler.on(namespace + "_doGetContents", (JsonObject contents) -> {
				try {
					runnable.run(contents.get("contents").getAsString());
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
	}

	public void getContentsAsync(IEquoRunnable<String> runnable) {
		equoEventHandler.on(namespace + "_doGetContents", (JsonObject contents) -> {
			runnable.run(contents.get("contents").getAsString());
		});
		equoEventHandler.send(namespace + "_getContents");
	}

	public void handleAfterSave() {
		equoEventHandler.send(namespace + "_didSave");
	}

	public void notifyFilePathChanged() {
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

	public void paste() {
		if (browser != null)
			browser.setFocus();
		equoEventHandler.send(namespace + "_doPaste");
	}

	public void selectAll() {
		if (browser != null)
			browser.setFocus();
		equoEventHandler.send(namespace + "_doSelectAll");
	}

	public void saveAs() {
		getContentsAsync(content -> {
			Display.getDefault().asyncExec(() -> {
				File file = equoFileSystem.saveFileAs(content);
				if (file != null) {
					filePath = file.getAbsolutePath();
					handleAfterSave();
				}
			});
		});
	}

	public void save() {
		if (filePath == null || filePath.trim().equals("")) {
			saveAs();
		} else {
			getContentsAsync(content -> {
				Display.getDefault().asyncExec(() -> {
					if (equoFileSystem.saveFile(new File(filePath), content)) {
						handleAfterSave();
					}
				});
			});
		}
	}
	
	public void listenChangesPath() {
		WatchService watchService = null;
		Path path = Paths.get(filePath);
		boolean poll = true;
		try {
			watchService = FileSystems.getDefault().newWatchService();
			path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		while (poll && !dispose) {
			WatchKey key = null;
			try {
				key = watchService.take();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			for (WatchEvent<?> event : key.pollEvents()) {
				if (event.context().toString().trim().equals(fileName)) {
					reportChanges();
				}
			}
			poll = key.reset();
		}
	}
	
	public void reportChanges() {
		equoEventHandler.send(namespace + "_reportChanges");
	}
	
	public void reload() {
		Path filePath = FileSystems.getDefault().getPath(this.filePath+"/"+fileName);
		String content = "";
		try {
			content = Files.lines(filePath).collect(Collectors.joining("\n"));
			equoEventHandler.send(namespace + "_doReload", content);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void configSelection(IEquoRunnable<Boolean> selectionFunction) {
		equoEventHandler.on(namespace + "_selection", (JsonObject contents) -> {
			selectionFunction.run(contents.get("endColumn").getAsInt() != contents.get("startColumn").getAsInt()
					|| contents.get("endLineNumber").getAsInt() != contents.get("startLineNumber").getAsInt());
		});
	}

	public void subscribeChanges(IEquoRunnable<Boolean> dirtyListener, IEquoRunnable<Boolean> undoListener,
			IEquoRunnable<Boolean> redoListener) {
		equoEventHandler.on(namespace + "_changesNotification", (JsonObject changes) -> {
			dirtyListener.run(changes.get("isDirty").getAsBoolean());
			undoListener.run(changes.get("canUndo").getAsBoolean());
			redoListener.run(changes.get("canRedo").getAsBoolean());
		});

		if (loaded) {
			equoEventHandler.send(namespace + "_subscribeModelChanges");
		} else {
			addOnLoadListener((IEquoRunnable<Void>) runnable -> {
				equoEventHandler.send(namespace + "_subscribeModelChanges");
			});
		}
	}

	/**
	 * Add a lsp websocket server to be used by the editors on the files with the
	 * given extensions
	 * 
	 * @param fullServerPath The full path to the lsp server. Example:
	 *                       ws://127.0.0.1:3000/lspServer
	 * @param extensions     A collection of extensions for what the editor will use
	 *                       the given lsp server. The extensions must not have the
	 *                       initial dot. Example: ["php", "php4"]
	 */
	public static void addLspWsServer(String fullServerPath, Collection<String> extensions) {
		for (String extension : extensions) {
			lspServers.put(extension, fullServerPath);
		}
	}

	/**
	 * Add a lsp server to be used by the editors on the files with the given
	 * extensions
	 * 
	 * @param executionParameters The parameters needed to start the lsp server
	 *                            through stdio. Example: ["html-languageserver",
	 *                            "--stdio"]
	 * @param extensions          A collection of extensions for what the editor
	 *                            will use the given lsp server. The extensions must
	 *                            not have the initial dot. Example: ["php", "php4"]
	 */
	public static void addLspServer(List<String> executionParameters, Collection<String> extensions) {
		for (String extension : extensions) {
			lspProxy.addServer(extension, executionParameters);
			addLspWsServer("ws://127.0.0.1:" + lspProxy.getPort() + "/" + extension, Collections.singleton(extension));
		}
	}

	/**
	 * Remove a lsp server assigned to the given extensions
	 * 
	 * @param extensions A collection of the file extensions for which the
	 *                   previously assigned lsp will be removed The extensions must
	 *                   not have the initial dot. Example: ["php", "php4"]
	 */
	public static void removeLspServer(Collection<String> extensions) {
		lspProxy.removeServer(extensions);
		for (String extension : extensions) {
			lspServers.remove(extension);
		}
	}

	public void dispose() {
		lspProxy.stopServer();
		dispose = true;
	}

}
