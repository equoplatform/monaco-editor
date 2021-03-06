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

package com.equo.eclipse.monaco.editor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.function.Consumer;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerWrapper;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.Saveable;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.dialogs.SaveAsDialog;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.internal.DefaultSaveable;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.equo.eclipse.monaco.lsp.EclipseLspProxy;
import com.equo.monaco.EquoMonacoEditor;
import com.equo.monaco.lsp.LspProxy;
import com.equo.server.api.IEquoServer;

/**
 * An editor part that contains the Equo Editor inside. It intermediates between
 * the editor and the Eclipse app model.
 */
public class MonacoEditorPart extends AbstractTextEditor {
  private static final String GENERICEDITOR_FIND_REFERENCES =
      "org.eclipse.ui.genericeditor.findReferences";

  protected static final Logger logger = LoggerFactory.getLogger(MonacoEditorPart.class);

  @Reference
  private EquoMonacoEditorWidgetBuilder monacoBuilder;

  private volatile boolean isDirty = false;

  private EquoMonacoEditor editor;
  private IDocumentProvider documentProvider;

  private ISelectionProvider selectionProvider = new MonacoEditorSelectionProvider();

  private EditorAction undoAction;
  private EditorAction redoAction;
  private EditorAction selectAllAction;
  private EditorAction copyAction;
  private EditorAction cutAction;
  private EditorAction pasteAction;
  private EditorAction findAction;

  private IFileBufferListener ownFileBufferListener = null;
  private IDocumentListener ownDocumentListener = null;
  private IDocument ownDocument = null;

  private ITextFileBuffer fileBuffer;
  private boolean reload = true;

  @Override
  public void doSave(IProgressMonitor monitor) {
    String editorContents = editor.getContentsSync();
    IEditorInput input = getEditorInput();
    if (input instanceof FileEditorInput) {
      Display.getDefault().asyncExec(() -> {
        try {
          ((FileEditorInput) input).getFile().setContents(
              new ByteArrayInputStream(editorContents.getBytes(Charset.forName("UTF-8"))), true,
              false, monitor);
          editor.handleAfterSave();
        } catch (CoreException e) {
          logger.error("Error storing new content in file", e);
        }
      });
    }

  }

  @Override
  public void doSaveAs() {
    Shell shell = PlatformUI.getWorkbench().getModalDialogShellProvider().getShell();
    final IEditorInput input = getEditorInput();
    final IEditorInput newInput;

    SaveAsDialog dialog = new SaveAsDialog(shell);

    IFile original =
        (input instanceof IFileEditorInput) ? ((IFileEditorInput) input).getFile() : null;
    if (original != null) {
      dialog.setOriginalFile(original);
    } else {
      dialog.setOriginalName(input.getName());
    }

    dialog.create();

    if (dialog.open() == Window.CANCEL) {
      return;
    }

    IPath filePath = dialog.getResult();
    IWorkspace workspace = ResourcesPlugin.getWorkspace();
    IFile file = workspace.getRoot().getFile(filePath);
    newInput = new FileEditorInput(file);
    try {
      file.getLocation().toFile().createNewFile();
      file.getParent().refreshLocal(1, new NullProgressMonitor());
    } catch (IOException | CoreException e) {
      logger.error("Failure creating destination file", e);
      return;
    }

    setInputOnEditorPart(newInput);
    initializeNewInput(newInput);
    editor.setFilePath(((FileEditorInput) newInput).getPath().toString());
    doSave(new NullProgressMonitor());
  }

  protected void setInputOnEditorPart(IEditorInput input) {
    if (input != null) {
      try {
        Field f1 = this.getClass().getSuperclass().getSuperclass().getDeclaredField("editorInput");
        f1.setAccessible(true);
        f1.set(this, input);
      } catch (IllegalAccessException | IllegalArgumentException | SecurityException
          | NoSuchFieldException e) {
        logger.error("Couldn't set input to the editor", e);
      }
    }
  }

  protected void setSourceViewer() {
    try {
      Field f1 = this.getClass().getSuperclass().getDeclaredField("fSourceViewer");
      f1.setAccessible(true);
      f1.set(this, new MonacoSourceViewer(this));
    } catch (IllegalAccessException | IllegalArgumentException | SecurityException
        | NoSuchFieldException e) {
      logger.error("Couldn't set source viewer", e);
    }
  }

  @Override
  public Saveable[] getSaveables() {
    return new Saveable[] { new DefaultSaveable(this) };
  }

