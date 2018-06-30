/**
 * Copyright © 2018 Jesse Gallagher
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
package org.openntf.nsfodp.exporter;

import static org.openntf.nsfodp.commons.h.StdNames.*;
import static com.ibm.designer.domino.napi.NotesConstants.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.openntf.nsfodp.commons.odp.util.DXLUtil;
import org.w3c.dom.Document;

import com.ibm.commons.util.StringUtil;
import com.ibm.commons.util.io.StreamUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;
import com.ibm.designer.domino.napi.NotesAPIException;
import com.ibm.designer.domino.napi.NotesDatabase;
import com.ibm.designer.domino.napi.NotesDatetime;
import com.ibm.designer.domino.napi.NotesFormula;
import com.ibm.designer.domino.napi.NotesIDTable;
import com.ibm.designer.domino.napi.NotesNote;
import com.ibm.designer.domino.napi.design.FileAccess;
import com.ibm.designer.domino.napi.dxl.DXLExporter;
import com.ibm.domino.napi.NException;
import com.ibm.domino.napi.c.IdTable;
import com.ibm.domino.napi.c.NsfNote;
import com.ibm.domino.napi.c.callback.IDENUMERATEPROC;

/**
 * Represents an on-disk project export environment.
 * 
 * <p>This class is the primary entry point for ODP exporting.</p>
 * 
 * @author Jesse Gallagher
 * @since 1.4.0
 */
public class ODPExporter {
	public static final String EXT_METADATA = ".metadata"; //$NON-NLS-1$
	
	// Get handles on some FileAccess methods, since the public ones use the wrong item name
	private static Method NReadScriptContent;
	static {
		try {
			AccessController.doPrivileged((PrivilegedExceptionAction<Void>)() -> {
				NReadScriptContent = FileAccess.class.getDeclaredMethod("NReadScriptContent", int.class, String.class, OutputStream.class); //$NON-NLS-1$
				NReadScriptContent.setAccessible(true);
				return null;
			});
		} catch (PrivilegedActionException e) {
			e.printStackTrace();
		}
	}
	
	private final NotesDatabase database;
	private boolean binaryDxl = false;
	private boolean swiperFilter = false;
	private Templates swiper;

	public ODPExporter(NotesDatabase database) {
		this.database = database;
	}
	
	/**
	 * Sets whether to use "binary" DXL for exporting operations.
	 * 
	 * @param binaryDxl the value to set
	 */
	public void setBinaryDxl(boolean binaryDxl) {
		this.binaryDxl = binaryDxl;
	}
	
	/**
	 * Whether the exporter is configured to use "binary" DXL.
	 * 
	 * @return the current binary DXL setting
	 */
	public boolean isBinaryDxl() {
		return binaryDxl;
	}
	
	/**
	 * Sets whether to filter exported DXL using the XSLT files from Swiper.
	 * 
	 * @param swiperFilter the value to set
	 * @throws IOException if there is a problem initializing Swiper
	 * @throws TransformerFactoryConfigurationError if there is a problem initializing Swiper
	 * @throws TransformerConfigurationException if there is a problem initializing Swiper
	 */
	public void setSwiperFilter(boolean swiperFilter) throws IOException, TransformerConfigurationException, TransformerFactoryConfigurationError {
		this.swiperFilter = swiperFilter;
		if(swiperFilter && swiper == null) {
			// Initialize Swiper now
			try(InputStream is = getClass().getResourceAsStream("/res/SwiperDXLClean.xsl")) { //$NON-NLS-1$
				swiper = TransformerFactory.newInstance().newTemplates(new StreamSource(is));
			}
		}
	}
	
	/**
	 * Whether the export is configured to filter exported DXL using the
	 * XSLT files from Swiper.
	 * 
	 * @return the current Swiper filter setting
	 */
	public boolean isSwiperFilter() {
		return swiperFilter;
	}
	
