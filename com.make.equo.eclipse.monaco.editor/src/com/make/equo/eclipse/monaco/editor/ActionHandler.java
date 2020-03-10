package com.make.equo.eclipse.monaco.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;

public class ActionHandler extends AbstractHandler {
	private boolean enable;
	private Runnable execution;
	private boolean alwaysEnable;
	
	ActionHandler(ISelectionProvider selectionProvider, Runnable execution){
		this(selectionProvider, execution, false);
	}
	
	ActionHandler(ISelectionProvider selectionProvider, Runnable execution, boolean alwaysEnable){
		selectionProvider.addSelectionChangedListener(new MyListener());
		enable = false;
		this.execution = execution;
		this.alwaysEnable = alwaysEnable;
	}
	
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		execution.run();
		return null;
	}
	
	@Override
	public boolean isEnabled() {
		return alwaysEnable || enable;
	}

	private class MyListener implements ISelectionChangedListener {

		@Override
		public void selectionChanged(SelectionChangedEvent e) {
			enable = !e.getSelection().isEmpty();
		}

	}
}