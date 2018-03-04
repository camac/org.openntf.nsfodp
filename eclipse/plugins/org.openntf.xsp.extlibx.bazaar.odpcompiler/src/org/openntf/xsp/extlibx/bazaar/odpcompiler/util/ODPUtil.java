package org.openntf.xsp.extlibx.bazaar.odpcompiler.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IExtensionRegistry;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.w3c.dom.Document;

import com.ibm.commons.util.io.StreamUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;
import com.ibm.designer.runtime.domino.bootstrap.util.StringUtil;
import com.ibm.xsp.extlib.javacompiler.JavaSourceClassLoader;

public enum ODPUtil {
	;
	
	public static String readFile(Path path) {
		try(InputStream is = new FileInputStream(path.toFile())) {
			return StreamUtil.readString(is);
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}
	public static String readFile(File file) {
		return readFile(file.toPath());
	}
	
	public static Document readXml(File file) {
		try(InputStream is = new FileInputStream(file)) {
			return DOMUtil.createDocument(is);
		} catch(IOException | XMLException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static String toJavaClassName(Path path) {
		String name = path.toString();
		return name.substring(0, name.length()-JavaSourceClassLoader.JAVA_EXTENSION.length()).replace(File.separatorChar, '.');
	}
	
	public static List<Path> listJavaFiles(File baseDir) {
		try {
			return Files.find(baseDir.toPath(), Integer.MAX_VALUE,
					(path, attr) -> path.toString().endsWith(".java") && attr.isRegularFile())
					.collect(Collectors.toList());
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Retrieves the temporary user token for the provided extension registry.
	 * 
	 * <p>This method uses reflection with the assumption that the implementing
	 * class has a "getTemporaryUserToken()" method.</p>
	 * 
	 * @param reg the registry to get the token for
	 * @return the registry's user token
	 */
	public static Object getTemporaryUserToken(IExtensionRegistry reg) {
		try {
			Method getTemporaryUserToken = reg.getClass().getMethod("getTemporaryUserToken");
			Object token = getTemporaryUserToken.invoke(reg);
			return token;
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Gathers a full dependency hierarchy for the bundles described in {@code bundleIds}.
	 * 
	 * @param bundleContext the bundle context to use for resolution
	 * @param bundleIds the base bundle IDs
	 * @return a list of bundle IDs for the provided bundles and all re-exported dependencies
	 */
	public static Collection<String> expandRequiredBundles(BundleContext bundleContext, List<String> bundleIds) {
		Objects.requireNonNull(bundleContext);
		return Objects.requireNonNull(bundleIds).stream()
			.map(ODPUtil::findBundle)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(bundle -> {
				if(bundle.getState() == Bundle.INSTALLED) {
					throw new IllegalStateException("Required bundle " + bundle.getSymbolicName() + " is in INSTALLED state");
				}
				
				List<Bundle> deps = new ArrayList<>(resolveRequiredBundles(bundleContext, bundle));
				deps.add(bundle);
				return deps;
			})
			.flatMap(Collection::stream)
			.filter(Objects::nonNull)
			.map(Bundle::getSymbolicName)
			.collect(Collectors.toSet());
	}
	
	private static List<Bundle> resolveRequiredBundles(BundleContext bundleContext, Bundle bundle) {
		String requiredBundles = bundle.getHeaders().get("Require-Bundle");
		if(StringUtil.isNotEmpty(requiredBundles)) {
			String[] requires = StringUtil.splitString(requiredBundles, ',', true);
			return Arrays.stream(requires)
				.filter(req -> req.contains("visibility:=reexport"))
				.map(req -> req.substring(0, req.indexOf(';')))
				.map(ODPUtil::findBundle)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.map(dependency -> {
					if(dependency.getState() == Bundle.INSTALLED) {
						throw new IllegalStateException("Required bundle " + dependency.getSymbolicName() + " is in INSTALLED state");
					}
					
					List<Bundle> deps = new ArrayList<>(resolveRequiredBundles(bundleContext, dependency));
					deps.add(dependency);
					return deps;
				})
				.flatMap(Collection::stream)
				.collect(Collectors.toList());
		} else {
			return Collections.emptyList();
		}
	}
	
	public static Optional<Bundle> findBundle(String bundleId) {
		return Optional.ofNullable(org.eclipse.core.runtime.Platform.getBundle(bundleId));
	}
}