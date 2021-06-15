package com.equo.monaco;

import com.equo.monaco.lsp.LspProxy;

/**
 * Abstract editor builder.
 */
public abstract class AbstractEquoMonacoEditorBuilder {
  protected void createEditor(EquoMonacoEditor editor, String contents, String filePath,
      LspProxy lsp) {
    editor.createEditor(contents, filePath, lsp);
  }
}
