/**
 * Copyright © 2018-2019 Jesse Gallagher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openntf.maven.nsfodp.equinox;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.repository.ComponentDependency;
import org.openntf.maven.nsfodp.Messages;

public abstract class AbstractEquinoxTask {
	private final PluginDescriptor pluginDescriptor;
	private final MavenSession mavenSession;
	private final MavenProject project;
	private final Log log;
	private final Path notesProgram;
	private final URL notesPlatform;
	
	private Collection<Path> classpathJars;
	private Map<String, String> systemProperties;

	public AbstractEquinoxTask(PluginDescriptor pluginDescriptor, MavenSession mavenSession, MavenProject project, Log log, Path notesProgram, URL notesPlatform) throws IOException {
		this.pluginDescriptor = pluginDescriptor;
		this.mavenSession = mavenSession;
		this.log = log;
		this.notesProgram = notesProgram;
		this.project = project;
		this.notesPlatform = notesPlatform;
	}
	
	protected MavenProject getProject() {
		return project;
	}

	protected void setClasspathJars(Collection<Path> classpathJars) {
		this.classpathJars = classpathJars;
	}
	
	protected void setSystemProperties(Map<String, String> properties) {
		this.systemProperties = properties;
	}
	
	protected void run(String applicationId) {
		try {
			Path equinox = getDependencyJar("org.eclipse.equinox.launcher"); //$NON-NLS-1$
			if(log.isDebugEnabled()) {
				log.debug(Messages.getString("EquinoxMojo.usingEquinoxLauncher", equinox)); //$NON-NLS-1$
			}
			
			Path javaBin = getJavaBinary(notesProgram);
			
			List<Path> classpath = new ArrayList<>();
			if(classpathJars != null) {
				classpath.addAll(classpathJars);
			}
			addIBMJars(notesProgram, classpath);
			
			if(!Files.exists(notesProgram)) {
				throw new MojoExecutionException(Messages.getString("EquinoxMojo.notesProgramDirDoesNotExist", notesProgram)); //$NON-NLS-1$
			}
			
			Path target;
			if("standalone-pom".equals(project.getArtifactId())) {
				target = Files.createTempDirectory("nsfodp");
			} else {
				target = Paths.get(project.getBuild().getDirectory());
			}
			Path framework = target.resolve("nsfodpequinox"); //$NON-NLS-1$
			if(log.isDebugEnabled()) {
				log.debug(Messages.getString("EquinoxMojo.creatingOsgi", framework)); //$NON-NLS-1$
			}
			Files.createDirectories(framework);
			
			Path plugins = framework.resolve("plugins"); //$NON-NLS-1$
			Files.createDirectories(plugins);

			List<String> platform = new ArrayList<>(Arrays.asList(
				getDependencyRef("org.openntf.nsfodp.commons", -1), //$NON-NLS-1$
				getDependencyRef("org.openntf.nsfodp.commons.dxl", -1), //$NON-NLS-1$
				getDependencyRef("org.openntf.nsfodp.commons.odp", -1), //$NON-NLS-1$
				getDependencyRef("org.openntf.nsfodp.compiler", 2), //$NON-NLS-1$
				getDependencyRef("org.openntf.nsfodp.compiler.equinox", -1), //$NON-NLS-1$
				getDependencyRef("org.openntf.nsfodp.deployment", 2), //$NON-NLS-1$
				getDependencyRef("org.openntf.nsfodp.exporter", -1), //$NON-NLS-1$
				getDependencyRef("org.openntf.nsfodp.exporter.equinox", -1), //$NON-NLS-1$
				getDependencyRef("com.ibm.xsp.extlibx.bazaar", -1), //$NON-NLS-1$
				getDependencyRef("com.ibm.xsp.extlibx.bazaar.interpreter", -1), //$NON-NLS-1$
				getDependencyRef("com.darwino.domino.napi", -1), //$NON-NLS-1$
				createJempowerShim(notesProgram),
				createClasspathExtensionBundle(classpath, plugins)
			));
			
			classpath.add(equinox);
			
			Path notesPlatform = Paths.get(this.notesPlatform.toURI());
			if(!Files.exists(notesPlatform)) {
				throw new MojoExecutionException(Messages.getString("EquinoxMojo.notesPlatformDoesNotExist", notesPlatform)); //$NON-NLS-1$
			}
			Path notesPlugins = notesPlatform.resolve("plugins"); //$NON-NLS-1$
			if(!Files.exists(notesPlugins)) {
				throw new MojoExecutionException(Messages.getString("EquinoxMojo.notesPluginsDirDoesNotExist", notesPlugins)); //$NON-NLS-1$
			}
			String[] osgiBundle = new String[1];
			Files.list(notesPlugins)
				.filter(p -> p.getFileName().toString().endsWith(".jar")) //$NON-NLS-1$
				.filter(p -> {
					if(p.getFileName().toString().startsWith("org.eclipse.osgi_")) {
						osgiBundle[0] = p.toUri().toString();
						return false;
					}
					return true;
				})
				.map(p -> "reference:" + p.toUri())
				.forEach(platform::add);
			if(osgiBundle[0] == null) {
				throw new IllegalStateException("Unable to locate org.eclipse.osgi bundle");
			}

			Path workspace = framework.resolve("workspace");
			Files.createDirectories(workspace);
			
			Path configuration = framework.resolve("configuration"); //$NON-NLS-1$
			Files.createDirectories(configuration);
			Path configIni = configuration.resolve("config.ini"); //$NON-NLS-1$
			Properties config = new Properties();
			config.put("osgi.bundles.defaultStartLevel", "4"); //$NON-NLS-1$ //$NON-NLS-2$
			config.put("osgi.bundles", String.join(",", platform)); //$NON-NLS-1$ //$NON-NLS-2$
			config.put("eclipse.application", applicationId); //$NON-NLS-1$
			config.put("osgi.configuration.cascaded", "false"); //$NON-NLS-1$ //$NON-NLS-2$
			config.put("osgi.install.area", framework.toUri().toString()); //$NON-NLS-1$
			config.put("osgi.instance.area", workspace.toAbsolutePath().toString());
			config.put("osgi.framework", osgiBundle[0]); //$NON-NLS-1$
			config.put("eclipse.log.level", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
			config.put("osgi.parentClassloader", "ext"); //$NON-NLS-1$ //$NON-NLS-2$
			config.put("osgi.classloader.define.packages", "noattributes"); //$NON-NLS-1$ //$NON-NLS-2$
			config.put("org.osgi.framework.bootdelegation", "lotus.*"); //$NON-NLS-1$ //$NON-NLS-2$
			try(OutputStream os = Files.newOutputStream(configIni, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				config.store(os, "NSF ODP OSGi Configuration"); //$NON-NLS-1$
			}
			
			List<String> command = new ArrayList<>();
			command.add(javaBin.toAbsolutePath().toString());
			command.add("-Dosgi.frameworkParentClassloader=boot"); //$NON-NLS-1$
			if(systemProperties != null) {
				for(Map.Entry<String, String> prop : systemProperties.entrySet()) {
					command.add("-D" + prop.getKey() + "=" + prop.getValue());
				}
			}
			command.add("org.eclipse.core.launcher.Main"); //$NON-NLS-1$
			command.add("-framwork"); //$NON-NLS-1$
			command.add(framework.toAbsolutePath().toString());
			command.add("-configuration"); //$NON-NLS-1$
			command.add(configuration.toAbsolutePath().toString());
			if(log.isDebugEnabled()) {
				log.debug(Messages.getString("EquinoxMojo.launchingEquinox", command.stream().collect(Collectors.joining(" "))));  //$NON-NLS-1$//$NON-NLS-2$
			}
			
			
			ProcessBuilder builder = new ProcessBuilder()
					.command(command)
					.redirectOutput(Redirect.INHERIT)
//					.redirectError(Redirect.INHERIT)
					.redirectInput(Redirect.INHERIT);
			builder.environment().put("Notes_ExecDirectory", notesProgram.toAbsolutePath().toString()); //$NON-NLS-1$
			builder.environment().put("PATH", notesProgram.toAbsolutePath().toString()); //$NON-NLS-1$
			builder.environment().put("LD_LIBRARY_PATH", notesProgram.toAbsolutePath().toString()); //$NON-NLS-1$
			builder.environment().put("DYLD_LIBRARY_PATH", notesProgram.toAbsolutePath().toString()); //$NON-NLS-1$
			builder.environment().put("JAVA_HOME", javaBin.getParent().getParent().toString()); //$NON-NLS-1$
			builder.environment().put("CLASSPATH", //$NON-NLS-1$
					classpath.stream()
					.map(path -> path.toString())
					.collect(Collectors.joining(File.pathSeparator)) //$NON-NLS-1$
			);
			
			Collection<Path> jars = initJreJars(notesProgram);
			try {
				Process proc = builder.start();
				proc.waitFor();
				int exitValue = proc.exitValue();
				if(exitValue != 0) {
					throw new RuntimeException(Messages.getString("EquinoxMojo.processExitedWithNonZero", exitValue));
				}
			} finally {
				teardownJreJars(jars);
			}
		} catch (InterruptedException e) {
			// No problem here
		} catch(Throwable e) {
			e.printStackTrace();
			throw new RuntimeException(Messages.getString("EquinoxMojo.exceptionLaunching"), e); //$NON-NLS-1$
		}
	}
	
	// *******************************************************************************
	// * Internal utility methods
	// *******************************************************************************
	
	private Path getDependencyJar(String artifactId) throws MojoExecutionException {
		List<ComponentDependency> dependencies = pluginDescriptor.getDependencies();
		ComponentDependency dep = dependencies.stream()
				.filter(a -> artifactId.equals(a.getArtifactId()))
				.findFirst()
				.orElseThrow(() -> new MojoExecutionException(Messages.getString("EquinoxMojo.couldNotFindDependency", artifactId))); //$NON-NLS-1$
		Artifact art = new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), "", dep.getType(), "", new DefaultArtifactHandler()); //$NON-NLS-1$ //$NON-NLS-2$
		art = mavenSession.getLocalRepository().find(art);
		File file = art.getFile();
		Path result;
		if(file.toString().endsWith(".jar")) { //$NON-NLS-1$
			result = file.toPath();
			if(!Files.exists(result)) {
				throw new MojoExecutionException(Messages.getString("EquinoxMojo.dependencyJarDoesNotExist", result)); //$NON-NLS-1$
			}
		} else {
			result = Paths.get(file.toString()+".jar"); //$NON-NLS-1$
		}
		if(!Files.exists(result)) {
			throw new MojoExecutionException(Messages.getString("EquinoxMojo.dependencyJarDoesNotExist", result)); //$NON-NLS-1$
		}
		return result;
	}
	
	private String getDependencyRef(String artifactId, int startLevel) throws MojoExecutionException {
		Path path = getDependencyJar(artifactId);
		if(startLevel < 1) {
			return "reference:" + path.toAbsolutePath().toUri();
		} else {
			return "reference:" + path.toAbsolutePath().toUri() + "@" + startLevel + ":start";
		}
	}
	
	private Path getJavaBinary(Path notesProgram) throws MojoExecutionException {
		// Look to see if we can find a Notes JVM
		Path jvmBin = notesProgram.resolve("jvm").resolve("bin");
		if(!Files.isDirectory(jvmBin)) {
			// macOS 10.0.1+ embedded JVM
			jvmBin = notesProgram.getParent().getParent().resolve("jre").resolve("Contents").resolve("Home").resolve("bin");
		}
		if(!Files.isDirectory(jvmBin)) {
			if(log.isWarnEnabled()) {
				log.warn("Unable to locate Notes/Domino JVM; using active JVM instead");
			}
			throw new RuntimeException("Could not find JVM at " + jvmBin);
		}
			
		
		String javaBinName;
		if(SystemUtils.IS_OS_WINDOWS) {
			javaBinName = "java.exe"; //$NON-NLS-1$
		} else {
			javaBinName = "java"; //$NON-NLS-1$
		}
		Path javaBin = jvmBin.resolve(javaBinName); //$NON-NLS-1$
		if(!Files.exists(javaBin)) {
			throw new MojoExecutionException(Messages.getString("EquinoxMojo.unableToLocateJava", javaBin)); //$NON-NLS-1$
		}
		return javaBin;
	}
	
	public static String createJempowerShim(Path notesBin) throws IOException {
		Path njempcl = notesBin.resolve("jvm").resolve("lib").resolve("ext").resolve("njempcl.jar"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		Path tempBundle = Files.createTempFile("njempcl", ".jar"); //$NON-NLS-1$ //$NON-NLS-2$
		try(OutputStream os = Files.newOutputStream(tempBundle)) {
			try(JarOutputStream jos = new JarOutputStream(os)) {
				JarEntry entry = new JarEntry("META-INF/MANIFEST.MF"); //$NON-NLS-1$
				jos.putNextEntry(entry);
				try(InputStream is = EquinoxCompiler.class.getResourceAsStream("/res/COM.ibm.JEmpower/META-INF/MANIFEST.MF")) { //$NON-NLS-1$
					copyStream(is, jos, 8192);
				}
				JarEntry njempclEntry = new JarEntry("lib/njempcl.jar"); //$NON-NLS-1$
				jos.putNextEntry(njempclEntry);
				try(InputStream is = Files.newInputStream(njempcl)) {
					copyStream(is, jos, 8192);
				}
			}
		}
		return "reference:" + tempBundle.toAbsolutePath().toUri();
	}
	
	public static String createClasspathExtensionBundle(Collection<Path> classpathJars, Path plugins) throws IOException {
		Path tempBundle = Files.createTempFile(plugins, "org.openntf.nsfodp.frameworkextension", ".jar");
		try(OutputStream os = Files.newOutputStream(tempBundle, StandardOpenOption.TRUNCATE_EXISTING)) {
			try(JarOutputStream jos = new JarOutputStream(os)) {
				JarEntry entry = new JarEntry("META-INF/MANIFEST.MF"); //$NON-NLS-1$
				jos.putNextEntry(entry);
				
				Manifest manifest = new Manifest();
				Attributes attrs = manifest.getMainAttributes();
				attrs.putValue("Manifest-Version", "1.0");
				attrs.putValue("Bundle-ManifestVersion", "2");
				attrs.putValue("Bundle-SymbolicName", "org.openntf.nsfodp.classpathprovider");
				attrs.putValue("Bundle-Version", "1.0.0." + System.currentTimeMillis());
				attrs.putValue("Bundle-Name", "NSF ODP Tooling Extended Classpath Provider");
				
				if(classpathJars != null) {
					String exportPackage = classpathJars.stream()
						.map(AbstractEquinoxTask::getPackages)
						.flatMap(Collection::stream)
						.collect(Collectors.joining(","));
					attrs.putValue("Export-Package", exportPackage);
					
					attrs.putValue("Bundle-ClassPath", classpathJars.stream()
						.map(j -> "external:" + j.toAbsolutePath())
						.collect(Collectors.joining(","))
					);
				}
				
				manifest.write(jos);
			}
		}
		return "reference:" + tempBundle.toAbsolutePath().toUri();
	}
	
    private static long copyStream(InputStream is, OutputStream os, int bufferSize) throws IOException {
		byte[] buffer = new byte[bufferSize];
		long totalBytes = 0;
		int readBytes;
		while( (readBytes = is.read(buffer))>0 ) {
			os.write(buffer, 0, readBytes);
			totalBytes += readBytes;
		}
		return totalBytes;
    }
    
    private static Collection<String> getPackages(Path jar) {
    	try {
    		Collection<String> packages = new HashSet<String>();
			try(InputStream is = Files.newInputStream(jar)) {
				try(JarInputStream jis = new JarInputStream(is)) {
					JarEntry jarEntry = jis.getNextJarEntry();
					while(jarEntry != null) {
						String name = jarEntry.getName();
						if(name.endsWith(".class") && !name.startsWith("java/")) {
							String packagePath = name.substring(0, name.lastIndexOf('/'));
							packages.add(packagePath.replace('/', '.'));
						}
						
						jarEntry = jis.getNextJarEntry();
					}
				}
			}
			return packages;
    	} catch(IOException e) {
    		throw new RuntimeException(e);
    	}
    }
    
    private void addIBMJars(Path notesProgram, Collection<Path> classpath) {
    	Path lib = notesProgram.resolve("jvm").resolve("lib");
    	
    	Path ibmPkcs = lib.resolve("ibmpkcs.jar");
    	if(!Files.isReadable(ibmPkcs)) {
    		// Different path on macOS
    		ibmPkcs = notesProgram.getParent().getParent().resolve("jre").resolve("Contents").resolve("Home").resolve("lib").resolve("endorsed").resolve("ibmpkcs.jar");
    	}
    	if(!Files.isReadable(ibmPkcs)) {
    		throw new IllegalStateException("Unable to locate ibmpkcs.jar at expected path " +  lib.resolve("ibmpkcs.jar"));
    	}
    	classpath.add(ibmPkcs);
    	
    	Path notesJar = lib.resolve("ext").resolve("Notes.jar");
    	if(!Files.isReadable(notesJar)) {
    		throw new IllegalStateException("Unable to locate Notes.jar at expected path " + notesJar);
    	}
    	classpath.add(notesJar);
    	
    	Path websvc = lib.resolve("ext").resolve("websvc.jar");
    	if(!Files.isReadable(websvc)) {
    		throw new IllegalStateException("Unable to locate websvc.jar at expected path " + websvc);
    	}
    	classpath.add(websvc);
    }
    
	private Collection<Path> initJreJars(Path notesProgram) throws MojoExecutionException, IOException {
    	// On macOS, we'll need to create some symlinks in our active JRE due to the way the ext folder works
    	if(SystemUtils.IS_OS_MAC) {
    		if(log.isDebugEnabled()) {
    			log.debug("Linking environment Jars in macOS Notes JRE");
    		}
    		
    		Path tools = SystemUtils.getJavaHome().toPath().resolve("lib").resolve("tools.jar");
    		if(!Files.exists(tools)) {
    			// Java Home might be a JRE dir - try a level up
    			tools = SystemUtils.getJavaHome().toPath().getParent().resolve("lib").resolve("tools.jar");
    		}
    		if(!Files.exists(tools)) {
    			throw new IllegalStateException("Unable to locate tools.jar in running JVM - ensure that this is a JDK (expected " + tools + ")");
    		}

    		Collection<Path> toLink = new LinkedHashSet<>();
    		addIBMJars(notesProgram, toLink);
    		
    		Path destBase = notesProgram.getParent().getParent().resolve("jre").resolve("Contents").resolve("Home").resolve("lib").resolve("ext");
    		if(!Files.isDirectory(destBase)) {
    			throw new IllegalStateException("Unable to locate embedded Notes JRE ext directory at " + destBase);
    		}
    		if(!Files.isWritable(destBase)) {
    			throw new IllegalStateException("Unable to write to embedded Notes JRE ext directory at " + destBase);
    		}
    		
    		Collection<Path> result = new LinkedHashSet<>();
    		for(Path jar : toLink) {
    			Path destJar = destBase.resolve(jar.getFileName());
    			if(!Files.exists(destJar)) {
    				Files.copy(jar, destJar);
    				result.add(destJar);
    			}
    		}
    		
    		Path toolsDest = destBase.getParent().resolve("tools.jar");
    		if(!Files.exists(toolsDest)) {
    			Files.copy(tools, toolsDest);
    			result.add(toolsDest);
    		}
    		
    		return result;
    	} else {
    		return Collections.emptyList();
    	}
    }
    private void teardownJreJars(Collection<Path> result) throws MojoExecutionException, IOException {
    	if(SystemUtils.IS_OS_MAC) {
    		if(log.isDebugEnabled()) {
    			log.debug("Unlinking environment Jars in macOS Notes JRE");
    		}
    		
    		for(Path jar : result) {
    			Files.deleteIfExists(jar);
    		}
    	}
    }
}