  @Override
  public void init(IEditorSite site, IEditorInput input) throws PartInitException {
    setInputOnEditorPart(input);
    setSite(site);
  }

  @Override
  public boolean isDirty() {
    return isDirty;
  }

  @Override
  public boolean isSaveAsAllowed() {
    return true;
  }

  private String getRootPath(IFile file) {
    return file.getProject().getLocation().toString();
  }

  private void initializeNewInput(IEditorInput input) {
    Display.getDefault().syncExec(() -> {
      setPartName(input.getName());
      if (input instanceof FileEditorInput) {
        final FileEditorInput fileInput = (FileEditorInput) input;
        setTitleToolTip(fileInput.getPath().toString());
        IFile file = fileInput.getFile();
        registerFileBufferListener(file);
        ownDocument = null;
        fileBuffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(),
            LocationKind.IFILE);
        if (fileBuffer != null) {
          registerDocumentListener(fileBuffer.getDocument());
        }
        ResourcesPlugin.getWorkspace().addResourceChangeListener(new IResourceChangeListener() {
          @Override
          public void resourceChanged(final IResourceChangeEvent event) {
            IResourceDelta delta = event.getDelta();

            delta = delta.findMember(fileInput.getFile().getFullPath());
            if (delta == null) {
              return;
            }

            if (delta.getKind() == IResourceDelta.REMOVED) {
              if ((delta.getFlags() & IResourceDelta.MOVED_TO) != 0) {
                IWorkspace workspace = ResourcesPlugin.getWorkspace();
                workspace.removeResourceChangeListener(this);
                IPath newPath = delta.getMovedToPath();
                IFile file = workspace.getRoot().getFile(newPath);
                FileEditorInput newInput = new FileEditorInput(file);
                setInputOnEditorPart(newInput);
                initializeNewInput(newInput);
                LspProxy lspProxy = getLspProxy(file);
                try (InputStream contents = file.getContents()) {
                  int singleByte;
                  ByteArrayOutputStream baos = new ByteArrayOutputStream();
                  while ((singleByte = contents.read()) != -1) {
                    baos.write(singleByte);
                  }
                  String textContent = new String(baos.toByteArray());
                  if (fileBuffer != null) {
                    String textContentFileBuffer = ownDocument.get();
                    if (!textContentFileBuffer.equals(textContent)) {
                      textContent = textContentFileBuffer;
                    }
                  }
                  editor.reInitialize(textContent, newInput.getPath().toString(), getRootPath(file),
                      lspProxy);
                } catch (IOException | CoreException e) {
                  logger.error("Couldn't read file content", e);
                }
              }
            }
          }

        });
      }
    });
  }

  @Override
  public void createPartControl(Composite parent) {
    IEditorInput input = getEditorInput();
    setSourceViewer();
    if (input instanceof FileEditorInput) {
      FileEditorInput fileInput = (FileEditorInput) input;
      setTitleToolTip(fileInput.getPath().toString());
      IFile file = fileInput.getFile();
      initializeNewInput(input);
      LspProxy lspProxy = getLspProxy(file);

      try (InputStream contents = file.getContents()) {
        int singleByte;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while ((singleByte = contents.read()) != -1) {
          baos.write(singleByte);
        }
        String textContent = new String(baos.toByteArray());
        boolean setContentDirty = false;
        if (fileBuffer != null) {
          String textContentFileBuffer = ownDocument.get();
          if (!textContentFileBuffer.equals(textContent)) {
            textContent = textContentFileBuffer;
            setContentDirty = true;
          }
        }

        try {
          BundleContext bndContext =
              FrameworkUtil.getBundle(EquoMonacoEditorWidgetBuilder.class).getBundleContext();
          activateNeededServices(bndContext);

          ServiceReference<EquoMonacoEditorWidgetBuilder> svcReference =
              bndContext.getServiceReference(EquoMonacoEditorWidgetBuilder.class);

          EquoMonacoEditorWidgetBuilder builder = bndContext.getService(svcReference);
          editor = builder.withParent(parent).withStyle(parent.getStyle()).withContents(textContent)
              .withFilePath(fileInput.getURI().toString()).withLsp(lspProxy)
              .withRootPath(getRootPath(file)).create();
          documentProvider = new MonacoEditorDocumentProvider(editor);
          editorConfigs();
          if (setContentDirty) {
            editor.setContent(textContent, false);
          }

          getSite().setSelectionProvider(selectionProvider);

          createMonacoActions();
          activateActions();
        } catch (Exception e) {
          logger.error("Couldn't retrieve Monaco Editor service", e);
        }
      } catch (CoreException | IOException e) {
        logger.error("Couldn't read file content", e);
      }

    }

  }

  private void registerDocumentListener(IDocument document) {
    if (ownDocumentListener != null) {
      ownDocument.removeDocumentListener(ownDocumentListener);
    }
    ownDocumentListener = new IDocumentListener() {
      @Override
      public void documentAboutToBeChanged(DocumentEvent event) {
      }

      @Override
      public void documentChanged(DocumentEvent event) {
        if (reload) {
          editor.setContent(fileBuffer.getDocument().get(), true);
        }
      }
    };
    document.addDocumentListener(ownDocumentListener);
    ownDocument = document;
  }

  private void registerFileBufferListener(IFile file) {
    if (ownFileBufferListener != null) {
      FileBuffers.getTextFileBufferManager().removeFileBufferListener(ownFileBufferListener);
    }
    ownFileBufferListener = new IFileBufferListener() {

      @Override
      public void bufferCreated(IFileBuffer buffer) {
        if (file.getLocation().toPortableString()
            .endsWith(buffer.getLocation().toPortableString())) {
          if (buffer instanceof ITextFileBuffer) {
            fileBuffer = (ITextFileBuffer) buffer;
            registerDocumentListener(fileBuffer.getDocument());
          }
        }
      }

      @Override
      public void bufferDisposed(IFileBuffer buffer) {
      }

      @Override
      public void bufferContentAboutToBeReplaced(IFileBuffer buffer) {
      }

      @Override
      public void bufferContentReplaced(IFileBuffer buffer) {
      }

      @Override
      public void stateChanging(IFileBuffer buffer) {
      }

      @Override
      public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) {
        if (file.getLocation().toPortableString()
            .endsWith(buffer.getLocation().toPortableString())) {
          if (reload) {
            editor.setContent(ownDocument.get(), true);
          }
        }
      }

      @Override
      public void stateValidationChanged(IFileBuffer buffer, boolean isStateValidated) {
      }

      @Override
      public void underlyingFileMoved(IFileBuffer buffer, IPath path) {
      }

      @Override
      public void underlyingFileDeleted(IFileBuffer buffer) {
      }

      @Override
      public void stateChangeFailed(IFileBuffer buffer) {
      }

    };
    FileBuffers.getTextFileBufferManager().addFileBufferListener(ownFileBufferListener);
  }

  @SuppressWarnings("unchecked")
  private void activateNeededServices(BundleContext bndContext) {
    ServiceReference<IEquoServer> serviceReference =
        (ServiceReference<IEquoServer>) bndContext.getServiceReference(IEquoServer.class.getName());
    if (serviceReference != null) {
      bndContext.getService(serviceReference);
    }
  }

  private LspProxy getLspProxy(IFile file) {
    try {
      Collection<LanguageServerWrapper> wrappers =
          LanguageServiceAccessor.getLSWrappers(file, null);
      if (!wrappers.isEmpty()) {
        LanguageServerWrapper lspServer = wrappers.iterator().next();
        lspServer.getInitializedServer();
        return new EclipseLspProxy(lspServer);
      }
    } catch (IOException e) {
      logger.error("Error obtaining language servers", e);
    }
    return null;
  }

  private void editorConfigs() {
    Consumer<Boolean> dirtyListener = isDirty -> {
      this.isDirty = isDirty;
      Display.getDefault().asyncExec(() -> {
        firePropertyChange(PROP_DIRTY);
      });
    };

    Consumer<Boolean> redoListener = canRedo -> {
      redoAction.setEnabled(canRedo);
    };
    Consumer<Boolean> undoListener = canUndo -> {
      undoAction.setEnabled(canUndo);
    };
    Consumer<String> contentChangeListener = content -> {
      if (ownDocument != null) {
        reload = false;
        ownDocument.set(content);
        reload = true;
      }
    };
    editor.subscribeChanges(dirtyListener, undoListener, redoListener, contentChangeListener);

    editor.configSelection((selection) -> {
      Display.getDefault().asyncExec(() -> {
        selectionProvider.setSelection(selection);
      });
    });
    editor.configRename((empty) -> {
      Display.getDefault().asyncExec(() -> {
        try {
          IHandlerService handlerService =
              (IHandlerService) getSite().getService(IHandlerService.class);
          handlerService.executeCommand(IWorkbenchCommandConstants.FILE_RENAME, null);
        } catch (Exception e) {
          logger.error("Error calling Renaming handler", e);
        }
      });
    });

    editor.configGetModel(uri -> {
      IResource resource = LSPEclipseUtils.findResourceFor(uri);
      if (resource != null && resource instanceof IPath) {
        IPath file = (IPath) resource;
        ITextFileBuffer buffer =
            FileBuffers.getTextFileBufferManager().getTextFileBuffer(file, LocationKind.IFILE);
        editor.sendModel(uri, buffer.getDocument().get());
      } else {
        editor.sendModel(uri, null);
      }
    });

    editor.configFindAllReferences(v -> {
      Display.getDefault().asyncExec(() -> {
        try {
          IHandlerService handlerService =
              (IHandlerService) getSite().getService(IHandlerService.class);
          handlerService.executeCommand(GENERICEDITOR_FIND_REFERENCES, null);
        } catch (Exception e) {
          logger.error("Error calling Find References handler", e);
        }
      });
    });
  }

  private void createMonacoActions() {
    undoAction = new EditorAction(() -> editor.undo());
    redoAction = new EditorAction(() -> editor.redo());
    copyAction = new EditorAction(() -> editor.copy(), selectionProvider);
    cutAction = new EditorAction(() -> editor.cut(), selectionProvider);
    pasteAction = new EditorAction(() -> editor.paste(), null, () -> {
      Clipboard clipboard = new Clipboard(Display.getCurrent());
      TextTransfer textTransfer = TextTransfer.getInstance();
      String textData = (String) clipboard.getContents(textTransfer);
      clipboard.dispose();
      return (textData != null);
    });
    findAction = new EditorAction(() -> editor.find());
    findAction.setEnabled(true);
    selectAllAction = new EditorAction(() -> editor.selectAll());
    selectAllAction.setEnabled(true);
  }

  private void activateActions() {
    IActionBars actionBars = getEditorSite().getActionBars();
    actionBars.setGlobalActionHandler(ActionFactory.UNDO.getId(), undoAction);
    actionBars.setGlobalActionHandler(ActionFactory.REDO.getId(), redoAction);
    actionBars.setGlobalActionHandler(ActionFactory.SELECT_ALL.getId(), selectAllAction);
    actionBars.setGlobalActionHandler(ActionFactory.COPY.getId(), copyAction);
    actionBars.setGlobalActionHandler(ActionFactory.CUT.getId(), cutAction);
    actionBars.setGlobalActionHandler(ActionFactory.PASTE.getId(), pasteAction);
    actionBars.setGlobalActionHandler(ActionFactory.FIND.getId(), findAction);
    actionBars.updateActionBars();
  }

  @Override
  public void setFocus() {
    getSite().setSelectionProvider(selectionProvider);
    try {
      activateActions();
    } catch (Exception e) {
      logger.error("Error activating editor actions", e);
    }
  }

  @Override
  public void dispose() {
    super.dispose();
    if (ownFileBufferListener != null) {
      FileBuffers.getTextFileBufferManager().removeFileBufferListener(ownFileBufferListener);
    }
    editor.dispose();
  }

  // ITextEditor methods:

  @Override
  public IDocumentProvider getDocumentProvider() {
    return documentProvider;
  }

  @Override
  public void close(boolean save) {
  }

  @Override
  public boolean isEditable() {
    return false;
  }

  @Override
  public void doRevertToSaved() {
  }

  @Override
  public void setAction(String actionID, IAction action) {
  }

  @Override
  public IAction getAction(String actionId) {
    return null;
  }

  @Override
  public void setActionActivationCode(String actionId, char activationCharacter,
      int activationKeyCode, int activationStateMask) {
  }

  @Override
  public void removeActionActivationCode(String actionId) {
  }

  @Override
  public boolean showsHighlightRangeOnly() {
    return false;
  }

  @Override
  public void showHighlightRangeOnly(boolean showHighlightRangeOnly) {
  }

  @Override
  public void setHighlightRange(int offset, int length, boolean moveCursor) {
  }

  @Override
  public IRegion getHighlightRange() {
    return null;
  }

  @Override
  public void resetHighlightRange() {
  }

  @Override
  public ISelectionProvider getSelectionProvider() {
    return selectionProvider;
  }

  @Override
  public void selectAndReveal(int offset, int length) {
    editor.selectAndReveal(offset, length);
  }

  @Override
  public void saveState(IMemento memento) {

  }

}
