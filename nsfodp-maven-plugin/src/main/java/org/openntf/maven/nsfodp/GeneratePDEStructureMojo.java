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
package org.openntf.maven.nsfodp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.sonatype.plexus.build.incremental.BuildContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;

/**
 * Creates PDE structure files (MANIFEST.MF, build.properties) at the top level of
 * the current project based on the On-Disk Project data.
 * 
 * @author Jesse Gallagher
 * @since 2.0.0
 */
@Mojo(name="generate-pde-structure")
public class GeneratePDEStructureMojo extends AbstractMojo {
	@Parameter(defaultValue="${project}", readonly=true, required=false)
	protected MavenProject project;
	
	/**
	 * Location of the ODP directory.
	 */
	@Parameter(defaultValue="odp", required=true)
	private File odpDirectory;
	
	/**
	 * Any additional jars to include on the compilation classpath.
	 * 
	 * @since 2.0.0
	 */
	@Parameter(required=false)
	private File[] classpathJars;
	
	@Component
	private BuildContext buildContext;
	
	Log log;
	
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		log = getLog();
		
		if(!project.getPackaging().equals("domino-nsf")) {
			if(log.isInfoEnabled()) {
				log.info(Messages.getString("GeneratePDEStructureMojo.skip")); //$NON-NLS-1$
			}
			return;
		}
		
		try {
			generateBuildProperties();
		} catch(IOException | XMLException e) {
			throw new MojoExecutionException("Exception while generating build.properties", e);
		}
		try {
			generateManifestMf();
		} catch(IOException | XMLException e) {
			throw new MojoExecutionException("Exception while generating META-INF/MANIFEST.MF", e);
		}
	}

	private void generateBuildProperties() throws IOException, XMLException {
		Path classpath = odpDirectory.toPath().resolve(".classpath");
		if(!Files.isReadable(classpath) || !Files.isRegularFile(classpath)) {
			if(log.isWarnEnabled()) {
				log.warn(Messages.getString("GeneratePDEStructureMojo.noClasspath"));
			}
			return;
		}
		
		Document classpathXml;
		try(InputStream is = Files.newInputStream(classpath)) {
			classpathXml = DOMUtil.createDocument(is);
		}
		Collection<String> sourceFolders = Stream.of(DOMUtil.nodes(classpathXml, "/classpath/classpathentry[kind=src]"))
			.map(Element.class::cast)
			.map(el -> el.getAttribute("path"))
			.filter(path -> !"Local".equals(path))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		sourceFolders.add("Code/Java");
		sourceFolders.add("Resources/Files");
		
		Properties props = new Properties();
		props.put("bin.includes", "META-INF/,.");
		props.put("output..", "target/classes");
		props.put("source..", sourceFolders.stream()
				.map(path -> "odp/" + path)
				.collect(Collectors.joining(",")));
		// Look for jars specified in the Maven project
		if(this.classpathJars != null) {
			// Though the Eclipse docs say that you should use relative paths, the IDE only actually
			//   picks up on absolute paths
			props.put("extra..", Stream.of(this.classpathJars)
				.map(File::getAbsolutePath)
				.collect(Collectors.joining(","))
			);
		}
		
		Path buildProperties = project.getBasedir().toPath().resolve("build.properties");
		try(OutputStream os = buildContext.newFileOutputStream(buildProperties.toFile())) {
			props.store(os, "Generated by " + getClass().getName());
		}
	}
	
	private void generateManifestMf() throws IOException, XMLException {
		Path metaInf = project.getBasedir().toPath().resolve("META-INF");
		Files.createDirectories(metaInf);
		Path manifestMf = metaInf.resolve("MANIFEST.MF");
		
		Manifest manifest = new Manifest();
		Attributes attrs = manifest.getMainAttributes();
		attrs.putValue("Manifest-Version", "1.0");
		attrs.putValue("Bundle-ManifestVersion", "2");
		attrs.putValue("Bundle-Name", project.getName());
		attrs.putValue("Bundle-SymbolicName", project.getName());
		attrs.putValue("Automatic-Module-Name", project.getName());
		attrs.putValue("Bundle-RequiredExecutionEnvironment", "JavaSE-1.8");
		attrs.putValue("Bundle-Version", project.getVersion().replace("-SNAPSHOT", ".qualifier"));
		
		// Look for plugin dependencies
		Path pluginXmlFile = odpDirectory.toPath().resolve("plugin.xml");
		if(Files.isReadable(pluginXmlFile) && Files.isRegularFile(pluginXmlFile)) {
			Document pluginXml;
			try(InputStream is = Files.newInputStream(pluginXmlFile)) {
				pluginXml = DOMUtil.createDocument(is);
			}
			
			Object[] nodes = DOMUtil.nodes(pluginXml, "/plugin/requires/import");
			if(nodes.length > 0) {
				attrs.putValue("Require-Bundle", Stream.of(nodes)
						.map(Element.class::cast)
						.map(e -> e.getAttribute("plugin"))
						.collect(Collectors.joining(","))
				);
			}
		}
		
		Collection<String> jarPaths = new LinkedHashSet<>();
		
		// Look for jars in Code/Jars and WebContent/WEB-INF/lib
		Path jars = odpDirectory.toPath().resolve("Code").resolve("Jars");
		if(Files.isDirectory(jars)) {
			Files.walk(jars)
				.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".jar"))
				.forEach(path -> jarPaths.add("odp/Code/Jars/" + jars.relativize(path).toString().replace('/', File.separatorChar)));
		}
		
		Path lib = odpDirectory.toPath().resolve("WebContent").resolve("WEB-INF").resolve("lib");
		if(Files.isDirectory(lib)) {
			Files.walk(lib)
				.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".jar"))
				.forEach(path -> jarPaths.add("odp/WebContent/WEB-INF/lib/" + lib.relativize(path).toString().replace('/', File.separatorChar)));
		}
		
		if(!jarPaths.isEmpty()) {
			attrs.putValue("Bundle-Classpath", String.join(",", jarPaths));
		}
		
		attrs.putValue("Created-By", getClass().getName());
		
		try(OutputStream os = buildContext.newFileOutputStream(manifestMf.toFile())) {
			manifest.write(os);
		}
	}
}