	public Path export() throws IOException, NotesAPIException, NException {
		Path result = Files.createTempDirectory(getClass().getName());
		
		DXLExporter exporter = new DXLExporter(database);
		try {
			exporter.open();

			Path databaseProperties = result.resolve("AppProperties").resolve("database.properties"); //$NON-NLS-1$ //$NON-NLS-2$
			Files.createDirectories(databaseProperties.getParent());
			try(OutputStream os = Files.newOutputStream(databaseProperties)) {
				exporter.exportDbProperties(os, database);
			}
			
			exporter.setExporterProperty(DXLExporter.eForceNoteFormat, isBinaryDxl() ? 1 : 0);
			
			final Throwable[] err = new Throwable[1];
			NotesIDTable designCollection = new NotesIDTable(database);
			try {
				designCollection.create();
				
				database.search(designCollection, NsfNote.NOTE_CLASS_ALLNONDATA, (NotesFormula)null, (String)null, 0, (NotesDatetime)null);
				
				IdTable.IDEnumerate(designCollection.getHandle(), new IDENUMERATEPROC() {
					@Override
					public short callback(int noteId) {
						try {
							NotesNote note = database.openNote(noteId, NsfNote.OPEN_RAW_MIME);
							exportNote(note, exporter, result);
						} catch (Throwable e) {
							e.printStackTrace();
							err[0] = e;
							return 1;
						}
						
						return 0;
					}
				});
			} finally {
				designCollection.recycle();
			}
			if(err[0] != null) {
				if(err[0] instanceof RuntimeException) {
					throw (RuntimeException)err[0];
				} else if(err[0] instanceof IOException) {
					throw (IOException)err[0];
				} else if(err[0] instanceof NotesAPIException) {
					throw (NotesAPIException)err[0];
				} else if(err[0] instanceof NException) {
					throw (NException)err[0];
				} else {
					throw new RuntimeException(err[0]);
				}
			}
		} finally {
			exporter.recycle();
		}
		
		return result;
	}

