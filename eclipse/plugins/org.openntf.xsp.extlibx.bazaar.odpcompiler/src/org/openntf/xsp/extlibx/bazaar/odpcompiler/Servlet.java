package org.openntf.xsp.extlibx.bazaar.odpcompiler;

import com.ibm.xsp.extlib.library.BazaarActivator;
import com.ibm.xsp.registry.FacesSharableRegistry;
import com.ibm.xsp.registry.SharableRegistryImpl;
import com.ibm.xsp.registry.config.XspRegistryManager;
import com.ibm.xsp.registry.config.XspRegistryProvider;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.Principal;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openntf.xsp.extlibx.bazaar.odpcompiler.odp.OnDiskProject;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.update.FilesystemUpdateSite;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.update.UpdateSite;

public class Servlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		Principal user = req.getUserPrincipal();
		resp.setBufferSize(0);
		
		OutputStream os = resp.getOutputStream();
		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType("text/plain");
		
		PrintStream out = new PrintStream(os);
		PrintStream origOut = System.out;
		System.setOut(out);
		PrintStream origErr = System.err;
		PrintStream err = new PrintStream(os);
		System.setErr(err);
		try {
			
			FacesSharableRegistry registry = createRegistry();
			System.out.println("Registry: " + registry);
			
			File odpFile = new File("H:\\Projects\\SourceTree\\endeavor\\nsf\\nsf-dashboard");
			OnDiskProject odp = new OnDiskProject(odpFile);
			File siteFile = new File("H:\\Projects\\SourceTree\\endeavor\\endeavour-plugin\\releng\\net.cmssite.endeavour60.updatesite\\target\\site");
			UpdateSite updateSite = new FilesystemUpdateSite(siteFile);
			
			ODPCompiler compiler = new ODPCompiler(BazaarActivator.instance.getBundle().getBundleContext(), odp, registry);
			compiler.addUpdateSite(updateSite);
			compiler.compile();
			
			out.println("done");
		} catch(Exception e) {
			e.printStackTrace(out);
		} finally {
			System.setOut(origOut);
			System.setErr(origErr);
			out.flush();
		}
	}
	
	private FacesSharableRegistry createRegistry() {
		SharableRegistryImpl registry = new SharableRegistryImpl(getClass().getPackage().getName());
		XspRegistryManager regMan = XspRegistryManager.getManager();
		regMan.getRegistryProviderIds().stream()
			.map(regMan::getRegistryProvider)
			.map(XspRegistryProvider::getRegistry)
			.forEach(registry::addDepend);
		return registry;
	}
}