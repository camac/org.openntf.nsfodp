package org.openntf.nsfodp.eclipse.resources;

import java.text.Collator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerSorter;

@SuppressWarnings("deprecation")
public class DesignElementsSorter extends ViewerSorter {
	public DesignElementsSorter() {
	}

	public DesignElementsSorter(Collator collator) {
		super(collator);
	}
	
	@Override
	public int category(Object element) {
		if (element instanceof AbstractDesignElementResource) {
			return 0;
		}
		return 1;
	}
	
	@Override
	public int compare(Viewer viewer, Object e1, Object e2) {
		if (e1 instanceof AbstractDesignElementResource && e2 instanceof IResource) {
			return -1;
		} else if (e2 instanceof AbstractDesignElementResource && e1 instanceof IResource) {
			return 1;
		}
		if (e1 instanceof IFolder && e2 instanceof IFile) {
			return -1;
		} else if (e2 instanceof IFolder && e1 instanceof IFile) {
			return 1;
		}
		if(e1 instanceof AbstractDesignElementResource && e2 instanceof AbstractDesignElementResource) {
			return Integer.compare(((AbstractDesignElementResource)e1).getIndex(), ((AbstractDesignElementResource)e2).getIndex());
		}

		return super.compare(viewer, e1, e2);
	}
}