	private void exportNote(NotesNote note, DXLExporter exporter, Path baseDir) throws IOException, NotesAPIException, NException, XMLException {
		NoteType type = NoteType.forNote(note);
		switch(type) {
		case AboutDocument:
		case UsingDocument:
		case SharedActions:
		case DBIcon:
		case IconNote:
		case DBScript:
			exportExplicitNote(note, exporter, baseDir, type.path);
			break;
		case Form:
		case Frameset:
		case JavaLibrary:
		case Outline:
		case Page:
		case SharedField:
		case View:
		case ImportedJavaAgent:
		case JavaAgent:
		case JavaWebService:
		case Folder:
		case LotusScriptAgent:
		case LotusScriptWebService:
		case JavaWebServiceConsumer:
		case LotusScriptWebServiceConsumer:
		case SharedColumn:
		case Subform:
		case SimpleActionAgent:
		case FormulaAgent:
		case Navigator:
		case DB2AccessView:
		case DataConnection:
		case Applet:
			exportNamedNote(note, exporter, baseDir, type);
			break;
		case CustomControl:
		case FileResource:
		case ImageResource:
		case JavaScriptLibrary:
		case Java:
		case LotusScriptLibrary:
		case StyleSheet:
		case Theme:
		case XPage:
		case ServerJavaScriptLibrary:
		case Jar:
		case WiringProperties:
		case CompositeApplication:
		case CompositeComponent:
			exportNamedDataAndMetadata(note, exporter, baseDir, type);
			break;
		case WebContentFile:
		case GenericFile:
			exportNamedData(note, exporter, baseDir, type);
			break;
		case DesignCollection:
		case ACL:
			// Nothing to do here
			break;
		case Unknown:
		default:
			String flags = note.isItemPresent(DESIGN_FLAGS) ? note.getItemValueAsString(DESIGN_FLAGS) : StringUtil.EMPTY_STRING;
			String title = note.isItemPresent(FIELD_TITLE) ? note.getItemValueAsString(FIELD_TITLE) : String.valueOf(note.getNoteId());
			System.out.println("Unknown note, flags=" + flags + ", title=" + title + ", class=" + (note.getNoteClass() & ~NsfNote.NOTE_CLASS_DEFAULT));
			//throw new UnsupportedOperationException("Unhandled note: " + doc.getUniversalID() + ", flags " + doc.getItemValueString("$Flags")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		
	}
	
	/**
	 * Exports an individually-named note, based on its $TITLE value.
	 * 
	 * @param note the note to export
	 * @param exporter the exporter to use for the process
	 * @param baseDir the base directory for export operations
	 * @param type the NoteType enum for the note
	 * @throws IOException 
	 * @throws NotesAPIException 
	 */
	private void exportNamedNote(NotesNote note, DXLExporter exporter, Path baseDir, NoteType type) throws IOException, NotesAPIException {
		String name = getCleanName(note);
		if(StringUtil.isNotEmpty(type.extension) && !name.endsWith(type.extension)) {
			name += '.' + type.extension;
		}
		
		exportExplicitNote(note, exporter, baseDir, type.path.resolve(name));
	}
	
	/**
	 * Converted a VFS-style file name to an FS-friendly version.
	 * 
	 * @param note the note to get a title for
	 * @return an FS-friendly version of the title
	 * @throws NotesAPIException 
	 */
	private String getCleanName(NotesNote note) throws NotesAPIException {
		if(!note.isItemPresent(FIELD_TITLE)) {
			return "(Untitled)";
		}
		String title = note.getItemAsTextList(FIELD_TITLE).get(0);
		
		int pipe = title.indexOf('|');
		String clean = pipe > -1 ? title.substring(0, pipe) : title;
		clean = clean.isEmpty() ? "(Untitled)" : clean; //$NON-NLS-1$
		
		// TODO replace with a proper algorithm 
		return clean
			.replace("\\", "_5c") //$NON-NLS-1$ //$NON-NLS-2$
			.replace("*", "_2a"); //$NON-NLS-1$ //$NON-NLS-2$
	}
	
	/**
	 * Exports the file data of the provided note, without the metadata file.
	 * 
	 * @param note the note to export
	 * @param exporter the exporter to use for the process
	 * @param baseDir the base directory for export operations
	 * @param type the NoteType enum for the note
	 * @throws NotesAPIException
	 * @throws IOException
	 * @throws NException 
	 * @throws XMLException 
	 */
	private void exportNamedData(NotesNote note, DXLExporter exporter, Path baseDir, NoteType type) throws NotesAPIException, IOException, NException, XMLException {
		String name = getCleanName(note);
		if(StringUtil.isNotEmpty(type.extension) && !name.endsWith(type.extension)) {
			name += '.' + type.extension;
		}
		
		// These are normal files in the NSF, but should not be exported
		if(name.startsWith("WebContent/WEB-INF/classes") || name.startsWith("WEB-INF/classes")) { //$NON-NLS-1$ //$NON-NLS-2$
			return;
		} else if(name.equals("build.properties")) { //$NON-NLS-1$
			return;
		}
		
		exportFileData(note, exporter, baseDir, type.path.resolve(name), type);
	}
	
	/**
	 * Exports the file data of the provided note, plus a neighboring ".metadata" file.
	 * 
	 * @param note the note to export
	 * @param exporter the exporter to use for the process
	 * @param baseDir the base directory for export operations
	 * @param type the NoteType enum for the note
	 * @throws NotesAPIException
	 * @throws IOException
	 * @throws NException 
	 * @throws XMLException 
	 */
	private void exportNamedDataAndMetadata(NotesNote note, DXLExporter exporter, Path baseDir, NoteType type) throws NotesAPIException, IOException, NException, XMLException {
		exportNamedData(note, exporter, baseDir, type);
		
		String name = getCleanName(note);
		if(StringUtil.isNotEmpty(type.extension) && !name.endsWith(type.extension)) {
			name += '.' + type.extension;
		}
		
		List<String> ignoreItems = new ArrayList<>(Arrays.asList(type.fileItem, ITEM_NAME_FILE_SIZE, XSP_CLASS_INDEX, SCRIPTLIB_OBJECT));
		// Some of these will have pattern-based item ignores
		if(StringUtil.isNotEmpty(type.itemNameIgnorePattern)) {
			for(String itemName : note.getItemNames()) {
				if(Pattern.matches(type.itemNameIgnorePattern, itemName)) {
					ignoreItems.add(itemName);
				}
			}
		}
		
		exporter.setExporterListProperty(DXLExporter.eOmitItemNames, ignoreItems.toArray(new String[ignoreItems.size()]));
		exporter.setExporterProperty(38, 1);
		try {
			exportExplicitNote(note, exporter, baseDir, type.path.resolve(name + EXT_METADATA));
		} finally {
			exporter.setExporterListProperty(DXLExporter.eOmitItemNames, StringUtil.EMPTY_STRING_ARRAY);
			exporter.setExporterProperty(38, 0);
		}
	}
	
	/**
	 * Exports the file data of the provided note to the specified path.
	 * 
	 * @param note the note to export
	 * @param exporter the exporter to use for the process
	 * @param baseDir the base directory for export operations
	 * @param path the relative file path to export to within the base dir
	 * @param type the NoteType enum for the note
	 * @throws NotesAPIException
	 * @throws IOException
	 * @throws NException 
	 * @throws XMLException 
	 */
	private void exportFileData(NotesNote note, DXLExporter exporter, Path baseDir, Path path, NoteType type) throws NotesAPIException, IOException, NException, XMLException {
		Path fullPath = baseDir.resolve(path);
		Files.createDirectories(fullPath.getParent());
		
		try(OutputStream os = Files.newOutputStream(fullPath)) {
			// readFileContent works for some but not all file types
			switch(type) {
			case LotusScriptLibrary:
				// This is actually just a set of text items. The NAPI wrapper, however, doesn't
				//   properly handle multiple text items of the same name, so we have to do it
				//   manually
				try(PrintWriter writer = new PrintWriter(os)) {
					// TODO replace with better NAPI implementation. Using the direct NAPI methods for
					//   item info led to UnsatisfiedLinkErrors. The legacy API also falls on its face,
					//   with iterating over the items properly finding the right count, but then returning
					//   the value for only the first each time
					byte[] dxl;
					try(ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
						if(!isBinaryDxl()) {
							exporter.setExporterProperty(DXLExporter.eForceNoteFormat, 1);
						}
						
						exporter.exportNote(baos, note);
						
						if(!isBinaryDxl()) {
							exporter.setExporterProperty(DXLExporter.eForceNoteFormat, 0);
						}
						
						dxl = baos.toByteArray();
					}
					
					try(InputStream in = new ByteArrayInputStream(dxl)) {
						Document doc = DOMUtil.createDocument(in);
						for(String bit : DXLUtil.getItemValueStrings(doc, type.fileItem)) {
							writer.write(bit);
						}
					}
				}
				
				break;
			case JavaScriptLibrary:
			case ServerJavaScriptLibrary:
				try {
					NReadScriptContent.invoke(null, note.getHandle(), type.fileItem, os);
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					throw new NotesAPIException(e, "Exception when reading script content"); //$NON-NLS-1$
				}
				break;
			case CustomControl:
				// Special behavior: also export the config data field
				FileAccess.readFileContent(note, os);
				
				Path configPath = fullPath.getParent().resolve(fullPath.getFileName()+"-config"); //$NON-NLS-1$
				try(OutputStream configOut = Files.newOutputStream(configPath)) {
					try(InputStream configIn = FileAccess.readFileContentAsInputStream(note, ITEM_NAME_CONFIG_FILE_DATA)) {
						StreamUtil.copyStream(configIn, configOut);
					}
				}
				break;
			default:
				FileAccess.readFileContent(note, os);
				break;
			}
		}
	}
	
	/**
	 * Exports a note to the provided path.
	 * 
	 * @param note the note to export
	 * @param exporter the exporter to use for the process
	 * @param baseDir the base directory for export operations
	 * @param path the relative file path to export to within the base dir
	 * @throws IOException 
	 * @throws NotesAPIException 
	 */
	private void exportExplicitNote(NotesNote note, DXLExporter exporter, Path baseDir, Path path) throws IOException, NotesAPIException {
		Path fullPath = baseDir.resolve(path);
		Files.createDirectories(fullPath.getParent());
		
		try(OutputStream os = new SwiperOutputStream(fullPath)) {
			exporter.exportNote(os, note);
		}
	}
	
	/**
	 * This OutputStream implementation toggles its behavior depending on whether or not Swiper is
	 * enabled for this exporter.
	 * 
	 * @since 1.4.0
	 */
	private class SwiperOutputStream extends OutputStream {
		
		private final Path path;
		private OutputStream os;
		private final boolean isSwiper;
		
		public SwiperOutputStream(Path path) throws IOException {
			this.path = path;
			this.isSwiper = isSwiperFilter();
			if(this.isSwiper) {
				os = new ByteArrayOutputStream();
			} else {
				os = Files.newOutputStream(path);
			}
		}

		@Override
		public void write(int b) throws IOException {
			os.write(b);
		}
		
		@Override
		public void close() throws IOException {
			super.close();
			
			// Either close the underlying stream and be done or do Swiper transformations
			if(this.isSwiper) {
				os.close();
				byte[] xml = ((ByteArrayOutputStream)os).toByteArray();
				try(InputStream is = new ByteArrayInputStream(xml)) {
					try(OutputStream os = Files.newOutputStream(path)) {
						Transformer transformer = swiper.newTransformer();

						transformer.setOutputProperty(OutputKeys.INDENT, "yes"); //$NON-NLS-1$
						transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2"); //$NON-NLS-1$ //$NON-NLS-2$

						transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "yes"); //$NON-NLS-1$
						
						transformer.transform(new StreamSource(is), new StreamResult(os));
					} catch (TransformerException e) {
						throw new IOException(e);
					}
				}
			} else {
				os.close();
			}
		}
	}
}
